import json
import unittest
from collections import deque

from termux_personal import ProtocolError, TermuxClient, TransportError, UnavailableError
from termux_personal.domains import TaskPreset

ALL = ["system.hello", "system.capabilities", "process.list", "file.roots", "file.list", "file.stat", "file.read",
       "workspace.list", "workspace.get", "workspace.create", "workspace.update", "workspace.remove", "workspace.select",
       "workspace.tasks", "workspace.task.run", "workspace.git"]
WS = {"id": "ws_0123456789abcdef", "name": "my-app", "rootId": "home", "path": "projects/my-app", "createdAt": 1, "updatedAt": 2,
      "available": True, "taskPresets": [{"id": "test", "label": "Run tests", "executable": "cargo", "args": ["test"], "timeoutMs": 0}]}


class FakeTransport:
    def __init__(self, methods, handler=lambda request, transport: None):
        self.methods = methods
        self.handler = handler
        self.requests = []
        self.responses = deque()

    def write_frame(self, data):
        request = json.loads(data.decode("utf-8"))
        self.requests.append(request)
        if request["method"] == "system.hello":
            self.reply(request, {"version": 1})
        elif request["method"] == "system.capabilities":
            self.reply(request, {"methods": self.methods})
        else:
            self.handler(request, self)

    def reply(self, request, result):
        self.responses.append(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode("utf-8"))

    def read_frame(self):
        if not self.responses:
            raise EOFError()
        return self.responses.popleft()

    def close(self):
        pass

    def sent(self):
        return [request["method"] for request in self.requests]


class UploadTransport(FakeTransport):
    """Serves a transfer: the frame after a file.transfer.write request is its raw chunk."""

    def __init__(self, fail_at=None):
        super().__init__(ALL + ["file.mkdir", "file.transfer.begin", "file.transfer.write", "file.transfer.commit", "file.transfer.abort"],
                         self.handle)
        self.pending = None
        self.received = b""
        self.fail_at = fail_at

    def write_frame(self, data):
        if self.pending is not None:
            request, self.pending = self.pending, None
            self.received += data
            if self.fail_at is not None and request["params"]["offset"] >= self.fail_at:
                self.responses.append(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {"code": -32001, "message": "CONFLICT"}}).encode())
            else:
                self.reply(request, {"nextOffset": request["params"]["offset"] + len(data)})
            return
        super().write_frame(data)

    def handle(self, request, transport):
        method = request["method"]
        if method == "file.transfer.begin":
            transport.reply(request, {"transferId": "tr_" + "0" * 32, "chunkSize": 65536, "offset": 0})
        elif method == "file.transfer.write":
            self.pending = request
        elif method == "file.transfer.commit":
            transport.reply(request, {"entry": {"name": "a", "path": "a", "type": "file", "size": len(self.received), "mtime": 1, "revision": "r1"},
                                      "sha256": "0" * 64, "committed": True})
        elif method == "file.transfer.abort":
            transport.reply(request, {"aborted": True})


class DomainsTest(unittest.TestCase):
    def test_upload_sends_ordered_chunks_then_commits(self):
        data = bytes(range(256)) * 600
        peer = UploadTransport()
        with TermuxClient(peer) as client:
            entry, _ = client.files.upload("home", "a", data)
        self.assertEqual(peer.received, data)
        self.assertEqual(entry.size, len(data))
        writes = [r for r in peer.requests if r["method"] == "file.transfer.write"]
        self.assertEqual([w["params"]["offset"] for w in writes], [0, 65536, 131072])
        self.assertEqual(peer.sent()[-1], "file.transfer.commit")

    def test_failed_upload_aborts_and_never_commits(self):
        peer = UploadTransport(fail_at=65536)
        with TermuxClient(peer) as client:
            with self.assertRaises(Exception):
                client.files.upload("home", "a", b"x" * 100000)
        # A rejected binary request closes the v1 connection, so the abort cannot be sent; the server
        # discards the idle transfer on expiry and never publishes it.
        self.assertNotIn("file.transfer.commit", peer.sent())

    def test_file_list_sends_documented_fields_and_validates_entries(self):
        peer = FakeTransport(ALL, lambda r, t: t.reply(r, {"entries": [{"name": "notes.txt", "path": "notes.txt", "type": "file",
                                                                       "size": 11, "mtime": 5, "revision": "r5.b"}], "nextCursor": None}))
        with TermuxClient(peer) as client:
            entries, next_cursor = client.files.list("home", limit=10)
        self.assertEqual(entries[0].type, "file")
        self.assertIsNone(next_cursor)
        request = next(r for r in peer.requests if r["method"] == "file.list")
        self.assertEqual(request["params"], {"rootId": "home", "path": "", "limit": 10})

    def test_file_read_returns_binary_and_rejects_inconsistent_metadata(self):
        def good(request, transport):
            transport.reply(request, {"offset": 0, "nextOffset": 3, "eof": False, "length": 3, "revision": "r1.3"})
            transport.responses.append(bytes([104, 105, 10]))
        with TermuxClient(FakeTransport(ALL, good)) as client:
            chunk = client.files.read("home", "notes.txt", 0, 3)
        self.assertEqual(chunk.data, bytes([104, 105, 10]))

        def bad(request, transport):
            transport.reply(request, {"offset": 0, "nextOffset": 9, "eof": True, "length": 3, "revision": "r1.3"})
            transport.responses.append(bytes([1, 2, 3]))
        with TermuxClient(FakeTransport(ALL, bad)) as client:
            with self.assertRaises(ProtocolError):
                client.files.read("home", "notes.txt", 0, 3)

    def test_file_methods_are_not_sent_when_not_advertised(self):
        peer = FakeTransport(["system.hello", "system.capabilities"])
        with TermuxClient(peer) as client:
            with self.assertRaises(UnavailableError):
                client.files.roots()
        self.assertNotIn("file.roots", peer.sent())

    def test_process_list_validates_rows_and_refuses_unknown_scope_before_sending(self):
        row = {"pid": 10, "ppid": 1, "name": "zsh", "state": "S", "rssKiB": 4096, "cpuPercent": None, "startTicks": 55,
               "managedJobId": None, "visibility": "partial"}
        peer = FakeTransport(ALL, lambda r, t: t.reply(r, {"processes": [row], "incomplete": False, "missingRoots": 0, "nextCursor": None}))
        with TermuxClient(peer) as client:
            listing = client.processes.list(scope="owned")
            self.assertEqual(listing["processes"][0].pid, 10)
            with self.assertRaises(ValueError):
                client.processes.list(scope="signal")
        self.assertEqual(peer.sent().count("process.list"), 1)

        bad_row = dict(row, managedJobId="job_1")
        bad = FakeTransport(ALL, lambda r, t: t.reply(r, {"processes": [bad_row], "incomplete": False, "missingRoots": 0, "nextCursor": None}))
        with TermuxClient(bad) as client:
            with self.assertRaises(ProtocolError):
                client.processes.list()

    def test_process_signal_sends_job_id_and_signal_only(self):
        def handler(request, transport):
            self.assertEqual(request["params"], {"jobId": "job_1", "signal": "TERM"})
            transport.reply(request, {"sent": True})
        peer = FakeTransport(ALL + ["process.signal"], handler)
        with TermuxClient(peer) as client:
            client.processes.signal_job("job_1", "TERM")
            with self.assertRaises(ValueError):
                client.processes.signal_job("job_1", "HUP")
        self.assertEqual(peer.sent().count("process.signal"), 1)
        with TermuxClient(FakeTransport(ALL)) as client:
            with self.assertRaises(UnavailableError):
                client.processes.signal_job("job_1", "KILL")
        with TermuxClient(FakeTransport(ALL + ["process.signal"], lambda r, t: t.reply(r, {}))) as client:
            with self.assertRaises(ProtocolError) as raised:
                client.processes.signal_job("job_1", "KILL")
            self.assertTrue(raised.exception.outcome_unknown)

    def test_workspace_create_and_get_validate_metadata(self):
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"workspace": WS}))) as client:
            created = client.workspaces.create("my-app", "home", "projects/my-app")
            self.assertEqual(created.id, WS["id"])
            self.assertEqual(created.task_presets[0].executable, "cargo")

        bad = dict(WS, id="not-an-id")
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"workspace": bad}))) as client:
            with self.assertRaises(ProtocolError) as caught:
                client.workspaces.create("x", "home", "projects/my-app")
        self.assertTrue(caught.exception.outcome_unknown)

    def test_workspace_list_rejects_invalid_active_identifier(self):
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"workspaces": [WS], "activeId": "active"}))) as client:
            with self.assertRaises(ProtocolError):
                client.workspaces.list()

    def test_run_task_sends_only_identifiers(self):
        job = {"id": "job_9", "state": "queued", "startedAt": None, "endedAt": None, "exitCode": None, "failureCode": None,
               "pid": None, "workspaceId": WS["id"], "output": {"stdoutEndOffset": 0, "stderrEndOffset": 0}}
        peer = FakeTransport(ALL, lambda r, t: t.reply(r, {"job": job}))
        with TermuxClient(peer) as client:
            admitted = client.workspaces.run_task(WS["id"], "test", client_request_id="task-1")
        self.assertEqual(admitted.id, "job_9")
        request = next(r for r in peer.requests if r["method"] == "workspace.task.run")
        self.assertEqual(request["params"], {"workspaceId": WS["id"], "taskId": "test", "clientRequestId": "task-1"})

    def test_lost_task_run_response_has_unknown_outcome_and_is_not_retried(self):
        peer = FakeTransport(ALL)
        with TermuxClient(peer) as client:
            with self.assertRaises(TransportError) as caught:
                client.workspaces.run_task(WS["id"], "test")
        self.assertTrue(caught.exception.outcome_unknown)
        self.assertEqual(peer.sent().count("workspace.task.run"), 1)

    def test_lost_file_read_is_retry_safe(self):
        with TermuxClient(FakeTransport(ALL)) as client:
            with self.assertRaises(TransportError) as caught:
                client.files.read("home", "notes.txt")
        self.assertFalse(caught.exception.outcome_unknown)

    def test_git_sends_only_subcommand_and_rejects_mutating_names(self):
        job = {"id": "job_10", "state": "queued", "startedAt": None, "endedAt": None, "exitCode": None, "failureCode": None,
               "pid": None, "workspaceId": WS["id"], "output": {"stdoutEndOffset": 0, "stderrEndOffset": 0}}
        peer = FakeTransport(ALL, lambda r, t: t.reply(r, {"job": job}))
        with TermuxClient(peer) as client:
            self.assertEqual(client.workspaces.git(WS["id"], "status").id, "job_10")
            with self.assertRaises(ValueError):
                client.workspaces.git(WS["id"], "commit")
        request = next(r for r in peer.requests if r["method"] == "workspace.git")
        self.assertEqual(request["params"], {"workspaceId": WS["id"], "subcommand": "status"})
        self.assertEqual(peer.sent().count("workspace.git"), 1)

    def test_task_preset_wire_form_round_trips(self):
        preset = TaskPreset("test", "Run tests", "cargo", ["test"], 0)
        self.assertEqual(preset.to_wire(), {"id": "test", "label": "Run tests", "executable": "cargo", "args": ["test"], "timeoutMs": 0})


if __name__ == "__main__":
    unittest.main()

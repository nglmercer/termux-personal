import json
import sys
import os
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from test_domains import FakeTransport  # noqa: E402  (the shared fake)

from termux_personal import ProtocolError, TermuxClient, UnavailableError
from termux_personal.domains import TaskPreset

WS = "ws_0123456789abcdef"
METHODS = ["system.hello", "system.capabilities", "resource.capabilities", "resource.snapshot", "job.resource.get", "job.history.list",
           "job.history.get", "job.diagnostics.export", "job.processes", "job.start", "workspace.create", "workspace.update", "workspace.get",
           "workspace.service.list", "workspace.service.status", "workspace.service.start", "workspace.service.stop", "workspace.service.restart",
           "workspace.service.logs", "workspace.service.health", "workspace.service.ports", "workspace.service.proxy",
           "workspace.backup.export", "workspace.backup.import", "workspace.backup.status", "workspace.backup.cancel", "job.preview", "workspace.task.preview"]
SERVICE = {"workspaceId": WS, "serviceId": "dev-server", "state": "running", "restartPolicy": "never", "jobId": "job_1", "restartsInWindow": 0,
           "nextStartInMillis": None, "lastReason": None, "lastExitCode": None, "ports": [3000]}
JOB = {"id": "job_1", "state": "queued", "startedAt": None, "endedAt": None, "exitCode": None, "failureCode": None, "pid": None, "workspaceId": None,
       "output": {"stdoutEndOffset": 0, "stderrEndOffset": 0}}


def client(handler):
    return TermuxClient(FakeTransport(METHODS, handler))


def replying(result):
    return lambda request, transport: transport.reply(request, result)


class ResourcesTest(unittest.TestCase):
    def test_capabilities_and_snapshot_are_validated(self):
        caps = {"resourceLimits": {k: False for k in ("managedJobs", "memorySoftMonitoring", "rlimitAs", "rlimitCpu", "cgroups", "safeProcessStop", "virtualMachine")},
                "profiles": ["balanced"], "pressureActions": ["warn"], "items": []}
        self.assertEqual(client(replying(caps)).resources.capabilities()["profiles"], ["balanced"])
        for bad in ({**caps, "profiles": ["turbo"]}, {**caps, "resourceLimits": {"managedJobs": True}}, []):
            with self.assertRaises(ProtocolError):
                client(replying(bad)).resources.capabilities()
        snap = {"device": {"totalMemoryBytes": None, "availableMemoryBytes": None, "lowMemory": None, "thermalStatus": None, "cpuCount": 8},
                "pressure": "unknown", "admission": {"slots": 1, "active": 0, "queued": 0}}
        self.assertIsNone(client(replying(snap)).resources.snapshot()["device"]["totalMemoryBytes"])
        with self.assertRaises(ProtocolError):
            client(replying({**snap, "pressure": "panic"})).resources.snapshot()

    def test_history_limit_is_checked_before_sending_anything(self):
        transport = FakeTransport(METHODS, replying({"history": [], "available": True}))
        c = TermuxClient(transport)
        for bad in (0, 51, "5", True):
            with self.assertRaises(ValueError):
                c.resources.history(bad)
        self.assertNotIn("job.history.list", transport.sent())
        self.assertEqual(c.resources.history(5), [])

    def test_diagnostics_only_ask_for_output_when_told_to(self):
        sent = []

        def handler(request, transport):
            sent.append(request["params"])
            transport.reply(request, {"report": "r", "files": [], "outputIncluded": False, "found": True})
        c = client(handler)
        c.resources.export_diagnostics("job_1")
        c.resources.export_diagnostics("job_1", include_output=True)
        self.assertEqual(sent, [{"id": "job_1"}, {"id": "job_1", "includeOutput": True}])

    def test_job_processes_rejects_unknown_relations(self):
        good = {"jobId": "job_1", "running": True, "incomplete": False,
                "processes": [{"pid": 5, "ppid": 1, "name": "cargo", "state": "S", "rssKiB": None, "startTicks": 1, "relation": "orphan_possible"}]}
        self.assertEqual(client(replying(good)).resources.job_processes("job_1")["processes"][0]["relation"], "orphan_possible")
        bad = json.loads(json.dumps(good)); bad["processes"][0]["relation"] = "child"
        with self.assertRaises(ProtocolError):
            client(replying(bad)).resources.job_processes("job_1")


class ServicesTest(unittest.TestCase):
    def test_status_roundtrip_and_strict_validation(self):
        status = client(replying({"service": SERVICE})).services.status(WS, "dev-server")
        self.assertEqual((status.state, status.ports, status.healthy), ("running", [3000], None))
        for patch in ({"state": "zombie"}, {"ports": ["3000"]}, {"restartsInWindow": -1}, {"restartPolicy": "sometimes"}):
            with self.assertRaises(ProtocolError):
                client(replying({"service": {**SERVICE, **patch}})).services.status(WS, "dev-server")

    def test_ids_are_validated_locally(self):
        transport = FakeTransport(METHODS, replying({"service": SERVICE}))
        c = TermuxClient(transport)
        for call in (lambda: c.services.start("not-a-workspace", "dev"), lambda: c.services.stop(WS, "Bad Id"), lambda: c.services.list("x")):
            with self.assertRaises(ValueError):
                call()
        self.assertEqual([m for m in transport.sent() if m.startswith("workspace.")], [])

    def test_a_bad_start_response_is_flagged_outcome_unknown_and_never_retried(self):
        transport = FakeTransport(METHODS, replying({"outcome": "exploded", "service": SERVICE}))
        c = TermuxClient(transport)
        with self.assertRaises(ProtocolError) as ctx:
            c.services.start(WS, "dev-server")
        self.assertTrue(ctx.exception.outcome_unknown)
        self.assertEqual(transport.sent().count("workspace.service.start"), 1)

    def test_logs_check_offsets_against_the_binary_frame(self):
        frames = []

        def handler(request, transport):
            transport.reply(request, {"offset": 0, "nextOffset": 4, "endOffset": 4, "gap": False, "length": 4, "finished": False, "complete": False})
            transport.responses.append(b"hi\n\x00")
        c = client(handler)
        chunk = c.services.logs(WS, "dev-server", "stdout", 0, 16)
        self.assertEqual((chunk.data, chunk.next_offset), (b"hi\n\x00", 4))
        with self.assertRaises(ValueError):
            c.services.logs(WS, "dev-server", "stdin", 0)

    def test_proxy_target_is_always_loopback_in_the_response_contract(self):
        proxy = {"id": "px_1", "listenAddress": "127.0.0.1", "listenPort": 8080, "targetAddress": "127.0.0.1", "targetPort": 3000, "lan": False}
        sent = []

        def handler(request, transport):
            sent.append(request["params"])
            transport.reply(request, {"proxy": proxy})
        c = client(handler)
        c.services.open_proxy(8080, 3000)
        c.services.open_proxy(8081, 3000, listen_address="0.0.0.0", confirm_lan=True)
        self.assertEqual(sent[0], {"action": "open", "listenPort": 8080, "targetPort": 3000})
        self.assertEqual(sent[1]["confirmLan"], True)
        with self.assertRaises(ProtocolError):
            client(replying({"proxy": {**proxy, "targetAddress": "10.0.0.5"}})).services.open_proxy(8080, 3000)


class BackupsTest(unittest.TestCase):
    def test_destructive_options_need_confirmation_before_anything_is_sent(self):
        transport = FakeTransport(METHODS, replying({}))
        c = TermuxClient(transport)
        for kwargs in ({"overwrite": True}, {"apply_config": True}):
            with self.assertRaises(ValueError):
                c.backups.restore(WS, "b.zip", **kwargs)
        self.assertNotIn("workspace.backup.import", transport.sent())

    def test_export_and_restore_validate_the_responses(self):
        backup = {"id": WS + "-20250101-000000.zip", "format": "zip", "files": 3, "bytes": 9, "archiveBytes": 5, "truncated": False,
                  "skipped": {"excluded": 0, "sensitive": 0, "links": 0, "unsafe": 0}}
        self.assertEqual(client(replying({"backup": backup})).backups.export(WS)["files"], 3)
        with self.assertRaises(ProtocolError) as ctx:
            client(replying({"backup": {**backup, "format": "rar"}})).backups.export(WS)
        self.assertTrue(ctx.exception.outcome_unknown)
        with self.assertRaises(ValueError):
            client(replying({})).backups.export(WS, format="rar")
        restore = {"files": 1, "bytes": 2, "conflicts": 0, "skippedUnsafe": 0, "truncated": False, "configApplied": True}
        sent = []

        def handler(request, transport):
            sent.append(request["params"])
            transport.reply(request, {"restore": restore})
        client(handler).backups.restore(WS, "b.zip", paths=["src"], overwrite=True, confirm=True, apply_config=True)
        self.assertEqual(sent[0], {"workspaceId": WS, "backupId": "b.zip", "paths": ["src"], "overwrite": True, "confirm": True, "applyConfig": True})


OP = {"id": "bk_1", "kind": "export", "workspaceId": WS, "state": "running", "files": 3, "bytes": 10, "startedAt": 1, "endedAt": None,
      "result": None, "error": None}


class BackgroundAndPreviewTest(unittest.TestCase):
    def test_background_backups_return_an_operation_with_progress_and_can_be_cancelled(self):
        sent = []

        def handler(request, transport):
            sent.append(request["params"]); transport.reply(request, {"operation": OP})
        c = client(handler)
        self.assertEqual(c.backups.export(WS, background=True)["state"], "running")
        self.assertTrue(sent[0]["async"])
        self.assertEqual(c.backups.status("bk_1")["files"], 3)
        self.assertEqual(c.backups.cancel("bk_1")["id"], "bk_1")
        c.backups.restore(WS, "b.zip", background=True)
        self.assertTrue(sent[-1]["async"])
        with self.assertRaises(ProtocolError) as ctx:
            client(replying({"operation": {**OP, "state": "weird"}})).backups.export(WS, background=True)
        self.assertTrue(ctx.exception.outcome_unknown)
        with self.assertRaises(ProtocolError):
            client(replying({"operation": {**OP, "files": -1}})).backups.status("bk_1")

    def test_a_plain_export_does_not_send_async(self):
        sent = []
        backup = {"id": "x", "format": "zip", "files": 1, "bytes": 1, "archiveBytes": 1, "truncated": False,
                  "skipped": {"excluded": 0, "sensitive": 0, "links": 0, "unsafe": 0}}

        def handler(request, transport):
            sent.append(request["params"]); transport.reply(request, {"backup": backup})
        client(handler).backups.export(WS)
        self.assertNotIn("async", sent[0])

    def test_previews_are_validated_and_send_what_a_start_would(self):
        preview = {"executable": "cargo", "arguments": ["build", "-j", "2"], "presetApplied": True, "changed": True, "notes": ["cargo -j 2"],
                   "resources": {"profile": "balanced"}}
        sent = []

        def handler(request, transport):
            sent.append((request["method"], request["params"])); transport.reply(request, preview)
        c = client(handler)
        self.assertEqual(c.jobs.preview("cargo", ["build"], resources={"profile": "balanced", "applyBuildPreset": True})["arguments"], ["build", "-j", "2"])
        self.assertEqual(sent[0], ("job.preview", {"executable": "cargo", "args": ["build"], "resources": {"profile": "balanced", "applyBuildPreset": True}}))
        self.assertTrue(c.workspaces.preview_task(WS, "build")["changed"])
        with self.assertRaises(ProtocolError):
            client(replying({**preview, "arguments": [1]})).jobs.preview("cargo", [])
        with self.assertRaises(ValueError):
            c.jobs.preview("cargo", "build")


class WorkspaceAndJobsCompatibilityTest(unittest.TestCase):
    WSDATA = {"id": WS, "name": "app", "rootId": "home", "path": "p", "createdAt": 1, "updatedAt": 2, "available": True,
              "taskPresets": [{"id": "build", "label": "Build", "executable": "cargo", "args": ["build"], "timeoutMs": 0}]}

    def test_a_workspace_without_the_new_members_parses_as_before(self):
        w = client(replying({"workspace": self.WSDATA})).workspaces.get(WS)
        self.assertIsNone(w.resource_profile); self.assertIsNone(w.services); self.assertIsNone(w.task_presets[0].resources)
        self.assertNotIn("resources", w.task_presets[0].to_wire())

    def test_new_members_parse_and_invalid_ones_are_rejected(self):
        data = {**self.WSDATA, "resourceProfile": "balanced", "services": [{"id": "dev"}]}
        data["taskPresets"] = [{**self.WSDATA["taskPresets"][0], "resources": {"profile": "protected"}}]
        w = client(replying({"workspace": data})).workspaces.get(WS)
        self.assertEqual((w.resource_profile, w.task_presets[0].resources), ("balanced", {"profile": "protected"}))
        with self.assertRaises(ProtocolError):
            client(replying({"workspace": {**self.WSDATA, "resourceProfile": "turbo"}})).workspaces.get(WS)

    def test_update_distinguishes_clearing_the_profile_from_leaving_it(self):
        sent = []

        def handler(request, transport):
            sent.append(request["params"]); transport.reply(request, {"workspace": self.WSDATA})
        c = client(handler)
        c.workspaces.update(WS, name="x")
        c.workspaces.update(WS, resource_profile=None)
        c.workspaces.update(WS, resource_profile="protected", services=[{"id": "dev", "executable": "node", "args": []}])
        self.assertNotIn("resourceProfile", sent[0])
        self.assertIsNone(sent[1]["resourceProfile"]); self.assertIn("resourceProfile", sent[1])
        self.assertEqual(sent[2]["resourceProfile"], "protected")

    def test_job_start_sends_resources_only_when_given(self):
        sent = []

        def handler(request, transport):
            sent.append(request["params"]); transport.reply(request, {"job": JOB})
        c = client(handler)
        c.jobs.start("cargo", ["build"])
        c.jobs.start("cargo", ["build"], resources={"profile": "balanced", "applyBuildPreset": True})
        self.assertNotIn("resources", sent[0])
        self.assertEqual(sent[1]["resources"], {"profile": "balanced", "applyBuildPreset": True})

    def test_unavailable_methods_raise_unavailable(self):
        c = TermuxClient(FakeTransport(["system.hello", "system.capabilities"], replying({})))
        with self.assertRaises(UnavailableError):
            c.resources.snapshot()
        with self.assertRaises(UnavailableError):
            c.services.list(WS)


if __name__ == "__main__":
    unittest.main()

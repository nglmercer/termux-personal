import json
import unittest
from collections import deque

from termux_personal import ProtocolError, TermuxClient, TransportError, UnavailableError
from termux_personal.jobs import JobWaitTimeoutError

ALL = ["system.hello", "system.capabilities", "job.start", "job.list", "job.get", "job.output.read", "job.cancel"]
JOB = {"id": "job_1", "state": "exited", "startedAt": 1, "endedAt": 2, "exitCode": 0, "failureCode": None, "pid": 42,
       "workspaceId": None, "output": {"stdoutEndOffset": 3, "stderrEndOffset": 0}}


class FakeTransport:
    """Implements the frame interface used by TermuxClient; the handler answers each non-negotiation request."""

    def __init__(self, methods, handler=lambda request, transport: None):
        self.methods = methods
        self.handler = handler
        self.requests = []
        self.responses = deque()
        self.closed = False

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

    def error(self, request, code, message):
        self.responses.append(json.dumps({"jsonrpc": "2.0", "id": request["id"], "error": {"code": code, "message": message}}).encode("utf-8"))

    def read_frame(self):
        if not self.responses:
            raise EOFError()
        return self.responses.popleft()

    def close(self):
        self.closed = True

    def sent(self):
        return [request["method"] for request in self.requests]


class JobsTest(unittest.TestCase):
    def test_start_sends_only_documented_fields(self):
        def handle(request, transport):
            transport.reply(request, {"job": dict(JOB, state="queued", exitCode=None, endedAt=None, startedAt=None)})
        peer = FakeTransport(ALL, handle)
        with TermuxClient(peer) as client:
            record = client.jobs.start("/bin/echo", ["hi"], client_request_id="req-1")
        self.assertEqual(record.state, "queued")
        start = next(request for request in peer.requests if request["method"] == "job.start")
        self.assertEqual(start["params"], {"executable": "/bin/echo", "args": ["hi"], "clientRequestId": "req-1"})

    def test_unparseable_start_response_reports_unknown_outcome_without_retry(self):
        peer = FakeTransport(ALL, lambda request, transport: transport.reply(request, {"job": {"id": 7}}))
        with TermuxClient(peer) as client:
            with self.assertRaises(ProtocolError) as caught:
                client.jobs.start("/bin/echo", [])
        self.assertTrue(caught.exception.outcome_unknown)
        self.assertEqual(peer.sent().count("job.start"), 1)

    def test_missing_method_is_not_sent(self):
        peer = FakeTransport(["system.hello", "system.capabilities"])
        with TermuxClient(peer) as client:
            with self.assertRaises(UnavailableError):
                client.jobs.start("/bin/echo", [])
        self.assertNotIn("job.start", peer.sent())

    def test_read_output_returns_binary_and_rejects_bad_offsets(self):
        def handle(request, transport):
            transport.reply(request, {"offset": 0, "nextOffset": 3, "endOffset": 3, "gap": False, "length": 3,
                                      "finished": True, "complete": True})
            transport.responses.append(bytes([104, 105, 10]))
        with TermuxClient(FakeTransport(ALL, handle)) as client:
            chunk = client.jobs.read_output("job_1", "stdout", 0, 16)
        self.assertEqual(chunk.data, bytes([104, 105, 10]))
        self.assertTrue(chunk.finished)

        def bad(request, transport):
            transport.reply(request, {"offset": 0, "nextOffset": 5, "endOffset": 9, "gap": False, "length": 3,
                                      "finished": True, "complete": True})
            transport.responses.append(bytes([1, 2, 3]))
        with TermuxClient(FakeTransport(ALL, bad)) as client:
            with self.assertRaises(ProtocolError):
                client.jobs.read_output("job_1", "stdout", 0)

    def test_wait_polls_to_terminal_and_times_out(self):
        polls = []

        def handle(request, transport):
            polls.append(request)
            transport.reply(request, {"job": dict(JOB, state="running" if len(polls) < 3 else "exited")})
        with TermuxClient(FakeTransport(ALL, handle)) as client:
            self.assertEqual(client.jobs.wait("job_1", poll_interval=0.001).state, "exited")
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"job": dict(JOB, state="running")}))) as client:
            with self.assertRaises(JobWaitTimeoutError):
                client.jobs.wait("job_1", timeout=0.01, poll_interval=0.001)

    def test_follow_yields_in_order_and_reports_gap(self):
        replies = [
            ({"offset": 0, "nextOffset": 2, "endOffset": 9, "gap": False, "length": 2, "finished": False, "complete": False}, bytes([97, 98])),
            ({"offset": 5, "nextOffset": 7, "endOffset": 7, "gap": True, "length": 2, "finished": True, "complete": True}, bytes([99, 100])),
        ]

        def handle(request, transport):
            metadata, data = replies.pop(0)
            transport.reply(request, metadata)
            transport.responses.append(data)
        with TermuxClient(FakeTransport(ALL, handle)) as client:
            seen = [(chunk.gap, list(chunk.data)) for chunk in client.jobs.follow("job_1", "stdout", poll_interval=0.001)]
        self.assertEqual(seen, [(False, [97, 98]), (True, [99, 100])])

    def test_cancel_requires_explicit_acknowledgement(self):
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"accepted": True}))) as client:
            client.jobs.cancel("job_1")
        with TermuxClient(FakeTransport(ALL, lambda r, t: t.reply(r, {"accepted": False}))) as client:
            with self.assertRaises(ProtocolError) as caught:
                client.jobs.cancel("job_1")
        self.assertTrue(caught.exception.outcome_unknown)

    def test_lost_start_response_has_unknown_outcome(self):
        peer = FakeTransport(ALL)
        with TermuxClient(peer) as client:
            with self.assertRaises(TransportError) as caught:
                client.jobs.start("/bin/echo", [])
        self.assertTrue(caught.exception.outcome_unknown)
        self.assertEqual(peer.sent().count("job.start"), 1)


if __name__ == "__main__":
    unittest.main()

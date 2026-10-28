import json
import socket
import struct
import threading
import tempfile
import time
from pathlib import Path
import unittest

from termux_personal import RpcError, SocketTransport, TermuxClient, TransportError


def read_exact(sock, length):
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise EOFError()
        data.extend(chunk)
    return bytes(data)


def read_frame(sock):
    return read_exact(sock, struct.unpack(">I", read_exact(sock, 4))[0])


def send_frame(sock, data):
    frame = struct.pack(">I", len(data)) + data
    for byte in frame:  # Deliberately fragment headers and bodies.
        sock.sendall(bytes([byte]))


def respond(sock, request, result=None, error=None):
    reply = {"jsonrpc": "2.0", "id": request["id"]}
    reply["error" if error else "result"] = error if error else result
    send_frame(sock, json.dumps(reply).encode())


class ClientTest(unittest.TestCase):
    def pair(self, handler):
        client, server = socket.socketpair()
        client.settimeout(3)
        failures = []

        def run():
            try:
                with server:
                    hello = json.loads(read_frame(server))
                    self.assertEqual({"version": 1}, hello["params"])
                    respond(server, hello, {"version": 1})
                    handler(server)
            except BaseException as exc:
                failures.append(exc)

        thread = threading.Thread(target=run)
        thread.start()
        self.addCleanup(lambda: self.finish(thread, failures))
        instance = TermuxClient(SocketTransport(client))
        self.addCleanup(instance.close)
        return instance

    def finish(self, thread, failures):
        thread.join(5)
        self.assertFalse(thread.is_alive())
        if failures:
            raise failures[0]

    def test_describe_falls_back_only_on_missing_method(self):
        def server(sock):
            request = json.loads(read_frame(sock))
            self.assertEqual("system.describe", request["method"])
            respond(sock, request, error={"code": -32601, "message": "Method not found"})
            request = json.loads(read_frame(sock))
            self.assertEqual("system.capabilities", request["method"])
            respond(sock, request, {"methods": ["session.list"]})
        client = self.pair(server)
        self.assertEqual({"protocolVersion": 1, "methods": [{"name": "session.list"}], "legacy": True}, client.describe())

    def test_binary_request_and_response_keep_frames_aligned(self):
        def server(sock):
            request = json.loads(read_frame(sock))
            self.assertEqual("session.write", request["method"])
            self.assertEqual(b"\x00\xff\x03", read_frame(sock))
            respond(sock, request, {"ok": True})
            request = json.loads(read_frame(sock))
            self.assertEqual("session.output.read", request["method"])
            respond(sock, request, {"offset": 0, "nextOffset": 3, "endOffset": 3, "gap": False, "length": 3})
            send_frame(sock, b"\x00\xff\x03")
        client = self.pair(server)
        self.assertEqual({"ok": True}, client.request("session.write", {"id": "s1", "length": 3}, binary=b"\x00\xff\x03")[0])
        self.assertEqual(b"\x00\xff\x03", client.request("session.output.read", {"id": "s1", "offset": 0, "limit": 3})[1])

    def test_lost_mutation_response_is_unknown_and_not_retried(self):
        seen = []
        def server(sock):
            seen.append(json.loads(read_frame(sock))["method"])
        client = self.pair(server)
        with self.assertRaises(TransportError) as error:
            client.request("session.rename", {"id": "s1", "name": "x"})
        self.assertTrue(error.exception.outcome_unknown)
        self.assertEqual(["session.rename"], seen)
        with self.assertRaises(TransportError):
            client.request("session.list")

    def test_dispatch_unknown_outcome_is_exposed(self):
        def server(sock):
            request = json.loads(read_frame(sock))
            respond(sock, request, error={"code": -32002, "message": "OUTCOME_UNKNOWN"})
        client = self.pair(server)
        with self.assertRaises(RpcError) as error:
            client.request("session.rename", {"id": "s1", "name": "x"})
        self.assertTrue(error.exception.outcome_unknown)

    def test_bad_binary_response_taints_connection(self):
        def server(sock):
            request = json.loads(read_frame(sock))
            respond(sock, request, {"length": 3})
            send_frame(sock, b"ab")
        client = self.pair(server)
        with self.assertRaises(TransportError) as error:
            client.request("session.output.read", {"id": "s1", "offset": 0, "limit": 3})
        self.assertFalse(error.exception.outcome_unknown)

    def test_mismatched_id_after_mutation_is_unknown(self):
        def server(sock):
            request = json.loads(read_frame(sock))
            request["id"] += 1
            respond(sock, request, {"ok": True})
        client = self.pair(server)
        with self.assertRaises(TransportError) as error:
            client.request("session.rename", {"id": "s1", "name": "x"})
        self.assertTrue(error.exception.outcome_unknown)

    def test_adb_non_debuggable_is_reported_without_raw_stderr(self):
        with tempfile.TemporaryDirectory(prefix="termux-adb-error-") as directory:
            adb = Path(directory) / "adb"
            adb.write_text("#!/bin/sh\necho 'run-as: package not debuggable: private detail' >&2\nexit 1\n")
            adb.chmod(0o700)
            with self.assertRaises(TransportError) as error:
                TermuxClient.connect_adb(adb_path=str(adb))
            self.assertEqual("ADB run-as requires a debuggable Termux installation", str(error.exception))
            self.assertFalse(error.exception.outcome_unknown)

    def test_adb_stalled_handshake_times_out(self):
        with tempfile.TemporaryDirectory(prefix="termux-adb-stall-") as directory:
            adb = Path(directory) / "adb"
            adb.write_text("#!/bin/sh\nexec sleep 5\n")
            adb.chmod(0o700)
            start = time.monotonic()
            with self.assertRaises(TransportError) as error:
                TermuxClient.connect_adb(adb_path=str(adb), timeout=0.1)
            self.assertFalse(error.exception.outcome_unknown)
            self.assertLess(time.monotonic() - start, 2)


if __name__ == "__main__":
    unittest.main()

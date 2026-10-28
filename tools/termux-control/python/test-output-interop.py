#!/usr/bin/env python3
"""Host-only binary output protocol test; uses simulated service and credentials."""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile

with tempfile.TemporaryDirectory(prefix="out-", dir=sys.argv[2]) as directory:
    path = os.path.join(directory, "socket")
    server = subprocess.Popen(["java", "-cp", sys.argv[1], "com.termux.app.control.ipc.HostOutputSocketSmoke", path],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert server.stdout.readline().strip() == "READY"
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as conn:
            conn.settimeout(5)
            conn.connect(path)
            def send(id_, method, params):
                body = json.dumps({"jsonrpc": "2.0", "id": id_, "method": method, "params": params}).encode()
                conn.sendall(struct.pack(">I", len(body)) + body)
            def read_exact(count):
                chunks = bytearray()
                while len(chunks) < count:
                    part = conn.recv(count - len(chunks))
                    if not part:
                        raise AssertionError("truncated frame")
                    chunks.extend(part)
                return bytes(chunks)
            def frame():
                length = struct.unpack(">I", read_exact(4))[0]
                assert 0 < length <= 65536
                return read_exact(length)
            send(1, "system.hello", {"version": 1})
            assert json.loads(frame())["result"]["version"] == 1
            send(2, "session.output.subscribe", {"id": "s"})
            assert json.loads(frame())["result"]["ok"]
            send(3, "session.output.read", {"id": "s", "offset": 0, "limit": 16})
            result = json.loads(frame())["result"]
            assert result["length"] == 4 and result["nextOffset"] == 4 and not result["gap"]
            assert frame() == bytes([0, 255, 27, 10])
        server.wait(timeout=5)
        assert server.returncode == 0, server.stderr.read()
        print("host socket output framing and disconnect cleanup passed")
    finally:
        if server.poll() is None:
            server.kill()
            server.wait()

#!/usr/bin/env python3
"""Mock-socket tests for the native output reader."""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading

binary, root = map(os.path.abspath, sys.argv[1:3])
os.makedirs(root, exist_ok=True)

def exact(conn, count):
    data = bytearray()
    while len(data) < count:
        part = conn.recv(count - len(data))
        if not part:
            raise AssertionError("truncated frame")
        data.extend(part)
    return bytes(data)

def receive(conn):
    size = struct.unpack(">I", exact(conn, 4))[0]
    assert 0 < size <= 65536
    return json.loads(exact(conn, size))

def send(conn, obj):
    data = json.dumps(obj, separators=(",", ":")).encode()
    conn.sendall(struct.pack(">I", len(data)) + data)

def run_case(mode):
    errors = []
    with tempfile.TemporaryDirectory(prefix="follow-", dir=root) as directory:
        path = os.path.join(directory, "s")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path); listener.listen(1)
        def server():
            try:
                with listener.accept()[0] as conn:
                    conn.settimeout(5)
                    assert receive(conn)["method"] == "system.hello"
                    send(conn, {"jsonrpc":"2.0", "id":1, "result":{"version":1}})
                    sub = receive(conn)
                    assert sub["method"] == "session.output.subscribe" and sub["params"]["id"] == "s"
                    send(conn, {"jsonrpc":"2.0", "id":2, "result":{"ok": True}})
                    read = receive(conn)
                    assert read["method"] == "session.output.read" and read["params"]["offset"] == 0
                    if mode == "bad_metadata":
                        result = {"offset":0,"nextOffset":2,"endOffset":2,"gap":False,"length":3}
                        send(conn, {"jsonrpc":"2.0", "id":3, "result":result})
                        return
                    result = {"offset":2,"nextOffset":5,"endOffset":5,"gap":True,"length":3}
                    send(conn, {"jsonrpc":"2.0", "id":3, "result":result})
                    conn.sendall(struct.pack(">I", 2 if mode == "bad_frame" else 3) + b"\x00\xff\x1b")
                    if mode == "good":
                        read = receive(conn)
                        assert read["params"]["offset"] == 5
                        send(conn, {"jsonrpc":"2.0", "id":3, "error":{"code":-32001,"message":"NOT_RUNNING"}})
            except Exception as exc:
                errors.append(exc)
            finally:
                listener.close()
        thread = threading.Thread(target=server); thread.start()
        result = subprocess.run([binary, "--socket", path, "follow", "s"], capture_output=True, timeout=10)
        thread.join(5)
        assert not thread.is_alive() and not errors, errors
        assert result.returncode != 0
        if mode == "good":
            assert result.stdout == b"\x00\xff\x1b" and b"output gap" in result.stderr
        else:
            assert result.stdout == b"" and b"invalid" in result.stderr

for case in ("good", "bad_metadata", "bad_frame"):
    run_case(case)
print("native output reader mock-socket tests passed")

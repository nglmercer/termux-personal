#!/usr/bin/env python3
"""Host-only protocol tests; Python is not required by the delivered client."""
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

binary = os.path.abspath(sys.argv[1])
base = os.path.abspath(sys.argv[2])
os.makedirs(base, exist_ok=True)


def read_exact(conn, count):
    result = bytearray()
    while len(result) < count:
        part = conn.recv(count - len(result))
        if not part:
            raise AssertionError("truncated client request")
        result.extend(part)
    return bytes(result)


def frame_read(conn):
    size = struct.unpack(">I", read_exact(conn, 4))[0]
    if not 0 < size <= 65536:
        raise AssertionError("invalid client frame")
    return json.loads(read_exact(conn, size).decode("utf-8"))


def frame_write(conn, value):
    payload = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    frame = struct.pack(">I", len(payload)) + payload
    for offset in range(0, len(frame), 3):
        conn.sendall(frame[offset:offset + 3])


def run_case(args, response, inspect=lambda req: None, payload=None):
    with tempfile.TemporaryDirectory(prefix="tc-", dir=base) as directory:
        path = os.path.join(directory, "s")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(path)
        listener.listen(1)
        errors = []

        def serve():
            try:
                with listener.accept()[0] as conn:
                    conn.settimeout(4)
                    hello = frame_read(conn)
                    assert hello == {"jsonrpc": "2.0", "id": 1, "method": "system.hello", "params": {"version": 1}}
                    frame_write(conn, {"jsonrpc": "2.0", "id": 1, "result": {"version": 1}})
                    request = frame_read(conn)
                    inspect(request)
                    if payload is not None:
                        size = struct.unpack(">I", read_exact(conn, 4))[0]
                        assert size == len(payload)
                        assert read_exact(conn, size) == payload
                    if isinstance(response, bytes):
                        conn.sendall(response)
                    else:
                        frame_write(conn, response)
            except BaseException as exc:
                errors.append(exc)
            finally:
                listener.close()

        thread = threading.Thread(target=serve)
        thread.start()
        try:
            result = subprocess.run([binary, "--socket", path] + args, input=payload,
                                    capture_output=True, text=payload is None, timeout=10)
        finally:
            thread.join(6)
            listener.close()
        if thread.is_alive():
            raise AssertionError("server did not stop")
        if errors:
            raise errors[0]
        return result


reply = {"jsonrpc": "2.0", "id": 2, "result": {"sessions": [{"id": "s1", "name": 'a "error": value'}]}}
result = run_case(["list"], reply, lambda r: r["method"] == "session.list" or (_ for _ in ()).throw(AssertionError(r)))
assert result.returncode == 0, result.stderr
assert json.loads(result.stdout) == reply

name = 'line\nquote" slash\\ unicode 世界'
def check_rename(request):
    assert request == {"jsonrpc": "2.0", "id": 2, "method": "session.rename", "params": {"id": "s1", "name": name}}
result = run_case(["rename", "s1", name], {"jsonrpc": "2.0", "id": 2, "result": {"ok": True}}, check_rename)
assert result.returncode == 0, result.stderr

binary_payload = b"\x00\xff\x1bhello\n"
def check_write(request):
    assert request == {"jsonrpc": "2.0", "id": 2, "method": "session.write", "params": {"id": "s1", "length": len(binary_payload)}}
result = run_case(["write", "s1"], {"jsonrpc": "2.0", "id": 2, "result": {"ok": True}}, check_write, binary_payload)
assert result.returncode == 0, result.stderr

large_payload = bytes(range(256)) * 256
result = run_case(["write", "s1"], {"jsonrpc": "2.0", "id": 2, "result": {"ok": True}},
                  lambda request: request["params"]["length"] == 65536 or (_ for _ in ()).throw(AssertionError(request)), large_payload)
assert result.returncode == 0, result.stderr
for invalid in (b"", b"x" * 65537):
    result = subprocess.run([binary, "--socket", os.path.join(base, "missing.sock"), "write", "s1"],
                            input=invalid, capture_output=True, timeout=10)
    assert result.returncode != 0 and b"1 to 65536" in result.stderr

result = run_case(["close", "s1"], {"jsonrpc": "2.0", "id": 2, "error": {"code": -32001, "message": "DENIED"}})
assert result.returncode != 0 and "DENIED" in result.stderr
result = run_case(["list"], struct.pack(">I", 65537))
assert result.returncode != 0 and "invalid response length" in result.stderr
result = run_case(["list"], {"jsonrpc": "2.0", "id": 3, "result": {}})
assert result.returncode != 0 and "invalid server response" in result.stderr
result = run_case(["list"], struct.pack(">I", 2) + b"{}")
assert result.returncode != 0 and "invalid server response" in result.stderr
result = run_case(["close", "s1"], struct.pack(">I", 2) + b"{}")
assert result.returncode != 0 and "outcome may be unknown" in result.stderr
result = subprocess.run([binary, "--socket", os.path.join(base, "missing.sock"), "list"], capture_output=True, text=True, timeout=10)
assert result.returncode != 0 and "cannot connect" in result.stderr
# A peer that stalls after receiving the handshake cannot hold the client indefinitely.
with tempfile.TemporaryDirectory(prefix="tc-", dir=base) as directory:
    path = os.path.join(directory, "s")
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(path)
    listener.listen(1)
    def stall():
        with listener.accept()[0] as conn:
            frame_read(conn)
            time.sleep(6)
        listener.close()
    thread = threading.Thread(target=stall)
    thread.start()
    started = time.monotonic()
    result = subprocess.run([binary, "--socket", path, "list"], capture_output=True, text=True, timeout=9)
    elapsed = time.monotonic() - started
    thread.join(3)
    assert result.returncode != 0 and "timed out" in result.stderr
    assert 4 <= elapsed < 8, elapsed
    assert not thread.is_alive()
print("termux-control mock socket tests passed")

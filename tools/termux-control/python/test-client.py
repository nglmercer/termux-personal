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
# Managed jobs and workspaces: fixed methods with exact parameters, and usage errors that never reach the socket.
def expect_request(method, params):
    def check(request):
        assert request == {"jsonrpc": "2.0", "id": 2, "method": method, "params": params}, request
    return check

job_result = {"jsonrpc": "2.0", "id": 2, "result": {"job": {"id": "job_1"}}}
result = run_case(["jobs", "list"], {"jsonrpc": "2.0", "id": 2, "result": {"jobs": [], "nextCursor": None}}, expect_request("job.list", {}))
assert result.returncode == 0, result.stderr
result = run_case(["jobs", "get", "job_1"], job_result, expect_request("job.get", {"id": "job_1"}))
assert result.returncode == 0, result.stderr
result = run_case(["jobs", "cancel", "job_1"], {"jsonrpc": "2.0", "id": 2, "result": {"accepted": True}}, expect_request("job.cancel", {"id": "job_1"}))
assert result.returncode == 0, result.stderr
ws = "ws_0123456789abcdef"
result = run_case(["workspaces", "git", ws, "status"], {"jsonrpc": "2.0", "id": 2, "result": {"job": {"id": "job_2"}}},
                  expect_request("workspace.git", {"workspaceId": ws, "subcommand": "status"}))
assert result.returncode == 0, result.stderr
result = run_case(["workspaces", "run", ws, "test"], {"jsonrpc": "2.0", "id": 2, "result": {"job": {"id": "job_3"}}},
                  expect_request("workspace.task.run", {"workspaceId": ws, "taskId": "test"}))
assert result.returncode == 0, result.stderr
result = run_case(["workspaces", "tasks", ws], {"jsonrpc": "2.0", "id": 2, "result": {"tasks": []}}, expect_request("workspace.tasks", {"id": ws}))
assert result.returncode == 0, result.stderr
quoted_arg = 'name "with" quote'
result = run_case(["jobs", "get", quoted_arg], job_result, expect_request("job.get", {"id": quoted_arg}))
assert result.returncode == 0, result.stderr
# A lost response to a mutation reports unknown outcome; the client never retries it.
result = run_case(["jobs", "cancel", "job_1"], struct.pack(">I", 2) + b"{}")
assert result.returncode != 0 and "outcome may be unknown" in result.stderr
result = run_case(["workspaces", "create", "my-app", "home", "projects/my-app"], {"jsonrpc": "2.0", "id": 2, "result": {"workspace": {"id": ws}}},
                  expect_request("workspace.create", {"name": "my-app", "rootId": "home", "path": "projects/my-app"}))
assert result.returncode == 0, result.stderr
result = run_case(["workspaces", "select", ws], {"jsonrpc": "2.0", "id": 2, "result": {"workspace": {"id": ws}, "activeId": ws}},
                  expect_request("workspace.select", {"id": ws}))
assert result.returncode == 0, result.stderr
result = run_case(["workspaces", "remove", ws], {"jsonrpc": "2.0", "id": 2, "result": {"accepted": True}},
                  expect_request("workspace.remove", {"id": ws}))
assert result.returncode == 0, result.stderr
missing = os.path.join(base, "absent.sock")
for usage_args in (["jobs"], ["jobs", "bogus"], ["jobs", "list", "extra"], ["workspaces", "git", ws, "commit"],
                   ["workspaces", "run", ws], ["workspaces", "create", "x"], ["--resume", "jobs", "list"], ["workspaces", "list", "extra"]):
    result = subprocess.run([binary, "--socket", missing] + usage_args, capture_output=True, text=True, timeout=10)
    assert result.returncode == 2 and ("Usage" in result.stderr or "git subcommand" in result.stderr), (usage_args, result.returncode, result.stderr)

result = run_case(["workspaces", "update", ws, "renamed", "home", "projects/other"], {"jsonrpc": "2.0", "id": 2, "result": {"workspace": {"id": ws}}},
                  expect_request("workspace.update", {"id": ws, "name": "renamed", "rootId": "home", "path": "projects/other"}))
assert result.returncode == 0, result.stderr
argv_with_spaces = ["test", "--workspace", "a b", "quote\"d", ""]
result = run_case(["jobs", "start", "--cwd", "/home", "--timeout", "5000", "--", "/usr/bin/cargo"] + argv_with_spaces, job_result,
                  expect_request("job.start", {"executable": "/usr/bin/cargo", "args": argv_with_spaces, "cwd": "/home", "timeoutMs": 5000}))
assert result.returncode == 0, result.stderr
for usage_args in (["jobs", "start", "--", ], ["jobs", "start", "cargo"], ["jobs", "start", "--timeout", "0", "--", "x"],
                   ["jobs", "start", "--bogus", "x", "--", "y"], ["jobs", "logs"], ["jobs", "logs", "job_1", "--stream", "both"],
                   ["workspaces", "update", ws, "name"]):
    result = subprocess.run([binary, "--socket", missing] + usage_args, capture_output=True, text=True, timeout=10)
    assert result.returncode == 2 and "Usage" in result.stderr, (usage_args, result.returncode, result.stderr)


def run_script(args, steps):
    """Serves one connection; each step checks a request and replies with JSON and an optional binary frame."""
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
                    assert frame_read(conn)["method"] == "system.hello"
                    frame_write(conn, {"jsonrpc": "2.0", "id": 1, "result": {"version": 1}})
                    for check, reply, data in steps:
                        request = frame_read(conn)
                        check(request)
                        frame_write(conn, dict(reply, id=request["id"]))
                        if data:
                            conn.sendall(struct.pack(">I", len(data)) + data)
            except BaseException as exc:
                errors.append(exc)
            finally:
                listener.close()

        thread = threading.Thread(target=serve)
        thread.start()
        try:
            result = subprocess.run([binary, "--socket", path] + args, capture_output=True, timeout=15)
        finally:
            thread.join(8)
            listener.close()
        if errors:
            raise errors[0]
        return result


def output_step(stream, offset, data, finished, end=None):
    def check(request):
        assert request["method"] == "job.output.read" and request["params"] == {"id": "job_1", "stream": stream, "offset": offset, "limit": 65536}, request
    end = offset + len(data) if end is None else end
    reply = {"jsonrpc": "2.0", "result": {"offset": offset, "nextOffset": offset + len(data), "endOffset": end, "gap": False,
                                          "length": len(data), "finished": finished, "complete": finished}}
    return check, reply, data


def get_step(state, exit_code):
    def check(request):
        assert request["method"] == "job.get" and request["params"] == {"id": "job_1"}, request
    return check, {"jsonrpc": "2.0", "result": {"job": {"id": "job_1", "state": state, "exitCode": exit_code}}}, None


start_step = (lambda r: r["method"] == "job.start" or (_ for _ in ()).throw(AssertionError(r)),
              {"jsonrpc": "2.0", "result": {"job": {"id": "job_1", "state": "starting", "exitCode": None}}}, None)
# run streams both outputs to their own descriptors, then returns the job's exit code.
result = run_script(["jobs", "run", "--", "/bin/sh", "-c", "x"], [
    start_step, output_step("stdout", 0, b"out", False), output_step("stderr", 0, b"", False),
    output_step("stdout", 3, b"", True), output_step("stderr", 0, b"err", True),
    get_step("running", None), get_step("exited", 3)])
assert result.returncode == 3 and result.stdout == b"out" and result.stderr == b"err", result
for state, code in (("timed_out", 124), ("cancelled", 130), ("failed", 125)):
    result = run_script(["jobs", "run", "--", "/bin/true"], [
        start_step, output_step("stdout", 0, b"", True), output_step("stderr", 0, b"", True), get_step(state, None)])
    assert result.returncode == code, (state, result)
# logs prints what is available; --follow waits for the finished stream.
result = run_script(["jobs", "logs", "job_1", "--stream", "stderr"], [
    output_step("stderr", 0, b"partial", False, end=7), output_step("stderr", 7, b"", False)])
assert result.returncode == 0 and result.stdout == b"partial", result
result = run_script(["jobs", "logs", "job_1", "--follow"], [
    output_step("stdout", 0, b"a", False), output_step("stdout", 1, b"", False), output_step("stdout", 1, b"b", True)])
assert result.returncode == 0 and result.stdout == b"ab", result

print("termux-control mock socket tests passed")

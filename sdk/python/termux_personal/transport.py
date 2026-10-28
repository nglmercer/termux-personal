"""Bounded, synchronous v1 stream framing."""
import socket
import struct
import os
import select
import shlex
import subprocess
import threading
import time
from typing import Protocol

from .errors import ProtocolError

MAX_FRAME = 1024 * 1024
MAX_MESSAGE = 64 * 1024


class FrameTransport(Protocol):
    def write_frame(self, data: bytes, limit: int = MAX_MESSAGE) -> None: ...
    def read_frame(self, limit: int = MAX_MESSAGE) -> bytes: ...
    def close(self) -> None: ...


class SocketTransport:
    def __init__(self, sock: socket.socket):
        self._socket = sock

    @classmethod
    def connect(cls, path: str, timeout: float = 5.0):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            sock.settimeout(timeout)
            sock.connect(path)
            return cls(sock)
        except BaseException:
            sock.close()
            raise

    def write_frame(self, data: bytes, limit: int = MAX_MESSAGE) -> None:
        if not 0 < len(data) <= min(limit, MAX_FRAME):
            raise ProtocolError("Invalid frame length")
        self._socket.sendall(struct.pack(">I", len(data)) + data)

    def read_frame(self, limit: int = MAX_MESSAGE) -> bytes:
        length = struct.unpack(">I", self._read_exact(4))[0]
        if not 0 < length <= min(limit, MAX_FRAME):
            raise ProtocolError("Invalid frame length")
        return self._read_exact(length)

    def _read_exact(self, length: int) -> bytes:
        result = bytearray()
        while len(result) < length:
            chunk = self._socket.recv(length - len(result))
            if not chunk:
                raise EOFError("Connection closed during frame")
            result.extend(chunk)
        return bytes(result)

    def close(self) -> None:
        self._socket.close()


class AdbTransport:
    """Debug-only framed stream through run-as and the installed native bridge."""

    def __init__(self, process: subprocess.Popen, timeout: float):
        self._process = process
        self._timeout = timeout
        self._stderr = bytearray()
        self._stderr_done = threading.Event()
        os.set_blocking(process.stdin.fileno(), False)
        os.set_blocking(process.stdout.fileno(), False)
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    @classmethod
    def connect(cls, *, serial=None, adb_path="adb", socket_path="/data/data/com.termux/files/control-ipc/control.sock",
                bridge_path="/data/data/com.termux/files/termux-control-adb", timeout=5.0):
        if timeout <= 0:
            raise ValueError("Invalid transport timeout")
        command = shlex.join(["run-as", "com.termux", bridge_path, "--socket", socket_path, "bridge"])
        args = [adb_path] + (["-s", serial] if serial else []) + ["shell", "-T", command]
        process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
        return cls(process, timeout)

    def _drain_stderr(self):
        try:
            while True:
                chunk = self._process.stderr.read(1024)
                if not chunk:
                    break
                if len(self._stderr) < 4096:
                    self._stderr.extend(chunk[:4096 - len(self._stderr)])
        finally:
            self._process.stderr.close()
            self._stderr_done.set()

    def _failed(self):
        self._stderr_done.wait(0.2)
        if b"not debuggable" in bytes(self._stderr).lower():
            return OSError("ADB run-as requires a debuggable Termux installation")
        return OSError("ADB bridge unavailable; check device, debug build, bridge and local control")

    def _io(self, fd, data=None, length=0):
        deadline = time.monotonic() + self._timeout
        result = bytearray()
        offset = 0
        target = len(data) if data is not None else length
        while offset < target:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("ADB bridge timeout")
            readable, writable, _ = select.select([fd] if data is None else [], [fd] if data is not None else [], [], remaining)
            if not readable and not writable:
                raise TimeoutError("ADB bridge timeout")
            try:
                if data is None:
                    chunk = os.read(fd, target - offset)
                    if not chunk:
                        raise self._failed()
                    result.extend(chunk)
                    offset += len(chunk)
                else:
                    count = os.write(fd, data[offset:])
                    if count <= 0:
                        raise self._failed()
                    offset += count
            except BlockingIOError:
                continue
            except BrokenPipeError as exc:
                raise self._failed() from exc
        return bytes(result)

    def write_frame(self, data: bytes, limit: int = MAX_MESSAGE) -> None:
        if not 0 < len(data) <= min(limit, MAX_FRAME):
            raise ProtocolError("Invalid frame length")
        self._io(self._process.stdin.fileno(), data=struct.pack(">I", len(data)) + data)

    def read_frame(self, limit: int = MAX_MESSAGE) -> bytes:
        fd = self._process.stdout.fileno()
        length = struct.unpack(">I", self._io(fd, length=4))[0]
        if not 0 < length <= min(limit, MAX_FRAME):
            raise ProtocolError("Invalid frame length")
        return self._io(fd, length=length)

    def close(self) -> None:
        for stream in (self._process.stdin, self._process.stdout):
            try:
                stream.close()
            except OSError:
                pass
        if self._process.poll() is None:
            try:
                self._process.terminate()
            except ProcessLookupError:
                pass
            try:
                self._process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                try:
                    self._process.kill()
                except ProcessLookupError:
                    pass
                self._process.wait()

"""Bounded, synchronous v1 stream framing."""
import socket
import struct

from .errors import ProtocolError

MAX_FRAME = 1024 * 1024
MAX_MESSAGE = 64 * 1024


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

"""Typed access to the existing PTY session methods; writes are not jobs."""
from dataclasses import dataclass
from typing import Optional

from .errors import ProtocolError, UnavailableError


@dataclass(frozen=True)
class SessionInfo:
    id: str
    name: Optional[str]
    state: str
    pid: Optional[int]

    @classmethod
    def parse(cls, value):
        if (not isinstance(value, dict) or not {"id", "name", "state", "pid"}.issubset(value) or not isinstance(value.get("id"), str) or
            (value.get("name") is not None and not isinstance(value.get("name"), str)) or
            value.get("state") not in ("starting", "running", "exited") or
            (value.get("pid") is not None and type(value.get("pid")) is not int)):
            raise ProtocolError("Invalid session metadata")
        return cls(value["id"], value.get("name"), value["state"], value.get("pid"))


@dataclass(frozen=True)
class OutputChunk:
    offset: int
    next_offset: int
    end_offset: int
    gap: bool
    data: bytes


class Sessions:
    def __init__(self, client):
        self._client = client

    def _call(self, method, params=None, *, binary=None, extra=()):
        methods = set(self._client.capabilities()["methods"])
        if method not in methods or not all(name in methods for name in extra):
            raise UnavailableError("Method not available: " + method)
        return self._client.request(method, params, binary=binary)

    def list(self):
        result, _ = self._call("session.list")
        if not isinstance(result, dict) or not isinstance(result.get("sessions"), list):
            raise ProtocolError("Invalid session list")
        return [SessionInfo.parse(item) for item in result["sessions"]]

    def create(self, columns: int, rows: int, *, name: Optional[str] = None, cell_width: int = 0, cell_height: int = 0):
        params = {"columns": columns, "rows": rows, "cellWidth": cell_width, "cellHeight": cell_height}
        if name is not None:
            params["name"] = name
        result, _ = self._call("session.create", params)
        try:
            return SessionInfo.parse(result)
        except ProtocolError as exc:
            raise ProtocolError("Invalid created session metadata", outcome_unknown=True) from exc

    def write(self, session_id: str, data: bytes):
        """Write exact bytes to a PTY; acceptance does not imply command completion."""
        result, _ = self._call("session.write", {"id": session_id, "length": len(data)}, binary=data)
        self._expect(result, "ok")

    def rename(self, session_id: str, name: str):
        result, _ = self._call("session.rename", {"id": session_id, "name": name})
        self._expect(result, "ok")

    def resize(self, session_id: str, columns: int, rows: int, *, cell_width: int = 0, cell_height: int = 0):
        result, _ = self._call("session.resize", {"id": session_id, "columns": columns, "rows": rows, "cellWidth": cell_width, "cellHeight": cell_height})
        self._expect(result, "ok")

    def focus(self, session_id: str):
        result, _ = self._call("session.focus", {"id": session_id})
        self._expect(result, "ok")

    def snapshot(self, session_id: str):
        result, _ = self._call("session.snapshot", {"id": session_id})
        if not isinstance(result, dict) or not isinstance(result.get("text"), str):
            raise ProtocolError("Invalid snapshot")
        return result["text"]

    def close(self, session_id: str):
        result, _ = self._call("session.close", {"id": session_id})
        self._expect(result, "accepted")

    def subscribe(self, session_id: str, *, resumable: bool = False):
        params = {"id": session_id}
        if resumable:
            params["resumable"] = True
        result, _ = self._call("session.output.subscribe", params,
                               extra=("session.output.resume",) if resumable else ())
        self._expect(result, "ok")
        if resumable:
            token = result.get("resumeToken")
            if not isinstance(token, str) or len(token) != 64 or any(c not in "0123456789abcdef" for c in token):
                raise ProtocolError("Invalid resume token", outcome_unknown=True)
            return token
        return None

    def resume(self, session_id: str, token: str):
        result, _ = self._call("session.output.resume", {"id": session_id, "token": token})
        self._expect(result, "ok")

    def unsubscribe(self, session_id: str):
        result, _ = self._call("session.output.unsubscribe", {"id": session_id})
        self._expect(result, "ok")

    def read_output(self, session_id: str, offset: int, limit: int = 65536):
        result, data = self._call("session.output.read", {"id": session_id, "offset": offset, "limit": limit})
        if (not isinstance(result, dict) or any(type(result.get(key)) is not int or result[key] < 0 for key in ("offset", "nextOffset", "endOffset")) or
            type(result.get("gap")) is not bool or result.get("length") != len(data) or
            result["nextOffset"] != result["offset"] + len(data) or result["nextOffset"] > result["endOffset"]):
            raise ProtocolError("Invalid output offsets")
        return OutputChunk(result["offset"], result["nextOffset"], result["endOffset"], result["gap"], data)

    @staticmethod
    def _expect(result, key):
        if not isinstance(result, dict) or result.get(key) is not True:
            raise ProtocolError("Invalid session acknowledgement", outcome_unknown=True)

"""Serialized JSON-RPC v1 client. Mutations are never retried automatically."""
import json
import threading
from typing import Any, Mapping, Optional

from .errors import ControlError, ProtocolError, RpcError, TransportError
from .transport import AdbTransport, FrameTransport, MAX_MESSAGE, SocketTransport
from .sessions import Sessions

_READ_ONLY = frozenset({"system.hello", "system.capabilities", "system.describe", "session.list", "session.snapshot", "session.output.read"})


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("Duplicate JSON field")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ProtocolError("Invalid JSON number")


class TermuxClient:
    def __init__(self, transport: FrameTransport):
        self._transport = transport
        self._lock = threading.Lock()
        self._next_id = 1
        self._closed = False
        self.sessions = Sessions(self)
        try:
            hello, _ = self.request("system.hello", {"version": 1})
            if hello != {"version": 1}:
                raise ProtocolError("Unexpected protocol negotiation response")
        except BaseException:
            self.close()
            raise

    @classmethod
    def connect(cls, path: str, timeout: float = 5.0):
        try:
            return cls(SocketTransport.connect(path, timeout))
        except OSError as exc:
            raise TransportError("Unable to connect to local control socket") from exc

    @classmethod
    def connect_adb(cls, **options):
        try:
            return cls(AdbTransport.connect(**options))
        except OSError as exc:
            raise TransportError("Unable to start ADB bridge") from exc

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()

    def close(self):
        if not self._closed:
            self._closed = True
            self._transport.close()

    def request(self, method: str, params: Optional[Mapping[str, Any]] = None, *, binary: Optional[bytes] = None):
        """Return (JSON result, binary bytes). Unknown methods are treated as mutations."""
        if not isinstance(method, str) or not method or len(method) > 128:
            raise ValueError("Invalid method")
        if params is not None and not isinstance(params, Mapping):
            raise ValueError("params must be a mapping")
        if method == "session.write":
            if not isinstance(binary, bytes) or not 0 < len(binary) <= MAX_MESSAGE or (params or {}).get("length") != len(binary):
                raise ValueError("session.write requires a matching binary frame")
        elif binary is not None:
            raise ValueError("No binary request route for this method")
        with self._lock:
            if self._closed:
                raise TransportError("Client is closed")
            request_id = self._next_id
            self._next_id += 1
            try:
                request = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": dict(params or {})},
                                     ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
            except (TypeError, ValueError, UnicodeError) as exc:
                raise ValueError("Invalid request parameters") from exc
            if len(request) > MAX_MESSAGE:
                raise ValueError("Request is too large")
            try:
                self._transport.write_frame(request)
                if binary is not None:
                    self._transport.write_frame(binary)
                frame = self._transport.read_frame()
                response = json.loads(frame.decode("utf-8"), object_pairs_hook=_object, parse_constant=_invalid_constant)
                if not isinstance(response, dict) or response.get("jsonrpc") != "2.0" or response.get("id") != request_id or type(response.get("id")) is not int:
                    raise ProtocolError("Invalid or mismatched response")
                if ("result" in response) == ("error" in response):
                    raise ProtocolError("Invalid response envelope")
                if "error" in response:
                    error = response["error"]
                    if not isinstance(error, dict) or type(error.get("code")) is not int or not isinstance(error.get("message"), str):
                        raise ProtocolError("Invalid error response")
                    raise RpcError(error["code"], error["message"], error.get("data"))
                result = response["result"]
                payload = b""
                if method == "session.output.read":
                    if not isinstance(result, dict) or type(result.get("length")) is not int or not 0 <= result["length"] <= MAX_MESSAGE:
                        raise ProtocolError("Invalid binary response metadata")
                    if result["length"]:
                        payload = self._transport.read_frame()
                        if len(payload) != result["length"]:
                            raise ProtocolError("Binary response length mismatch")
                return result, payload
            except RpcError as exc:
                # The v1 server closes after a rejected binary preflight and after dispatch failures.
                if binary is not None or exc.code == -32002:
                    self.close()
                raise
            except (OSError, EOFError, UnicodeError, ValueError, ControlError) as exc:
                self.close()
                message = str(exc) if str(exc).startswith("ADB ") else "Control connection failed"
                raise TransportError(message, outcome_unknown=method not in _READ_ONLY) from exc

    def capabilities(self):
        result, _ = self.request("system.capabilities")
        if not isinstance(result, dict) or not isinstance(result.get("methods"), list) or not all(isinstance(x, str) for x in result["methods"]):
            raise ProtocolError("Invalid capabilities")
        return result

    def describe(self):
        try:
            result, _ = self.request("system.describe")
            if not isinstance(result, dict):
                raise ProtocolError("Invalid description")
            return result
        except RpcError as exc:
            if exc.code != -32601:
                raise
            return {"protocolVersion": 1, "methods": [{"name": name} for name in self.capabilities()["methods"]], "legacy": True}

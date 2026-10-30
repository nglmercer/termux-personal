"""Managed noninteractive jobs. Start and cancel are mutations and are never retried by the SDK."""
import time
from dataclasses import dataclass
from typing import Any, Iterator, List, Mapping, Optional

from .errors import ProtocolError, UnavailableError

STATES = frozenset({"queued", "starting", "running", "cancelling", "exited", "failed", "cancelled", "timed_out", "lost"})
TERMINAL = frozenset({"exited", "failed", "cancelled", "timed_out", "lost"})
STREAMS = frozenset({"stdout", "stderr"})
MAX_READ = 65536


class JobWaitTimeoutError(Exception):
    def __init__(self, job_id: str):
        super().__init__("Timed out waiting for job " + job_id)


def _nullable_int(value) -> bool:
    return value is None or type(value) is int


def _nonnegative(value) -> bool:
    return type(value) is int and value >= 0


@dataclass(frozen=True)
class JobRecord:
    id: str
    state: str
    started_at: Optional[int]
    ended_at: Optional[int]
    exit_code: Optional[int]
    failure_code: Optional[str]
    pid: Optional[int]
    workspace_id: Optional[str]
    stdout_end_offset: int
    stderr_end_offset: int

    @classmethod
    def parse(cls, value):
        output = value.get("output") if isinstance(value, dict) else None
        if (not isinstance(value, dict) or not isinstance(value.get("id"), str) or value.get("state") not in STATES or
            not all(_nullable_int(value.get(key)) for key in ("startedAt", "endedAt", "exitCode", "pid")) or
            not (value.get("failureCode") is None or isinstance(value.get("failureCode"), str)) or
            not (value.get("workspaceId") is None or isinstance(value.get("workspaceId"), str)) or
            not isinstance(output, dict) or not _nonnegative(output.get("stdoutEndOffset")) or
            not _nonnegative(output.get("stderrEndOffset"))):
            raise ProtocolError("Invalid job metadata")
        return cls(value["id"], value["state"], value.get("startedAt"), value.get("endedAt"), value.get("exitCode"),
                   value.get("failureCode"), value.get("pid"), value.get("workspaceId"),
                   output["stdoutEndOffset"], output["stderrEndOffset"])


@dataclass(frozen=True)
class JobOutputChunk:
    offset: int
    next_offset: int
    end_offset: int
    gap: bool
    finished: bool
    complete: bool
    data: bytes


class Jobs:
    def __init__(self, client):
        self._client = client

    def _call(self, method, params=None, *, binary=None):
        methods = set(self._client.capabilities()["methods"])
        if method not in methods:
            raise UnavailableError("Method not available: " + method)
        return self._client.request(method, params, binary=binary)

    def start(self, executable: str, args, *, cwd: Optional[str] = None, workspace_id: Optional[str] = None,
              env: Optional[Mapping[str, str]] = None, stdin: Optional[str] = None, timeout_ms: Optional[int] = None,
              presentation: Optional[str] = None, client_request_id: Optional[str] = None) -> JobRecord:
        if not isinstance(executable, str) or not isinstance(args, (list, tuple)) or not all(isinstance(item, str) for item in args):
            raise ValueError("executable must be a string and args a list of strings")
        params: dict = {"executable": executable, "args": list(args)}
        optional = {"cwd": cwd, "workspaceId": workspace_id, "env": None if env is None else dict(env), "stdin": stdin,
                    "timeoutMs": timeout_ms, "presentation": presentation, "clientRequestId": client_request_id}
        params.update({key: value for key, value in optional.items() if value is not None})
        result, _ = self._call("job.start", params)
        try:
            if not isinstance(result, dict):
                raise ProtocolError("Invalid job start response")
            return JobRecord.parse(result.get("job"))
        except ProtocolError as exc:
            # The job may have been admitted even though its response was unusable.
            raise ProtocolError("Invalid job start response", outcome_unknown=True) from exc

    def list(self, *, workspace_id: Optional[str] = None, cursor: Optional[int] = None, limit: Optional[int] = None) -> tuple:
        params: dict = {}
        if workspace_id is not None:
            params["workspaceId"] = workspace_id
        if cursor is not None:
            params["cursor"] = cursor
        if limit is not None:
            params["limit"] = limit
        result, _ = self._call("job.list", params)
        if (not isinstance(result, dict) or not isinstance(result.get("jobs"), list) or
                not (result.get("nextCursor") is None or _nonnegative(result.get("nextCursor")))):
            raise ProtocolError("Invalid job list")
        return [JobRecord.parse(item) for item in result["jobs"]], result.get("nextCursor")

    def get(self, job_id: str) -> JobRecord:
        result, _ = self._call("job.get", {"id": job_id})
        if not isinstance(result, dict):
            raise ProtocolError("Invalid job response")
        return JobRecord.parse(result.get("job"))

    def cancel(self, job_id: str) -> None:
        result, _ = self._call("job.cancel", {"id": job_id})
        if not isinstance(result, dict) or result.get("accepted") is not True:
            raise ProtocolError("Invalid cancel acknowledgement", outcome_unknown=True)

    def read_output(self, job_id: str, stream: str, offset: int, limit: int = MAX_READ) -> JobOutputChunk:
        if stream not in STREAMS:
            raise ValueError("stream must be stdout or stderr")
        result, data = self._call("job.output.read", {"id": job_id, "stream": stream, "offset": offset, "limit": limit})
        if (not isinstance(result, dict) or not all(_nonnegative(result.get(key)) for key in ("offset", "nextOffset", "endOffset", "length")) or
                not all(isinstance(result.get(key), bool) for key in ("gap", "finished", "complete")) or
                result["length"] != len(data) or result["nextOffset"] != result["offset"] + len(data) or
                result["nextOffset"] > result["endOffset"]):
            raise ProtocolError("Invalid job output offsets")
        return JobOutputChunk(result["offset"], result["nextOffset"], result["endOffset"], result["gap"],
                              result["finished"], result["complete"], data)

    def wait(self, job_id: str, *, timeout: float = 60.0, poll_interval: float = 0.25) -> JobRecord:
        """Client-side polling helper; the server never blocks on it."""
        deadline = time.monotonic() + timeout
        while True:
            record = self.get(job_id)
            if record.state in TERMINAL:
                return record
            if time.monotonic() >= deadline:
                raise JobWaitTimeoutError(job_id)
            time.sleep(poll_interval)

    def follow(self, job_id: str, stream: str, *, poll_interval: float = 0.25) -> Iterator[JobOutputChunk]:
        """Yields output in order until finished. A chunk with gap=True reports bytes that were overwritten."""
        offset = 0
        while True:
            chunk = self.read_output(job_id, stream, offset)
            if chunk.data:
                yield chunk
            offset = chunk.next_offset
            if chunk.finished and (not chunk.data or offset >= chunk.end_offset):
                return
            if not chunk.data:
                time.sleep(poll_interval)

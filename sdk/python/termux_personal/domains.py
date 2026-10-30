"""Read-only process and file access, and workspace definitions with preset runs.

Mutations (create, update, remove, select, run) are never retried by the SDK. Reads are retry-safe.
"""
from dataclasses import dataclass
import re
from typing import Any, List, Mapping, Optional

from .errors import ProtocolError, UnavailableError
from .jobs import JobRecord

PROCESS_SCOPES = frozenset({"owned", "session", "task", "all-visible"})
FILE_TYPES = frozenset({"file", "directory", "symlink", "other"})
_WS_ID = re.compile(r"^ws_[0-9a-f]{16}$")
_PRESET_ID = re.compile(r"^[a-z0-9_-]{1,64}$")


def _nonnegative(value) -> bool:
    return type(value) is int and value >= 0


def _nullable_nonnegative(value) -> bool:
    return value is None or _nonnegative(value)


def _require_object(value, message: str) -> dict:
    if not isinstance(value, dict):
        raise ProtocolError(message)
    return value


def _capabilities(client) -> set:
    return set(client.capabilities()["methods"])


@dataclass(frozen=True)
class FileEntry:
    name: str
    path: str
    type: str
    size: Optional[int]
    mtime: int
    revision: str

    @classmethod
    def parse(cls, value):
        value = _require_object(value, "Invalid file entry")
        if (not isinstance(value.get("name"), str) or not isinstance(value.get("path"), str) or value.get("type") not in FILE_TYPES or
                not _nullable_nonnegative(value.get("size")) or not _nonnegative(value.get("mtime")) or not isinstance(value.get("revision"), str)):
            raise ProtocolError("Invalid file entry")
        return cls(value["name"], value["path"], value["type"], value.get("size"), value["mtime"], value["revision"])


@dataclass(frozen=True)
class FileChunk:
    offset: int
    next_offset: int
    eof: bool
    revision: str
    data: bytes


class Files:
    """Server-named roots only. Paths are root-relative; the SDK never builds device paths."""

    def __init__(self, client):
        self._client = client

    def _call(self, method, params=None, *, binary=None):
        if method not in _capabilities(self._client):
            raise UnavailableError("Method not available: " + method)
        return self._client.request(method, params, binary=binary)

    def roots(self) -> list:
        result, _ = self._call("file.roots", {})
        result = _require_object(result, "Invalid file roots")
        roots = result.get("roots")
        if not isinstance(roots, list) or not all(isinstance(item, dict) and isinstance(item.get("id"), str) and isinstance(item.get("writable"), bool) for item in roots):
            raise ProtocolError("Invalid file roots")
        return roots

    def list(self, root_id: str, *, path: str = "", cursor: Optional[int] = None, limit: Optional[int] = None) -> tuple:
        params: dict = {"rootId": root_id, "path": path}
        if cursor is not None:
            params["cursor"] = cursor
        if limit is not None:
            params["limit"] = limit
        result, _ = self._call("file.list", params)
        result = _require_object(result, "Invalid file list")
        if not isinstance(result.get("entries"), list) or not _nullable_nonnegative(result.get("nextCursor")):
            raise ProtocolError("Invalid file list")
        return [FileEntry.parse(item) for item in result["entries"]], result.get("nextCursor")

    def stat(self, root_id: str, path: str) -> FileEntry:
        result, _ = self._call("file.stat", {"rootId": root_id, "path": path})
        return FileEntry.parse(_require_object(result, "Invalid file stat response").get("entry"))

    def read(self, root_id: str, path: str, offset: int = 0, limit: int = 65536) -> FileChunk:
        result, data = self._call("file.read", {"rootId": root_id, "path": path, "offset": offset, "limit": limit})
        result = _require_object(result, "Invalid file read metadata")
        if (not all(_nonnegative(result.get(key)) for key in ("offset", "nextOffset", "length")) or not isinstance(result.get("eof"), bool) or
                not isinstance(result.get("revision"), str) or result["length"] != len(data) or result["nextOffset"] != result["offset"] + len(data)):
            raise ProtocolError("Invalid file read metadata")
        return FileChunk(result["offset"], result["nextOffset"], result["eof"], result["revision"], data)

    def _entry_call(self, method, params) -> FileEntry:
        result, _ = self._call(method, params)
        try:
            return FileEntry.parse(_require_object(result, "Invalid file response").get("entry"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid file response", outcome_unknown=True) from exc

    def mkdir(self, root_id: str, path: str) -> FileEntry:
        return self._entry_call("file.mkdir", {"rootId": root_id, "path": path})

    def rename(self, root_id: str, path: str, new_path: str, *, if_revision: Optional[str] = None) -> FileEntry:
        """Never overwrites; with if_revision the source must be unchanged."""
        params = {"rootId": root_id, "path": path, "newPath": new_path}
        if if_revision is not None:
            params["ifRevision"] = if_revision
        return self._entry_call("file.rename", params)

    def delete(self, root_id: str, path: str, *, if_revision: Optional[str] = None) -> None:
        """A regular file or an empty directory; there is no recursive delete."""
        params = {"rootId": root_id, "path": path}
        if if_revision is not None:
            params["ifRevision"] = if_revision
        result, _ = self._call("file.delete", params)
        if not isinstance(result, dict) or result.get("accepted") is not True:
            raise ProtocolError("Invalid delete acknowledgement", outcome_unknown=True)

    def upload(self, root_id: str, path: str, data: bytes, *, if_revision: Optional[str] = None, sha256: Optional[str] = None) -> tuple:
        """Uploads in server-sized chunks and commits; returns (entry, sha256). Replacing an existing file requires
        its current revision. On failure the transfer is aborted while the connection is still open; a rejected
        chunk closes the v1 connection, and the server then discards the idle transfer on expiry. Nothing is
        published without a commit. A lost commit reply leaves the outcome unknown."""
        if not isinstance(data, (bytes, bytearray)):
            raise ValueError("data must be bytes")
        params: dict = {"rootId": root_id, "path": path, "totalBytes": len(data)}
        if sha256 is not None:
            params["sha256"] = sha256
        if if_revision is not None:
            params["ifRevision"] = if_revision
            params["overwrite"] = True
        begun, _ = self._call("file.transfer.begin", params)
        if (not isinstance(begun, dict) or not isinstance(begun.get("transferId"), str) or not _nonnegative(begun.get("chunkSize")) or
                begun["chunkSize"] < 1 or begun.get("offset") != 0):
            raise ProtocolError("Invalid transfer start", outcome_unknown=True)
        transfer_id, chunk_size = begun["transferId"], min(begun["chunkSize"], 65536)
        try:
            for offset in range(0, len(data), chunk_size):
                chunk = bytes(data[offset:offset + chunk_size])
                result, _ = self._client.request("file.transfer.write", {"transferId": transfer_id, "offset": offset, "length": len(chunk)},
                                                 binary=chunk)
                if not isinstance(result, dict) or result.get("nextOffset") != offset + len(chunk):
                    raise ProtocolError("Invalid transfer offset")
        except BaseException:
            try:
                self._client.request("file.transfer.abort", {"transferId": transfer_id})
            except Exception:
                pass
            raise
        result, _ = self._client.request("file.transfer.commit", {"transferId": transfer_id})
        if not isinstance(result, dict) or result.get("committed") is not True or not isinstance(result.get("sha256"), str):
            raise ProtocolError("Invalid commit", outcome_unknown=True)
        return FileEntry.parse(result.get("entry")), result["sha256"]


@dataclass(frozen=True)
class ProcessRow:
    pid: int
    ppid: int
    name: str
    state: str
    rss_kib: Optional[int]
    cpu_percent: Optional[float]
    start_ticks: int

    @classmethod
    def parse(cls, value):
        value = _require_object(value, "Invalid process row")
        if (type(value.get("pid")) is not int or not _nonnegative(value.get("ppid")) or not isinstance(value.get("name"), str) or
                not isinstance(value.get("state"), str) or len(value["state"]) != 1 or not _nullable_nonnegative(value.get("rssKiB")) or
                not (value.get("cpuPercent") is None or isinstance(value.get("cpuPercent"), (int, float))) or
                not _nonnegative(value.get("startTicks")) or value.get("managedJobId") is not None or value.get("visibility") != "partial"):
            raise ProtocolError("Invalid process row")
        return cls(value["pid"], value["ppid"], value["name"], value["state"], value.get("rssKiB"), value.get("cpuPercent"), value["startTicks"])


class Processes:
    """Bounded read-only listing. Each call takes a fresh sample, so pages are not a stable snapshot."""

    def __init__(self, client):
        self._client = client

    def signal_job(self, job_id: str, signal: str, *, client_request_id: Optional[str] = None) -> None:
        """Sends TERM, KILL or INT to a running managed job's group. There is no PID form; prefer Jobs.cancel to stop."""
        if not isinstance(job_id, str) or signal not in ("TERM", "KILL", "INT"):
            raise ValueError("job_id must be a string and signal TERM, KILL or INT")
        if "process.signal" not in _capabilities(self._client):
            raise UnavailableError("Method not available: process.signal")
        params: dict = {"jobId": job_id, "signal": signal}
        if client_request_id is not None:
            params["clientRequestId"] = client_request_id
        result, _ = self._client.request("process.signal", params)
        if not isinstance(result, dict) or result.get("sent") is not True:
            raise ProtocolError("Invalid signal acknowledgement", outcome_unknown=True)

    def list(self, *, scope: str = "owned", cursor: Optional[int] = None, limit: Optional[int] = None) -> dict:
        if scope not in PROCESS_SCOPES:
            raise ValueError("Unknown process scope")
        params: dict = {"scope": scope}
        if cursor is not None:
            params["cursor"] = cursor
        if limit is not None:
            params["limit"] = limit
        if "process.list" not in _capabilities(self._client):
            raise UnavailableError("Method not available: process.list")
        result, _ = self._client.request("process.list", params)
        result = _require_object(result, "Invalid process list")
        if (not isinstance(result.get("processes"), list) or not isinstance(result.get("incomplete"), bool) or
                not _nonnegative(result.get("missingRoots")) or not _nullable_nonnegative(result.get("nextCursor"))):
            raise ProtocolError("Invalid process list")
        return {"processes": [ProcessRow.parse(item) for item in result["processes"]], "incomplete": result["incomplete"],
                "missingRoots": result["missingRoots"], "nextCursor": result["nextCursor"]}


@dataclass(frozen=True)
class TaskPreset:
    id: str
    label: str
    executable: str
    args: List[str]
    timeout_ms: int

    @classmethod
    def parse(cls, value):
        value = _require_object(value, "Invalid task preset")
        if (not isinstance(value.get("id"), str) or not _PRESET_ID.match(value["id"]) or not isinstance(value.get("label"), str) or
                not isinstance(value.get("executable"), str) or not isinstance(value.get("args"), list) or
                not all(isinstance(arg, str) for arg in value["args"]) or not _nonnegative(value.get("timeoutMs"))):
            raise ProtocolError("Invalid task preset")
        return cls(value["id"], value["label"], value["executable"], list(value["args"]), value["timeoutMs"])

    def to_wire(self) -> dict:
        return {"id": self.id, "label": self.label, "executable": self.executable, "args": list(self.args), "timeoutMs": self.timeout_ms}


@dataclass(frozen=True)
class Workspace:
    id: str
    name: str
    root_id: str
    path: str
    created_at: int
    updated_at: int
    available: bool
    task_presets: List[TaskPreset]

    @classmethod
    def parse(cls, value):
        value = _require_object(value, "Invalid workspace metadata")
        if (not isinstance(value.get("id"), str) or not _WS_ID.match(value["id"]) or not isinstance(value.get("name"), str) or
                not isinstance(value.get("rootId"), str) or not isinstance(value.get("path"), str) or not _nonnegative(value.get("createdAt")) or
                not _nonnegative(value.get("updatedAt")) or not isinstance(value.get("available"), bool) or not isinstance(value.get("taskPresets"), list)):
            raise ProtocolError("Invalid workspace metadata")
        return cls(value["id"], value["name"], value["rootId"], value["path"], value["createdAt"], value["updatedAt"], value["available"],
                   [TaskPreset.parse(item) for item in value["taskPresets"]])


class Workspaces:
    """Named directories and stored presets. Running a preset starts a managed job in the workspace directory."""

    def __init__(self, client):
        self._client = client

    def _call(self, method, params=None):
        if method not in _capabilities(self._client):
            raise UnavailableError("Method not available: " + method)
        return self._client.request(method, params)

    def list(self) -> tuple:
        result, _ = self._call("workspace.list", {})
        result = _require_object(result, "Invalid workspace list")
        active = result.get("activeId")
        if not isinstance(result.get("workspaces"), list) or not (active is None or (isinstance(active, str) and _WS_ID.match(active))):
            raise ProtocolError("Invalid workspace list")
        return [Workspace.parse(item) for item in result["workspaces"]], active

    def get(self, workspace_id: str) -> Workspace:
        result, _ = self._call("workspace.get", {"id": workspace_id})
        return Workspace.parse(_require_object(result, "Invalid workspace response").get("workspace"))

    def create(self, name: str, root_id: str, path: str, task_presets=None) -> Workspace:
        params: dict = {"name": name, "rootId": root_id, "path": path}
        if task_presets is not None:
            params["taskPresets"] = [preset.to_wire() if isinstance(preset, TaskPreset) else preset for preset in task_presets]
        result, _ = self._call("workspace.create", params)
        try:
            return Workspace.parse(_require_object(result, "Invalid workspace create response").get("workspace"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid workspace create response", outcome_unknown=True) from exc

    def update(self, workspace_id: str, *, name: Optional[str] = None, root_id: Optional[str] = None, path: Optional[str] = None,
               task_presets=None) -> Workspace:
        params: dict = {"id": workspace_id}
        if name is not None:
            params["name"] = name
        if root_id is not None:
            params["rootId"] = root_id
        if path is not None:
            params["path"] = path
        if task_presets is not None:
            params["taskPresets"] = [preset.to_wire() if isinstance(preset, TaskPreset) else preset for preset in task_presets]
        result, _ = self._call("workspace.update", params)
        try:
            return Workspace.parse(_require_object(result, "Invalid workspace update response").get("workspace"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid workspace update response", outcome_unknown=True) from exc

    def remove(self, workspace_id: str) -> None:
        result, _ = self._call("workspace.remove", {"id": workspace_id})
        if not isinstance(result, dict) or result.get("accepted") is not True:
            raise ProtocolError("Invalid remove acknowledgement", outcome_unknown=True)

    def select(self, workspace_id: str) -> tuple:
        result, _ = self._call("workspace.select", {"id": workspace_id})
        result = _require_object(result, "Invalid selection response")
        active = result.get("activeId")
        if not isinstance(active, str) or not _WS_ID.match(active):
            raise ProtocolError("Invalid selection response", outcome_unknown=True)
        return Workspace.parse(result.get("workspace")), active

    def tasks(self, workspace_id: str) -> List[TaskPreset]:
        result, _ = self._call("workspace.tasks", {"id": workspace_id})
        result = _require_object(result, "Invalid task list")
        if not isinstance(result.get("tasks"), list):
            raise ProtocolError("Invalid task list")
        return [TaskPreset.parse(item) for item in result["tasks"]]

    def git(self, workspace_id: str, subcommand: str, *, client_request_id: Optional[str] = None) -> JobRecord:
        """Read-only Git inspection. The server supplies the argv; the client names only the subcommand."""
        if subcommand not in ("status", "branch", "diff", "log"):
            raise ValueError("subcommand must be status, branch, diff or log")
        params: dict = {"workspaceId": workspace_id, "subcommand": subcommand}
        if client_request_id is not None:
            params["clientRequestId"] = client_request_id
        result, _ = self._call("workspace.git", params)
        try:
            return JobRecord.parse(_require_object(result, "Invalid git response").get("job"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid git response", outcome_unknown=True) from exc

    def git_change(self, workspace_id: str, action: str, *, branch: Optional[str] = None, paths=None, message: Optional[str] = None,
                   client_request_id: Optional[str] = None) -> JobRecord:
        """fetch, pull, switch (branch), stage (paths) or commit (message), as a managed job. The server builds the argv."""
        if action not in ("fetch", "pull", "switch", "stage", "commit"):
            raise ValueError("Unknown Git action")
        params: dict = {"workspaceId": workspace_id, "action": action}
        if branch is not None:
            params["branch"] = branch
        if paths is not None:
            if not isinstance(paths, (list, tuple)) or not all(isinstance(item, str) for item in paths):
                raise ValueError("paths must be a list of strings")
            params["paths"] = list(paths)
        if message is not None:
            params["message"] = message
        if client_request_id is not None:
            params["clientRequestId"] = client_request_id
        result, _ = self._call("workspace.git.change", params)
        try:
            return JobRecord.parse(_require_object(result, "Invalid Git change response").get("job"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid Git change response", outcome_unknown=True) from exc

    def run_task(self, workspace_id: str, task_id: str, *, client_request_id: Optional[str] = None) -> JobRecord:
        """Only identifiers are sent. The server supplies the executable, arguments and directory."""
        params: dict = {"workspaceId": workspace_id, "taskId": task_id}
        if client_request_id is not None:
            params["clientRequestId"] = client_request_id
        result, _ = self._call("workspace.task.run", params)
        try:
            return JobRecord.parse(_require_object(result, "Invalid task run response").get("job"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid task run response", outcome_unknown=True) from exc

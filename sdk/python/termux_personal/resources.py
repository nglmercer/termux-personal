"""Resource, history, diagnostics, workspace services, ports and backups.

Reads are retry-safe. Starting, stopping or restarting a service, opening a proxy and exporting or importing a backup are
mutations and are never retried by the SDK. Every response is validated; an unusable response to a mutation is reported with
``outcome_unknown=True`` because the server may already have acted.
"""
from dataclasses import dataclass
import re
from typing import List, Optional

from .errors import ProtocolError, UnavailableError
from .jobs import JobOutputChunk, MAX_READ, STREAMS

PROFILES = frozenset({"unrestricted", "protected", "balanced", "performance", "custom"})
PRESSURE = frozenset({"normal", "elevated", "critical", "unknown"})
SERVICE_STATES = frozenset({"stopped", "running", "backoff", "stopping", "crash_loop", "memory_stopped", "denied"})
OUTCOMES = frozenset({"started", "stopped", "restarting"})
RELATIONS = frozenset({"root", "descendant", "orphan_possible"})
BACKUP_FORMATS = frozenset({"zip", "tar.gz"})
_WS_ID = re.compile(r"^ws_[0-9a-f]{16}$")
_SERVICE_ID = re.compile(r"^[a-z0-9_-]{1,64}$")


def _nonneg(value) -> bool:
    return type(value) is int and value >= 0


def _nullable_nonneg(value) -> bool:
    return value is None or _nonneg(value)


def _obj(value, message: str) -> dict:
    if not isinstance(value, dict):
        raise ProtocolError(message)
    return value


def _check_ws(workspace_id) -> None:
    if not isinstance(workspace_id, str) or not _WS_ID.match(workspace_id):
        raise ValueError("Invalid workspace ID")


def _check_service(service_id) -> None:
    if not isinstance(service_id, str) or not _SERVICE_ID.match(service_id):
        raise ValueError("Invalid service ID")


@dataclass(frozen=True)
class ServiceStatus:
    workspace_id: str
    service_id: str
    state: str
    restart_policy: str
    job_id: Optional[str]
    restarts_in_window: int
    next_start_in_millis: Optional[int]
    last_reason: Optional[str]
    last_exit_code: Optional[int]
    ports: List[int]
    healthy: Optional[bool] = None
    port_checks: Optional[list] = None

    @classmethod
    def parse(cls, value):
        v = _obj(value, "Invalid service status")
        if (not isinstance(v.get("workspaceId"), str) or not _WS_ID.match(v["workspaceId"]) or not isinstance(v.get("serviceId"), str) or
                v.get("state") not in SERVICE_STATES or v.get("restartPolicy") not in ("never", "on-failure", "always") or
                not (v.get("jobId") is None or isinstance(v.get("jobId"), str)) or not _nonneg(v.get("restartsInWindow")) or
                not _nullable_nonneg(v.get("nextStartInMillis")) or not (v.get("lastReason") is None or isinstance(v.get("lastReason"), str)) or
                not (v.get("lastExitCode") is None or type(v.get("lastExitCode")) is int) or not isinstance(v.get("ports"), list) or
                not all(type(p) is int for p in v["ports"])):
            raise ProtocolError("Invalid service status")
        healthy = v.get("healthy")
        checks = v.get("portChecks")
        if healthy is not None and not isinstance(healthy, bool):
            raise ProtocolError("Invalid service status")
        if checks is not None and (not isinstance(checks, list) or not all(isinstance(c, dict) and type(c.get("port")) is int and
                                                                          isinstance(c.get("listening"), bool) for c in checks)):
            raise ProtocolError("Invalid service status")
        return cls(v["workspaceId"], v["serviceId"], v["state"], v["restartPolicy"], v["jobId"], v["restartsInWindow"],
                   v["nextStartInMillis"], v["lastReason"], v["lastExitCode"], list(v["ports"]), healthy, checks)


class _Domain:
    def __init__(self, client):
        self._client = client

    def _call(self, method, params=None):
        if method not in set(self._client.capabilities()["methods"]):
            raise UnavailableError("Method not available: " + method)
        return self._client.request(method, params)

    def _mutation(self, method, params, message):
        result, _ = self._call(method, params)
        return result, message


class Resources(_Domain):
    """What this device can enforce, a fresh resource snapshot, and per-job limits, metrics, history and diagnostics."""

    def capabilities(self) -> dict:
        result, _ = self._call("resource.capabilities", {})
        r = _obj(result, "Invalid capabilities")
        limits = _obj(r.get("resourceLimits"), "Invalid capabilities")
        keys = ("managedJobs", "memorySoftMonitoring", "rlimitAs", "rlimitCpu", "cgroups", "safeProcessStop", "virtualMachine")
        if (not all(isinstance(limits.get(k), bool) for k in keys) or not isinstance(r.get("profiles"), list) or
                not all(p in PROFILES for p in r["profiles"]) or not isinstance(r.get("items"), list)):
            raise ProtocolError("Invalid capabilities")
        return r

    def snapshot(self) -> dict:
        result, _ = self._call("resource.snapshot", {})
        r = _obj(result, "Invalid resource snapshot")
        device = _obj(r.get("device"), "Invalid resource snapshot")
        admission = _obj(r.get("admission"), "Invalid resource snapshot")
        if (r.get("pressure") not in PRESSURE or not _nullable_nonneg(device.get("totalMemoryBytes")) or
                not _nullable_nonneg(device.get("availableMemoryBytes")) or type(device.get("cpuCount")) is not int or
                not all(_nonneg(admission.get(k)) for k in ("slots", "active", "queued"))):
            raise ProtocolError("Invalid resource snapshot")
        return r

    def job(self, job_id: str) -> dict:
        result, _ = self._call("job.resource.get", {"id": job_id})
        r = _obj(_obj(result, "Invalid job resources").get("resource"), "Invalid job resources")
        if (not isinstance(r.get("jobId"), str) or not isinstance(r.get("unsupported"), list) or not isinstance(r.get("events"), list) or
                not (r.get("limits") is None or isinstance(r.get("limits"), dict)) or not (r.get("metrics") is None or isinstance(r.get("metrics"), dict))):
            raise ProtocolError("Invalid job resources")
        return r

    def history(self, limit: Optional[int] = None) -> list:
        params = {} if limit is None else {"limit": limit}
        if limit is not None and (type(limit) is not int or not 1 <= limit <= 50):
            raise ValueError("limit must be 1..50")
        result, _ = self._call("job.history.list", params)
        r = _obj(result, "Invalid job history")
        if not isinstance(r.get("history"), list) or not all(isinstance(i, dict) for i in r["history"]):
            raise ProtocolError("Invalid job history")
        return r["history"]

    def history_get(self, job_id: str) -> dict:
        result, _ = self._call("job.history.get", {"id": job_id})
        return _obj(_obj(result, "Invalid job history record").get("record"), "Invalid job history record")

    def export_diagnostics(self, job_id: str, *, include_output: bool = False) -> dict:
        """A redacted report. Output is included only if asked for, and the server then also requires output consent."""
        params = {"id": job_id}
        if include_output:
            params["includeOutput"] = True
        result, _ = self._call("job.diagnostics.export", params)
        r = _obj(result, "Invalid diagnostics")
        if not isinstance(r.get("report"), str) or not isinstance(r.get("files"), list) or not isinstance(r.get("outputIncluded"), bool):
            raise ProtocolError("Invalid diagnostics")
        return r

    def job_processes(self, job_id: str) -> dict:
        """Best effort: relation ``orphan_possible`` means a process left the job's tree and its ownership cannot be established."""
        result, _ = self._call("job.processes", {"id": job_id})
        r = _obj(result, "Invalid job processes")
        if not isinstance(r.get("running"), bool) or not isinstance(r.get("incomplete"), bool) or not isinstance(r.get("processes"), list):
            raise ProtocolError("Invalid job processes")
        for row in r["processes"]:
            row = _obj(row, "Invalid job processes")
            if type(row.get("pid")) is not int or row["pid"] < 1 or row.get("relation") not in RELATIONS or not _nonneg(row.get("startTicks")):
                raise ProtocolError("Invalid job processes")
        return r


class Services(_Domain):
    """Approved development services of a workspace. Definitions live in the workspace; this controls running them."""

    def list(self, workspace_id: str) -> List[ServiceStatus]:
        _check_ws(workspace_id)
        result, _ = self._call("workspace.service.list", {"workspaceId": workspace_id})
        r = _obj(result, "Invalid service list")
        if not isinstance(r.get("services"), list):
            raise ProtocolError("Invalid service list")
        return [ServiceStatus.parse(i) for i in r["services"]]

    def status(self, workspace_id: str, service_id: str) -> ServiceStatus:
        _check_ws(workspace_id); _check_service(service_id)
        result, _ = self._call("workspace.service.status", {"workspaceId": workspace_id, "serviceId": service_id})
        return ServiceStatus.parse(_obj(result, "Invalid service status").get("service"))

    def health(self, workspace_id: str, service_id: str) -> ServiceStatus:
        _check_ws(workspace_id); _check_service(service_id)
        result, _ = self._call("workspace.service.health", {"workspaceId": workspace_id, "serviceId": service_id})
        return ServiceStatus.parse(_obj(result, "Invalid service health").get("service"))

    def _control(self, action, workspace_id, service_id, client_request_id):
        _check_ws(workspace_id); _check_service(service_id)
        params = {"workspaceId": workspace_id, "serviceId": service_id}
        if client_request_id is not None:
            params["clientRequestId"] = client_request_id
        result, _ = self._call("workspace.service." + action, params)
        try:
            r = _obj(result, "Invalid service response")
            if r.get("outcome") not in OUTCOMES:
                raise ProtocolError("Invalid service response")
            return ServiceStatus.parse(r.get("service"))
        except ProtocolError as exc:
            raise ProtocolError("Invalid service " + action + " response", outcome_unknown=True) from exc

    def start(self, workspace_id: str, service_id: str, *, client_request_id: Optional[str] = None) -> ServiceStatus:
        return self._control("start", workspace_id, service_id, client_request_id)

    def stop(self, workspace_id: str, service_id: str, *, client_request_id: Optional[str] = None) -> ServiceStatus:
        return self._control("stop", workspace_id, service_id, client_request_id)

    def restart(self, workspace_id: str, service_id: str, *, client_request_id: Optional[str] = None) -> ServiceStatus:
        return self._control("restart", workspace_id, service_id, client_request_id)

    def logs(self, workspace_id: str, service_id: str, stream: str, offset: int, limit: int = MAX_READ) -> JobOutputChunk:
        _check_ws(workspace_id); _check_service(service_id)
        if stream not in STREAMS:
            raise ValueError("stream must be stdout or stderr")
        result, data = self._call("workspace.service.logs", {"workspaceId": workspace_id, "serviceId": service_id, "stream": stream,
                                                             "offset": offset, "limit": limit})
        if (not isinstance(result, dict) or not all(_nonneg(result.get(k)) for k in ("offset", "nextOffset", "endOffset", "length")) or
                not all(isinstance(result.get(k), bool) for k in ("gap", "finished", "complete")) or result["length"] != len(data) or
                result["nextOffset"] != result["offset"] + len(data) or result["nextOffset"] > result["endOffset"]):
            raise ProtocolError("Invalid service log offsets")
        return JobOutputChunk(result["offset"], result["nextOffset"], result["endOffset"], result["gap"], result["finished"], result["complete"], data)

    def ports(self, workspace_id: str) -> list:
        _check_ws(workspace_id)
        result, _ = self._call("workspace.service.ports", {"workspaceId": workspace_id})
        r = _obj(result, "Invalid port list")
        if not isinstance(r.get("ports"), list):
            raise ProtocolError("Invalid port list")
        for row in r["ports"]:
            row = _obj(row, "Invalid port list")
            if (type(row.get("port")) is not int or not isinstance(row.get("listening"), bool) or not isinstance(row.get("lanExposed"), bool) or
                    row.get("conflict") not in ("declared_by_other_service", "in_use_by_other_process", None)):
                raise ProtocolError("Invalid port list")
        return r["ports"]

    def open_proxy(self, listen_port: int, target_port: int, *, listen_address: Optional[str] = None, confirm_lan: bool = False) -> dict:
        """Relays a local port to 127.0.0.1:target_port. Loopback by default; any other address needs ``confirm_lan=True``."""
        params: dict = {"action": "open", "listenPort": listen_port, "targetPort": target_port}
        if listen_address is not None:
            params["listenAddress"] = listen_address
        if confirm_lan:
            params["confirmLan"] = True
        result, _ = self._call("workspace.service.proxy", params)
        proxy = _obj(_obj(result, "Invalid proxy response").get("proxy"), "Invalid proxy response")
        if (not isinstance(proxy.get("id"), str) or type(proxy.get("listenPort")) is not int or proxy.get("targetAddress") != "127.0.0.1" or
                not isinstance(proxy.get("lan"), bool)):
            raise ProtocolError("Invalid proxy response", outcome_unknown=True)
        return proxy

    def close_proxy(self, proxy_id: str) -> bool:
        result, _ = self._call("workspace.service.proxy", {"action": "close", "id": proxy_id})
        if not isinstance(result, dict) or not isinstance(result.get("closed"), bool):
            raise ProtocolError("Invalid proxy response", outcome_unknown=True)
        return result["closed"]

    def proxies(self) -> list:
        result, _ = self._call("workspace.service.proxy", {"action": "list"})
        r = _obj(result, "Invalid proxy list")
        if not isinstance(r.get("proxies"), list):
            raise ProtocolError("Invalid proxy list")
        return r["proxies"]


class Backups(_Domain):
    """Workspace archives in app-private storage. Restoring never overwrites without ``overwrite`` and ``confirm``."""

    def export(self, workspace_id: str, *, format: str = "zip", include_sensitive: bool = False) -> dict:
        _check_ws(workspace_id)
        if format not in BACKUP_FORMATS:
            raise ValueError("format must be zip or tar.gz")
        params: dict = {"workspaceId": workspace_id, "format": format}
        if include_sensitive:
            params["includeSensitive"] = True
        result, _ = self._call("workspace.backup.export", params)
        try:
            b = _obj(_obj(result, "Invalid backup response").get("backup"), "Invalid backup response")
            if (not isinstance(b.get("id"), str) or b.get("format") not in BACKUP_FORMATS or not _nonneg(b.get("files")) or
                    not isinstance(b.get("truncated"), bool) or not isinstance(b.get("skipped"), dict)):
                raise ProtocolError("Invalid backup response")
            return b
        except ProtocolError as exc:
            raise ProtocolError("Invalid backup response", outcome_unknown=True) from exc

    def restore(self, workspace_id: str, backup_id: str, *, paths=None, overwrite: bool = False, confirm: bool = False,
                apply_config: bool = False) -> dict:
        _check_ws(workspace_id)
        if (overwrite or apply_config) and not confirm:
            raise ValueError("overwrite and apply_config need confirm=True")
        params: dict = {"workspaceId": workspace_id, "backupId": backup_id}
        if paths is not None:
            if not isinstance(paths, (list, tuple)) or not paths or not all(isinstance(p, str) for p in paths):
                raise ValueError("paths must be a non-empty list of strings")
            params["paths"] = list(paths)
        if overwrite:
            params["overwrite"] = True
        if confirm:
            params["confirm"] = True
        if apply_config:
            params["applyConfig"] = True
        result, _ = self._call("workspace.backup.import", params)
        try:
            r = _obj(_obj(result, "Invalid restore response").get("restore"), "Invalid restore response")
            if not all(_nonneg(r.get(k)) for k in ("files", "bytes", "conflicts", "skippedUnsafe")) or not isinstance(r.get("configApplied"), bool):
                raise ProtocolError("Invalid restore response")
            return r
        except ProtocolError as exc:
            raise ProtocolError("Invalid restore response", outcome_unknown=True) from exc

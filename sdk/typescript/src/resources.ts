import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";
import type { JobOutputChunk, JobStream } from "./jobs.ts";

export type ResourceProfile = "unrestricted" | "protected" | "balanced" | "performance" | "custom";
export type PressureLevel = "normal" | "elevated" | "critical" | "unknown";
export type ServiceState = "stopped" | "running" | "backoff" | "stopping" | "crash_loop" | "memory_stopped" | "denied";
export type ProcessRelation = "root" | "descendant" | "orphan_possible";
export interface ResourceLimits {
  managedJobs: boolean; memorySoftMonitoring: boolean; rlimitAs: boolean; rlimitCpu: boolean; cgroups: boolean;
  safeProcessStop: boolean; virtualMachine: boolean;
}
export interface ResourceCapabilities {
  resourceLimits: ResourceLimits; profiles: ResourceProfile[]; pressureActions: string[];
  items: Array<{ id: string; status: "supported" | "unsupported" | "unknown" | "info"; detail: string }>;
}
export interface ResourceSnapshot {
  device: { totalMemoryBytes: number | null; availableMemoryBytes: number | null; lowMemory: boolean | null; thermalStatus: number | null; cpuCount: number };
  pressure: PressureLevel; admission: { slots: number; active: number; queued: number };
}
export interface ServiceStatus {
  workspaceId: string; serviceId: string; state: ServiceState; restartPolicy: "never" | "on-failure" | "always"; jobId: string | null;
  restartsInWindow: number; nextStartInMillis: number | null; lastReason: string | null; lastExitCode: number | null; ports: number[];
  healthy?: boolean; portChecks?: Array<{ port: number; listening: boolean }>;
}
export interface JobProcess { pid: number; ppid: number; name: string; state: string; rssKiB: number | null; startTicks: number; relation: ProcessRelation }
export interface PortRow {
  serviceId: string; port: number; listening: boolean; serviceRunning: boolean; lanExposed: boolean;
  conflict: "declared_by_other_service" | "in_use_by_other_process" | null; conflictsWith: string[]; address: string;
}
export interface Proxy { id: string; listenAddress: string; listenPort: number; targetAddress: "127.0.0.1"; targetPort: number; lan: boolean }
export type BackupFormat = "zip" | "tar.gz";
export interface BackupSummary {
  id: string; format: BackupFormat; files: number; bytes: number; archiveBytes: number; truncated: boolean;
  skipped: { excluded: number; sensitive: number; links: number; unsafe: number };
}
export interface BackupOperation {
  id: string; kind: "export" | "import"; workspaceId: string; state: "running" | "completed" | "failed" | "cancelled";
  files: number; bytes: number; startedAt: number; endedAt: number | null; result: Record<string, unknown> | null; error: string | null;
}
export interface RestoreSummary { files: number; bytes: number; conflicts: number; skippedUnsafe: number; truncated: boolean; configApplied: boolean }

type Params = Record<string, unknown>;
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);
const nonNegative = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const nullableNonNegative = (value: unknown) => value === null || nonNegative(value);
const WS_ID = /^ws_[0-9a-f]{16}$/;
const SERVICE_ID = /^[a-z0-9_-]{1,64}$/;
const PROFILES = new Set(["unrestricted", "protected", "balanced", "performance", "custom"]);
const PRESSURE = new Set(["normal", "elevated", "critical", "unknown"]);
const STATES = new Set(["stopped", "running", "backoff", "stopping", "crash_loop", "memory_stopped", "denied"]);
const OUTCOMES = new Set(["started", "stopped", "restarting"]);
const RELATIONS = new Set(["root", "descendant", "orphan_possible"]);
const FORMATS = new Set(["zip", "tar.gz"]);

function checkWorkspace(id: string): void { if (typeof id !== "string" || !WS_ID.test(id)) throw new TypeError("Invalid workspace ID"); }
function checkService(id: string): void { if (typeof id !== "string" || !SERVICE_ID.test(id)) throw new TypeError("Invalid service ID"); }

export function serviceStatus(value: unknown): ServiceStatus {
  if (!isObject(value) || typeof value.workspaceId !== "string" || !WS_ID.test(value.workspaceId) || typeof value.serviceId !== "string" ||
      !STATES.has(value.state as string) || !["never", "on-failure", "always"].includes(value.restartPolicy as string) ||
      !(value.jobId === null || typeof value.jobId === "string") || !nonNegative(value.restartsInWindow) || !nullableNonNegative(value.nextStartInMillis) ||
      !(value.lastReason === null || typeof value.lastReason === "string") || !(value.lastExitCode === null || Number.isSafeInteger(value.lastExitCode)) ||
      !Array.isArray(value.ports) || !value.ports.every(Number.isSafeInteger) ||
      (value.healthy !== undefined && typeof value.healthy !== "boolean") ||
      (value.portChecks !== undefined && (!Array.isArray(value.portChecks) || !value.portChecks.every(check => isObject(check) && Number.isSafeInteger(check.port) && typeof check.listening === "boolean"))))
    throw new ProtocolError("Invalid service status");
  return value as unknown as ServiceStatus;
}

abstract class Domain {
  protected readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }
  protected async call(method: string, params: Params, signal?: AbortSignal) {
    const { result } = await this.client.request("system.capabilities", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string")) throw new ProtocolError("Invalid capabilities");
    if (!(result.methods as string[]).includes(method)) throw new UnavailableError(`Method not available: ${method}`);
    return this.client.request(method, params, { signal });
  }
}

/** What this device can enforce, a fresh snapshot, and per-job limits, metrics, history and diagnostics. */
export class Resources extends Domain {
  async capabilities(signal?: AbortSignal): Promise<ResourceCapabilities> {
    const { result } = await this.call("resource.capabilities", {}, signal);
    const keys = ["managedJobs", "memorySoftMonitoring", "rlimitAs", "rlimitCpu", "cgroups", "safeProcessStop", "virtualMachine"];
    if (!isObject(result) || !isObject(result.resourceLimits) || !keys.every(key => typeof (result.resourceLimits as Params)[key] === "boolean") ||
        !Array.isArray(result.profiles) || !result.profiles.every(p => PROFILES.has(p as string)) || !Array.isArray(result.items))
      throw new ProtocolError("Invalid capabilities");
    return result as unknown as ResourceCapabilities;
  }

  async snapshot(signal?: AbortSignal): Promise<ResourceSnapshot> {
    const { result } = await this.call("resource.snapshot", {}, signal);
    if (!isObject(result) || !PRESSURE.has(result.pressure as string) || !isObject(result.device) || !isObject(result.admission) ||
        !nullableNonNegative(result.device.totalMemoryBytes) || !nullableNonNegative(result.device.availableMemoryBytes) ||
        !Number.isSafeInteger(result.device.cpuCount) || !["slots", "active", "queued"].every(k => nonNegative((result.admission as Params)[k])))
      throw new ProtocolError("Invalid resource snapshot");
    return result as unknown as ResourceSnapshot;
  }

  async job(id: string, signal?: AbortSignal): Promise<Params> {
    const { result } = await this.call("job.resource.get", { id }, signal);
    const r = isObject(result) ? result.resource : undefined;
    if (!isObject(r) || typeof r.jobId !== "string" || !Array.isArray(r.unsupported) || !Array.isArray(r.events) ||
        !(r.limits === null || isObject(r.limits)) || !(r.metrics === null || isObject(r.metrics)))
      throw new ProtocolError("Invalid job resources");
    return r;
  }

  async history(limit?: number, signal?: AbortSignal): Promise<Params[]> {
    if (limit !== undefined && (!Number.isSafeInteger(limit) || limit < 1 || limit > 50)) throw new TypeError("limit must be 1..50");
    const { result } = await this.call("job.history.list", limit === undefined ? {} : { limit }, signal);
    if (!isObject(result) || !Array.isArray(result.history) || !result.history.every(isObject)) throw new ProtocolError("Invalid job history");
    return result.history as Params[];
  }

  async historyGet(id: string, signal?: AbortSignal): Promise<Params> {
    const { result } = await this.call("job.history.get", { id }, signal);
    if (!isObject(result) || !isObject(result.record)) throw new ProtocolError("Invalid job history record");
    return result.record;
  }

  /** A redacted report. Output is added only when asked for, and the server then also requires output consent. */
  async exportDiagnostics(id: string, options: { includeOutput?: boolean; signal?: AbortSignal } = {}): Promise<{ report: string; files: string[]; outputIncluded: boolean }> {
    const params: Params = { id };
    if (options.includeOutput) params.includeOutput = true;
    const { result } = await this.call("job.diagnostics.export", params, options.signal);
    if (!isObject(result) || typeof result.report !== "string" || !Array.isArray(result.files) || typeof result.outputIncluded !== "boolean")
      throw new ProtocolError("Invalid diagnostics");
    return result as unknown as { report: string; files: string[]; outputIncluded: boolean };
  }

  /** Best effort. Relation orphan_possible means a process left the job's tree and its ownership cannot be established. */
  async jobProcesses(id: string, signal?: AbortSignal): Promise<{ jobId: string; running: boolean; processes: JobProcess[]; incomplete: boolean }> {
    const { result } = await this.call("job.processes", { id }, signal);
    if (!isObject(result) || typeof result.running !== "boolean" || typeof result.incomplete !== "boolean" || !Array.isArray(result.processes) ||
        !result.processes.every(row => isObject(row) && Number.isSafeInteger(row.pid) && (row.pid as number) >= 1 && RELATIONS.has(row.relation as string) && nonNegative(row.startTicks)))
      throw new ProtocolError("Invalid job processes");
    return result as unknown as { jobId: string; running: boolean; processes: JobProcess[]; incomplete: boolean };
  }
}

/** Approved development services of a workspace. Starting, stopping and restarting are mutations and are never retried. */
export class Services extends Domain {
  async list(workspaceId: string, signal?: AbortSignal): Promise<ServiceStatus[]> {
    checkWorkspace(workspaceId);
    const { result } = await this.call("workspace.service.list", { workspaceId }, signal);
    if (!isObject(result) || !Array.isArray(result.services)) throw new ProtocolError("Invalid service list");
    return result.services.map(serviceStatus);
  }

  async status(workspaceId: string, serviceId: string, signal?: AbortSignal): Promise<ServiceStatus> {
    checkWorkspace(workspaceId); checkService(serviceId);
    const { result } = await this.call("workspace.service.status", { workspaceId, serviceId }, signal);
    return serviceStatus(isObject(result) ? result.service : undefined);
  }

  async health(workspaceId: string, serviceId: string, signal?: AbortSignal): Promise<ServiceStatus> {
    checkWorkspace(workspaceId); checkService(serviceId);
    const { result } = await this.call("workspace.service.health", { workspaceId, serviceId }, signal);
    return serviceStatus(isObject(result) ? result.service : undefined);
  }

  /**
   * How to reach the current run: loopback URL, instanceId and, for a service that opted in, its credential. A changed
   * instanceId is a different run whose previous credential is invalid. Nothing is retried here.
   */
  async endpoint(workspaceId: string, serviceId: string, signal?: AbortSignal): Promise<ServiceEndpoint> {
    checkWorkspace(workspaceId); checkService(serviceId);
    const { result } = await this.call("workspace.service.endpoint", { workspaceId, serviceId }, signal);
    const e = isObject(result) ? result.endpoint : undefined;
    const hex = (v: unknown, n: number) => v === null || (typeof v === "string" && new RegExp(`^[0-9a-f]{${n}}$`).test(v));
    const port = isObject(e) ? e.port : undefined;
    if (!isObject(e) || typeof e.state !== "string" || !hex(e.instanceId, 32) || !hex(e.credential, 64) || typeof e.listening !== "boolean" ||
        typeof e.credentialRequired !== "boolean" || !(port === null || (Number.isSafeInteger(port) && (port as number) >= 1 && (port as number) <= 65535)) ||
        e.url !== (port === null ? null : `http://127.0.0.1:${port}/`) || e.origin !== (port === null ? null : `http://127.0.0.1:${port}`))
      throw new ProtocolError("Invalid service endpoint");
    return e as unknown as ServiceEndpoint;
  }

  private async control(action: "start" | "stop" | "restart", workspaceId: string, serviceId: string, options: { clientRequestId?: string; signal?: AbortSignal }): Promise<ServiceStatus> {
    checkWorkspace(workspaceId); checkService(serviceId);
    const params: Params = { workspaceId, serviceId };
    if (options.clientRequestId !== undefined) params.clientRequestId = options.clientRequestId;
    const { result } = await this.call(`workspace.service.${action}`, params, options.signal);
    try {
      if (!isObject(result) || !OUTCOMES.has(result.outcome as string)) throw new ProtocolError(`Invalid service ${action} response`);
      return serviceStatus(result.service);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError(`Invalid service ${action} response`, true);
      throw error;
    }
  }
  start(workspaceId: string, serviceId: string, options: { clientRequestId?: string; signal?: AbortSignal } = {}) { return this.control("start", workspaceId, serviceId, options); }
  stop(workspaceId: string, serviceId: string, options: { clientRequestId?: string; signal?: AbortSignal } = {}) { return this.control("stop", workspaceId, serviceId, options); }
  restart(workspaceId: string, serviceId: string, options: { clientRequestId?: string; signal?: AbortSignal } = {}) { return this.control("restart", workspaceId, serviceId, options); }

  async logs(workspaceId: string, serviceId: string, stream: JobStream, offset: number, limit = 65536, signal?: AbortSignal): Promise<JobOutputChunk> {
    checkWorkspace(workspaceId); checkService(serviceId);
    if (stream !== "stdout" && stream !== "stderr") throw new TypeError("stream must be stdout or stderr");
    const { result, binary } = await this.call("workspace.service.logs", { workspaceId, serviceId, stream, offset, limit }, signal);
    if (!isObject(result) || !["offset", "nextOffset", "endOffset", "length"].every(k => nonNegative(result[k])) ||
        !["gap", "finished", "complete"].every(k => typeof result[k] === "boolean") || result.length !== binary.length ||
        (result.nextOffset as number) !== (result.offset as number) + binary.length || (result.nextOffset as number) > (result.endOffset as number))
      throw new ProtocolError("Invalid service log offsets");
    return { offset: result.offset as number, nextOffset: result.nextOffset as number, endOffset: result.endOffset as number,
      gap: result.gap as boolean, finished: result.finished as boolean, complete: result.complete as boolean, data: binary };
  }

  async ports(workspaceId: string, signal?: AbortSignal): Promise<PortRow[]> {
    checkWorkspace(workspaceId);
    const { result } = await this.call("workspace.service.ports", { workspaceId }, signal);
    if (!isObject(result) || !Array.isArray(result.ports) || !result.ports.every(row => isObject(row) && Number.isSafeInteger(row.port) &&
        typeof row.listening === "boolean" && typeof row.lanExposed === "boolean" &&
        (row.conflict === null || row.conflict === "declared_by_other_service" || row.conflict === "in_use_by_other_process")))
      throw new ProtocolError("Invalid port list");
    return result.ports as unknown as PortRow[];
  }

  /** Relays a local port to 127.0.0.1:targetPort. Loopback by default; any other address needs confirmLan. */
  async openProxy(listenPort: number, targetPort: number, options: { listenAddress?: string; confirmLan?: boolean; signal?: AbortSignal } = {}): Promise<Proxy> {
    const params: Params = { action: "open", listenPort, targetPort };
    if (options.listenAddress !== undefined) params.listenAddress = options.listenAddress;
    if (options.confirmLan) params.confirmLan = true;
    const { result } = await this.call("workspace.service.proxy", params, options.signal);
    const proxy = isObject(result) ? result.proxy : undefined;
    if (!isObject(proxy) || typeof proxy.id !== "string" || !Number.isSafeInteger(proxy.listenPort) || proxy.targetAddress !== "127.0.0.1" || typeof proxy.lan !== "boolean")
      throw new ProtocolError("Invalid proxy response", true);
    return proxy as unknown as Proxy;
  }

  async closeProxy(id: string, signal?: AbortSignal): Promise<boolean> {
    const { result } = await this.call("workspace.service.proxy", { action: "close", id }, signal);
    if (!isObject(result) || typeof result.closed !== "boolean") throw new ProtocolError("Invalid proxy response", true);
    return result.closed;
  }

  async proxies(signal?: AbortSignal): Promise<Proxy[]> {
    const { result } = await this.call("workspace.service.proxy", { action: "list" }, signal);
    if (!isObject(result) || !Array.isArray(result.proxies)) throw new ProtocolError("Invalid proxy list");
    return result.proxies as Proxy[];
  }
}

export interface ServiceEndpoint {
  workspaceId: string; serviceId: string; state: string; instanceId: string | null; port: number | null;
  url: string | null; origin: string | null; listening: boolean; credentialRequired: boolean; credential: string | null;
}
export interface CommandPreview {
  executable: string; arguments: string[]; presetApplied: boolean; changed: boolean; notes: string[]; resources: Record<string, unknown> | null;
  /** job.preview only: pass it as expectedPreviewFingerprint to start exactly this plan. Absent from older servers. */
  previewFingerprint?: string;
}
export function commandPreview(result: unknown): CommandPreview {
  if (!isObject(result) || typeof result.executable !== "string" || !Array.isArray(result.arguments) || !result.arguments.every(a => typeof a === "string") ||
      typeof result.presetApplied !== "boolean" || typeof result.changed !== "boolean" || !Array.isArray(result.notes) ||
      !(result.previewFingerprint === undefined || (typeof result.previewFingerprint === "string" && /^[0-9a-f]{64}$/.test(result.previewFingerprint))))
    throw new ProtocolError("Invalid command preview");
  return result as unknown as CommandPreview;
}

/** Workspace archives in app-private storage. Restoring never overwrites without overwrite and confirm. */
export class Backups extends Domain {
  private operation(result: unknown, message: string): BackupOperation {
    const op = isObject(result) ? result.operation : undefined;
    if (!isObject(op) || typeof op.id !== "string" || !["export", "import"].includes(op.kind as string) ||
        !["running", "completed", "failed", "cancelled"].includes(op.state as string) || !nonNegative(op.files) || !nonNegative(op.bytes) ||
        !(op.result === null || isObject(op.result)))
      throw new ProtocolError(message, true);
    return op as unknown as BackupOperation;
  }

  /** Progress (files, bytes) and, once finished, the result of a background export or restore. */
  async status(operationId: string, signal?: AbortSignal): Promise<BackupOperation> {
    const { result } = await this.call("workspace.backup.status", { operationId }, signal);
    const op = isObject(result) ? result.operation : undefined;
    if (!isObject(op) || !["running", "completed", "failed", "cancelled"].includes(op.state as string) || !nonNegative(op.files)) throw new ProtocolError("Invalid backup status");
    return op as unknown as BackupOperation;
  }

  /** Cooperative: the operation stops between chunks and removes its temporary files. Poll status for the outcome. */
  async cancel(operationId: string, signal?: AbortSignal): Promise<BackupOperation> {
    const { result } = await this.call("workspace.backup.cancel", { operationId }, signal);
    return this.operation(result, "Invalid backup cancel response");
  }

  /** With background: true the operation is returned at once; poll status and stop it with cancel. */
  async export(workspaceId: string, options: { format?: BackupFormat; includeSensitive?: boolean; background: true; signal?: AbortSignal }): Promise<BackupOperation>;
  async export(workspaceId: string, options?: { format?: BackupFormat; includeSensitive?: boolean; background?: false; signal?: AbortSignal }): Promise<BackupSummary>;
  async export(workspaceId: string, options: { format?: BackupFormat; includeSensitive?: boolean; background?: boolean; signal?: AbortSignal } = {}): Promise<BackupSummary | BackupOperation> {
    checkWorkspace(workspaceId);
    const format = options.format ?? "zip";
    if (!FORMATS.has(format)) throw new TypeError("format must be zip or tar.gz");
    const params: Params = { workspaceId, format };
    if (options.includeSensitive) params.includeSensitive = true;
    if (options.background) params.async = true;
    const { result } = await this.call("workspace.backup.export", params, options.signal);
    if (options.background) return this.operation(result, "Invalid backup operation");
    const b = isObject(result) ? result.backup : undefined;
    if (!isObject(b) || typeof b.id !== "string" || !FORMATS.has(b.format as string) || !nonNegative(b.files) || typeof b.truncated !== "boolean" || !isObject(b.skipped))
      throw new ProtocolError("Invalid backup response", true);
    return b as unknown as BackupSummary;
  }

  async restore(workspaceId: string, backupId: string, options: { paths?: string[]; overwrite?: boolean; confirm?: boolean; applyConfig?: boolean; background: true; signal?: AbortSignal }): Promise<BackupOperation>;
  async restore(workspaceId: string, backupId: string, options?: { paths?: string[]; overwrite?: boolean; confirm?: boolean; applyConfig?: boolean; background?: false; signal?: AbortSignal }): Promise<RestoreSummary>;
  async restore(workspaceId: string, backupId: string, options: { paths?: string[]; overwrite?: boolean; confirm?: boolean; applyConfig?: boolean; background?: boolean; signal?: AbortSignal } = {}): Promise<RestoreSummary | BackupOperation> {
    checkWorkspace(workspaceId);
    if ((options.overwrite || options.applyConfig) && !options.confirm) throw new TypeError("overwrite and applyConfig need confirm");
    const params: Params = { workspaceId, backupId };
    if (options.paths !== undefined) {
      if (!Array.isArray(options.paths) || !options.paths.length || !options.paths.every(p => typeof p === "string")) throw new TypeError("paths must be a non-empty list of strings");
      params.paths = [...options.paths];
    }
    if (options.overwrite) params.overwrite = true;
    if (options.confirm) params.confirm = true;
    if (options.applyConfig) params.applyConfig = true;
    if (options.background) params.async = true;
    const { result } = await this.call("workspace.backup.import", params, options.signal);
    if (options.background) return this.operation(result, "Invalid restore operation");
    const r = isObject(result) ? result.restore : undefined;
    if (!isObject(r) || !["files", "bytes", "conflicts", "skippedUnsafe"].every(k => nonNegative(r[k])) || typeof r.configApplied !== "boolean")
      throw new ProtocolError("Invalid restore response", true);
    return r as unknown as RestoreSummary;
  }
}

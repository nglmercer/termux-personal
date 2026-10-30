import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";
import { type JobRecord, jobRecord } from "./jobs.ts";

export interface TaskPreset { id: string; label: string; executable: string; args: string[]; timeoutMs: number }
export interface Workspace {
  id: string; name: string; rootId: string; path: string; createdAt: number; updatedAt: number;
  /** False when the root or directory no longer resolves. Writes and selection are refused for such a workspace. */
  available: boolean; taskPresets: TaskPreset[];
}
export interface WorkspaceSpec { name: string; rootId: string; path: string; taskPresets?: TaskPreset[] }
export interface WorkspaceChanges { name?: string; rootId?: string; path?: string; taskPresets?: TaskPreset[] }

type Params = Record<string, unknown>;
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);
const nonNegative = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const WS_ID = /^ws_[0-9a-f]{16}$/;

function preset(value: unknown): TaskPreset {
  if (!isObject(value) || typeof value.id !== "string" || !/^[a-z0-9_-]{1,64}$/.test(value.id) || typeof value.label !== "string" ||
      typeof value.executable !== "string" || !Array.isArray(value.args) || !value.args.every(arg => typeof arg === "string") ||
      !nonNegative(value.timeoutMs))
    throw new ProtocolError("Invalid task preset");
  return value as unknown as TaskPreset;
}

function workspace(value: unknown): Workspace {
  if (!isObject(value) || typeof value.id !== "string" || !WS_ID.test(value.id) || typeof value.name !== "string" ||
      typeof value.rootId !== "string" || typeof value.path !== "string" || !nonNegative(value.createdAt) || !nonNegative(value.updatedAt) ||
      typeof value.available !== "boolean" || !Array.isArray(value.taskPresets))
    throw new ProtocolError("Invalid workspace metadata");
  return { ...(value as unknown as Workspace), taskPresets: value.taskPresets.map(item => preset(item)) };
}

/**
 * Workspace definitions and preset runs. A workspace names a directory; it never changes a session's working
 * directory. Running a preset is a managed job. Creating, updating, removing, selecting and running are
 * mutations and are never retried here.
 */
export class Workspaces {
  private readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }

  private async call(method: string, params: Params, signal?: AbortSignal) {
    const { result } = await this.client.request("system.capabilities", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string"))
      throw new ProtocolError("Invalid capabilities");
    if (!(result.methods as string[]).includes(method)) throw new UnavailableError(`Method not available: ${method}`);
    return this.client.request(method, params, { signal });
  }

  async list(signal?: AbortSignal): Promise<{ workspaces: Workspace[]; activeId: string | null }> {
    const { result } = await this.call("workspace.list", {}, signal);
    if (!isObject(result) || !Array.isArray(result.workspaces) || !(result.activeId === null || (typeof result.activeId === "string" && WS_ID.test(result.activeId))))
      throw new ProtocolError("Invalid workspace list");
    return { workspaces: result.workspaces.map(item => workspace(item)), activeId: result.activeId as string | null };
  }

  async get(id: string, signal?: AbortSignal): Promise<Workspace> {
    const { result } = await this.call("workspace.get", { id }, signal);
    if (!isObject(result)) throw new ProtocolError("Invalid workspace response");
    return workspace(result.workspace);
  }

  async create(spec: WorkspaceSpec, signal?: AbortSignal): Promise<Workspace> {
    const params: Params = { name: spec.name, rootId: spec.rootId, path: spec.path };
    if (spec.taskPresets !== undefined) params.taskPresets = spec.taskPresets;
    const { result } = await this.call("workspace.create", params, signal);
    try {
      if (!isObject(result)) throw new ProtocolError("Invalid workspace create response");
      return workspace(result.workspace);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError("Invalid workspace create response", true);
      throw error;
    }
  }

  async update(id: string, changes: WorkspaceChanges, signal?: AbortSignal): Promise<Workspace> {
    const { result } = await this.call("workspace.update", { id, ...changes }, signal);
    if (!isObject(result)) throw new ProtocolError("Invalid workspace update response", true);
    return workspace(result.workspace);
  }

  async remove(id: string, signal?: AbortSignal): Promise<void> {
    const { result } = await this.call("workspace.remove", { id }, signal);
    if (!isObject(result) || result.accepted !== true) throw new ProtocolError("Invalid remove acknowledgement", true);
  }

  async select(id: string, signal?: AbortSignal): Promise<{ workspace: Workspace; activeId: string }> {
    const { result } = await this.call("workspace.select", { id }, signal);
    if (!isObject(result) || typeof result.activeId !== "string" || !WS_ID.test(result.activeId)) throw new ProtocolError("Invalid selection response", true);
    return { workspace: workspace(result.workspace), activeId: result.activeId };
  }

  async tasks(id: string, signal?: AbortSignal): Promise<TaskPreset[]> {
    const { result } = await this.call("workspace.tasks", { id }, signal);
    if (!isObject(result) || !Array.isArray(result.tasks)) throw new ProtocolError("Invalid task list");
    return result.tasks.map(item => preset(item));
  }

  /** Read-only Git inspection in the workspace directory. The server supplies the argv; the client names only the subcommand. */
  async git(workspaceId: string, subcommand: "status" | "branch" | "diff" | "log", options: { clientRequestId?: string; signal?: AbortSignal } = {}): Promise<JobRecord> {
    const params: Params = { workspaceId, subcommand };
    if (options.clientRequestId !== undefined) params.clientRequestId = options.clientRequestId;
    const { result } = await this.call("workspace.git", params, options.signal);
    try {
      if (!isObject(result)) throw new ProtocolError("Invalid git response");
      return jobRecord(result.job);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError("Invalid git response", true);
      throw error;
    }
  }

  /**
   * Starts a stored preset as a managed job in the workspace directory. Only the two identifiers are sent;
   * the server supplies the executable, arguments and directory. Start returns admission, not completion.
   */
  /** fetch, pull, switch (branch), stage (paths) or commit (message), as a managed job. The server builds the argv. */
  async gitChange(workspaceId: string, action: "fetch" | "pull" | "switch" | "stage" | "commit",
                  options: { branch?: string; paths?: string[]; message?: string; clientRequestId?: string; signal?: AbortSignal } = {}): Promise<JobRecord> {
    if (!["fetch", "pull", "switch", "stage", "commit"].includes(action)) throw new TypeError("Unknown Git action");
    const params: Params = { workspaceId, action };
    if (options.branch !== undefined) params.branch = options.branch;
    if (options.paths !== undefined) {
      if (!Array.isArray(options.paths) || !options.paths.every(path => typeof path === "string")) throw new TypeError("paths must be strings");
      params.paths = [...options.paths];
    }
    if (options.message !== undefined) params.message = options.message;
    if (options.clientRequestId !== undefined) params.clientRequestId = options.clientRequestId;
    const { result } = await this.call("workspace.git.change", params, options.signal);
    try {
      if (!isObject(result)) throw new ProtocolError("Invalid Git change response");
      return jobRecord(result.job);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError("Invalid Git change response", true);
      throw error;
    }
  }

  async runTask(workspaceId: string, taskId: string, options: { clientRequestId?: string; signal?: AbortSignal } = {}): Promise<JobRecord> {
    const params: Params = { workspaceId, taskId };
    if (options.clientRequestId !== undefined) params.clientRequestId = options.clientRequestId;
    const { result } = await this.call("workspace.task.run", params, options.signal);
    try {
      if (!isObject(result)) throw new ProtocolError("Invalid task run response");
      return jobRecord(result.job);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError("Invalid task run response", true);
      throw error;
    }
  }
}


import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";

export type ProcessScope = "owned" | "session" | "task" | "all-visible";
export type JobSignal = "TERM" | "KILL" | "INT";
export interface ProcessRow {
  pid: number; ppid: number; name: string; state: string; rssKiB: number | null; cpuPercent: number | null;
  startTicks: number; managedJobId: null; visibility: "partial";
}

type Params = Record<string, unknown>;
const SCOPES = new Set<string>(["owned", "session", "task", "all-visible"]);
const SIGNALS = new Set<string>(["TERM", "KILL", "INT"]);
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);
const nonNegative = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;

function row(value: unknown): ProcessRow {
  if (!isObject(value) || !Number.isSafeInteger(value.pid) || !nonNegative(value.ppid) || typeof value.name !== "string" ||
      typeof value.state !== "string" || value.state.length !== 1 || !(value.rssKiB === null || nonNegative(value.rssKiB)) ||
      !(value.cpuPercent === null || typeof value.cpuPercent === "number") || !nonNegative(value.startTicks) ||
      value.managedJobId !== null || value.visibility !== "partial")
    throw new ProtocolError("Invalid process row");
  return value as unknown as ProcessRow;
}

/**
 * Bounded process listing, plus signals to managed jobs by job ID. Pages are not a stable snapshot; each call
 * takes a fresh sample. There is no way to signal a PID: the server accepts only a job it owns.
 */
export class Processes {
  private readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }

  private async require(method: string, signal?: AbortSignal): Promise<void> {
    const { result } = await this.client.request("system.capabilities", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string"))
      throw new ProtocolError("Invalid capabilities");
    if (!(result.methods as string[]).includes(method)) throw new UnavailableError("Method not available: " + method);
  }

  /** Sends one signal to a running managed job's process group. Prefer jobs.cancel to stop a job. */
  async signalJob(jobId: string, jobSignal: JobSignal, options: { clientRequestId?: string; signal?: AbortSignal } = {}): Promise<void> {
    if (typeof jobId !== "string" || !SIGNALS.has(jobSignal)) throw new TypeError("jobId must be a string and signal TERM, KILL or INT");
    await this.require("process.signal", options.signal);
    const params: Params = { jobId, signal: jobSignal };
    if (options.clientRequestId !== undefined) params.clientRequestId = options.clientRequestId;
    const { result } = await this.client.request("process.signal", params, { signal: options.signal });
    if (!isObject(result) || result.sent !== true) throw new ProtocolError("Invalid signal acknowledgement", true);
  }

  async list(options: { scope?: ProcessScope; cursor?: number; limit?: number; signal?: AbortSignal } = {}):
      Promise<{ processes: ProcessRow[]; incomplete: boolean; missingRoots: number; nextCursor: number | null }> {
    const params: Params = {};
    if (options.scope !== undefined) {
      if (!SCOPES.has(options.scope)) throw new TypeError("Unknown process scope");
      params.scope = options.scope;
    }
    if (options.cursor !== undefined) params.cursor = options.cursor;
    if (options.limit !== undefined) params.limit = options.limit;
    await this.require("process.list", options.signal);
    const response = await this.client.request("process.list", params, { signal: options.signal });
    const body = response.result;
    if (!isObject(body) || !Array.isArray(body.processes) || typeof body.incomplete !== "boolean" || !nonNegative(body.missingRoots) ||
        !(body.nextCursor === null || nonNegative(body.nextCursor)))
      throw new ProtocolError("Invalid process list");
    return { processes: body.processes.map(item => row(item)), incomplete: body.incomplete, missingRoots: body.missingRoots as number, nextCursor: body.nextCursor as number | null };
  }
}

import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";

export type JobState = "queued" | "starting" | "running" | "cancelling" | "exited" | "failed" | "cancelled" | "timed_out" | "lost";
export type JobStream = "stdout" | "stderr";
export interface JobSpec {
  executable: string;
  args: string[];
  cwd?: string;
  workspaceId?: string;
  env?: Record<string, string>;
  stdin?: string;
  timeoutMs?: number;
  presentation?: "foreground" | "background";
  clientRequestId?: string;
}
export interface JobRecord {
  id: string;
  state: JobState;
  startedAt: number | null;
  endedAt: number | null;
  exitCode: number | null;
  failureCode: string | null;
  pid: number | null;
  workspaceId: string | null;
  output: { stdoutEndOffset: number; stderrEndOffset: number };
}
export interface JobOutputChunk {
  offset: number;
  nextOffset: number;
  endOffset: number;
  gap: boolean;
  finished: boolean;
  complete: boolean;
  data: Uint8Array;
}
export class JobWaitTimeoutError extends Error {
  constructor(id: string) { super(`Timed out waiting for job ${id}`); this.name = "JobWaitTimeoutError"; }
}

type Params = Record<string, unknown>;
const STATES = new Set<string>(["queued", "starting", "running", "cancelling", "exited", "failed", "cancelled", "timed_out", "lost"]);
const TERMINAL = new Set<string>(["exited", "failed", "cancelled", "timed_out", "lost"]);
const SPEC_KEYS = ["cwd", "workspaceId", "env", "stdin", "timeoutMs", "presentation", "clientRequestId"] as const;
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);
const nonNegative = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const nullableInteger = (value: unknown) => value === null || Number.isSafeInteger(value);
const nullableString = (value: unknown) => value === null || typeof value === "string";

export function jobRecord(value: unknown): JobRecord {
  if (!isObject(value) || typeof value.id !== "string" || !STATES.has(value.state as string) ||
      !nullableInteger(value.startedAt) || !nullableInteger(value.endedAt) || !nullableInteger(value.exitCode) ||
      !nullableInteger(value.pid) || !nullableString(value.failureCode) || !nullableString(value.workspaceId) ||
      !isObject(value.output) || !nonNegative(value.output.stdoutEndOffset) || !nonNegative(value.output.stderrEndOffset))
    throw new ProtocolError("Invalid job metadata");
  return value as unknown as JobRecord;
}

function startParams(spec: JobSpec): Params {
  if (!isObject(spec) || typeof spec.executable !== "string" || !Array.isArray(spec.args)) throw new TypeError("Invalid job specification");
  const params: Params = { executable: spec.executable, args: spec.args };
  for (const key of SPEC_KEYS) if (spec[key] !== undefined) params[key] = spec[key];
  return params;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new Error("Job wait aborted")); return; }
    const onAbort = () => { clearTimeout(timer); reject(new Error("Job wait aborted")); };
    const timer = setTimeout(() => { signal?.removeEventListener("abort", onAbort); resolve(); }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

/**
 * Managed noninteractive jobs. A start or cancel that loses its response has an unknown outcome and is
 * never retried here. Reads may be retried by the caller.
 */
export class Jobs {
  private readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }

  /** Gates every call on the server's advertised methods; a missing method is reported without being sent. */
  private async call(method: string, params: Params, signal?: AbortSignal) {
    const { result } = await this.client.request("system.capabilities", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string"))
      throw new ProtocolError("Invalid capabilities");
    if (!(result.methods as string[]).includes(method)) throw new UnavailableError(`Method not available: ${method}`);
    return this.client.request(method, params, { signal });
  }

  /** Returns the admitted record. It does not mean the command has finished; use wait() or get(). */
  async start(spec: JobSpec, signal?: AbortSignal): Promise<JobRecord> {
    const params = startParams(spec);
    const { result } = await this.call("job.start", params, signal);
    try {
      if (!isObject(result)) throw new ProtocolError("Invalid job start response");
      return jobRecord(result.job);
    } catch (error) {
      if (error instanceof ProtocolError) throw new ProtocolError("Invalid job start response", true);
      throw error;
    }
  }

  async list(options: { workspaceId?: string; cursor?: number; limit?: number; signal?: AbortSignal } = {}): Promise<{ jobs: JobRecord[]; nextCursor: number | null }> {
    const params: Params = {};
    if (options.workspaceId !== undefined) params.workspaceId = options.workspaceId;
    if (options.cursor !== undefined) params.cursor = options.cursor;
    if (options.limit !== undefined) params.limit = options.limit;
    const { result } = await this.call("job.list", params, options.signal);
    if (!isObject(result) || !Array.isArray(result.jobs) || !(result.nextCursor === null || nonNegative(result.nextCursor)))
      throw new ProtocolError("Invalid job list");
    return { jobs: result.jobs.map(entry => jobRecord(entry)), nextCursor: result.nextCursor as number | null };
  }

  async get(id: string, signal?: AbortSignal): Promise<JobRecord> {
    const { result } = await this.call("job.get", { id }, signal);
    if (!isObject(result)) throw new ProtocolError("Invalid job response");
    return jobRecord(result.job);
  }

  async cancel(id: string, signal?: AbortSignal): Promise<void> {
    const { result } = await this.call("job.cancel", { id }, signal);
    if (!isObject(result) || result.accepted !== true) throw new ProtocolError("Invalid cancel acknowledgement", true);
  }

  /** Reads one bounded chunk. Offsets are validated so a gap is visible to the caller. */
  async readOutput(id: string, stream: JobStream, offset: number, limit = 65536, signal?: AbortSignal): Promise<JobOutputChunk> {
    const { result, binary } = await this.call("job.output.read", { id, stream, offset, limit }, signal);
    if (!isObject(result) || ![result.offset, result.nextOffset, result.endOffset, result.length].every(nonNegative) ||
        typeof result.gap !== "boolean" || typeof result.finished !== "boolean" || typeof result.complete !== "boolean" ||
        result.length !== binary.length || (result.nextOffset as number) !== (result.offset as number) + binary.length ||
        (result.nextOffset as number) > (result.endOffset as number))
      throw new ProtocolError("Invalid job output offsets");
    return {
      offset: result.offset as number, nextOffset: result.nextOffset as number, endOffset: result.endOffset as number,
      gap: result.gap, finished: result.finished, complete: result.complete, data: binary,
    };
  }

  /** Polls job.get until a terminal state. This is a client helper; the server never blocks on it. */
  async wait(id: string, options: { timeoutMs?: number; pollMs?: number; signal?: AbortSignal } = {}): Promise<JobRecord> {
    const deadline = Date.now() + (options.timeoutMs ?? 60_000);
    const pollMs = options.pollMs ?? 250;
    for (;;) {
      const record = await this.get(id, options.signal);
      if (TERMINAL.has(record.state)) return record;
      if (Date.now() >= deadline) throw new JobWaitTimeoutError(id);
      await sleep(pollMs, options.signal);
    }
  }

  /** Yields output in order until the stream is finished. A chunk with gap=true reports lost bytes. */
  async *follow(id: string, stream: JobStream, options: { pollMs?: number; signal?: AbortSignal } = {}): AsyncGenerator<JobOutputChunk, void, void> {
    const pollMs = options.pollMs ?? 250;
    let offset = 0;
    for (;;) {
      const chunk = await this.readOutput(id, stream, offset, 65536, options.signal);
      if (chunk.data.length > 0) yield chunk;
      offset = chunk.nextOffset;
      if (chunk.finished && (chunk.data.length === 0 || offset >= chunk.endOffset)) return;
      if (chunk.data.length === 0) await sleep(pollMs, options.signal);
    }
  }
}

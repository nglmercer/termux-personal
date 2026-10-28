import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";

export interface SessionInfo { id: string; name: string | null; state: "starting" | "running" | "exited"; pid: number | null }
export interface OutputChunk { offset: number; nextOffset: number; endOffset: number; gap: boolean; data: Uint8Array }
type Params = Record<string, unknown>;
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);

function session(value: unknown): SessionInfo {
  if (!isObject(value) || typeof value.id !== "string" || !(typeof value.name === "string" || value.name === null) ||
      !["starting", "running", "exited"].includes(value.state as string) ||
      !(value.pid === null || Number.isInteger(value.pid))) throw new ProtocolError("Invalid session metadata");
  return value as unknown as SessionInfo;
}
function acknowledged(value: unknown, key: string): void {
  if (!isObject(value) || value[key] !== true) throw new ProtocolError("Invalid session acknowledgement", true);
}

/** Existing PTY sessions. A write accepts bytes; it does not report command completion. */
export class Sessions {
  private readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }
  private async call(method: string, params: Params = {}, options: { binary?: Uint8Array; signal?: AbortSignal; extra?: string[] } = {}) {
    const capabilities = await this.client.request("system.capabilities", {}, { signal: options.signal });
    if (!isObject(capabilities.result) || !Array.isArray(capabilities.result.methods) || !capabilities.result.methods.every(x => typeof x === "string"))
      throw new ProtocolError("Invalid capabilities");
    const methods: string[] = capabilities.result.methods;
    if (!methods.includes(method) || !(options.extra ?? []).every(name => methods.includes(name))) throw new UnavailableError(`Method not available: ${method}`);
    return this.client.request(method, params, { binary: options.binary, signal: options.signal });
  }
  async list(signal?: AbortSignal): Promise<SessionInfo[]> {
    const { result } = await this.call("session.list", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.sessions)) throw new ProtocolError("Invalid session list");
    return result.sessions.map(session);
  }
  async create(columns: number, rows: number, options: { name?: string; cellWidth?: number; cellHeight?: number; signal?: AbortSignal } = {}): Promise<SessionInfo> {
    const { result } = await this.call("session.create", { columns, rows, cellWidth: options.cellWidth ?? 0, cellHeight: options.cellHeight ?? 0, ...(options.name === undefined ? {} : { name: options.name }) }, { signal: options.signal });
    try { return session(result); }
    catch (error) { if (error instanceof ProtocolError) throw new ProtocolError("Invalid created session metadata", true); throw error; }
  }
  async write(id: string, data: Uint8Array, signal?: AbortSignal): Promise<void> {
    acknowledged((await this.call("session.write", { id, length: data.length }, { binary: data, signal })).result, "ok");
  }
  async rename(id: string, name: string, signal?: AbortSignal): Promise<void> {
    acknowledged((await this.call("session.rename", { id, name }, { signal })).result, "ok");
  }
  async resize(id: string, columns: number, rows: number, options: { cellWidth?: number; cellHeight?: number; signal?: AbortSignal } = {}): Promise<void> {
    acknowledged((await this.call("session.resize", { id, columns, rows, cellWidth: options.cellWidth ?? 0, cellHeight: options.cellHeight ?? 0 }, { signal: options.signal })).result, "ok");
  }
  async focus(id: string, signal?: AbortSignal): Promise<void> { acknowledged((await this.call("session.focus", { id }, { signal })).result, "ok"); }
  async close(id: string, signal?: AbortSignal): Promise<void> { acknowledged((await this.call("session.close", { id }, { signal })).result, "accepted"); }
  async snapshot(id: string, signal?: AbortSignal): Promise<string> {
    const { result } = await this.call("session.snapshot", { id }, { signal });
    if (!isObject(result) || typeof result.text !== "string") throw new ProtocolError("Invalid snapshot");
    return result.text;
  }
  async subscribe(id: string, options: { resumable?: boolean; signal?: AbortSignal } = {}): Promise<string | null> {
    const { result } = await this.call("session.output.subscribe", { id, ...(options.resumable ? { resumable: true } : {}) },
      { signal: options.signal, extra: options.resumable ? ["session.output.resume"] : [] });
    acknowledged(result, "ok");
    if (!options.resumable) return null;
    if (!isObject(result) || typeof result.resumeToken !== "string" || !/^[0-9a-f]{64}$/.test(result.resumeToken)) throw new ProtocolError("Invalid resume token", true);
    return result.resumeToken;
  }
  async resume(id: string, token: string, signal?: AbortSignal): Promise<void> { acknowledged((await this.call("session.output.resume", { id, token }, { signal })).result, "ok"); }
  async unsubscribe(id: string, signal?: AbortSignal): Promise<void> { acknowledged((await this.call("session.output.unsubscribe", { id }, { signal })).result, "ok"); }
  async readOutput(id: string, offset: number, limit = 65536, signal?: AbortSignal): Promise<OutputChunk> {
    const { result, binary } = await this.call("session.output.read", { id, offset, limit }, { signal });
    if (!isObject(result) || ![result.offset, result.nextOffset, result.endOffset].every(n => Number.isSafeInteger(n) && (n as number) >= 0) ||
        typeof result.gap !== "boolean" || result.length !== binary.length ||
        (result.nextOffset as number) !== (result.offset as number) + binary.length || (result.nextOffset as number) > (result.endOffset as number))
      throw new ProtocolError("Invalid output offsets");
    return { offset: result.offset as number, nextOffset: result.nextOffset as number, endOffset: result.endOffset as number, gap: result.gap, data: binary };
  }
}

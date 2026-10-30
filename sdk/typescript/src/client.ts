import { ProtocolError, RpcError, TransportError } from "./errors.ts";
import { MAX_CHUNK, UnixSocketTransport, type ByteTransport } from "./transports.ts";
import { Sessions } from "./sessions.ts";
import { Jobs } from "./jobs.ts";
import { Files } from "./files.ts";
import { Processes } from "./processes.ts";
import { Workspaces } from "./workspaces.ts";

const readOnly = new Set(["system.hello", "system.capabilities", "system.describe", "session.list", "session.snapshot", "session.output.read",
  "job.list", "job.get", "job.output.read", "process.list", "file.roots", "file.list", "file.stat", "file.read",
  "workspace.list", "workspace.get", "workspace.tasks"]);
type JsonObject = Record<string, unknown>;
function object(value: unknown): value is JsonObject { return typeof value === "object" && value !== null && !Array.isArray(value); }

export class TermuxClient {
  private nextId = 1;
  private tail: Promise<void> = Promise.resolve();
  private closed = false;
  private readonly transport: ByteTransport;
  readonly sessions: Sessions;
  readonly jobs: Jobs;
  readonly processes: Processes;
  readonly files: Files;
  readonly workspaces: Workspaces;
  private constructor(transport: ByteTransport) {
    this.transport = transport;
    this.sessions = new Sessions(this);
    this.jobs = new Jobs(this);
    this.processes = new Processes(this);
    this.files = new Files(this);
    this.workspaces = new Workspaces(this);
  }

  static async fromTransport(transport: ByteTransport): Promise<TermuxClient> {
    const client = new TermuxClient(transport);
    try {
      const { result } = await client.request("system.hello", { version: 1 });
      if (!object(result) || result.version !== 1 || Object.keys(result).length !== 1) throw new ProtocolError("Unexpected negotiation response");
      return client;
    } catch (error) { client.close(); throw error; }
  }
  static async connect(path: string, timeoutMs = 5000): Promise<TermuxClient> {
    try { return await TermuxClient.fromTransport(await UnixSocketTransport.connect(path, timeoutMs)); }
    catch (error) {
      if (error instanceof RpcError || error instanceof ProtocolError || error instanceof TransportError) throw error;
      throw new TransportError("Unable to connect to local control socket", false, { cause: error });
    }
  }
  close(): void { if (!this.closed) { this.closed = true; this.transport.close(); } }

  request(method: string, params: JsonObject = {}, options: { binary?: Uint8Array; signal?: AbortSignal } = {}): Promise<{ result: unknown; binary: Uint8Array }> {
    if (typeof method !== "string" || !method || method.length > 128 || !object(params)) return Promise.reject(new TypeError("Invalid request"));
    const binary = options.binary;
    if (method === "session.write" || method === "file.transfer.write") {
      if (!(binary instanceof Uint8Array) || !binary.length || binary.length > MAX_CHUNK || params.length !== binary.length)
        return Promise.reject(new TypeError(method + " requires a matching binary frame"));
    } else if (binary !== undefined) return Promise.reject(new TypeError("No binary request route for this method"));
    const task = this.tail.then(() => this.perform(method, params, binary, options.signal));
    this.tail = task.then(() => undefined, () => undefined);
    return task;
  }

  private async perform(method: string, params: JsonObject, binary?: Uint8Array, signal?: AbortSignal): Promise<{ result: unknown; binary: Uint8Array }> {
    if (this.closed) throw new TransportError("Client is closed");
    if (signal?.aborted) throw new TransportError("Request aborted");
    const id = this.nextId++;
    let encoded: Uint8Array;
    try { encoded = new TextEncoder().encode(JSON.stringify({ jsonrpc: "2.0", id, method, params })); }
    catch (error) { throw new TypeError("Invalid request parameters", { cause: error }); }
    if (encoded.length > MAX_CHUNK) throw new TypeError("Request is too large");
    let started = false;
    const abort = () => this.close();
    signal?.addEventListener("abort", abort, { once: true });
    try {
      if (signal?.aborted) throw new TransportError("Request aborted");
      started = true;
      await this.transport.writeFrame(encoded);
      if (binary) await this.transport.writeFrame(binary);
      const frame = await this.transport.readFrame();
      const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(frame));
      if (!object(parsed) || parsed.jsonrpc !== "2.0" || parsed.id !== id || ("result" in parsed) === ("error" in parsed))
        throw new ProtocolError("Invalid or mismatched response");
      if ("error" in parsed) {
        const error = parsed.error;
        if (!object(error) || !Number.isInteger(error.code) || typeof error.message !== "string") throw new ProtocolError("Invalid error response");
        throw new RpcError(error.code as number, error.message, error.data);
      }
      let payload: Uint8Array = new Uint8Array(0);
      if (method === "session.output.read" || method === "job.output.read" || method === "file.read") {
        if (!object(parsed.result) || !Number.isInteger(parsed.result.length) || (parsed.result.length as number) < 0 || (parsed.result.length as number) > MAX_CHUNK)
          throw new ProtocolError("Invalid binary response metadata");
        const length = parsed.result.length as number;
        if (length) {
          payload = await this.transport.readFrame();
          if (payload.length !== length) throw new ProtocolError("Binary response length mismatch");
        }
      }
      return { result: parsed.result, binary: payload };
    } catch (error) {
      if (error instanceof RpcError) {
        if (binary || error.code === -32002) this.close();
        throw error;
      }
      this.close();
      if (!started && error instanceof TransportError) throw error;
      const message = error instanceof Error && error.message.startsWith("ADB ") ? error.message : "Control connection failed";
      throw new TransportError(message, started && !readOnly.has(method), { cause: error });
    } finally { signal?.removeEventListener("abort", abort); }
  }

  async capabilities(): Promise<{ methods: string[] }> {
    const { result } = await this.request("system.capabilities");
    if (!object(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string")) throw new ProtocolError("Invalid capabilities");
    return { methods: result.methods };
  }
  async describe(): Promise<JsonObject> {
    try {
      const { result } = await this.request("system.describe");
      if (!object(result)) throw new ProtocolError("Invalid description");
      return result;
    } catch (error) {
      if (!(error instanceof RpcError) || error.code !== -32601) throw error;
      return { protocolVersion: 1, methods: (await this.capabilities()).methods.map(name => ({ name })), legacy: true };
    }
  }
}

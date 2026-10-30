import type { TermuxClient } from "./client.ts";
import { ProtocolError, UnavailableError } from "./errors.ts";

export type FileType = "file" | "directory" | "symlink" | "other";
export interface FileRoot { id: string; writable: boolean }
export interface FileEntry { name: string; path: string; type: FileType; size: number | null; mtime: number; revision: string }
export interface FileChunk { offset: number; nextOffset: number; eof: boolean; revision: string; data: Uint8Array }

type Params = Record<string, unknown>;
const TYPES = new Set<string>(["file", "directory", "symlink", "other"]);
const isObject = (value: unknown): value is Params => typeof value === "object" && value !== null && !Array.isArray(value);
const nonNegative = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;

function entry(value: unknown): FileEntry {
  if (!isObject(value) || typeof value.name !== "string" || typeof value.path !== "string" || !TYPES.has(value.type as string) ||
      !(value.size === null || nonNegative(value.size)) || !nonNegative(value.mtime) || typeof value.revision !== "string")
    throw new ProtocolError("Invalid file entry");
  return value as unknown as FileEntry;
}

export interface FileCommit { entry: FileEntry; sha256: string }

/**
 * File access under server-named roots. Paths are root-relative. The SDK never builds absolute
 * device paths, and it never follows a symlink the server reports as one.
 */
export class Files {
  private readonly client: TermuxClient;
  constructor(client: TermuxClient) { this.client = client; }

  private async call(method: string, params: Params, signal?: AbortSignal) {
    const { result } = await this.client.request("system.capabilities", {}, { signal });
    if (!isObject(result) || !Array.isArray(result.methods) || !result.methods.every(x => typeof x === "string"))
      throw new ProtocolError("Invalid capabilities");
    if (!(result.methods as string[]).includes(method)) throw new UnavailableError(`Method not available: ${method}`);
    return this.client.request(method, params, { signal });
  }

  async roots(signal?: AbortSignal): Promise<FileRoot[]> {
    const { result } = await this.call("file.roots", {}, signal);
    if (!isObject(result) || !Array.isArray(result.roots) ||
        !result.roots.every(root => isObject(root) && typeof root.id === "string" && typeof root.writable === "boolean"))
      throw new ProtocolError("Invalid file roots");
    return result.roots as FileRoot[];
  }

  async list(rootId: string, options: { path?: string; cursor?: number; limit?: number; signal?: AbortSignal } = {}): Promise<{ entries: FileEntry[]; nextCursor: number | null }> {
    const params: Params = { rootId };
    if (options.path !== undefined) params.path = options.path;
    if (options.cursor !== undefined) params.cursor = options.cursor;
    if (options.limit !== undefined) params.limit = options.limit;
    const { result } = await this.call("file.list", params, options.signal);
    if (!isObject(result) || !Array.isArray(result.entries) || !(result.nextCursor === null || nonNegative(result.nextCursor)))
      throw new ProtocolError("Invalid file list");
    return { entries: result.entries.map(item => entry(item)), nextCursor: result.nextCursor as number | null };
  }

  async stat(rootId: string, path: string, signal?: AbortSignal): Promise<FileEntry> {
    const { result } = await this.call("file.stat", { rootId, path }, signal);
    if (!isObject(result)) throw new ProtocolError("Invalid file stat response");
    return entry(result.entry);
  }

  /** Reads one bounded chunk. The binary frame length and the offsets must agree. */
  async read(rootId: string, path: string, offset = 0, limit = 65536, signal?: AbortSignal): Promise<FileChunk> {
    const { result, binary } = await this.call("file.read", { rootId, path, offset, limit }, signal);
    if (!isObject(result) || ![result.offset, result.nextOffset, result.length].every(nonNegative) || typeof result.eof !== "boolean" ||
        typeof result.revision !== "string" || result.length !== binary.length || (result.nextOffset as number) !== (result.offset as number) + binary.length)
      throw new ProtocolError("Invalid file read metadata");
    return { offset: result.offset as number, nextOffset: result.nextOffset as number, eof: result.eof, revision: result.revision, data: binary };
  }

  private async entryCall(method: string, params: Params, signal?: AbortSignal): Promise<FileEntry> {
    const { result } = await this.call(method, params, signal);
    if (!isObject(result)) throw new ProtocolError("Invalid file response", true);
    try { return entry(result.entry); } catch { throw new ProtocolError("Invalid file response", true); }
  }

  mkdir(rootId: string, path: string, signal?: AbortSignal): Promise<FileEntry> {
    return this.entryCall("file.mkdir", { rootId, path }, signal);
  }

  /** Never overwrites; with ifRevision the source must be unchanged. */
  rename(rootId: string, path: string, newPath: string, options: { ifRevision?: string; signal?: AbortSignal } = {}): Promise<FileEntry> {
    const params: Params = { rootId, path, newPath };
    if (options.ifRevision !== undefined) params.ifRevision = options.ifRevision;
    return this.entryCall("file.rename", params, options.signal);
  }

  /** A regular file or an empty directory; there is no recursive delete. */
  async delete(rootId: string, path: string, options: { ifRevision?: string; signal?: AbortSignal } = {}): Promise<void> {
    const params: Params = { rootId, path };
    if (options.ifRevision !== undefined) params.ifRevision = options.ifRevision;
    const { result } = await this.call("file.delete", params, options.signal);
    if (!isObject(result) || result.accepted !== true) throw new ProtocolError("Invalid delete acknowledgement", true);
  }

  /**
   * Uploads in server-sized chunks and commits. Replacing an existing file requires its current revision.
   * On failure the transfer is aborted while the connection is open; a rejected chunk closes the v1 connection,
   * and the server then discards the idle transfer on expiry. Nothing is published without a commit, and a
   * lost commit reply leaves the outcome unknown.
   */
  async upload(rootId: string, path: string, data: Uint8Array, options: { ifRevision?: string; sha256?: string; signal?: AbortSignal } = {}): Promise<FileCommit> {
    if (!(data instanceof Uint8Array)) throw new TypeError("data must be a Uint8Array");
    const params: Params = { rootId, path, totalBytes: data.length };
    if (options.sha256 !== undefined) params.sha256 = options.sha256;
    if (options.ifRevision !== undefined) { params.ifRevision = options.ifRevision; params.overwrite = true; }
    const { result: begun } = await this.call("file.transfer.begin", params, options.signal);
    if (!isObject(begun) || typeof begun.transferId !== "string" || !nonNegative(begun.chunkSize) || begun.chunkSize < 1 || begun.offset !== 0)
      throw new ProtocolError("Invalid transfer start", true);
    const transferId = begun.transferId, chunkSize = Math.min(begun.chunkSize as number, 65536);
    try {
      for (let offset = 0; offset < data.length; offset += chunkSize) {
        const chunk = data.subarray(offset, Math.min(data.length, offset + chunkSize));
        const { result } = await this.client.request("file.transfer.write", { transferId, offset, length: chunk.length },
          { binary: chunk, signal: options.signal });
        if (!isObject(result) || result.nextOffset !== offset + chunk.length) throw new ProtocolError("Invalid transfer offset");
      }
    } catch (error) {
      await this.client.request("file.transfer.abort", { transferId }).catch(() => undefined);
      throw error;
    }
    const { result } = await this.client.request("file.transfer.commit", { transferId }, { signal: options.signal });
    if (!isObject(result) || result.committed !== true || typeof result.sha256 !== "string") throw new ProtocolError("Invalid commit", true);
    return { entry: entry(result.entry), sha256: result.sha256 };
  }
}

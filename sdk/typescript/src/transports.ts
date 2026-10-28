import net from "node:net";
import { ProtocolError } from "./errors.ts";

export const MAX_CHUNK = 64 * 1024;
export interface ByteTransport {
  readFrame(): Promise<Uint8Array>;
  writeFrame(bytes: Uint8Array): Promise<void>;
  close(): void;
}

/** Stream reads are demand-driven to avoid accumulating unbounded server output. */
export class UnixSocketTransport implements ByteTransport {
  private readonly iterator: AsyncIterator<Buffer>;
  private readonly socket: net.Socket;
  private failure?: Error;
  private pending = Buffer.alloc(0);
  private constructor(socket: net.Socket) {
    this.socket = socket;
    // Keep an error listener installed even between requests; idle timeouts must not crash the host.
    socket.on("error", error => { this.failure = error; });
    this.iterator = socket[Symbol.asyncIterator]() as AsyncIterator<Buffer>;
  }

  static connect(path: string, timeoutMs = 5000): Promise<UnixSocketTransport> {
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ path });
      socket.setTimeout(timeoutMs, () => socket.destroy(new Error("Socket timeout")));
      const error = (err: Error) => { socket.destroy(); reject(err); };
      socket.once("error", error);
      socket.once("connect", () => { socket.off("error", error); resolve(new UnixSocketTransport(socket)); });
    });
  }

  async writeFrame(bytes: Uint8Array): Promise<void> {
    if (!bytes.length || bytes.length > MAX_CHUNK) throw new ProtocolError("Invalid frame length");
    if (this.socket.destroyed) throw this.failure ?? new Error("Connection closed");
    const header = Buffer.alloc(4); header.writeUInt32BE(bytes.length);
    await new Promise<void>((resolve, reject) => {
      this.socket.write(Buffer.concat([header, bytes]), (error) => error ? reject(error) : resolve());
    });
  }

  private async readExact(length: number): Promise<Buffer> {
    const chunks: Buffer[] = [];
    let remaining = length;
    while (remaining) {
      if (!this.pending.length) {
        const next = await this.iterator.next();
        if (next.done) throw new Error("Connection closed during frame");
        this.pending = Buffer.from(next.value);
      }
      const take = Math.min(remaining, this.pending.length);
      chunks.push(this.pending.subarray(0, take));
      this.pending = this.pending.subarray(take); remaining -= take;
    }
    return Buffer.concat(chunks, length);
  }

  async readFrame(): Promise<Uint8Array> {
    const length = (await this.readExact(4)).readUInt32BE();
    if (!length || length > MAX_CHUNK) throw new ProtocolError("Invalid frame length");
    return this.readExact(length);
  }
  close(): void { this.socket.destroy(); }
}

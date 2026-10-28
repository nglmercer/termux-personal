import net from "node:net";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { Duplex } from "node:stream";
import { ProtocolError } from "./errors.ts";

export const MAX_CHUNK = 64 * 1024;
export interface ByteTransport {
  readFrame(): Promise<Uint8Array>;
  writeFrame(bytes: Uint8Array): Promise<void>;
  close(): void;
}

/** Stream reads are demand-driven to avoid accumulating unbounded server output. */
class FramedStreamTransport implements ByteTransport {
  private readonly iterator: AsyncIterator<Buffer>;
  protected readonly stream: Duplex;
  private failure?: Error;
  private readonly timeoutMs: number;
  private pending = Buffer.alloc(0);
  protected constructor(stream: Duplex, timeoutMs = 5000) {
    this.stream = stream;
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new RangeError("Invalid transport timeout");
    this.timeoutMs = timeoutMs;
    // Keep an error listener installed even between requests; idle timeouts must not crash the host.
    stream.on("error", error => { this.failure = error; });
    this.iterator = stream[Symbol.asyncIterator]() as AsyncIterator<Buffer>;
  }

  async writeFrame(bytes: Uint8Array): Promise<void> {
    if (!bytes.length || bytes.length > MAX_CHUNK) throw new ProtocolError("Invalid frame length");
    if (this.stream.destroyed) throw this.failure ?? new Error("Connection closed");
    const header = Buffer.alloc(4); header.writeUInt32BE(bytes.length);
    await this.withTimeout(new Promise<void>((resolve, reject) => {
      this.stream.write(Buffer.concat([header, bytes]), (error) => error ? reject(error) : resolve());
    }));
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
    return this.withTimeout((async () => {
      const length = (await this.readExact(4)).readUInt32BE();
      if (!length || length > MAX_CHUNK) throw new ProtocolError("Invalid frame length");
      return this.readExact(length);
    })());
  }
  private async withTimeout<T>(operation: Promise<T>): Promise<T> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([operation, new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => { this.close(); reject(new Error("Transport timeout")); }, this.timeoutMs);
      })]);
    } finally { if (timer) clearTimeout(timer); }
  }
  close(): void { this.stream.destroy(); }
}

export class UnixSocketTransport extends FramedStreamTransport {
  private constructor(socket: net.Socket, timeoutMs: number) { super(socket, timeoutMs); }
  static connect(path: string, timeoutMs = 5000): Promise<UnixSocketTransport> {
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) return Promise.reject(new RangeError("Invalid transport timeout"));
    return new Promise((resolve, reject) => {
      const socket = net.createConnection({ path });
      socket.setTimeout(timeoutMs, () => socket.destroy(new Error("Socket timeout")));
      const error = (err: Error) => { socket.destroy(); reject(err); };
      socket.once("error", error);
      socket.once("connect", () => { socket.off("error", error); resolve(new UnixSocketTransport(socket, timeoutMs)); });
    });
  }
}

/** Debug-only ADB adapter. The on-device bridge must already be installed and local control enabled. */
export class AdbTransport extends FramedStreamTransport {
  private readonly child: ChildProcessWithoutNullStreams;
  private diagnostic = "";
  private readonly stderrDone: Promise<void>;
  private constructor(child: ChildProcessWithoutNullStreams, timeoutMs: number) {
    super(Duplex.from({ readable: child.stdout, writable: child.stdin }), timeoutMs);
    this.child = child;
    // Bound and never expose raw stderr; it may contain device-specific data.
    child.stderr.on("data", (data: Buffer) => { this.diagnostic = (this.diagnostic + data.toString("utf8")).slice(0, 4096); });
    this.stderrDone = new Promise(resolve => {
      child.stderr.once("end", resolve);
      child.stderr.once("error", resolve);
      child.once("error", resolve);
    });
    child.on("error", () => this.stream.destroy(new Error("ADB process failed")));
    child.on("exit", () => {
      // Unblock writes if ADB exits before accepting the next frame.
      if (!child.stdin.destroyed) child.stdin.destroy(new Error("ADB process exited"));
    });
  }
  static connect(options: { serial?: string; adbPath?: string; socketPath?: string; bridgePath?: string; timeoutMs?: number } = {}): AdbTransport {
    const timeoutMs = options.timeoutMs ?? 5000;
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new RangeError("Invalid transport timeout");
    const quote = (value: string) => `'${value.replace(/'/g, `'\\''`)}'`;
    const socket = options.socketPath ?? "/data/data/com.termux/files/control-ipc/control.sock";
    const bridge = options.bridgePath ?? "/data/data/com.termux/files/termux-control-adb";
    const remote = ["run-as", "com.termux", bridge, "--socket", socket, "bridge"].map(quote).join(" ");
    const args = [...(options.serial ? ["-s", options.serial] : []), "shell", "-T", remote];
    return new AdbTransport(spawn(options.adbPath ?? "adb", args, { stdio: ["pipe", "pipe", "pipe"] }), timeoutMs);
  }
  override async readFrame(): Promise<Uint8Array> {
    try { return await super.readFrame(); }
    catch (error) { throw await this.bridgeError(error); }
  }
  override async writeFrame(bytes: Uint8Array): Promise<void> {
    let onExit: (() => void) | undefined;
    try {
      await Promise.race([super.writeFrame(bytes), new Promise<never>((_resolve, reject) => {
        onExit = () => reject(new Error("ADB process exited"));
        this.child.once("exit", onExit); this.child.once("error", onExit);
        if (this.child.exitCode !== null || this.child.signalCode !== null) onExit();
      })]);
    }
    catch (error) { throw await this.bridgeError(error); }
    finally { if (onExit) { this.child.off("exit", onExit); this.child.off("error", onExit); } }
  }
  private async bridgeError(error: unknown): Promise<Error> {
    // ADB can close stdout or stdin just before its stderr diagnostic is delivered.
    await Promise.race([this.stderrDone, new Promise<void>(resolve => setTimeout(resolve, 200))]);
    if (/not debuggable/i.test(this.diagnostic))
      return new Error("ADB run-as requires a debuggable Termux installation", { cause: error });
    return new Error("ADB bridge unavailable; check device, debug build, bridge and local control", { cause: error });
  }
  override close(): void { super.close(); if (!this.child.killed) this.child.kill(); }
}

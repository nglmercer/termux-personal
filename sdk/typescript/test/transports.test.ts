import test from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AdbTransport, TermuxClient, TransportError, UnixSocketTransport } from "../src/index.ts";

test("idle timeout is reported without an unhandled socket error", async () => {
  const dir = await mkdtemp(join(tmpdir(), "termux-idle-"));
  const path = join(dir, "socket");
  const server = net.createServer();
  await new Promise<void>(resolve => server.listen(path, resolve));
  const transport = await UnixSocketTransport.connect(path, 50);
  try {
    await new Promise(resolve => setTimeout(resolve, 150));
    await assert.rejects(transport.writeFrame(new Uint8Array([1])));
  } finally {
    transport.close(); await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
});

test("fragmented frame and close preserve the response", async () => {
  const dir = await mkdtemp(join(tmpdir(), "termux-frames-"));
  const path = join(dir, "socket");
  const server = net.createServer(socket => {
    for (const byte of [0, 0, 0, 3, 0, 255, 3]) socket.write(Buffer.from([byte]));
    socket.end();
  });
  await new Promise<void>(resolve => server.listen(path, resolve));
  const transport = await UnixSocketTransport.connect(path);
  try { assert.deepEqual(await transport.readFrame(), Buffer.from([0, 255, 3])); }
  finally {
    transport.close(); await new Promise<void>(resolve => server.close(() => resolve()));
    await rm(dir, { recursive: true, force: true });
  }
});

test("ADB reports a non-debuggable installation without exposing stderr", async () => {
  const dir = await mkdtemp(join(tmpdir(), "termux-adb-error-"));
  const adb = join(dir, "adb");
  await writeFile(adb, "#!/bin/sh\necho 'run-as: package not debuggable: private detail' >&2\nexit 1\n");
  await chmod(adb, 0o700);
  try {
    const start = Date.now();
    await assert.rejects(TermuxClient.fromTransport(AdbTransport.connect({ adbPath: adb })), (error: unknown) =>
      error instanceof TransportError && error.message === "ADB run-as requires a debuggable Termux installation" && !error.outcomeUnknown);
    assert.ok(Date.now() - start < 2000);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test("missing ADB executable fails without an unhandled process error", async () => {
  const transport = AdbTransport.connect({ adbPath: "/nonexistent/termux-test-adb", timeoutMs: 1000 });
  await assert.rejects(TermuxClient.fromTransport(transport), (error: unknown) => error instanceof TransportError && !error.outcomeUnknown);
});

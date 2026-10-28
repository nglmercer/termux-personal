import test from "node:test";
import assert from "node:assert/strict";
import { TermuxClient, TransportError, UnavailableError, ProtocolError, type ByteTransport } from "../src/index.ts";

class MockTransport implements ByteTransport {
  readonly requests: Array<Record<string, unknown>> = [];
  readonly responses: Uint8Array[] = [];
  binary?: Uint8Array;
  awaitingBinary = false;
  closed = false;
  private readonly handle: (request: Record<string, unknown>, transport: MockTransport) => void;
  constructor(handle: (request: Record<string, unknown>, transport: MockTransport) => void) { this.handle = handle; }
  async writeFrame(bytes: Uint8Array): Promise<void> {
    if (this.awaitingBinary) { this.binary = bytes.slice(); this.awaitingBinary = false; return; }
    const request = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
    this.requests.push(request);
    if (request.method === "system.hello") this.reply(request, { version: 1 });
    else this.handle(request, this);
  }
  reply(request: Record<string, unknown>, result: unknown): void {
    this.responses.push(new TextEncoder().encode(JSON.stringify({ jsonrpc: "2.0", id: request.id, result })));
  }
  error(request: Record<string, unknown>, code: number, message: string): void {
    this.responses.push(new TextEncoder().encode(JSON.stringify({ jsonrpc: "2.0", id: request.id, error: { code, message } })));
  }
  async readFrame(): Promise<Uint8Array> {
    const frame = this.responses.shift();
    if (!frame) throw new Error("connection closed");
    return frame;
  }
  close(): void { this.closed = true; }
}

test("optional describe falls back to capabilities", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "system.describe") transport.error(request, -32601, "Method not found");
    else transport.reply(request, { methods: ["session.list"] });
  });
  const client = await TermuxClient.fromTransport(peer);
  assert.deepEqual(await client.describe(), { protocolVersion: 1, methods: [{ name: "session.list" }], legacy: true });
  client.close();
});

test("binary request and response use separate frames", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "session.write") { transport.awaitingBinary = true; transport.reply(request, { ok: true }); }
    else { transport.reply(request, { length: 3 }); transport.responses.push(new Uint8Array([0, 255, 3])); }
  });
  const client = await TermuxClient.fromTransport(peer);
  await client.request("session.write", { id: "s1", length: 3 }, { binary: new Uint8Array([0, 255, 3]) });
  assert.deepEqual(peer.binary, new Uint8Array([0, 255, 3]));
  assert.deepEqual((await client.request("session.output.read", { id: "s1", offset: 0, limit: 3 })).binary, new Uint8Array([0, 255, 3]));
  client.close();
});

test("lost mutation response has unknown outcome and no retry", async () => {
  const peer = new MockTransport(() => {});
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.request("session.rename", { id: "s1", name: "x" }), (error: unknown) => error instanceof TransportError && error.outcomeUnknown);
  assert.deepEqual(peer.requests.map(r => r.method), ["system.hello", "session.rename"]);
  assert.equal(peer.closed, true);
});

test("mismatched binary response closes connection", async () => {
  const peer = new MockTransport((request, transport) => { transport.reply(request, { length: 3 }); transport.responses.push(new Uint8Array([1, 2])); });
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.request("session.output.read", { id: "s1", offset: 0, limit: 3 }), (error: unknown) => error instanceof TransportError && !error.outcomeUnknown);
  assert.equal(peer.closed, true);
});

test("pre-aborted request sends nothing and preserves connection", async () => {
  const peer = new MockTransport((request, transport) => transport.reply(request, { sessions: [] }));
  const client = await TermuxClient.fromTransport(peer);
  const abort = new AbortController(); abort.abort();
  await assert.rejects(client.request("session.rename", { id: "s1", name: "x" }, { signal: abort.signal }), (error: unknown) => error instanceof TransportError && !error.outcomeUnknown);
  assert.deepEqual((await client.request("session.list")).result, { sessions: [] });
  assert.equal(peer.requests.length, 2);
  client.close();
});

test("aborting an in-flight mutation reports an unknown outcome", async () => {
  class BlockingTransport extends MockTransport {
    private rejectPending?: (reason: Error) => void;
    override async readFrame(): Promise<Uint8Array> {
      if (this.responses.length) return super.readFrame();
      return new Promise((_resolve, reject) => { this.rejectPending = reject; });
    }
    override close(): void { super.close(); this.rejectPending?.(new Error("closed")); }
  }
  const peer = new BlockingTransport(() => {});
  const client = await TermuxClient.fromTransport(peer);
  const abort = new AbortController();
  const pending = client.request("session.rename", { id: "s1", name: "x" }, { signal: abort.signal });
  await new Promise(resolve => setImmediate(resolve));
  abort.abort();
  await assert.rejects(pending, (error: unknown) => error instanceof TransportError && error.outcomeUnknown);
  assert.equal(peer.closed, true);
});

test("session helpers discover capabilities and return typed metadata and gaps", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "system.capabilities") transport.reply(request, { methods: ["session.list", "session.write", "session.output.read"] });
    else if (request.method === "session.list") transport.reply(request, { sessions: [{ id: "s1", name: null, state: "running", pid: 42 }] });
    else if (request.method === "session.write") { transport.awaitingBinary = true; transport.reply(request, { ok: true }); }
    else if (request.method === "session.output.read") { transport.reply(request, { offset: 2, nextOffset: 4, endOffset: 4, gap: true, length: 2 }); transport.responses.push(new Uint8Array([3, 4])); }
    else throw new Error("Unexpected method");
  });
  const client = await TermuxClient.fromTransport(peer);
  assert.deepEqual(await client.sessions.list(), [{ id: "s1", name: null, state: "running", pid: 42 }]);
  await client.sessions.write("s1", new Uint8Array([0, 255]));
  assert.deepEqual(peer.binary, new Uint8Array([0, 255]));
  assert.deepEqual(await client.sessions.readOutput("s1", 0, 2), { offset: 2, nextOffset: 4, endOffset: 4, gap: true, data: new Uint8Array([3, 4]) });
  await assert.rejects(client.sessions.close("s1"), UnavailableError);
  assert.equal(peer.requests.some(r => r.method === "session.close"), false);
  client.close();
});

test("session output helper rejects inconsistent offsets", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "system.capabilities") transport.reply(request, { methods: ["session.output.read"] });
    else { transport.reply(request, { offset: 2, nextOffset: 9, endOffset: 9, gap: true, length: 1 }); transport.responses.push(new Uint8Array([1])); }
  });
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.sessions.readOutput("s1", 0, 1), ProtocolError);
  client.close();
});

test("malformed mutation acknowledgement reports an unknown outcome", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "system.capabilities") transport.reply(request, { methods: ["session.rename"] });
    else transport.reply(request, { unexpected: true });
  });
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.sessions.rename("s1", "x"), (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown);
  client.close();
});

test("ordinary subscription omits the optional resumable parameter", async () => {
  const peer = new MockTransport((request, transport) => {
    if (request.method === "system.capabilities") transport.reply(request, { methods: ["session.output.subscribe"] });
    else { assert.deepEqual(request.params, { id: "s1" }); transport.reply(request, { ok: true }); }
  });
  const client = await TermuxClient.fromTransport(peer);
  assert.equal(await client.sessions.subscribe("s1"), null);
  await assert.rejects(client.sessions.subscribe("s1", { resumable: true }), UnavailableError);
  client.close();
});

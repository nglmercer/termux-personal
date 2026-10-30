import test from "node:test";
import assert from "node:assert/strict";
import { TermuxClient, TransportError, UnavailableError, ProtocolError, JobWaitTimeoutError, type ByteTransport } from "../src/index.ts";

const ALL = ["system.hello", "system.capabilities", "job.start", "job.list", "job.get", "job.output.read", "job.cancel"];
const JOB = {
  id: "job_1", state: "exited", startedAt: 1, endedAt: 2, exitCode: 0, failureCode: null, pid: 42, workspaceId: null,
  output: { stdoutEndOffset: 3, stderrEndOffset: 0 },
};

class MockTransport implements ByteTransport {
  readonly requests: Array<Record<string, unknown>> = [];
  readonly responses: Uint8Array[] = [];
  readonly methods: string[];
  private readonly handle: (request: Record<string, unknown>, transport: MockTransport) => void;
  constructor(methods: string[], handle: (request: Record<string, unknown>, transport: MockTransport) => void = () => {}) {
    this.methods = methods; this.handle = handle;
  }
  async writeFrame(bytes: Uint8Array): Promise<void> {
    const request = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
    this.requests.push(request);
    if (request.method === "system.hello") this.reply(request, { version: 1 });
    else if (request.method === "system.capabilities") this.reply(request, { methods: this.methods });
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
  close(): void { }
}

const sent = (peer: MockTransport) => peer.requests.map(request => request.method);

test("start sends only the documented fields and returns the admitted record", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { job: { ...JOB, state: "queued", exitCode: null, endedAt: null, startedAt: null } }));
  const client = await TermuxClient.fromTransport(peer);
  const record = await client.jobs.start({ executable: "/bin/echo", args: ["hi"], clientRequestId: "req-1" });
  assert.equal(record.state, "queued");
  const start = peer.requests.find(request => request.method === "job.start");
  assert.deepEqual(start?.params, { executable: "/bin/echo", args: ["hi"], clientRequestId: "req-1" });
  client.close();
});

test("a start response that cannot be parsed reports unknown outcome and is not retried", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { job: { id: 7 } }));
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.jobs.start({ executable: "/bin/echo", args: [] }),
    (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown);
  assert.equal(sent(peer).filter(method => method === "job.start").length, 1);
  client.close();
});

test("a job method missing from capabilities is reported without sending it", async () => {
  const peer = new MockTransport(["system.hello", "system.capabilities"]);
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.jobs.start({ executable: "/bin/echo", args: [] }), UnavailableError);
  assert.equal(sent(peer).includes("job.start"), false);
  client.close();
});

test("readOutput returns the binary frame and rejects inconsistent offsets", async () => {
  const peer = new MockTransport(ALL, (request, transport) => {
    if (request.method !== "job.output.read") return transport.error(request, -32601, "Method not found");
    transport.reply(request, { offset: 0, nextOffset: 3, endOffset: 3, gap: false, length: 3, finished: true, complete: true });
    transport.responses.push(new Uint8Array([104, 105, 10]));
  });
  const client = await TermuxClient.fromTransport(peer);
  const chunk = await client.jobs.readOutput("job_1", "stdout", 0, 16);
  assert.deepEqual(chunk.data, new Uint8Array([104, 105, 10]));
  assert.equal(chunk.finished, true);

  const bad = new MockTransport(ALL, (request, transport) => {
    transport.reply(request, { offset: 0, nextOffset: 5, endOffset: 9, gap: false, length: 3, finished: true, complete: true });
    transport.responses.push(new Uint8Array([1, 2, 3]));
  });
  const other = await TermuxClient.fromTransport(bad);
  await assert.rejects(other.jobs.readOutput("job_1", "stdout", 0, 16), ProtocolError);
  client.close(); other.close();
});

test("wait polls until a terminal state and times out on a running job", async () => {
  let polls = 0;
  const peer = new MockTransport(ALL, (request, transport) => {
    if (request.method !== "job.get") return transport.error(request, -32601, "Method not found");
    polls++;
    transport.reply(request, { job: { ...JOB, state: polls < 3 ? "running" : "exited" } });
  });
  const client = await TermuxClient.fromTransport(peer);
  assert.equal((await client.jobs.wait("job_1", { pollMs: 1 })).state, "exited");

  const stuck = new MockTransport(ALL, (request, transport) => transport.reply(request, { job: { ...JOB, state: "running" } }));
  const other = await TermuxClient.fromTransport(stuck);
  await assert.rejects(other.jobs.wait("job_1", { timeoutMs: 5, pollMs: 1 }), JobWaitTimeoutError);
  client.close(); other.close();
});

test("follow yields output in order and reports a gap explicitly", async () => {
  const replies = [
    { offset: 0, nextOffset: 2, endOffset: 9, gap: false, length: 2, finished: false, complete: false, bytes: [97, 98] },
    { offset: 5, nextOffset: 7, endOffset: 7, gap: true, length: 2, finished: true, complete: true, bytes: [99, 100] },
  ];
  let index = 0;
  const peer = new MockTransport(ALL, (request, transport) => {
    const next = replies[index++];
    const { bytes, ...metadata } = next;
    transport.reply(request, metadata);
    transport.responses.push(new Uint8Array(bytes));
  });
  const client = await TermuxClient.fromTransport(peer);
  const seen: Array<{ gap: boolean; data: number[] }> = [];
  for await (const chunk of client.jobs.follow("job_1", "stdout", { pollMs: 1 })) seen.push({ gap: chunk.gap, data: [...chunk.data] });
  assert.deepEqual(seen, [{ gap: false, data: [97, 98] }, { gap: true, data: [99, 100] }]);
  client.close();
});

test("cancel acknowledges only an explicit accepted result", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { accepted: true }));
  const client = await TermuxClient.fromTransport(peer);
  await client.jobs.cancel("job_1");
  const bad = new MockTransport(ALL, (request, transport) => transport.reply(request, { accepted: false }));
  const other = await TermuxClient.fromTransport(bad);
  await assert.rejects(other.jobs.cancel("job_1"), (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown);
  client.close(); other.close();
});

test("a lost job.start response has an unknown outcome at the transport layer", async () => {
  const peer = new MockTransport(ALL, () => {});
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.jobs.start({ executable: "/bin/echo", args: [] }),
    (error: unknown) => error instanceof TransportError && error.outcomeUnknown);
  assert.equal(sent(peer).filter(method => method === "job.start").length, 1);
  client.close();
});

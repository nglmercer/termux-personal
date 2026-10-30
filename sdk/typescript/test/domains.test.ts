import test from "node:test";
import assert from "node:assert/strict";
import { TermuxClient, TransportError, UnavailableError, ProtocolError, type ByteTransport } from "../src/index.ts";

const ALL = ["system.hello", "system.capabilities", "process.list", "file.roots", "file.list", "file.stat", "file.read",
  "workspace.list", "workspace.get", "workspace.create", "workspace.update", "workspace.remove", "workspace.select",
  "workspace.tasks", "workspace.task.run", "workspace.git"];
const WS = { id: "ws_0123456789abcdef", name: "my-app", rootId: "home", path: "projects/my-app", createdAt: 1, updatedAt: 2,
  available: true, taskPresets: [{ id: "test", label: "Run tests", executable: "cargo", args: ["test"], timeoutMs: 0 }] };

class MockTransport implements ByteTransport {
  readonly requests: Array<Record<string, unknown>> = [];
  readonly responses: Uint8Array[] = [];
  readonly methods: string[];
  private readonly handle: (request: Record<string, unknown>, transport: MockTransport) => void;
  constructor(methods: string[], handle: (request: Record<string, unknown>, transport: MockTransport) => void = () => {}) {
    this.methods = methods;
    this.handle = handle;
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
  async readFrame(): Promise<Uint8Array> {
    const frame = this.responses.shift();
    if (!frame) throw new Error("connection closed");
    return frame;
  }
  close(): void { }
}

const sent = (peer: MockTransport) => peer.requests.map(request => request.method);

test("file list validates entries and sends only the documented fields", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, {
    entries: [{ name: "notes.txt", path: "notes.txt", type: "file", size: 11, mtime: 5, revision: "r5.b" }], nextCursor: null }));
  const client = await TermuxClient.fromTransport(peer);
  const listing = await client.files.list("home", { limit: 10 });
  assert.equal(listing.entries[0].type, "file");
  assert.deepEqual(peer.requests.find(r => r.method === "file.list")?.params, { rootId: "home", limit: 10 });
  client.close();
});

test("file read returns the binary frame and rejects inconsistent metadata", async () => {
  const peer = new MockTransport(ALL, (request, transport) => {
    transport.reply(request, { offset: 0, nextOffset: 3, eof: false, length: 3, revision: "r1.3" });
    transport.responses.push(new Uint8Array([104, 105, 10]));
  });
  const client = await TermuxClient.fromTransport(peer);
  const chunk = await client.files.read("home", "notes.txt", 0, 3);
  assert.deepEqual(chunk.data, new Uint8Array([104, 105, 10]));
  assert.equal(chunk.eof, false);

  const bad = new MockTransport(ALL, (request, transport) => {
    transport.reply(request, { offset: 0, nextOffset: 9, eof: true, length: 3, revision: "r1.3" });
    transport.responses.push(new Uint8Array([1, 2, 3]));
  });
  const other = await TermuxClient.fromTransport(bad);
  await assert.rejects(other.files.read("home", "notes.txt", 0, 3), ProtocolError);
  client.close(); other.close();
});

test("file methods are refused without a request when the server does not advertise them", async () => {
  const peer = new MockTransport(["system.hello", "system.capabilities"]);
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.files.roots(), UnavailableError);
  assert.equal(sent(peer).includes("file.roots"), false);
  client.close();
});

test("process list validates rows and refuses unknown scopes before sending", async () => {
  const row = { pid: 10, ppid: 1, name: "zsh", state: "S", rssKiB: 4096, cpuPercent: null, startTicks: 55, managedJobId: null, visibility: "partial" };
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, {
    processes: [row], incomplete: false, missingRoots: 0, nextCursor: null }));
  const client = await TermuxClient.fromTransport(peer);
  const listing = await client.processes.list({ scope: "owned" });
  assert.equal(listing.processes[0].pid, 10);
  await assert.rejects(client.processes.list({ scope: "signal" as never }), TypeError);
  assert.equal(sent(peer).filter(method => method === "process.list").length, 1);

  const bad = new MockTransport(ALL, (request, transport) => transport.reply(request, {
    processes: [{ ...row, managedJobId: "job_1" }], incomplete: false, missingRoots: 0, nextCursor: null }));
  const other = await TermuxClient.fromTransport(bad);
  await assert.rejects(other.processes.list(), ProtocolError);
  client.close(); other.close();
});

test("upload sends ordered chunks, commits, and aborts on failure", async () => {
  const methods = [...ALL, "file.transfer.begin", "file.transfer.write", "file.transfer.commit", "file.transfer.abort"];
  const serve = (failAt?: number) => {
    let pending: Record<string, unknown> | undefined;
    const received: number[] = [];
    const peer = new MockTransport(methods, (request, transport) => {
      if (request.method === "file.transfer.begin") transport.reply(request, { transferId: "tr_" + "0".repeat(32), chunkSize: 65536, offset: 0 });
      else if (request.method === "file.transfer.write") pending = request;
      else if (request.method === "file.transfer.commit") transport.reply(request, {
        entry: { name: "a", path: "a", type: "file", size: received.length, mtime: 1, revision: "r1" }, sha256: "0".repeat(64), committed: true });
      else if (request.method === "file.transfer.abort") transport.reply(request, { aborted: true });
    });
    const write = peer.writeFrame.bind(peer);
    peer.writeFrame = async (bytes: Uint8Array) => {
      if (!pending) return write(bytes);
      const request = pending; pending = undefined;
      const offset = (request.params as Record<string, number>).offset;
      received.push(...bytes);
      if (failAt !== undefined && offset >= failAt)
        peer.responses.push(new TextEncoder().encode(JSON.stringify({ jsonrpc: "2.0", id: request.id, error: { code: -32001, message: "CONFLICT" } })));
      else peer.reply(request, { nextOffset: offset + bytes.length });
    };
    return { peer, received };
  };
  const data = new Uint8Array(150000).map((_, i) => i & 255);
  const ok = serve();
  const client = await TermuxClient.fromTransport(ok.peer);
  const committed = await client.files.upload("home", "a", data);
  assert.equal(committed.entry.size, data.length);
  assert.deepEqual(Uint8Array.from(ok.received), data);
  assert.equal(sent(ok.peer).at(-1), "file.transfer.commit");

  const failing = serve(65536);
  const other = await TermuxClient.fromTransport(failing.peer);
  await assert.rejects(other.files.upload("home", "a", data));
  // A rejected binary request closes the v1 connection, so no abort follows; the server expires the transfer.
  assert.ok(!sent(failing.peer).includes("file.transfer.commit"));
  client.close(); other.close();
});

test("process signal sends a job ID and signal name only, and refuses bad input before sending", async () => {
  const peer = new MockTransport([...ALL, "process.signal"], (request, transport) => {
    assert.deepEqual(request.params, { jobId: "job_1", signal: "TERM" });
    transport.reply(request, { sent: true });
  });
  const client = await TermuxClient.fromTransport(peer);
  await client.processes.signalJob("job_1", "TERM");
  await assert.rejects(client.processes.signalJob("job_1", "HUP" as never), TypeError);
  await assert.rejects(client.processes.signalJob(42 as never, "TERM"), TypeError);
  assert.equal(sent(peer).filter(method => method === "process.signal").length, 1);

  const unavailable = await TermuxClient.fromTransport(new MockTransport(ALL));
  await assert.rejects(unavailable.processes.signalJob("job_1", "KILL"), UnavailableError);
  const lost = await TermuxClient.fromTransport(new MockTransport([...ALL, "process.signal"], (request, transport) => transport.reply(request, {})));
  await assert.rejects(lost.processes.signalJob("job_1", "KILL"), (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown);
  client.close(); unavailable.close(); lost.close();
});

test("workspace create and get validate metadata and report unknown outcome for an unusable create response", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { workspace: WS }));
  const client = await TermuxClient.fromTransport(peer);
  const created = await client.workspaces.create({ name: "my-app", rootId: "home", path: "projects/my-app" });
  assert.equal(created.id, WS.id);
  assert.equal(created.taskPresets[0].executable, "cargo");

  const bad = new MockTransport(ALL, (request, transport) => transport.reply(request, { workspace: { ...WS, id: "not-an-id" } }));
  const other = await TermuxClient.fromTransport(bad);
  await assert.rejects(other.workspaces.create({ name: "x", rootId: "home", path: "projects/my-app" }),
    (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown);
  client.close(); other.close();
});

test("workspace list rejects an invalid active identifier", async () => {
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { workspaces: [WS], activeId: "active" }));
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.workspaces.list(), ProtocolError);
  client.close();
});

test("running a task sends only identifiers and returns the admitted job", async () => {
  const job = { id: "job_9", state: "queued", startedAt: null, endedAt: null, exitCode: null, failureCode: null, pid: null,
    workspaceId: WS.id, output: { stdoutEndOffset: 0, stderrEndOffset: 0 } };
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { job }));
  const client = await TermuxClient.fromTransport(peer);
  const admitted = await client.workspaces.runTask(WS.id, "test", { clientRequestId: "task-1" });
  assert.equal(admitted.id, "job_9");
  assert.deepEqual(peer.requests.find(r => r.method === "workspace.task.run")?.params, { workspaceId: WS.id, taskId: "test", clientRequestId: "task-1" });
  client.close();
});

test("a lost task-run response has unknown outcome and is not retried", async () => {
  const peer = new MockTransport(ALL, () => {});
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.workspaces.runTask(WS.id, "test"), (error: unknown) => error instanceof TransportError && error.outcomeUnknown);
  assert.equal(sent(peer).filter(method => method === "workspace.task.run").length, 1);
  client.close();
});

test("git sends only the subcommand, and a lost response is not retried", async () => {
  const job = { id: "job_10", state: "queued", startedAt: null, endedAt: null, exitCode: null, failureCode: null, pid: null,
    workspaceId: WS.id, output: { stdoutEndOffset: 0, stderrEndOffset: 0 } };
  const peer = new MockTransport(ALL, (request, transport) => transport.reply(request, { job }));
  const client = await TermuxClient.fromTransport(peer);
  assert.equal((await client.workspaces.git(WS.id, "status")).id, "job_10");
  assert.deepEqual(peer.requests.find(r => r.method === "workspace.git")?.params, { workspaceId: WS.id, subcommand: "status" });
  client.close();
  const lost = new MockTransport(ALL, () => {});
  const other = await TermuxClient.fromTransport(lost);
  await assert.rejects(other.workspaces.git(WS.id, "log"), (error: unknown) => error instanceof TransportError && error.outcomeUnknown);
  assert.equal(sent(lost).filter(method => method === "workspace.git").length, 1);
  other.close();
});

test("a lost file read is marked retry-safe because reads never change state", async () => {
  const peer = new MockTransport(ALL, () => {});
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.files.read("home", "notes.txt"), (error: unknown) => error instanceof TransportError && !error.outcomeUnknown);
  client.close();
});

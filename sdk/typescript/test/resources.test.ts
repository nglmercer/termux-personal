import test from "node:test";
import assert from "node:assert/strict";
import { TermuxClient, ProtocolError, UnavailableError, type ByteTransport } from "../src/index.ts";

const WS_ID = "ws_0123456789abcdef";
const METHODS = ["system.hello", "system.capabilities", "resource.capabilities", "resource.snapshot", "job.resource.get", "job.history.list", "job.history.get",
  "job.diagnostics.export", "job.processes", "job.start", "workspace.get", "workspace.update", "workspace.service.list", "workspace.service.status",
  "workspace.service.start", "workspace.service.stop", "workspace.service.restart", "workspace.service.logs", "workspace.service.health",
  "workspace.service.ports", "workspace.service.proxy", "workspace.backup.export", "workspace.backup.import", "workspace.backup.status", "workspace.backup.cancel", "job.preview", "workspace.task.preview"];
const SERVICE = { workspaceId: WS_ID, serviceId: "dev-server", state: "running", restartPolicy: "never", jobId: "job_1", restartsInWindow: 0,
  nextStartInMillis: null, lastReason: null, lastExitCode: null, ports: [3000] };
const JOB = { id: "job_1", state: "queued", startedAt: null, endedAt: null, exitCode: null, failureCode: null, pid: null, workspaceId: null,
  output: { stdoutEndOffset: 0, stderrEndOffset: 0 } };

class Mock implements ByteTransport {
  readonly requests: Array<Record<string, unknown>> = [];
  readonly responses: Uint8Array[] = [];
  private readonly handle: (request: Record<string, unknown>, t: Mock) => void;
  private readonly methods: string[];
  constructor(handle: (request: Record<string, unknown>, t: Mock) => void, methods: string[] = METHODS) { this.handle = handle; this.methods = methods; }
  async writeFrame(bytes: Uint8Array) {
    const request = JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
    this.requests.push(request);
    if (request.method === "system.hello") this.reply(request, { version: 1 });
    else if (request.method === "system.capabilities") this.reply(request, { methods: this.methods });
    else this.handle(request, this);
  }
  reply(request: Record<string, unknown>, result: unknown) { this.responses.push(new TextEncoder().encode(JSON.stringify({ jsonrpc: "2.0", id: request.id, result }))); }
  async readFrame() { const f = this.responses.shift(); if (!f) throw new Error("connection closed"); return f; }
  close() {}
}
const replying = (result: unknown) => (request: Record<string, unknown>, t: Mock) => t.reply(request, result);
const methodsSent = (m: Mock) => m.requests.map(r => r.method);

test("capabilities and snapshot are validated, unknown values stay null", async () => {
  const caps = { resourceLimits: Object.fromEntries(["managedJobs", "memorySoftMonitoring", "rlimitAs", "rlimitCpu", "cgroups", "safeProcessStop", "virtualMachine"].map(k => [k, false])),
    profiles: ["balanced"], pressureActions: ["warn"], items: [] };
  assert.deepEqual((await (await TermuxClient.fromTransport(new Mock(replying(caps)))).resources.capabilities()).profiles, ["balanced"]);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ ...caps, profiles: ["turbo"] })))).resources.capabilities(), ProtocolError);
  const snap = { device: { totalMemoryBytes: null, availableMemoryBytes: null, lowMemory: null, thermalStatus: null, cpuCount: 8 }, pressure: "unknown", admission: { slots: 1, active: 0, queued: 0 } };
  assert.equal((await (await TermuxClient.fromTransport(new Mock(replying(snap)))).resources.snapshot()).device.totalMemoryBytes, null);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ ...snap, pressure: "panic" })))).resources.snapshot(), ProtocolError);
});

test("history limits are checked locally and diagnostics only request output when told to", async () => {
  const peer = new Mock((request, t) => t.reply(request, request.method === "job.history.list" ? { history: [], available: true } :
    { report: "r", files: [], outputIncluded: false, found: true }));
  const client = await TermuxClient.fromTransport(peer);
  for (const bad of [0, 51, 1.5]) await assert.rejects(client.resources.history(bad), TypeError);
  assert.ok(!methodsSent(peer).includes("job.history.list"));
  await client.resources.exportDiagnostics("job_1");
  await client.resources.exportDiagnostics("job_1", { includeOutput: true });
  const sent = peer.requests.filter(r => r.method === "job.diagnostics.export").map(r => r.params);
  assert.deepEqual(sent, [{ id: "job_1" }, { id: "job_1", includeOutput: true }]);
});

test("job processes rejects relations the contract does not define", async () => {
  const row = { pid: 5, ppid: 1, name: "cargo", state: "S", rssKiB: null, startTicks: 1, relation: "orphan_possible" };
  const good = { jobId: "job_1", running: true, incomplete: false, processes: [row] };
  assert.equal((await (await TermuxClient.fromTransport(new Mock(replying(good)))).resources.jobProcesses("job_1")).processes[0].relation, "orphan_possible");
  const bad = { ...good, processes: [{ ...row, relation: "child" }] };
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying(bad)))).resources.jobProcesses("job_1"), ProtocolError);
});

test("service status is strict and identifiers are validated before anything is sent", async () => {
  const peer = new Mock(replying({ service: SERVICE }));
  const client = await TermuxClient.fromTransport(peer);
  assert.equal((await client.services.status(WS_ID, "dev-server")).state, "running");
  for (const patch of [{ state: "zombie" }, { ports: ["3000"] }, { restartsInWindow: -1 }, { restartPolicy: "sometimes" }])
    await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ service: { ...SERVICE, ...patch } })))).services.status(WS_ID, "dev-server"), ProtocolError);
  const before = peer.requests.length;
  await assert.rejects(client.services.start("nope", "dev"), TypeError);
  await assert.rejects(client.services.stop(WS_ID, "Bad Id"), TypeError);
  assert.equal(peer.requests.length, before);
});

test("a malformed start response is flagged outcome-unknown and never retried", async () => {
  const peer = new Mock(replying({ outcome: "exploded", service: SERVICE }));
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.services.start(WS_ID, "dev-server"), (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown === true);
  assert.equal(methodsSent(peer).filter(m => m === "workspace.service.start").length, 1);
});

test("service logs verify offsets against the binary frame", async () => {
  const peer = new Mock((request, t) => {
    t.reply(request, { offset: 0, nextOffset: 4, endOffset: 4, gap: false, length: 4, finished: false, complete: false });
    t.responses.push(new Uint8Array([104, 105, 10, 0]));
  });
  const client = await TermuxClient.fromTransport(peer);
  const chunk = await client.services.logs(WS_ID, "dev-server", "stdout", 0, 16);
  assert.deepEqual([...chunk.data], [104, 105, 10, 0]);
  await assert.rejects(client.services.logs(WS_ID, "dev-server", "stdin" as never, 0), TypeError);
});

test("proxies keep a loopback target in the contract and ask for LAN confirmation explicitly", async () => {
  const proxy = { id: "px_1", listenAddress: "127.0.0.1", listenPort: 8080, targetAddress: "127.0.0.1", targetPort: 3000, lan: false };
  const peer = new Mock(replying({ proxy }));
  const client = await TermuxClient.fromTransport(peer);
  await client.services.openProxy(8080, 3000);
  await client.services.openProxy(8081, 3000, { listenAddress: "0.0.0.0", confirmLan: true });
  const sent = peer.requests.filter(r => r.method === "workspace.service.proxy").map(r => r.params);
  assert.deepEqual(sent[0], { action: "open", listenPort: 8080, targetPort: 3000 });
  assert.equal((sent[1] as Record<string, unknown>).confirmLan, true);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ proxy: { ...proxy, targetAddress: "10.0.0.5" } })))).services.openProxy(8080, 3000), ProtocolError);
});

test("destructive restore options need confirmation before anything is sent, and responses are validated", async () => {
  const peer = new Mock(replying({}));
  const client = await TermuxClient.fromTransport(peer);
  await assert.rejects(client.backups.restore(WS_ID, "b.zip", { overwrite: true }), TypeError);
  await assert.rejects(client.backups.restore(WS_ID, "b.zip", { applyConfig: true }), TypeError);
  assert.ok(!methodsSent(peer).includes("workspace.backup.import"));
  await assert.rejects(client.backups.export(WS_ID, { format: "rar" as never }), TypeError);
  const backup = { id: `${WS_ID}-20250101-000000.zip`, format: "zip", files: 1, bytes: 1, archiveBytes: 1, truncated: false, skipped: { excluded: 0, sensitive: 0, links: 0, unsafe: 0 } };
  assert.equal((await (await TermuxClient.fromTransport(new Mock(replying({ backup })))).backups.export(WS_ID)).files, 1);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ backup: { ...backup, format: "rar" } })))).backups.export(WS_ID),
    (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown === true);
  const restore = { files: 1, bytes: 2, conflicts: 0, skippedUnsafe: 0, truncated: false, configApplied: true };
  const ok = new Mock(replying({ restore }));
  await (await TermuxClient.fromTransport(ok)).backups.restore(WS_ID, "b.zip", { paths: ["src"], overwrite: true, confirm: true, applyConfig: true });
  assert.deepEqual(ok.requests.find(r => r.method === "workspace.backup.import")?.params,
    { workspaceId: WS_ID, backupId: "b.zip", paths: ["src"], overwrite: true, confirm: true, applyConfig: true });
});

test("workspaces and jobs stay compatible: new members are optional and only sent when given", async () => {
  const WS = { id: WS_ID, name: "app", rootId: "home", path: "p", createdAt: 1, updatedAt: 2, available: true,
    taskPresets: [{ id: "build", label: "Build", executable: "cargo", args: ["build"], timeoutMs: 0 }] };
  const peer = new Mock((request, t) => t.reply(request, request.method === "job.start" ? { job: JOB } : { workspace: WS }));
  const client = await TermuxClient.fromTransport(peer);
  const plain = await client.workspaces.get(WS_ID);
  assert.equal(plain.resourceProfile, undefined); assert.equal(plain.services, undefined);
  await client.workspaces.update(WS_ID, { name: "x" });
  await client.workspaces.update(WS_ID, { resourceProfile: null });
  const updates = peer.requests.filter(r => r.method === "workspace.update").map(r => r.params as Record<string, unknown>);
  assert.ok(!("resourceProfile" in updates[0]));
  assert.ok("resourceProfile" in updates[1] && updates[1].resourceProfile === null);
  await client.jobs.start({ executable: "cargo", args: ["build"] });
  await client.jobs.start({ executable: "cargo", args: ["build"], resources: { profile: "balanced", applyBuildPreset: true } });
  const starts = peer.requests.filter(r => r.method === "job.start").map(r => r.params as Record<string, unknown>);
  assert.ok(!("resources" in starts[0]));
  assert.deepEqual(starts[1].resources, { profile: "balanced", applyBuildPreset: true });
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ workspace: { ...WS, resourceProfile: "turbo" } })))).workspaces.get(WS_ID), ProtocolError);
});

test("methods the server does not advertise raise UnavailableError", async () => {
  const client = await TermuxClient.fromTransport(new Mock(replying({}), ["system.hello", "system.capabilities"]));
  await assert.rejects(client.resources.snapshot(), UnavailableError);
  await assert.rejects(client.services.list(WS_ID), UnavailableError);
});

const OP = { id: "bk_1", kind: "export", workspaceId: WS_ID, state: "running", files: 3, bytes: 10, startedAt: 1, endedAt: null, result: null, error: null };

test("background backups return an operation and expose progress and cancellation", async () => {
  const sentParams: Array<Record<string, unknown>> = [];
  const peer = new Mock((request, t) => { sentParams.push(request.params as Record<string, unknown>); t.reply(request, { operation: OP }); });
  const client = await TermuxClient.fromTransport(peer);
  const op = await client.backups.export(WS_ID, { background: true });
  assert.equal(op.state, "running");
  assert.equal(sentParams[0].async, true);
  assert.equal((await client.backups.status("bk_1")).files, 3);
  assert.equal((await client.backups.cancel("bk_1")).id, "bk_1");
  await client.backups.restore(WS_ID, "b.zip", { background: true });
  assert.equal(sentParams[sentParams.length - 1].async, true);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ operation: { ...OP, state: "weird" } })))).backups.export(WS_ID, { background: true }),
    (error: unknown) => error instanceof ProtocolError && error.outcomeUnknown === true);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ operation: { ...OP, files: -1 } })))).backups.status("bk_1"), ProtocolError);
  const plain = new Mock(replying({ backup: { id: "x", format: "zip", files: 1, bytes: 1, archiveBytes: 1, truncated: false, skipped: { excluded: 0, sensitive: 0, links: 0, unsafe: 0 } } }));
  await (await TermuxClient.fromTransport(plain)).backups.export(WS_ID);
  assert.ok(!("async" in (plain.requests.find(r => r.method === "workspace.backup.export")?.params as Record<string, unknown>)));
});

test("previews validate the response and send the same parameters as a start", async () => {
  const preview = { executable: "cargo", arguments: ["build", "-j", "2"], presetApplied: true, changed: true, notes: ["cargo -j 2"], resources: { profile: "balanced" } };
  const peer = new Mock(replying(preview));
  const client = await TermuxClient.fromTransport(peer);
  const result = await client.jobs.preview({ executable: "cargo", args: ["build"], resources: { profile: "balanced", applyBuildPreset: true } });
  assert.deepEqual(result.arguments, ["build", "-j", "2"]);
  assert.deepEqual(peer.requests.find(r => r.method === "job.preview")?.params, { executable: "cargo", args: ["build"], resources: { profile: "balanced", applyBuildPreset: true } });
  assert.equal((await client.workspaces.previewTask(WS_ID, "build")).changed, true);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ ...preview, arguments: [1] })))).jobs.preview({ executable: "cargo", args: [] }), ProtocolError);
});

test("a reviewed preview fingerprint is sent only by start and the plan fields match", async () => {
  const fp = "a".repeat(64);
  const spec = { executable: "/usr/bin/cargo", args: ["test"], cwd: "/home/p", workspaceId: WS_ID, stdin: "x", timeoutMs: 5000, presentation: "background" as const,
    resources: { profile: "protected", applyBuildPreset: true, testConcurrency: 1 } };
  const preview = { executable: "/usr/bin/cargo", arguments: ["test", "-j", "1", "--", "--test-threads=1"], presetApplied: true, changed: true, notes: [],
    resources: { profile: "protected", processConcurrencyHint: 1 }, previewFingerprint: fp };
  const peer = new Mock(request => peer.reply(request, request.method === "job.preview" ? preview : { job: JOB }));
  const client = await TermuxClient.fromTransport(peer);
  const reviewed = await client.jobs.preview({ ...spec, expectedPreviewFingerprint: fp });
  await client.jobs.start({ ...spec, expectedPreviewFingerprint: reviewed.previewFingerprint });
  const sentPreview = peer.requests.find(r => r.method === "job.preview")?.params as Record<string, unknown>;
  const sentStart = peer.requests.find(r => r.method === "job.start")?.params as Record<string, unknown>;
  assert.ok(!("expectedPreviewFingerprint" in sentPreview));
  assert.equal(sentStart.expectedPreviewFingerprint, fp);
  const { expectedPreviewFingerprint: _ignored, ...rest } = sentStart;
  assert.deepEqual(rest, sentPreview);
  await assert.rejects(client.jobs.start({ ...spec, expectedPreviewFingerprint: "ABC" }), TypeError);
  await assert.rejects((await TermuxClient.fromTransport(new Mock(replying({ ...preview, previewFingerprint: 7 })))).jobs.preview(spec), ProtocolError);
});

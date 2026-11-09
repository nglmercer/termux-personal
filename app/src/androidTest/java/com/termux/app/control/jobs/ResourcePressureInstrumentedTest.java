package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
import com.termux.app.control.ipc.RpcDispatcher;
import com.termux.app.control.ipc.RpcRequestParser;
import com.termux.app.control.resource.AndroidResourceDevice;
import com.termux.app.control.resource.ResourceService;
import com.termux.app.control.workspace.WorkspacePortService;
import com.termux.app.control.workspace.WorkspaceRepository;
import com.termux.app.control.workspace.WorkspaceServiceController;
import com.termux.app.monitor.ProcessSnapshot;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Issue #11: admission, cancellation, pressure actions and the preview fingerprint on a real phone, with real child
 * processes from the production launcher and the real /proc, but with the <em>device reading injected</em> through the
 * supervisor's Sampler. Nothing here exhausts memory or heats the device; the injected readings are test-only and no
 * production endpoint can fake them. Real-sensor pressure is therefore not what these tests prove.
 *
 * <p>The Rust and Node cases run only when the toolchains are installed in the Termux prefix; otherwise they are skipped
 * (reported as assumption failures), which must be recorded as NOT_TESTED.
 */
@RunWith(AndroidJUnit4.class)
public class ResourcePressureInstrumentedTest {
    private static final Set<ControlCore.Scope> ALL = EnumSet.of(ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_EXECUTE, ControlCore.Scope.JOBS_OUTPUT,
        ControlCore.Scope.JOBS_CANCEL, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.PROCESSES_READ);
    private static final long GIB = 1L << 30;
    private static final String PREFIX_BIN = "/data/data/com.termux/files/usr/bin";
    private static final PressureTracker.Reading NORMAL = new PressureTracker.Reading(6 * GIB, 8 * GIB, false, 0);
    private static final PressureTracker.Reading CRITICAL_MEMORY = new PressureTracker.Reading(100L << 20, 8 * GIB, false, 0);
    /** PowerManager.THERMAL_STATUS_SEVERE: elevated pressure from temperature alone, with plenty of memory. */
    private static final PressureTracker.Reading SEVERE_THERMAL = new PressureTracker.Reading(6 * GIB, 8 * GIB, false, 3);

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] d) { }
        @Override public void rename(String id, String n) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private final AtomicReference<PressureTracker.Reading> reading = new AtomicReference<>(NORMAL);
    private final AtomicReference<File> procRoot = new AtomicReference<>(new File("/proc"));
    private final AtomicInteger deviceSamples = new AtomicInteger();
    private File base;
    private RpcDispatcher dispatcher;
    private final RpcRequestParser parser = new RpcRequestParser();
    private JobCoordinator coordinator;
    private JobResourceSupervisor supervisor;
    private WorkspaceServiceController services;
    private WorkspacePortService ports;
    private int ids = 10;

    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        base = new File(context.getCacheDir(), "pressure-device-" + System.nanoTime());
        File home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        Map<String, File> roots = new HashMap<>(); roots.put("home", home);
        WorkspaceRepository repository = onWorker(() -> new WorkspaceRepository(new File(base, "workspaces.json"), new FileService(roots)));
        JobHistoryStore history = new JobHistoryStore(new File(base, "history")); onWorker(history::open);
        // A short cooldown keeps the recovery case quick; every other admission rule is the production default.
        ResourceAdmissionController admission = new ResourceAdmissionController(new ResourceAdmissionController.Config(8, 10_000, 1_000, 768L << 20, 256L << 20),
            JobIoPool.MAX_JOBS, android.os.SystemClock::elapsedRealtime);
        final int uid = android.os.Process.myUid();
        final long ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
        final long page = android.system.Os.sysconf(android.system.OsConstants._SC_PAGE_SIZE);
        supervisor = new JobResourceSupervisor(new JobResourceSupervisor.Sampler() {
            @Override public PressureTracker.Reading device() { deviceSamples.incrementAndGet(); return reading.get(); }
            @Override public ProcessSnapshot processes(Set<Integer> r) { return ProcessSnapshot.collect(procRoot.get(), uid, r); }
            @Override public long monotonicMillis() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public long ticksPerSecond() { return ticks; }
            @Override public long pageSizeBytes() { return page; }
        }, admission);
        JobAuthority authority = new JobAuthority();
        authority.grantScopes(ALL);
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) {
                return new JobCoordinator.Resolution(spec.executable(), spec.workingDirectory() != null ? spec.workingDirectory() : "/");
            }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        coordinator = new JobCoordinator(new JobRegistry(new JobIoPool(JobIoPool.MAX_JOBS), new JobOutputStore(), JobOutputStore.DEFAULT_RECORD_LIMIT),
            new AppShellJobAdapter(context), policy, authority, admission, supervisor, new JobDiagnostics(history, supervisor, android.os.Build.VERSION.SDK_INT));
        ResourceService resource = new ResourceService(coordinator, supervisor, admission, history, new AndroidResourceDevice(context));
        services = new WorkspaceServiceController(new WorkspaceServiceController.Definitions() {
            @Override public Resolved resolve(String ws, String svc) throws WorkspaceRepository.WorkspaceException {
                WorkspaceRepository.Workspace w = repository.get(ws); return new Resolved(repository.service(ws, svc), w.resourceProfile, w.available);
            }
            @Override public List<WorkspaceRepository.ServiceDefinition> list(String ws) throws WorkspaceRepository.WorkspaceException { return repository.get(ws).services; }
        }, WorkspaceServiceController.jobsOf(coordinator), id -> { JobHistoryStore.Record r = history.get(id); return r == null ? null : r.evidence; },
            WorkspaceServiceController.Config.defaults(), android.os.SystemClock::elapsedRealtime);
        ports = new WorkspacePortService();
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority, repository, resource, services, null, ports);
        dispatcher.dispatch(parser.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8)), Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() throws Exception {
        for (JsonElement job : ok(rpc("job.list", "{}")).getAsJsonArray("jobs")) {
            String state = job.getAsJsonObject().get("state").getAsString();
            if (state.equals("queued") || state.equals("running") || state.equals("starting")) rpc("job.cancel", "{\"id\":\"" + job.getAsJsonObject().get("id").getAsString() + "\"}");
        }
        services.close(); ports.close(); coordinator.stopAccepting(); supervisor.close(); delete(base);
    }

    // ---- admission and cancellation ----

    @Test public void protectedRunsAloneAQueuedCancelNeverLaunchesAndARunningCancelFreesTheSlot() throws Exception {
        String a = start("protected", null, "sleep 30");
        awaitState(a, "running");
        String b = start("balanced", null, "sleep 30");
        Thread.sleep(3000);
        assertEquals("a protected job runs alone, so the next one waits", "queued", state(b));
        ok(rpc("job.cancel", "{\"id\":\"" + b + "\"}"));
        awaitState(b, "cancelled");
        Thread.sleep(2000);
        JsonObject cancelled = job(b);
        assertEquals("cancelled", cancelled.get("state").getAsString());
        assertTrue("a job cancelled in the queue never had a process", cancelled.get("pid").isJsonNull());
        assertTrue(cancelled.get("startedAt").isJsonNull());

        ok(rpc("job.cancel", "{\"id\":\"" + a + "\"}"));
        awaitState(a, "cancelled");
        String c = start("protected", null, "sleep 1");
        assertTrue("the cancelled job released its slot", until(() -> "exited".equals(stateq(c)), 300));
        assertEquals(0, job(c).get("exitCode").getAsInt());
    }

    // ---- injected pressure ----

    @Test public void injectedCriticalMemoryPressureHoldsAdmissionAndItRecovers() throws Exception {
        String keeper = start("balanced", null, "sleep 60"); // keeps the supervisor sampling
        awaitState(keeper, "running");
        reading.set(CRITICAL_MEMORY);
        assertTrue("two critical samples escalate", until(() -> "critical".equals(pressure()), 150));
        String waiting = start("balanced", null, "sleep 1");
        Thread.sleep(3000);
        assertEquals("nothing is admitted under critical pressure", "queued", state(waiting));
        assertEquals("warn-only jobs are never stopped", "running", state(keeper));
        reading.set(NORMAL);
        assertTrue("three normal samples recover", until(() -> "normal".equals(pressure()), 200));
        assertTrue("admission resumes after the cooldown", until(() -> "exited".equals(stateq(waiting)), 300));
    }

    @Test public void onlyTheJobThatChoseStopJobIsStoppedUnderInjectedCriticalPressure() throws Exception {
        String warn = start("balanced", "warn", "sleep 60");
        String stop = start("balanced", "stop_job", "sleep 60");
        awaitState(warn, "running"); awaitState(stop, "running");
        reading.set(CRITICAL_MEMORY);
        assertTrue("stop_job ends its own job through the owned handle", until(() -> isTerminal(stateq(stop)), 300));
        assertEquals("a warn job keeps running", "running", state(warn));
        assertTrue(until(() -> rpcq("job.history.get", "{\"id\":\"" + stop + "\"}").has("result"), 100));
        assertEquals("RESOURCE_GUARD_STOP", ok(rpc("job.history.get", "{\"id\":\"" + stop + "\"}")).getAsJsonObject("record").getAsJsonObject("evidence").get("reason").getAsString());
        reading.set(NORMAL);
    }

    @Test public void pauseAdmissionBlocksOnlyNewAdmissionsUnderInjectedThermalPressure() throws Exception {
        String pausing = start("balanced", "pause_admission", "sleep 60");
        awaitState(pausing, "running");
        reading.set(SEVERE_THERMAL);
        assertTrue("severe thermal status alone is elevated pressure", until(() -> "elevated".equals(pressure()), 150));
        String next = start("balanced", null, "sleep 1");
        Thread.sleep(3000);
        assertEquals("a pause_admission job blocks the next admission while pressure lasts", "queued", state(next));
        assertEquals("pause_admission never stops its own job", "running", state(pausing));
        ok(rpc("job.cancel", "{\"id\":\"" + pausing + "\"}"));
        assertTrue("once it is gone the queued job runs, even while still elevated", until(() -> "exited".equals(stateq(next)), 300));
        reading.set(NORMAL);
    }

    // ---- observation ----

    @Test public void aMissingProcessTableIsReportedAsIncompleteWithNullMetrics() throws Exception {
        procRoot.set(new File(base, "no-proc"));
        String id = start("balanced", null, "sleep 10");
        awaitState(id, "running");
        assertTrue(until(() -> {
            JsonElement m = ok(rpcq("job.resource.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("resource").get("metrics");
            return m != null && m.isJsonObject() && m.getAsJsonObject().get("samples").getAsInt() > 0;
        }, 100));
        JsonObject metrics = ok(rpc("job.resource.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("resource").getAsJsonObject("metrics");
        assertTrue(metrics.get("incomplete").getAsBoolean());
        assertTrue("an unmeasured value stays null, never zero", metrics.get("rssBytes").isJsonNull());
        assertTrue(metrics.get("cpuPercent").isJsonNull());
    }

    @Test public void withoutSupervisedJobsTheSamplerStaysIdle() throws Exception {
        Thread.sleep(3000);
        assertEquals("nothing tracked, nothing sampled", 0, deviceSamples.get());
        String unmanaged = ok(rpc("job.start", "{\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\"sleep 2\"]}")).getAsJsonObject("job").get("id").getAsString();
        awaitState(unmanaged, "exited");
        assertEquals("a job without a policy is not supervised", 0, deviceSamples.get());
        String managed = start("balanced", null, "sleep 2");
        awaitState(managed, "exited");
        assertTrue(deviceSamples.get() > 0);
        Thread.sleep(3000); // let the last scheduled scan see that nothing is tracked
        int settled = deviceSamples.get();
        Thread.sleep(6000);
        assertEquals("sampling stops once no supervised job remains", settled, deviceSamples.get());
    }

    /**
     * Orientative cost of one supervisor sample on this phone: the real device reading (ActivityManager/PowerManager) and
     * the real /proc scan for a running job, timed in the test process. It is logged, not asserted.
     */
    @Test public void oneRealSampleCostIsMeasuredAndLogged() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id = start("balanced", null, "sleep 20");
        awaitState(id, "running");
        assertTrue(until(() -> ok(rpcq("job.processes", "{\"id\":\"" + id + "\"}")).getAsJsonArray("processes").size() > 0, 100));
        Set<Integer> roots = Collections.singleton(ok(rpc("job.processes", "{\"id\":\"" + id + "\"}")).getAsJsonArray("processes").get(0).getAsJsonObject().get("pid").getAsInt());
        int uid = android.os.Process.myUid();
        for (int i = 0; i < 5; i++) { com.termux.app.monitor.DeviceSnapshot.collect(context); ProcessSnapshot.collect(new File("/proc"), uid, roots); }
        int rounds = 50;
        long deviceNanos = 0, procNanos = 0, cpuBefore = android.os.Debug.threadCpuTimeNanos();
        for (int i = 0; i < rounds; i++) {
            long t0 = System.nanoTime(); com.termux.app.monitor.DeviceSnapshot.collect(context);
            long t1 = System.nanoTime(); ProcessSnapshot.collect(new File("/proc"), uid, roots);
            long t2 = System.nanoTime(); deviceNanos += t1 - t0; procNanos += t2 - t1;
        }
        long cpu = android.os.Debug.threadCpuTimeNanos() - cpuBefore;
        android.util.Log.i("ResourcePressureTest", String.format(java.util.Locale.ROOT,
            "sample cost over %d rounds: device reading %.2f ms, /proc scan %.2f ms, thread CPU %.2f ms per sample; at one sample every 2 s that is about %.2f%% of one core",
            rounds, deviceNanos / 1e6 / rounds, procNanos / 1e6 / rounds, cpu / 1e6 / rounds, cpu / 1e6 / rounds / 2000 * 100));
    }

    // ---- preview fingerprint ----

    @Test public void aStartPinnedToAPreviewIsRefusedWhenInjectedPressureChangedThePlan() throws Exception {
        assumeTrue("needs at least 4 CPUs for the recommendation to move", Runtime.getRuntime().availableProcessors() >= 4);
        reading.set(new PressureTracker.Reading(1500L << 20, 2 * GIB, false, 0)); // 2 GiB device, one heavy slot
        String keeper = start("balanced", null, "sleep 60");
        awaitState(keeper, "running");
        assertTrue(until(() -> "normal".equals(pressure()), 100));
        String params = "{\"executable\":\"cargo\",\"args\":[\"build\"],\"resources\":{\"profile\":\"balanced\",\"applyBuildPreset\":true}}";
        JsonObject preview = ok(rpc("job.preview", params));
        reading.set(new PressureTracker.Reading(100L << 20, 2 * GIB, false, 0));
        assertTrue(until(() -> "critical".equals(pressure()), 150));
        assertNotEquals("critical pressure lowers the recommendation", preview.getAsJsonArray("arguments").toString(),
            ok(rpc("job.preview", params)).getAsJsonArray("arguments").toString());
        int before = ok(rpc("job.list", "{}")).getAsJsonArray("jobs").size();
        assertEquals("CONFLICT", err(rpc("job.start", params.replace("{\"executable\"",
            "{\"expectedPreviewFingerprint\":\"" + preview.get("previewFingerprint").getAsString() + "\",\"executable\""))));
        assertEquals("nothing was admitted", before, ok(rpc("job.list", "{}")).getAsJsonArray("jobs").size());
        reading.set(NORMAL);
    }

    // ---- small real test runners (skipped when not installed) ----

    @Test public void nodeTestRunnerReceivesTheRequestedConcurrency() throws Exception {
        File node = new File(PREFIX_BIN, "node");
        assumeTrue("node is not installed in the Termux prefix: NOT_TESTED", node.canExecute());
        logVersion(node);
        File dir = new File(base, "home/projects/node"); assertTrue(dir.mkdirs());
        write(new File(dir, "a.test.js"), "const t=require('node:test');t('a',()=>{});\n");
        write(new File(dir, "b.test.js"), "const t=require('node:test');t('b',()=>{});\n");
        String params = "{\"executable\":\"" + node + "\",\"args\":[\"--test\",\"a.test.js\",\"b.test.js\"],\"cwd\":\"" + dir + "\","
            + "\"resources\":{\"profile\":\"protected\",\"applyBuildPreset\":true,\"testConcurrency\":1}}";
        JsonObject preview = ok(rpc("job.preview", params));
        assertEquals("[\"--test\",\"--test-concurrency=1\",\"a.test.js\",\"b.test.js\"]", preview.getAsJsonArray("arguments").toString());
        String id = ok(rpc("job.start", params.replace("{\"executable\"", "{\"expectedPreviewFingerprint\":\"" + preview.get("previewFingerprint").getAsString() + "\",\"executable\"")))
            .getAsJsonObject("job").get("id").getAsString();
        assertTrue(until(() -> isTerminal(stateq(id)), 600));
        // An old Node rejects the flag: that error is the observed result and is not retried without it.
        assertEquals("node --test exit: " + job(id), 0, job(id).get("exitCode").getAsInt());
    }

    @Test public void cargoTestReceivesBuildJobsAndHarnessThreads() throws Exception {
        File cargo = new File(PREFIX_BIN, "cargo");
        assumeTrue("cargo is not installed in the Termux prefix: NOT_TESTED", cargo.canExecute());
        logVersion(cargo); logVersion(new File(PREFIX_BIN, "rustc"));
        File dir = new File(base, "home/projects/crate"); assertTrue(new File(dir, "src").mkdirs());
        write(new File(dir, "Cargo.toml"), "[package]\nname = \"tiny\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
        write(new File(dir, "src/lib.rs"), "#[test] fn one() {}\n#[test] fn two() {}\n");
        String params = "{\"executable\":\"" + cargo + "\",\"args\":[\"test\",\"--offline\"],\"cwd\":\"" + dir + "\",\"env\":{\"CARGO_TARGET_DIR\":\"" + new File(base, "target") + "\"},"
            + "\"resources\":{\"profile\":\"protected\",\"applyBuildPreset\":true,\"testConcurrency\":1}}";
        JsonObject preview = ok(rpc("job.preview", params));
        assertEquals("[\"test\",\"-j\",\"1\",\"--offline\",\"--\",\"--test-threads=1\"]", preview.getAsJsonArray("arguments").toString());
        String id = ok(rpc("job.start", params.replace("{\"executable\"", "{\"expectedPreviewFingerprint\":\"" + preview.get("previewFingerprint").getAsString() + "\",\"executable\"")))
            .getAsJsonObject("job").get("id").getAsString();
        assertTrue("small crate builds and tests within 10 minutes", until(() -> isTerminal(stateq(id)), 6000));
        assertEquals("cargo test exit: " + job(id), 0, job(id).get("exitCode").getAsInt());
    }

    // ---- helpers ----

    /** Records the tool version for the device report; run as a job by this validation, never by job.preview. */
    private void logVersion(File tool) throws Exception {
        File out = new File(base, tool.getName() + ".version");
        String id = ok(rpc("job.start", "{\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\"'" + tool + "' --version > '" + out + "' 2>&1\"]}"))
            .getAsJsonObject("job").get("id").getAsString();
        assertTrue(until(() -> isTerminal(stateq(id)), 600));
        String text = out.exists() ? new String(java.nio.file.Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).trim() : "unavailable";
        android.util.Log.i("ResourcePressureTest", "version " + tool.getName() + ": " + text);
    }

    private String start(String profile, String onPressure, String script) throws Exception {
        String resources = "{\"profile\":\"" + profile + "\"" + (onPressure == null ? "" : ",\"onPressure\":\"" + onPressure + "\"") + "}";
        return ok(rpc("job.start", "{\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\"" + script + "\"],\"resources\":" + resources + "}"))
            .getAsJsonObject("job").get("id").getAsString();
    }
    private JsonObject job(String id) throws Exception { return ok(rpc("job.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("job"); }
    private String state(String id) throws Exception { return job(id).get("state").getAsString(); }
    private String stateq(String id) { try { return state(id); } catch (Exception e) { throw new AssertionError(e); } }
    private String pressure() { return ok(rpcq("resource.snapshot", "{}")).get("pressure").getAsString(); }
    private void awaitState(String id, String state) throws Exception { assertTrue(id + " -> " + state, until(() -> state.equals(stateq(id)), 300)); }
    private static boolean isTerminal(String state) {
        return state.equals("exited") || state.equals("failed") || state.equals("cancelled") || state.equals("timed_out") || state.equals("lost");
    }
    private JsonObject rpc(String method, String params) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + (++ids) + ",\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        return onWorker(() -> JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(json.getBytes(StandardCharsets.UTF_8)), ALL), StandardCharsets.UTF_8)).getAsJsonObject());
    }
    private JsonObject rpcq(String method, String params) { try { return rpc(method, params); } catch (Exception e) { throw new AssertionError(e); } }
    private static JsonObject ok(JsonObject r) { assertTrue(r.toString(), r.has("result")); return r.getAsJsonObject("result"); }
    private static String err(JsonObject r) { return r.has("error") ? r.getAsJsonObject("error").get("message").getAsString() : "no error: " + r; }
    private static boolean until(java.util.function.BooleanSupplier c, int tenthsOfSecond) throws Exception {
        for (int i = 0; i < tenthsOfSecond; i++) { if (c.getAsBoolean()) return true; Thread.sleep(100); }
        return c.getAsBoolean();
    }
    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private interface Call<T> { T call() throws Exception; }
    private static <T> T onWorker(Call<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { result.set(call.call()); } catch (Exception e) { failure.set(e); } });
        worker.start(); worker.join(120000);
        assertFalse("worker timed out", worker.isAlive());
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
}

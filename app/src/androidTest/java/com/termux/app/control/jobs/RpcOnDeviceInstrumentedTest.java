package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The JSON-RPC layer over the real runtime: real device capabilities, the production launcher and the real filesystem. */
@RunWith(AndroidJUnit4.class)
public class RpcOnDeviceInstrumentedTest {
    private static final Set<ControlCore.Scope> ALL = EnumSet.of(ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_EXECUTE, ControlCore.Scope.JOBS_OUTPUT,
        ControlCore.Scope.JOBS_CANCEL, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.WORKSPACES_WRITE, ControlCore.Scope.FILES_READ,
        ControlCore.Scope.FILES_WRITE, ControlCore.Scope.PROCESSES_READ);
    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] d) { }
        @Override public void rename(String id, String n) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private File base;
    private RpcDispatcher dispatcher;
    private RpcRequestParser parser = new RpcRequestParser();
    private JobCoordinator coordinator;
    private JobResourceSupervisor supervisor;
    private WorkspaceServiceController services;
    private WorkspacePortService ports;
    private int ids = 10;

    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        base = new File(context.getCacheDir(), "rpc-device-" + System.nanoTime());
        File home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        Map<String, File> roots = new HashMap<>(); roots.put("home", home);
        WorkspaceRepository repository = onWorker(() -> new WorkspaceRepository(new File(base, "workspaces.json"), new FileService(roots)));
        JobHistoryStore history = new JobHistoryStore(new File(base, "history")); onWorker(history::open);
        ResourceAdmissionController admission = new ResourceAdmissionController();
        supervisor = JobResourceSupervisor.forAndroid(context, admission);
        JobAuthority authority = new JobAuthority();
        authority.grantScopes(ALL);
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution(spec.executable(), "/"); }
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

    @After public void tearDown() { services.close(); ports.close(); coordinator.stopAccepting(); supervisor.close(); delete(base); }

    private JsonObject rpc(String method, String params) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + (++ids) + ",\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        return onWorker(() -> JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(json.getBytes(StandardCharsets.UTF_8)), ALL), StandardCharsets.UTF_8)).getAsJsonObject());
    }
    /** For polling inside lambdas, which cannot throw checked exceptions. */
    private JsonObject rpcq(String method, String params) {
        try { return rpc(method, params); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static JsonObject ok(JsonObject r) { assertTrue(r.toString(), r.has("result")); return r.getAsJsonObject("result"); }
    private static String err(JsonObject r) { return r.has("error") ? r.getAsJsonObject("error").get("message").getAsString() : "no error: " + r; }
    private static boolean until(java.util.function.BooleanSupplier c) throws Exception { for (int i = 0; i < 300; i++) { if (c.getAsBoolean()) return true; Thread.sleep(100); } return c.getAsBoolean(); }

    @Test public void capabilitiesAndSnapshotDescribeThisRealDevice() throws Exception {
        JsonObject caps = ok(rpc("resource.capabilities", "{}"));
        JsonObject limits = caps.getAsJsonObject("resourceLimits");
        assertTrue("the native launcher is available here", limits.get("safeProcessStop").getAsBoolean());
        assertTrue(limits.get("rlimitAs").getAsBoolean()); assertTrue(limits.get("rlimitCpu").getAsBoolean());
        assertTrue("memory and /proc are readable by the app", limits.get("memorySoftMonitoring").getAsBoolean());
        assertFalse("cgroup limits are never claimed", limits.get("cgroups").getAsBoolean());
        assertFalse("no isolated VM is claimed on a device that cannot run one", limits.get("virtualMachine").getAsBoolean());
        JsonObject snap = ok(rpc("resource.snapshot", "{}"));
        assertTrue("real total RAM", snap.getAsJsonObject("device").get("totalMemoryBytes").getAsLong() > (512L << 20));
        assertTrue(snap.getAsJsonObject("device").get("cpuCount").getAsInt() >= 1);
    }

    @Test public void aJobStartedOverRpcIsLimitedRecordedAndReportedByTheResourceMethods() throws Exception {
        String params = "{\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\"sleep 3\"],\"clientRequestId\":\"d1\",\"resources\":{\"profile\":\"balanced\",\"addressSpaceBytes\":8589934592,\"memorySoftBytes\":1073741824}}";
        String id = ok(rpc("job.start", params)).getAsJsonObject("job").get("id").getAsString();
        assertTrue(until(() -> "running".equals(ok(rpcq("job.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("job").get("state").getAsString())));
        JsonObject res = ok(rpc("job.resource.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("resource");
        assertEquals("balanced", res.getAsJsonObject("limits").get("profile").getAsString());
        assertEquals("every requested limit can be enforced on this device", 0, res.getAsJsonArray("unsupported").size());
        assertTrue("the supervisor already measured it", until(() -> !ok(rpcq("job.resource.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("resource").get("metrics").isJsonNull()));
        JsonObject procs = ok(rpc("job.processes", "{\"id\":\"" + id + "\"}"));
        assertTrue(procs.get("running").getAsBoolean());
        assertEquals("root", procs.getAsJsonArray("processes").get(0).getAsJsonObject().get("relation").getAsString());
        assertTrue(until(() -> "exited".equals(ok(rpcq("job.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("job").get("state").getAsString())));
        assertTrue(until(() -> rpcq("job.history.get", "{\"id\":\"" + id + "\"}").has("result")));
        JsonObject record = ok(rpc("job.history.get", "{\"id\":\"" + id + "\"}")).getAsJsonObject("record");
        assertEquals("NORMAL_EXIT", record.getAsJsonObject("evidence").get("reason").getAsString());
        assertEquals("confirmed", record.getAsJsonObject("evidence").get("confidence").getAsString());
        String report = ok(rpc("job.diagnostics.export", "{\"id\":\"" + id + "\"}")).get("report").getAsString();
        assertTrue(report.contains("native_launcher [supported]"));
        assertFalse("no app path leaks into the shared report", report.contains(base.getAbsolutePath()));
    }

    @Test public void anAddressSpaceLimitTheDeviceCannotRunIsRefusedOverRpc() throws Exception {
        assertEquals("UNSUPPORTED", err(rpc("job.start", "{\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\":\"],\"resources\":{\"addressSpaceBytes\":536870912}}")));
    }

    @Test public void aPreviewShowsTheDeviceAwareWorkerCountWithoutStartingAnything() throws Exception {
        JsonObject preview = ok(rpc("job.preview", "{\"executable\":\"cargo\",\"args\":[\"build\"],\"resources\":{\"profile\":\"balanced\",\"applyBuildPreset\":true}}"));
        assertTrue(preview.get("changed").getAsBoolean());
        assertEquals("build", preview.getAsJsonArray("arguments").get(0).getAsString());
        assertEquals("-j", preview.getAsJsonArray("arguments").get(1).getAsString());
        int workers = Integer.parseInt(preview.getAsJsonArray("arguments").get(2).getAsString());
        assertTrue("capped by this device's CPUs: " + workers, workers >= 1 && workers <= Runtime.getRuntime().availableProcessors());
        assertEquals(0, ok(rpc("job.list", "{}")).getAsJsonArray("jobs").size());
    }

    @Test public void aWorkspaceServiceIsStartedAndStoppedOverRpc() throws Exception {
        String ws = ok(rpc("workspace.create", "{\"name\":\"d\",\"rootId\":\"home\",\"path\":\"projects/app\",\"services\":[{\"id\":\"sleeper\",\"executable\":\"/system/bin/sh\",\"args\":[\"-c\",\"sleep 30\"],\"ports\":[]}]}"))
            .getAsJsonObject("workspace").get("id").getAsString();
        assertEquals("started", ok(rpc("workspace.service.start", "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"sleeper\"}")).get("outcome").getAsString());
        assertEquals("CONFLICT", err(rpc("workspace.service.start", "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"sleeper\"}")));
        assertEquals("running", ok(rpc("workspace.service.status", "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"sleeper\"}")).getAsJsonObject("service").get("state").getAsString());
        assertEquals("stopped", ok(rpc("workspace.service.stop", "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"sleeper\"}")).get("outcome").getAsString());
        assertTrue(until(() -> "stopped".equals(ok(rpcq("workspace.service.status", "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"sleeper\"}")).getAsJsonObject("service").get("state").getAsString())));
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

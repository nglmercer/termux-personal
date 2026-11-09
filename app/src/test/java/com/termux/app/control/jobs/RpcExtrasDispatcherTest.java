package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
import com.termux.app.control.ipc.RpcDispatcher;
import com.termux.app.control.ipc.RpcRequestParser;
import com.termux.app.control.resource.ResourceService;
import com.termux.app.control.workspace.WorkspaceBackupService;
import com.termux.app.control.workspace.WorkspacePortService;
import com.termux.app.control.workspace.WorkspaceRepository;
import com.termux.app.control.workspace.WorkspaceServiceController;
import com.termux.app.monitor.DeviceCompatibilityAdvisor;
import com.termux.app.monitor.ProcessSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The resource, service, backup and port methods over the real dispatcher, coordinator, repository and stores. */
public class RpcExtrasDispatcherTest {
    private static final ControlCore.Scope JR = ControlCore.Scope.JOBS_READ, JE = ControlCore.Scope.JOBS_EXECUTE, JO = ControlCore.Scope.JOBS_OUTPUT,
        WR = ControlCore.Scope.WORKSPACES_READ, WW = ControlCore.Scope.WORKSPACES_WRITE, FR = ControlCore.Scope.FILES_READ, FW = ControlCore.Scope.FILES_WRITE,
        PR = ControlCore.Scope.PROCESSES_READ;
    private static final Set<ControlCore.Scope> ALL = EnumSet.of(JR, JE, JO, ControlCore.Scope.JOBS_CANCEL, WR, WW, FR, FW, PR);
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] d) { }
        @Override public void rename(String id, String n) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private File base, home, store, proc;
    private final JobAuthority authority = new JobAuthority();
    private final List<JobSpec> launched = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private RpcRequestParser parser;
    private RpcDispatcher dispatcher;
    private JobCoordinator coordinator;
    private WorkspaceServiceController services;
    private WorkspacePortService ports;
    private JobHistoryStore history;
    private WorkspaceRepository repository;
    private int ids = 100;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("rpc-extras").toFile();
        home = new File(base, "home"); store = new File(base, "store"); proc = new File(base, "proc");
        assertTrue(new File(home, "projects/app").mkdirs()); assertTrue(store.mkdir()); assertTrue(proc.mkdir());
        Map<String, File> roots = new HashMap<>(); roots.put("home", home);
        repository = new WorkspaceRepository(new File(store, "v1.json"), new FileService(roots));
        history = new JobHistoryStore(new File(base, "resource-jobs")); history.open();

        JobRegistry registry = new JobRegistry(new JobIoPool(4), new JobOutputStore(1024, 8, 32 * 1024), 8);
        ResourceAdmissionController admission = new ResourceAdmissionController(ResourceAdmissionController.Config.defaults(), 4, System::nanoTime);
        JobResourceSupervisor supervisor = new JobResourceSupervisor(new JobResourceSupervisor.Sampler() {
            @Override public PressureTracker.Reading device() { return new PressureTracker.Reading(6L << 30, 8L << 30, false, 0); }
            @Override public ProcessSnapshot processes(Set<Integer> r) { return ProcessSnapshot.collect(proc, 1000, r); }
            @Override public long monotonicMillis() { return System.nanoTime() / 1_000_000; }
            @Override public long ticksPerSecond() { return 100; }
            @Override public long pageSizeBytes() { return 4096; }
        }, admission);
        AppShellJobAdapter adapter = new AppShellJobAdapter((spec, exe, cwd, capture, done) -> {
            launched.add(spec);
            Process process = new Process() {
                @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream("service log\n".getBytes(StandardCharsets.UTF_8)); }
                @Override public java.io.InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
                @Override public int waitFor() { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return 0; }
                @Override public int exitValue() { throw new IllegalThreadStateException(); }
                @Override public void destroy() { }
            };
            Thread t = new Thread(() -> { try { done.finished(capture.captureAndWait(process), false); } catch (Exception e) { done.finished(null, true); } });
            t.setDaemon(true); t.start();
            return process;
        });
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution("/bin/echo", "/"); }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        coordinator = new JobCoordinator(registry, adapter, policy, authority, 4, admission, supervisor, new JobDiagnostics(history, supervisor, 34));
        authority.grantScopes(ALL);

        ResourceService resource = new ResourceService(coordinator, supervisor, admission, history, new ResourceService.Device() {
            @Override public PressureTracker.Reading reading() { return new PressureTracker.Reading(6L << 30, 8L << 30, false, 0); }
            @Override public DeviceCompatibilityAdvisor.Inputs advisorInputs() {
                DeviceCompatibilityAdvisor.Inputs in = new DeviceCompatibilityAdvisor.Inputs();
                in.sdkInt = 34; in.release = "14"; in.nativeLauncherAvailable = false; in.memoryApiAvailable = true; in.procListingVisible = true;
                return in;
            }
            @Override public int cpuCount() { return 8; }
            @Override public ProcessSnapshot processes(Set<Integer> r) { return ProcessSnapshot.collect(proc, 1000, r); }
            @Override public long ticksPerSecond() { return 100; }
            @Override public long pageSizeBytes() { return 4096; }
            @Override public String systemSummary() { return "test device"; }
            @Override public String homePath() { return home.getAbsolutePath(); }
            @Override public String prefixPath() { return "/usr"; }
        });
        services = new WorkspaceServiceController(new WorkspaceServiceController.Definitions() {
            @Override public Resolved resolve(String ws, String svc) throws WorkspaceRepository.WorkspaceException {
                WorkspaceRepository.Workspace w = repository.get(ws);
                return new Resolved(repository.service(ws, svc), w.resourceProfile, w.available);
            }
            @Override public List<WorkspaceRepository.ServiceDefinition> list(String ws) throws WorkspaceRepository.WorkspaceException { return repository.get(ws).services; }
        }, WorkspaceServiceController.jobsOf(coordinator), id -> { JobHistoryStore.Record r = history.get(id); return r == null ? null : r.evidence; },
            WorkspaceServiceController.Config.defaults(), () -> System.nanoTime() / 1_000_000);
        ports = new WorkspacePortService();
        WorkspaceBackupService backup = new WorkspaceBackupService(new File(base, "backups"), WorkspaceBackupService.Limits.defaults());
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority, repository, resource, services, backup, ports);
        parser = new RpcRequestParser();
        dispatcher.dispatch(parser.parse(HELLO.getBytes(StandardCharsets.UTF_8)), Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() {
        release.countDown(); services.close(); ports.close(); coordinator.stopAccepting();
        delete(base);
    }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }

    private JsonObject rpc(String method, String params, Set<ControlCore.Scope> scopes) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + (++ids) + ",\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
        return JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(json.getBytes(StandardCharsets.UTF_8)), scopes), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static String err(JsonObject r) { return r.has("error") ? r.getAsJsonObject("error").get("message").getAsString() : "no error: " + r; }
    private static JsonObject ok(JsonObject r) { assertTrue(r.toString(), r.has("result")); return r.getAsJsonObject("result"); }
    private static Set<ControlCore.Scope> scopes(ControlCore.Scope... s) { return s.length == 0 ? EnumSet.noneOf(ControlCore.Scope.class) : EnumSet.copyOf(java.util.Arrays.asList(s)); }

    private String workspace(String extra) throws Exception {
        JsonObject r = rpc("workspace.create", "{\"name\":\"app\",\"rootId\":\"home\",\"path\":\"projects/app\"" + extra + "}", ALL);
        return ok(r).getAsJsonObject("workspace").get("id").getAsString();
    }
    private static boolean until(java.util.function.BooleanSupplier c) throws Exception { for (int i = 0; i < 300; i++) { if (c.getAsBoolean()) return true; Thread.sleep(10); } return c.getAsBoolean(); }

    private static final String SERVICE = ",\"services\":[{\"id\":\"dev-server\",\"executable\":\"node\",\"args\":[\"server.js\"],\"restartPolicy\":\"never\",\"ports\":[3999]}]";

    // ---- discovery and consent ----

    @Test public void eachMethodIsAdvertisedOnlyForItsScopesAndOnlyWhenItsServiceExists() throws Exception {
        List<String> readOnly = methods(scopes(JR, WR));
        assertTrue(readOnly.containsAll(java.util.Arrays.asList("resource.capabilities", "resource.snapshot", "job.resource.get", "job.history.list",
            "job.history.get", "job.diagnostics.export", "workspace.service.list", "workspace.service.status", "workspace.service.health", "workspace.service.ports")));
        for (String hidden : new String[] {"workspace.service.start", "workspace.service.stop", "workspace.service.restart", "workspace.service.logs",
            "workspace.service.proxy", "workspace.backup.export", "workspace.backup.import", "job.processes"}) assertFalse(hidden, readOnly.contains(hidden));
        assertTrue(methods(ALL).containsAll(java.util.Arrays.asList("workspace.service.start", "workspace.service.logs", "workspace.service.proxy",
            "workspace.backup.export", "workspace.backup.import", "job.processes")));
        RpcDispatcher bare = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority, repository);
        bare.dispatch(parser.parse(HELLO.getBytes(StandardCharsets.UTF_8)), Collections.<ControlCore.Scope>emptySet());
        String caps = new String(bare.dispatch(parser.parse("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"system.capabilities\"}".getBytes(StandardCharsets.UTF_8)), ALL), StandardCharsets.UTF_8);
        assertFalse(caps.contains("resource.") || caps.contains("workspace.service.") || caps.contains("workspace.backup."));
    }

    private List<String> methods(Set<ControlCore.Scope> s) throws Exception {
        JsonArray array = ok(rpc("system.capabilities", null, s)).getAsJsonArray("methods");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) names.add(array.get(i).getAsString());
        return names;
    }

    @Test public void methodsFailClosedWithoutTheirScopesEvenIfCalledDirectly() throws Exception {
        assertEquals("DENIED", err(rpc("resource.snapshot", "{}", scopes(WR))));
        assertEquals("DENIED", err(rpc("workspace.service.start", "{\"workspaceId\":\"ws_0123456789abcdef\",\"serviceId\":\"a\"}", scopes(WR))));
        assertEquals("DENIED", err(rpc("workspace.service.proxy", "{\"action\":\"list\"}", scopes(WR))));
        assertEquals("DENIED", err(rpc("workspace.backup.import", "{\"workspaceId\":\"ws_0123456789abcdef\",\"backupId\":\"x\"}", scopes(WW))));
        authority.revoke();
        assertEquals("DENIED", err(rpc("resource.capabilities", "{}", ALL)));
    }

    // ---- resource ----

    @Test public void capabilitiesAndSnapshotComeFromMeasuredStateNotConstants() throws Exception {
        JsonObject caps = ok(rpc("resource.capabilities", "{}", scopes(JR)));
        JsonObject limits = caps.getAsJsonObject("resourceLimits");
        assertFalse("the test device reports no native launcher", limits.get("rlimitAs").getAsBoolean());
        assertFalse(limits.get("safeProcessStop").getAsBoolean());
        assertTrue(limits.get("memorySoftMonitoring").getAsBoolean());
        assertFalse(limits.get("cgroups").getAsBoolean()); assertFalse(limits.get("virtualMachine").getAsBoolean());
        JsonObject snap = ok(rpc("resource.snapshot", "{}", scopes(JR)));
        assertEquals(8L << 30, snap.getAsJsonObject("device").get("totalMemoryBytes").getAsLong());
        // Nothing has been sampled yet, so pressure is unknown and admission stays conservative at one slot.
        assertEquals("unknown", snap.get("pressure").getAsString());
        assertEquals(1, snap.getAsJsonObject("admission").get("slots").getAsInt());
        assertEquals("bad params are rejected", -32602, rpc("resource.snapshot", "{\"x\":1}", scopes(JR)).getAsJsonObject("error").get("code").getAsInt());
    }

    private String runJob(String resources) throws Exception {
        JsonObject r = rpc("job.start", "{\"executable\":\"/bin/echo\",\"args\":[\"hi\"],\"clientRequestId\":\"c" + (++ids) + "\""
            + (resources == null ? "" : ",\"resources\":" + resources) + "}", ALL);
        return ok(r).getAsJsonObject("job").get("id").getAsString();
    }

    @Test public void historyAndResourceViewsReportWhatActuallyHappened() throws Exception {
        String id = runJob("{\"profile\":\"balanced\",\"memorySoftBytes\":1073741824}");
        assertTrue(until(() -> history.get(id) != null));
        JsonObject res = ok(rpc("job.resource.get", "{\"id\":\"" + id + "\"}", scopes(JR))).getAsJsonObject("resource");
        assertEquals("balanced", res.getAsJsonObject("limits").get("profile").getAsString());
        assertEquals(1073741824L, res.getAsJsonObject("limits").get("memorySoftBytes").getAsLong());
        assertTrue(res.getAsJsonArray("unsupported").toString(), res.getAsJsonArray("unsupported").toString().contains("niceLevel"));
        release.countDown();
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(id); return r != null && r.finished(); }));
        JsonObject list = ok(rpc("job.history.list", "{\"limit\":5}", scopes(JR)));
        assertTrue(list.get("available").getAsBoolean());
        assertEquals(id, list.getAsJsonArray("history").get(0).getAsJsonObject().get("jobId").getAsString());
        JsonObject record = ok(rpc("job.history.get", "{\"id\":\"" + id + "\"}", scopes(JR))).getAsJsonObject("record");
        assertEquals("NORMAL_EXIT", record.getAsJsonObject("evidence").get("reason").getAsString());
        assertEquals("NOT_FOUND", err(rpc("job.history.get", "{\"id\":\"job_" + "0".repeat(32) + "\"}", scopes(JR))));
        assertEquals("NOT_FOUND", err(rpc("job.resource.get", "{\"id\":\"job_" + "0".repeat(32) + "\"}", scopes(JR))));
        assertEquals("job ids that could escape the directory are just not found", "NOT_FOUND", err(rpc("job.history.get", "{\"id\":\"../x\"}", scopes(JR))));
        assertEquals(-32602, rpc("job.history.list", "{\"limit\":500}", scopes(JR)).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void diagnosticsAreRedactedAndOutputNeedsItsOwnConsent() throws Exception {
        String id = runJob(null);
        release.countDown();
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(id); return r != null && r.finished(); }));
        JsonObject report = ok(rpc("job.diagnostics.export", "{\"id\":\"" + id + "\"}", scopes(JR)));
        assertFalse(report.get("outputIncluded").getAsBoolean());
        assertTrue(report.get("report").getAsString().contains("Process output is NOT included"));
        assertEquals("output is a separate consent", "DENIED", err(rpc("job.diagnostics.export", "{\"id\":\"" + id + "\",\"includeOutput\":true}", scopes(JR))));
        JsonObject with = ok(rpc("job.diagnostics.export", "{\"id\":\"" + id + "\",\"includeOutput\":true}", scopes(JR, JO)));
        assertTrue(with.get("outputIncluded").getAsBoolean());
        assertEquals("NOT_FOUND", err(rpc("job.diagnostics.export", "{\"id\":\"job_" + "0".repeat(32) + "\"}", scopes(JR))));
    }

    @Test public void jobProcessesNeedsProcessConsentAndReportsNothingForAFinishedJob() throws Exception {
        String id = runJob(null);
        assertEquals("DENIED", err(rpc("job.processes", "{\"id\":\"" + id + "\"}", scopes(JR))));
        release.countDown();
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(id); return r != null && r.finished(); }));
        JsonObject result = ok(rpc("job.processes", "{\"id\":\"" + id + "\"}", scopes(JR, PR)));
        assertFalse(result.get("running").getAsBoolean());
        assertEquals(0, result.getAsJsonArray("processes").size());
        assertTrue("with nothing to look at, completeness is not claimed", result.get("incomplete").getAsBoolean());
    }

    @Test public void aHardLimitIsRefusedWhenTheDeviceReportsNoNativeSupport() throws Exception {
        assertEquals("UNSUPPORTED", err(rpc("job.start", "{\"executable\":\"/bin/echo\",\"args\":[],\"resources\":{\"cpuTimeSeconds\":60}}", ALL)));
    }

    // ---- workspace definition ----

    @Test public void profilesPresetResourcesAndServicesRoundTripThroughTheWireAndStayAbsentOtherwise() throws Exception {
        String plain = workspace("");
        JsonObject plainWs = ok(rpc("workspace.get", "{\"id\":\"" + plain + "\"}", scopes(WR))).getAsJsonObject("workspace");
        assertFalse("a workspace that never used them looks as it always did", plainWs.has("resourceProfile") || plainWs.has("services"));
        String id = workspace(",\"resourceProfile\":\"balanced\",\"taskPresets\":[{\"id\":\"build\",\"label\":\"Build\",\"executable\":\"cargo\",\"args\":[\"build\"],\"timeoutMs\":0,"
            + "\"resources\":{\"profile\":\"protected\",\"processConcurrencyHint\":2}}]" + SERVICE);
        JsonObject ws = ok(rpc("workspace.get", "{\"id\":\"" + id + "\"}", scopes(WR))).getAsJsonObject("workspace");
        assertEquals("balanced", ws.get("resourceProfile").getAsString());
        assertEquals("protected", ws.getAsJsonArray("taskPresets").get(0).getAsJsonObject().getAsJsonObject("resources").get("profile").getAsString());
        assertEquals("dev-server", ws.getAsJsonArray("services").get(0).getAsJsonObject().get("id").getAsString());
        JsonObject cleared = ok(rpc("workspace.update", "{\"id\":\"" + id + "\",\"resourceProfile\":null}", scopes(WW))).getAsJsonObject("workspace");
        assertFalse(cleared.has("resourceProfile"));
        assertTrue("services untouched", cleared.has("services"));
        for (String bad : new String[] {",\"resourceProfile\":\"turbo\"", ",\"services\":[{\"id\":\"BAD ID\",\"executable\":\"node\",\"args\":[]}]",
            ",\"services\":[{\"id\":\"s\",\"executable\":\"node\",\"args\":[],\"ports\":[0]}]", ",\"services\":[{\"id\":\"s\",\"executable\":\"../x\",\"args\":[]}]",
            ",\"taskPresets\":[{\"id\":\"t\",\"label\":\"T\",\"executable\":\"x\",\"args\":[],\"timeoutMs\":0,\"resources\":{\"niceLevel\":99}}]"})
            assertEquals(bad, "INVALID_REQUEST", err(rpc("workspace.create", "{\"name\":\"x\",\"rootId\":\"home\",\"path\":\"projects/app\"" + bad + "}", ALL)));
        assertEquals(-32602, rpc("workspace.create", "{\"name\":\"x\",\"rootId\":\"home\",\"path\":\"projects/app\",\"services\":\"nope\"}", ALL).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void runningATaskAppliesThePresetPolicyThenTheWorkspaceProfile() throws Exception {
        String id = workspace(",\"resourceProfile\":\"balanced\",\"taskPresets\":[{\"id\":\"own\",\"label\":\"Own\",\"executable\":\"cargo\",\"args\":[\"build\"],\"timeoutMs\":0,"
            + "\"resources\":{\"profile\":\"protected\"}},{\"id\":\"inherit\",\"label\":\"Inherit\",\"executable\":\"cargo\",\"args\":[\"build\"],\"timeoutMs\":0}]");
        ok(rpc("workspace.task.run", "{\"workspaceId\":\"" + id + "\",\"taskId\":\"own\",\"clientRequestId\":\"t1\"}", ALL));
        assertTrue(until(() -> launched.size() == 1));
        assertEquals(JobResourcePolicy.Profile.PROTECTED, launched.get(0).resources().profile());
        assertEquals("a known build tool gets the device-aware worker count", java.util.Arrays.asList("build", "-j", "1"), launched.get(0).arguments());
        release.countDown();
        assertTrue(until(() -> history.list(10).size() == 1 && history.list(10).get(0).finished()));
        ok(rpc("workspace.task.run", "{\"workspaceId\":\"" + id + "\",\"taskId\":\"inherit\",\"clientRequestId\":\"t2\"}", ALL));
        assertTrue(until(() -> launched.size() == 2));
        assertEquals(JobResourcePolicy.Profile.BALANCED, launched.get(1).resources().profile());
    }

    @Test public void aWorkspaceWithoutAnyProfileRunsTasksExactlyAsBefore() throws Exception {
        String id = workspace(",\"taskPresets\":[{\"id\":\"t\",\"label\":\"T\",\"executable\":\"cargo\",\"args\":[\"build\"],\"timeoutMs\":0}]");
        ok(rpc("workspace.task.run", "{\"workspaceId\":\"" + id + "\",\"taskId\":\"t\",\"clientRequestId\":\"plain\"}", ALL));
        assertTrue(until(() -> launched.size() == 1));
        assertNull(launched.get(0).resources());
        assertEquals(java.util.Arrays.asList("build"), launched.get(0).arguments());
    }

    // ---- services ----

    @Test public void aServiceStartsThroughTheCoordinatorAndStopsByTheOwnedHandle() throws Exception {
        String id = workspace(SERVICE);
        assertEquals("DENIED", err(rpc("workspace.service.start", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\"}", scopes(WR))));
        JsonObject started = ok(rpc("workspace.service.start", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\"}", scopes(WR, JE)));
        assertEquals("started", started.get("outcome").getAsString());
        assertEquals("running", started.getAsJsonObject("service").get("state").getAsString());
        assertTrue(until(() -> launched.size() == 1));
        assertEquals("node", launched.get(0).executable());
        assertEquals("CONFLICT", err(rpc("workspace.service.start", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\"}", scopes(WR, JE))));
        assertEquals("NOT_FOUND", err(rpc("workspace.service.start", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"nope\"}", scopes(WR, JE))));
        JsonObject listed = ok(rpc("workspace.service.list", "{\"workspaceId\":\"" + id + "\"}", scopes(WR)));
        assertEquals(1, listed.getAsJsonArray("services").size());
        JsonObject health = ok(rpc("workspace.service.health", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\"}", scopes(WR))).getAsJsonObject("service");
        assertFalse("running but nothing listens on its port", health.get("healthy").getAsBoolean());
        assertEquals("DENIED", err(rpc("workspace.service.logs", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\",\"stream\":\"stdout\",\"offset\":0,\"limit\":64}", scopes(WR))));
        JsonObject stopped = ok(rpc("workspace.service.stop", "{\"workspaceId\":\"" + id + "\",\"serviceId\":\"dev-server\"}", scopes(WR, JE)));
        assertEquals("stopped", stopped.get("outcome").getAsString());
    }

    @Test public void servicePortsReportListenersConflictsAndLoopbackOnlyExposure() throws Exception {
        String id = workspace(SERVICE.replace("3999", Integer.toString(freePort())));
        int port = ok(rpc("workspace.service.ports", "{\"workspaceId\":\"" + id + "\"}", scopes(WR))).getAsJsonArray("ports").get(0).getAsJsonObject().get("port").getAsInt();
        try (java.net.ServerSocket other = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            JsonObject row = ok(rpc("workspace.service.ports", "{\"workspaceId\":\"" + id + "\"}", scopes(WR))).getAsJsonArray("ports").get(0).getAsJsonObject();
            assertTrue(row.get("listening").getAsBoolean());
            assertEquals("in_use_by_other_process", row.get("conflict").getAsString());
            assertFalse(row.get("lanExposed").getAsBoolean());
        }
    }

    private static int freePort() throws Exception { try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { return s.getLocalPort(); } }

    @Test public void proxiesAreExplicitLoopbackByDefaultAndLanNeedsConfirmation() throws Exception {
        int target = freePort(), listen = freePort();
        JsonObject opened = ok(rpc("workspace.service.proxy", "{\"action\":\"open\",\"listenPort\":" + listen + ",\"targetPort\":" + target + "}", scopes(WW))).getAsJsonObject("proxy");
        assertEquals("127.0.0.1", opened.get("listenAddress").getAsString()); assertFalse(opened.get("lan").getAsBoolean());
        assertEquals("127.0.0.1", opened.get("targetAddress").getAsString());
        assertEquals("PERMISSION_REQUIRED", err(rpc("workspace.service.proxy", "{\"action\":\"open\",\"listenAddress\":\"0.0.0.0\",\"listenPort\":" + freePort() + ",\"targetPort\":" + target + "}", scopes(WW))));
        assertEquals("INVALID_REQUEST", err(rpc("workspace.service.proxy", "{\"action\":\"open\",\"listenAddress\":\"example.com\",\"listenPort\":" + freePort() + ",\"targetPort\":" + target + "}", scopes(WW))));
        assertEquals(1, ok(rpc("workspace.service.proxy", "{\"action\":\"list\"}", scopes(WW))).getAsJsonArray("proxies").size());
        assertTrue(ok(rpc("workspace.service.proxy", "{\"action\":\"close\",\"id\":\"" + opened.get("id").getAsString() + "\"}", scopes(WW))).get("closed").getAsBoolean());
        assertEquals(-32602, rpc("workspace.service.proxy", "{\"action\":\"explode\"}", scopes(WW)).getAsJsonObject("error").get("code").getAsInt());
    }

    // ---- backup ----

    @Test public void backupExportAndRestoreRespectConsentConfirmationAndSafety() throws Exception {
        String id = workspace(SERVICE);
        Files.write(new File(home, "projects/app/a.txt").toPath(), "alpha".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(home, "projects/app/.env").toPath(), "SECRET=1".getBytes(StandardCharsets.UTF_8));
        new File(home, "projects/app/node_modules").mkdirs();
        Files.write(new File(home, "projects/app/node_modules/x.js").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("DENIED", err(rpc("workspace.backup.export", "{\"workspaceId\":\"" + id + "\"}", scopes(WW))));
        JsonObject backup = ok(rpc("workspace.backup.export", "{\"workspaceId\":\"" + id + "\",\"format\":\"tar.gz\"}", scopes(WW, FR))).getAsJsonObject("backup");
        assertEquals(1, backup.get("files").getAsInt());
        assertEquals(1, backup.getAsJsonObject("skipped").get("sensitive").getAsInt());
        String backupId = backup.get("id").getAsString();
        assertTrue(new File(home, "projects/app/a.txt").delete());
        // Plain restore is non-destructive; overwrite needs explicit confirmation.
        assertEquals("PERMISSION_REQUIRED", err(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + backupId + "\",\"overwrite\":true}", scopes(WW, FW))));
        assertEquals("PERMISSION_REQUIRED", err(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + backupId + "\",\"applyConfig\":true}", scopes(WW, FW))));
        JsonObject restored = ok(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + backupId + "\"}", scopes(WW, FW))).getAsJsonObject("restore");
        assertEquals(1, restored.get("files").getAsInt());
        assertEquals("alpha", new String(Files.readAllBytes(new File(home, "projects/app/a.txt").toPath()), StandardCharsets.UTF_8));
        JsonObject again = ok(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + backupId + "\"}", scopes(WW, FW))).getAsJsonObject("restore");
        assertEquals("existing files are kept", 1, again.get("conflicts").getAsInt());
        // The stored definition can be re-applied, but only with confirmation.
        ok(rpc("workspace.update", "{\"id\":\"" + id + "\",\"services\":[]}", scopes(WW)));
        JsonObject applied = ok(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + backupId + "\",\"applyConfig\":true,\"confirm\":true}", scopes(WW, FW))).getAsJsonObject("restore");
        assertTrue(applied.get("configApplied").getAsBoolean());
        assertEquals("dev-server", ok(rpc("workspace.get", "{\"id\":\"" + id + "\"}", scopes(WR))).getAsJsonObject("workspace").getAsJsonArray("services").get(0).getAsJsonObject().get("id").getAsString());
        for (String bad : new String[] {"../" + backupId, "/etc/passwd", backupId + "/../x", "nope.zip"})
            assertEquals(bad, "NOT_FOUND", err(rpc("workspace.backup.import", "{\"workspaceId\":\"" + id + "\",\"backupId\":\"" + bad + "\"}", scopes(WW, FW))));
        String other = workspace("");
        assertEquals("a backup belongs to its own workspace", "NOT_FOUND", err(rpc("workspace.backup.import", "{\"workspaceId\":\"" + other + "\",\"backupId\":\"" + backupId + "\"}", scopes(WW, FW))));
    }
}

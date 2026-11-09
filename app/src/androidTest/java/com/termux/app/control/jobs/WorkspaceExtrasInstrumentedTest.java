package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.app.control.files.FileService;
import com.termux.app.control.workspace.WorkspaceBackupService;
import com.termux.app.control.workspace.WorkspacePortService;
import com.termux.app.control.workspace.WorkspaceRepository;
import com.termux.app.control.workspace.WorkspaceServiceController;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Services, backups, ports and supervision against the real Android runtime and filesystem: the production job
 * coordinator, AppShell and owned launcher, java.nio.file on this Android version, and loopback sockets.
 */
@RunWith(AndroidJUnit4.class)
public class WorkspaceExtrasInstrumentedTest {
    private Context context;
    private File base, home;
    private WorkspaceRepository repository;
    private JobCoordinator coordinator;
    private JobResourceSupervisor supervisor;
    private ResourceAdmissionController admission;
    private JobHistoryStore history;
    private JobAuthority authority;
    private JobAuthority.Lease lease;
    private String workspaceId;
    private WorkspaceServiceController services;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        base = new File(context.getCacheDir(), "extras-test-" + System.nanoTime());
        home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        Map<String, File> roots = new HashMap<>(); roots.put("home", home);
        repository = onWorker(() -> new WorkspaceRepository(new File(base, "workspaces.json"), new FileService(roots)));
        history = new JobHistoryStore(new File(base, "history")); onWorker(history::open);
        admission = new ResourceAdmissionController();
        supervisor = JobResourceSupervisor.forAndroid(context, admission);
        authority = new JobAuthority();
        lease = authority.grant(EnumSet.allOf(JobAuthority.Permission.class));
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution(spec.executable(), "/"); }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        coordinator = new JobCoordinator(new JobRegistry(new JobIoPool(JobIoPool.MAX_JOBS), new JobOutputStore(), JobOutputStore.DEFAULT_RECORD_LIMIT),
            new AppShellJobAdapter(context), policy, authority, admission, supervisor, new JobDiagnostics(history, supervisor, android.os.Build.VERSION.SDK_INT));
        workspaceId = onWorker(() -> repository.create("device", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList()).id);
    }

    @After public void tearDown() {
        if (services != null) services.close();
        coordinator.stopAccepting(); supervisor.close();
        delete(base);
    }

    private WorkspaceServiceController controller(WorkspaceServiceController.Config config) {
        services = new WorkspaceServiceController(new WorkspaceServiceController.Definitions() {
            @Override public Resolved resolve(String ws, String svc) throws WorkspaceRepository.WorkspaceException {
                WorkspaceRepository.Workspace w = repository.get(ws); return new Resolved(repository.service(ws, svc), w.resourceProfile, w.available);
            }
            @Override public List<WorkspaceRepository.ServiceDefinition> list(String ws) throws WorkspaceRepository.WorkspaceException { return repository.get(ws).services; }
        }, WorkspaceServiceController.jobsOf(coordinator), id -> { JobHistoryStore.Record r = history.get(id); return r == null ? null : r.evidence; },
            config, android.os.SystemClock::elapsedRealtime);
        return services;
    }

    private void defineService(String id, String script, WorkspaceRepository.RestartPolicy policy, List<Integer> ports) throws Exception {
        WorkspaceRepository.ServiceDefinition d = new WorkspaceRepository.ServiceDefinition(id, "/system/bin/sh", Arrays.asList("-c", script), policy,
            JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED), ports);
        onWorker(() -> repository.update(workspaceId, null, null, null, null, false, null, Collections.singletonList(d)));
    }

    private String state(String svc) {
        try { return (String) services.status(workspaceId, svc).get("state"); } catch (WorkspaceRepository.WorkspaceException e) { throw new AssertionError(e); }
    }
    private static boolean until(java.util.function.BooleanSupplier c) throws Exception { for (int i = 0; i < 300; i++) { if (c.getAsBoolean()) return true; Thread.sleep(100); } return c.getAsBoolean(); }

    @Test public void aServiceRunsUnderTheProductionLauncherAndStopsThroughItsOwnedHandle() throws Exception {
        defineService("sleeper", "sleep 60", WorkspaceRepository.RestartPolicy.NEVER, Collections.<Integer>emptyList());
        WorkspaceServiceController c = controller(WorkspaceServiceController.Config.defaults());
        assertEquals(WorkspaceServiceController.Outcome.STARTED, onWorker(() -> c.start(workspaceId, "sleeper", lease)));
        String jobId = (String) c.status(workspaceId, "sleeper").get("jobId");
        assertTrue(until(() -> coordinator.localSnapshot(jobId).state == JobRecord.State.RUNNING));
        assertTrue("the service has a live owned child", until(() -> coordinator.ownedPid(jobId) != null));
        assertEquals(WorkspaceServiceController.Outcome.ALREADY_RUNNING, onWorker(() -> c.start(workspaceId, "sleeper", lease)));
        assertEquals(WorkspaceServiceController.Outcome.STOPPED, c.stop(workspaceId, "sleeper"));
        assertTrue(until(() -> JobRecord.isTerminal(coordinator.localSnapshot(jobId).state) && "stopped".equals(state("sleeper"))));
        assertEquals(JobRecord.State.CANCELLED, coordinator.localSnapshot(jobId).state);
        assertNull("the child is gone", coordinator.ownedPid(jobId));
        assertTrue("its end is recorded with evidence", until(() -> { JobHistoryStore.Record r = history.get(jobId); return r != null && r.finished() && r.evidence != null; }));
    }

    @Test public void aFailingServiceIsRestartedWithBackoffThenStopsInACrashLoop() throws Exception {
        defineService("flaky", "exit 3", WorkspaceRepository.RestartPolicy.ON_FAILURE, Collections.<Integer>emptyList());
        WorkspaceServiceController c = controller(new WorkspaceServiceController.Config(200, 800, 600_000, 2));
        assertEquals(WorkspaceServiceController.Outcome.STARTED, onWorker(() -> c.start(workspaceId, "flaky", lease)));
        assertTrue("two automatic restarts, then crash_loop", until(() -> "crash_loop".equals(state("flaky"))));
        assertEquals("1 start plus 2 restarts", 3, history.list(20).size());
        for (JobHistoryStore.Record r : history.list(20)) { assertEquals(Integer.valueOf(3), r.exitCode); assertEquals(JobTerminationEvidence.Reason.NORMAL_EXIT, r.evidence.reason); }
        Thread.sleep(1500);
        assertEquals("no further automatic attempts", 3, history.list(20).size());
        assertEquals(Integer.valueOf(3), c.status(workspaceId, "flaky").get("lastExitCode"));
    }

    @Test public void theSupervisorMeasuresRealMemoryGrowthAndRecordsAThresholdCrossing() throws Exception {
        // About 40 MB held in the shell for a few seconds: far below any device limit, far above the 16 MiB observation threshold.
        String script = "x=$(yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | head -c 40000000); sleep 12";
        JobResourcePolicy policy = JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.CUSTOM).memorySoftBytes(16L << 20).build();
        JobSpec spec = new JobSpec("/system/bin/sh", Arrays.asList("-c", script), "/", null, Collections.<String, String>emptyMap(), null, 0,
            JobSpec.Presentation.BACKGROUND, "mem-1", policy);
        JobCoordinator.StartResult started = onWorker(() -> coordinator.start(spec, lease));
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, started.status);
        String id = started.job.id;
        assertTrue("RSS crossed the threshold and was recorded", until(() -> { JobResourceSupervisor.Metrics m = supervisor.metrics(id); return m != null && m.memorySoftExceeded; }));
        JobResourceSupervisor.Metrics m = supervisor.metrics(id);
        assertTrue("peak RSS reflects the real allocation: " + m.peakRssBytes, m.peakRssBytes != null && m.peakRssBytes >= (16L << 20));
        boolean recorded = false;
        for (JobResourceSupervisor.Event e : supervisor.events(id)) recorded |= e.type.equals("MEMORY_SOFT_EXCEEDED");
        assertTrue(recorded);
        assertNotNull("the device reading was real", m.availableBytesAtLastSample);
        assertNotEquals(PressureTracker.Level.UNKNOWN, supervisor.pressure());
        assertEquals("a warn-only job is observed, never stopped", JobRecord.State.RUNNING, coordinator.localSnapshot(id).state);
        assertEquals(JobCoordinator.CancelStatus.ACCEPTED, coordinator.localCancel(id));
        assertTrue(until(() -> JobRecord.isTerminal(coordinator.localSnapshot(id).state)));
    }

    @Test public void aBackupRoundTripWorksOnAndroidWithSymlinksSensitiveFilesAndHostileArchives() throws Exception {
        File app = new File(home, "projects/app");
        write(new File(app, "src/main.rs"), "fn main() {}");
        write(new File(app, ".env"), "SECRET=1");
        write(new File(app, "node_modules/x.js"), "x");
        File outside = new File(base, "outside.txt"); write(outside, "private");
        Files.createSymbolicLink(new File(app, "link.txt").toPath(), outside.toPath());
        WorkspaceBackupService backup = new WorkspaceBackupService(new File(base, "backups"), WorkspaceBackupService.Limits.defaults());
        for (WorkspaceBackupService.Format format : WorkspaceBackupService.Format.values()) {
            WorkspaceBackupService.Summary s = onWorker(() -> backup.export(workspaceId, app, null, format, false, new WorkspaceBackupService.Control()));
            assertEquals(format.toString(), 1, s.files);
            assertEquals(1, s.skippedSensitive); assertTrue(s.skippedLinks >= 1); assertTrue(s.skippedExcluded >= 1);
            File target = new File(base, "restore-" + format.wireName); assertTrue(target.mkdirs());
            WorkspaceBackupService.Summary r = onWorker(() -> backup.restore(s.backupId, backup.resolve(workspaceId, s.backupId), target, null, false, new WorkspaceBackupService.Control(), null));
            assertEquals(1, r.files);
            assertEquals("fn main() {}", read(new File(target, "src/main.rs")));
            assertFalse(new File(target, ".env").exists()); assertFalse(new File(target, "link.txt").exists());
        }
        // A hostile archive: traversal, an absolute path, and a write through a symbolic link planted in the target.
        File target = new File(base, "hostile-target"); assertTrue(target.mkdirs());
        Files.createSymbolicLink(new File(target, "evil").toPath(), outside.getParentFile().toPath());
        File zip = new File(new File(base, "backups"), workspaceId + "-20250101-000000.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (String name : new String[] {"../escaped.txt", "/abs.txt", "evil/pwned.txt", "ok.txt"}) { out.putNextEntry(new ZipEntry(name)); out.write("x".getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        WorkspaceBackupService.Summary h = onWorker(() -> backup.restore(zip.getName(), zip, target, null, false, new WorkspaceBackupService.Control(), null));
        assertEquals("only the harmless file was written", 1, h.files); assertEquals(3, h.skippedUnsafe);
        assertFalse(new File(base, "escaped.txt").exists()); assertFalse(new File(base, "pwned.txt").exists());
        assertEquals("private", read(outside));
    }

    @Test public void aLoopbackProxyRelaysTrafficAndLoopbackOnlyListenersAreNotReportedAsLanExposed() throws Exception {
        try (ServerSocket target = new ServerSocket(0, 1, IPV4_LOOPBACK)) {
            // Several connections: the probes below connect and close before the relay's own connection arrives.
            Thread echo = new Thread(() -> {
                while (!target.isClosed()) {
                    try (Socket s = target.accept()) { InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream(); int b; while ((b = in.read()) != -1) { out.write(b); out.flush(); } }
                    catch (Exception ignored) { }
                }
            });
            echo.setDaemon(true); echo.start();
            assertTrue(WorkspacePortService.listeningOnLoopback(target.getLocalPort(), 500));
            assertFalse("a loopback-only listener is not reachable from the device's LAN addresses", WorkspacePortService.lanExposed(target.getLocalPort(), 500));
            int listen; try (ServerSocket s = new ServerSocket(0)) { listen = s.getLocalPort(); }
            try (WorkspacePortService ports = new WorkspacePortService()) {
                WorkspacePortService.Proxy proxy = ports.open(null, listen, target.getLocalPort(), false);
                assertFalse(proxy.lan);
                try (Socket client = new Socket(IPV4_LOOPBACK, listen)) {
                    client.setSoTimeout(5000);
                    client.getOutputStream().write("ping".getBytes()); client.getOutputStream().flush();
                    byte[] got = new byte[4]; int off = 0;
                    while (off < 4) { int n = client.getInputStream().read(got, off, 4 - off); if (n < 0) break; off += n; }
                    assertEquals("ping", new String(got));
                }
            }
        }
    }

    /** The IPv4 literal; on Android getLoopbackAddress() can be ::1, which is not where a 127.0.0.1 service listens. */
    private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();
    private static InetAddress ipv4Loopback() {
        try { return InetAddress.getByAddress(new byte[] {127, 0, 0, 1}); } catch (java.net.UnknownHostException e) { throw new IllegalStateException(e); }
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
    private static void write(File f, String text) throws Exception { f.getParentFile().mkdirs(); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
}

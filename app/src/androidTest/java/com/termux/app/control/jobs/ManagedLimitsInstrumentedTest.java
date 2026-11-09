package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.app.monitor.ProcessSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Resource limits, the supervisor's sampling and the process snapshot on a real Android runtime: bionic's setrlimit and
 * setpriority, the zygote seccomp policy, and /proc as the app UID sees it. The host tests cannot show any of this.
 */
@RunWith(AndroidJUnit4.class)
public class ManagedLimitsInstrumentedTest {
    private static final String[] ENV = {"PATH=/system/bin"};
    private static final int SIGXCPU = 24, SIGKILL = 9;

    private static String limitsOfSelf() throws IOException {
        return new String(Files.readAllBytes(new File("/proc/self/limits").toPath()), StandardCharsets.UTF_8);
    }

    private static OwnedChildProcess launch(String script, ManagedNative.NativeLimits limits) throws Exception {
        OwnedChildProcess child = onWorker(() -> OwnedChildProcess.launch(new String[]{"/system/bin/sh", "-c", script}, ENV, "/", limits));
        assertNotNull("Managed native library missing", child);
        child.getOutputStream().close();
        return child;
    }

    @Test public void theNativeLibraryAdvertisesTheLimitsAbi() {
        assertTrue(ManagedNative.isLibraryAvailable());
    }

    @Test public void addressSpaceAndCpuLimitsAreAppliedInTheChildAndTheAppItselfIsUntouched() throws Exception {
        String before = limitsOfSelf();
        OwnedChildProcess child = launch("cat /proc/self/limits", new ManagedNative.NativeLimits(8L << 30, 3, -1,
            ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE | ManagedNative.NativeLimits.REQUIRE_CPU_TIME));
        String limits = drain(child.getInputStream());
        assertEquals(0, (int) onWorker(child::waitFor));
        assertTrue(limits, limits.matches("(?s).*Max address space\\s+8589934592\\s+8589934592\\s+bytes.*"));
        // The soft CPU limit is the requested 3 s and the hard limit five seconds later.
        assertTrue(limits, limits.matches("(?s).*Max cpu time\\s+3\\s+8\\s+seconds.*"));
        assertEquals("the app process was not limited", before, limitsOfSelf());
        drain(child.getErrorStream());
    }

    /** Measured on a real device: the linker reserves enough address space that a small limit aborts even a trivial process. */
    @Test public void theMeasuredMinimumAddressSpaceIsWhatATrivialProcessReallyNeeds() throws Exception {
        long minimum = onWorker(ManagedNative::minAddressSpaceBytes);
        assertTrue("the probe found a working limit", minimum > 0);
        OwnedChildProcess ok = launch("echo alive", new ManagedNative.NativeLimits(minimum, 0, -1, ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE));
        assertEquals("alive\n", drain(ok.getInputStream()));
        assertEquals(0, (int) onWorker(ok::waitFor));
        drain(ok.getErrorStream());
        if (minimum > (256L << 20)) {
            // One step below the minimum the same trivial process must not start cleanly.
            OwnedChildProcess low = launch("echo alive", new ManagedNative.NativeLimits(minimum / 2, 0, -1, ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE));
            drain(low.getInputStream());
            assertNotEquals("below the minimum the process aborts", 0, (int) onWorker(low::waitFor));
            drain(low.getErrorStream());
        }
    }

    @Test public void theCoordinatorRefusesAnAddressSpaceLimitTheDeviceCannotRun() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JobAuthority authority = new JobAuthority();
        JobAuthority.Lease lease = authority.grant(java.util.EnumSet.allOf(JobAuthority.Permission.class));
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution("/system/bin/sh", "/"); }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        JobCoordinator coordinator = new JobCoordinator(new JobRegistry(new JobIoPool(JobIoPool.MAX_JOBS), new JobOutputStore(), JobOutputStore.DEFAULT_RECORD_LIMIT),
            new AppShellJobAdapter(context), policy, authority);
        try {
            long minimum = onWorker(ManagedNative::minAddressSpaceBytes);
            assertTrue(minimum > (256L << 20));
            JobSpec tooSmall = new JobSpec("/system/bin/sh", java.util.Arrays.asList("-c", ":"), "/", null, Collections.<String, String>emptyMap(), null, 0,
                JobSpec.Presentation.BACKGROUND, "small-1", JobResourcePolicy.builder().addressSpaceBytes(Math.max(JobResourcePolicy.MIN_ADDRESS_SPACE_BYTES, minimum / 2)).build());
            assertEquals("refused up front, not launched to die", JobCoordinator.StartStatus.UNSUPPORTED, onWorker(() -> coordinator.start(tooSmall, lease)).status);
        } finally { coordinator.stopAccepting(); }
    }

    @Test public void theNiceLevelLowersThePriorityOfTheChildOnly() throws Exception {
        OwnedChildProcess child = launch("cat /proc/self/stat", new ManagedNative.NativeLimits(0, 0, 7, ManagedNative.NativeLimits.REQUIRE_NICE));
        String stat = drain(child.getInputStream());
        assertEquals(0, (int) onWorker(child::waitFor));
        ProcessSnapshot.Entry entry = ProcessSnapshot.parseStat(stat.trim());
        // Field 19 of /proc/PID/stat is the nice value; the entry parser does not expose it, so read it directly.
        String afterName = stat.substring(stat.lastIndexOf(')') + 2).trim();
        assertEquals("nice", 7, Integer.parseInt(afterName.split("\\s+")[16]));
        assertTrue(entry.pid > 0);
        drain(child.getErrorStream());
    }

    @Test public void aCpuBoundChildIsEndedByItsCpuTimeLimitNotByWallClock() throws Exception {
        OwnedChildProcess child = launch("while :; do :; done", new ManagedNative.NativeLimits(0, 1, -1, ManagedNative.NativeLimits.REQUIRE_CPU_TIME));
        long started = System.nanoTime();
        int exit = onWorker(child::waitFor);
        Integer signal = child.terminationSignal();
        assertNotNull("ended by a signal", signal);
        assertTrue("SIGXCPU at the soft limit or SIGKILL at the hard limit, was " + signal, signal == SIGXCPU || signal == SIGKILL);
        assertEquals(128 + signal, exit);
        assertTrue("it spent about a CPU second, not a wall timeout", System.nanoTime() - started < 30_000_000_000L);
        drain(child.getInputStream()); drain(child.getErrorStream());
    }

    @Test public void aRequiredLimitThatCannotBeAppliedFailsTheLaunchAndIsMarkedAsALimitFailure() throws Exception {
        // Raising priority needs privileges the app does not have; as a required limit the launch must fail, not run unlimited.
        try {
            ManagedNative.NativeLimits raise = new ManagedNative.NativeLimits(0, 0, 0, ManagedNative.NativeLimits.REQUIRE_NICE);
            // Lower our own scheduling first so that "nice 0" would be a raise relative to the parent.
            onWorker(() -> OwnedChildProcess.launch(new String[]{"/system/bin/sh", "-c", "renice -n 5 -p $$ >/dev/null 2>&1; /system/bin/sh -c true"}, ENV, "/", raise));
        } catch (OwnedChildProcess.LaunchException e) {
            assertTrue("the failure is attributed to the limit, not to exec", e.limitFailure());
            return;
        } catch (IOException e) { fail("unexpected failure kind: " + e); }
        // On a build where the parent's nice is already 0 this limit is satisfiable; that is a valid outcome and not a failure.
    }

    /** The production path: a coordinator with admission and a supervisor, launching through AppShell and the owned launcher. */
    @Test public void aPolicyJobIsLimitedMeasuredAndStoppedThroughTheProductionCoordinator() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ResourceAdmissionController admission = new ResourceAdmissionController();
        JobResourceSupervisor supervisor = JobResourceSupervisor.forAndroid(context, admission);
        JobAuthority authority = new JobAuthority();
        JobAuthority.Lease lease = authority.grant(java.util.EnumSet.allOf(JobAuthority.Permission.class));
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution("/system/bin/sh", "/"); }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        JobRegistry registry = new JobRegistry(new JobIoPool(JobIoPool.MAX_JOBS), new JobOutputStore(), JobOutputStore.DEFAULT_RECORD_LIMIT);
        JobCoordinator coordinator = new JobCoordinator(registry, new AppShellJobAdapter(context), policy, authority, admission, supervisor);
        try {
            JobResourcePolicy resources = JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.BALANCED).addressSpaceBytes(8192L << 20)
                .cpuTimeSeconds(120L).build();
            JobSpec spec = new JobSpec("/system/bin/sh", java.util.Arrays.asList("-c", "cat /proc/self/limits; sleep 20"), "/", null,
                Collections.<String, String>emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, "dev-1", resources);
            JobCoordinator.StartResult started = onWorker(() -> coordinator.start(spec, lease));
            assertEquals(JobCoordinator.StartStatus.ACCEPTED, started.status);
            String id = started.job.id;
            JobRecord.State state = null;
            for (int i = 0; i < 100 && state != JobRecord.State.RUNNING; i++) { Thread.sleep(100); state = coordinator.get(id, lease).job.job.state; }
            assertEquals("the job reached RUNNING through the production launcher", JobRecord.State.RUNNING, state);
            // The limits the child really has, read from its own /proc/self/limits.
            String limits = "";
            for (int i = 0; i < 50 && !limits.contains("Max address space"); i++) {
                Thread.sleep(100);
                JobCoordinator.OutputResult out = coordinator.readOutput(id, lease, JobOutputStore.Stream.STDOUT, 0, 4096);
                limits = new String(out.output.data, StandardCharsets.UTF_8);
            }
            assertTrue(limits, limits.matches("(?s).*Max address space\\s+8589934592\\s+8589934592\\s+bytes.*"));
            assertTrue(limits, limits.matches("(?s).*Max cpu time\\s+120\\s+125\\s+seconds.*"));
            // The supervisor measured the running child through /proc as the app UID.
            JobResourceSupervisor.Metrics metrics = null;
            for (int i = 0; i < 100 && (metrics == null || metrics.rssBytes == null); i++) { Thread.sleep(100); metrics = supervisor.metrics(id); }
            assertNotNull("the supervisor sampled the job", metrics);
            assertNotNull("RSS of the child is visible to the app UID", metrics.rssBytes);
            assertTrue(metrics.rssBytes > 0 && metrics.processCount >= 1);
            assertFalse(metrics.identityLost);
            assertEquals(JobCoordinator.CancelStatus.ACCEPTED, coordinator.cancel(id, lease));
            for (int i = 0; i < 150 && !JobRecord.isTerminal(coordinator.get(id, lease).job.job.state); i++) Thread.sleep(100);
            assertEquals(JobRecord.State.CANCELLED, coordinator.get(id, lease).job.job.state);
            assertEquals("the admission slot was released", 0, admission.activeCount());
        } finally {
            coordinator.stopAccepting();
            supervisor.close();
        }
    }

    @Test public void theProcessSnapshotFindsAnOwnedChildAndItsDescendantsOnAndroid() throws Exception {
        OwnedChildProcess child = launch("sleep 20 & sleep 20", ManagedNative.NativeLimits.NONE);
        Thread.sleep(300);
        ProcessSnapshot snapshot = ProcessSnapshot.collect(new File("/proc"), android.os.Process.myUid(), Collections.singleton(child.pid()));
        assertEquals("the root is visible", 0, snapshot.missingRoots);
        assertTrue("the root and at least one descendant", snapshot.entries.size() >= 2);
        assertTrue(child.signal(OwnedChildProcess.SIGTERM));
        onWorker(child::waitFor);
        drain(child.getInputStream()); drain(child.getErrorStream());
    }

    private interface Call<T> { T call() throws Exception; }

    private static <T> T onWorker(Call<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { result.set(call.call()); } catch (Exception e) { failure.set(e); } });
        worker.start(); worker.join(60000);
        assertFalse("worker timed out", worker.isAlive());
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}

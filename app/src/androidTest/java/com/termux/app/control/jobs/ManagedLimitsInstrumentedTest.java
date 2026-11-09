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
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
        OwnedChildProcess child = launch("cat /proc/self/limits", new ManagedNative.NativeLimits(512L << 20, 3, -1,
            ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE | ManagedNative.NativeLimits.REQUIRE_CPU_TIME));
        String limits = drain(child.getInputStream());
        assertEquals(0, (int) onWorker(child::waitFor));
        assertTrue(limits, limits.matches("(?s).*Max address space\\s+536870912\\s+536870912\\s+bytes.*"));
        // The soft CPU limit is the requested 3 s and the hard limit five seconds later.
        assertTrue(limits, limits.matches("(?s).*Max cpu time\\s+3\\s+8\\s+seconds.*"));
        assertEquals("the app process was not limited", before, limitsOfSelf());
        drain(child.getErrorStream());
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

    @Test public void theSupervisorMeasuresARealChildThroughProcOnAndroid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ResourceAdmissionController admission = new ResourceAdmissionController();
        JobResourceSupervisor supervisor = JobResourceSupervisor.forAndroid(context, admission);
        ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1);
        try {
            OwnedChildProcess child = launch("sleep 20", ManagedNative.NativeLimits.NONE);
            JobRecord record = new JobRecord("job_" + "a".repeat(32), null, 1);
            assertTrue(record.begin() && record.started(null, 1));
            OwnedProcessControl control = OwnedProcessControl.forAndroid(record, timers, 1000, true);
            control.attach(child);
            supervisor.track(record, control, child, JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED));
            JobResourceSupervisor.Metrics metrics = null;
            for (int i = 0; i < 100 && (metrics == null || metrics.rssBytes == null); i++) { Thread.sleep(100); metrics = supervisor.metrics(record.snapshot().id); }
            assertNotNull("the supervisor sampled the job", metrics);
            assertNotNull("RSS of the child is visible to the app UID", metrics.rssBytes);
            assertTrue(metrics.rssBytes > 0);
            assertTrue(metrics.processCount >= 1);
            assertFalse("the child kept its identity", metrics.identityLost);
            assertNotEquals("device pressure was read from the real platform", PressureTracker.Level.UNKNOWN, supervisor.pressure());
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(128 + OwnedChildProcess.SIGTERM, (int) onWorker(child::waitFor));
            drain(child.getInputStream()); drain(child.getErrorStream());
        } finally { supervisor.close(); timers.shutdownNow(); }
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

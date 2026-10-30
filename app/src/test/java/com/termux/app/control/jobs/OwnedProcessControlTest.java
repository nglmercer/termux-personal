package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class OwnedProcessControlTest {
    @Test public void cancellationDuringLaunchUsesTheReturnedHandle() throws Exception {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try (JobRegistry registry = new JobRegistry(new JobIoPool(1), new JobOutputStore(16, 1, 32), 1)) {
            JobSpec spec = new JobSpec("echo", Collections.emptyList(), null, null, Collections.emptyMap(), null, 0,
                JobSpec.Presentation.BACKGROUND, "id");
            JobRegistry.Entry entry = registry.admit(spec, 1, 1000).entry;
            CountDownLatch launching = new CountDownLatch(1), resume = new CountDownLatch(1);
            AtomicReference<AppShellJobAdapter.Completion> completion = new AtomicReference<>();
            FakeProcess process = new FakeProcess();
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, callback) -> {
                capture.discardBeforeLaunch(); completion.set(callback); launching.countDown(); await(resume); return process;
            });
            OwnedProcessControl control = new OwnedProcessControl(entry.record(), timer, 1000, Process::destroy, p -> fail("unexpected force"));
            Thread launch = new Thread(() -> adapter.launch(entry, spec, "/bin/echo", "/home", control));
            launch.start(); assertTrue(launching.await(2, TimeUnit.SECONDS));
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(OwnedProcessControl.Status.PENDING_HANDLE, control.status());
            resume.countDown(); launch.join(2000); assertFalse(launch.isAlive());
            assertEquals(1, process.destroyCalls.get());
            assertEquals(JobRecord.State.CANCELLING, entry.record().snapshot().state);
            process.running = false; completion.get().finished(143, false);
            assertEquals(JobRecord.State.CANCELLED, entry.record().snapshot().state);
            assertNull(entry.record().snapshot().exitCode);
            assertEquals(JobRecord.StopReason.CANCEL, entry.record().snapshot().stopReason);
        } finally { timer.shutdownNow(); }
    }

    @Test public void escalationUsesSameOwnedHandleOnlyWhileStillRunning() throws Exception {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeProcess process = new FakeProcess(); CountDownLatch forced = new CountDownLatch(1);
            OwnedProcessControl control = new OwnedProcessControl(job, timer, 20, Process::destroy, p -> { assertSame(process, p); forced.countDown(); });
            control.attach(process); assertTrue(job.started(null, 1));
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.TIMEOUT));
            assertEquals(OwnedProcessControl.Request.ALREADY_REQUESTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertTrue(forced.await(2, TimeUnit.SECONDS));
            assertEquals(1, process.destroyCalls.get());
            control.completed();
        } finally { timer.shutdownNow(); }
    }

    @Test public void completedOrAlreadyExitedProcessIsNeverSignalled() throws Exception {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeProcess process = new FakeProcess(); process.running = false;
            OwnedProcessControl control = new OwnedProcessControl(job, timer, 20, Process::destroy, p -> fail("unexpected force"));
            control.attach(process);
            assertEquals(OwnedProcessControl.Request.FINISHED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(0, process.destroyCalls.get());
            assertNull(job.snapshot().stopReason);
            control.completed();
        } finally { timer.shutdownNow(); }
    }

    @Test public void unavailableForceIsReportedAndQueuedJobsAreNotPretendedStopped() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1);
            OwnedProcessControl control = new OwnedProcessControl(job, timer, 20, Process::destroy, null);
            assertEquals(OwnedProcessControl.Request.NOT_STARTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertTrue(job.begin()); FakeProcess process = new FakeProcess(); control.attach(process);
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(OwnedProcessControl.Status.FORCE_UNAVAILABLE, control.status());
            control.completed();
        } finally { timer.shutdownNow(); }
    }

    @Test public void androidRefusesUnverifiedSignalsWithoutChangingTheRecord() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeProcess process = new FakeProcess();
            OwnedProcessControl control = OwnedProcessControl.forAndroid(job, timer, 20, false);
            control.attach(process);
            assertEquals(OwnedProcessControl.Request.UNSUPPORTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(OwnedProcessControl.Status.STOP_UNAVAILABLE, control.status());
            assertNull(job.snapshot().stopReason);
            assertEquals(0, process.destroyCalls.get());
        } finally { timer.shutdownNow(); }
    }

    @Test public void ownedAndroidControlTerminatesThenKillsTheSameChild() throws Exception {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeOps ops = new FakeOps();
            OwnedChildProcess child = ops.child(4242);
            OwnedProcessControl control = OwnedProcessControl.forAndroid(job, timer, 20, true);
            assertTrue(control.canStop());
            control.attach(child); assertTrue(job.started(null, 1));
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertTrue(ops.killed.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList(OwnedChildProcess.SIGTERM, OwnedChildProcess.SIGKILL), ops.signals);
            assertEquals(4242, ops.signalledPid.get());
            control.completed();
        } finally { timer.shutdownNow(); }
    }

    @Test public void ownedAndroidControlNeverSignalsAForeignHandle() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeProcess foreign = new FakeProcess();
            OwnedProcessControl control = OwnedProcessControl.forAndroid(job, timer, 20, true);
            control.attach(foreign);
            assertEquals(OwnedProcessControl.Request.ACCEPTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(OwnedProcessControl.Status.FAILED, control.status());
            assertEquals(0, foreign.destroyCalls.get());
            control.completed();
        } finally { timer.shutdownNow(); }
    }

    @Test public void timeoutRequestsAStopAndCompletionCancelsIt() throws Exception {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
        try {
            JobRecord job = new JobRecord("id", null, 1); assertTrue(job.begin());
            FakeOps ops = new FakeOps();
            OwnedProcessControl control = OwnedProcessControl.forAndroid(job, timer, 1000, true);
            assertTrue(control.scheduleTimeout(20));
            assertFalse("a timeout is armed once", control.scheduleTimeout(20));
            control.attach(ops.child(7)); assertTrue(job.started(null, 1));
            assertTrue(ops.terminated.await(2, TimeUnit.SECONDS));
            assertEquals(JobRecord.StopReason.TIMEOUT, job.snapshot().stopReason);
            control.completed();

            JobRecord quick = new JobRecord("quick", null, 1); assertTrue(quick.begin());
            OwnedProcessControl finished = OwnedProcessControl.forAndroid(quick, timer, 1000, true);
            assertTrue(finished.scheduleTimeout(50));
            finished.completed();
            Thread.sleep(100);
            assertNull("a completed job is not stopped by its timeout", quick.snapshot().stopReason);
            assertFalse(OwnedProcessControl.forAndroid(quick, timer, 1000, false).scheduleTimeout(50));
        } finally { timer.shutdownNow(); }
    }

    @Test public void ownedChildIsNotSignalledOnceItsExitIsObserved() throws Exception {
        FakeOps ops = new FakeOps();
        OwnedChildProcess child = ops.child(99);
        assertThrows(IllegalThreadStateException.class, child::exitValue);
        assertTrue(child.signal(OwnedChildProcess.SIGINT));
        ops.exitSignal = 15; ops.exited.countDown();
        assertEquals(143, child.waitFor());
        assertEquals(Integer.valueOf(15), child.terminationSignal());
        assertEquals(143, child.exitValue());
        assertEquals(1, ops.reaps.get());
        assertFalse(child.signal(OwnedChildProcess.SIGKILL));
        assertEquals(1, ops.signals.size());
        assertEquals(143, child.waitFor());
        assertEquals("a child is reaped once", 1, ops.reaps.get());
        assertThrows(IllegalArgumentException.class, () -> child.signal(1));
    }

    @Test public void concurrentWaitersShareOneReap() throws Exception {
        FakeOps ops = new FakeOps();
        OwnedChildProcess child = ops.child(5);
        AtomicInteger first = new AtomicInteger(-1), second = new AtomicInteger(-1);
        Thread a = new Thread(() -> first.set(child.waitFor())), b = new Thread(() -> second.set(child.waitFor()));
        a.start(); b.start();
        Thread.sleep(50);
        ops.exitCode = 3; ops.exited.countDown();
        a.join(2000); b.join(2000);
        assertEquals(3, first.get()); assertEquals(3, second.get());
        assertEquals(1, ops.reaps.get());
        assertNull(child.terminationSignal());
    }

    private static final class FakeOps implements OwnedChildProcess.Ops {
        final java.util.List<Integer> signals = Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicInteger reaps = new AtomicInteger(), signalledPid = new AtomicInteger();
        final CountDownLatch exited = new CountDownLatch(1), terminated = new CountDownLatch(1), killed = new CountDownLatch(1);
        volatile int exitCode, exitSignal;
        OwnedChildProcess child(int pid) {
            return new OwnedChildProcess(pid, this, new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]));
        }
        @Override public int waitExit(int pid) { await(exited); return 0; }
        @Override public int reap(int pid, int[] out) {
            reaps.incrementAndGet();
            out[0] = exitSignal == 0 ? 1 : 0; out[1] = exitCode; out[2] = exitSignal == 0 ? 0 : 1; out[3] = exitSignal;
            return 0;
        }
        @Override public int signal(int pid, int signal) {
            signals.add(signal); signalledPid.set(pid);
            if (signal == OwnedChildProcess.SIGTERM) terminated.countDown();
            if (signal == OwnedChildProcess.SIGKILL) killed.countDown();
            return 0;
        }
    }

    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static final class FakeProcess extends Process {
        volatile boolean running = true;
        final AtomicInteger destroyCalls = new AtomicInteger();
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { if (running) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroyCalls.incrementAndGet(); }
    }
}

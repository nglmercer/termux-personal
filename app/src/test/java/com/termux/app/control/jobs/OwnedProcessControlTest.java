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
            assertEquals(JobRecord.State.EXITED, entry.record().snapshot().state);
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
            OwnedProcessControl control = OwnedProcessControl.forAndroid(job, timer, 20);
            control.attach(process);
            assertEquals(OwnedProcessControl.Request.UNSUPPORTED, control.requestStop(JobRecord.StopReason.CANCEL));
            assertEquals(OwnedProcessControl.Status.STOP_UNAVAILABLE, control.status());
            assertNull(job.snapshot().stopReason);
            assertEquals(0, process.destroyCalls.get());
        } finally { timer.shutdownNow(); }
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

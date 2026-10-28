package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JobCoordinatorTest {
    private static JobSpec spec(String id, long timeout) {
        return new JobSpec("echo", Collections.singletonList("x"), null, null, Collections.emptyMap(), null, timeout,
            JobSpec.Presentation.BACKGROUND, id);
    }
    private static JobRegistry registry() { return new JobRegistry(new JobIoPool(2), new JobOutputStore(16, 4, 128), 4); }

    @Test public void queuedCancellationNeverLaunchesAndReplayReturnsSameRecord() throws Exception {
        JobRegistry registry = registry();
        CountDownLatch launching = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, callback) -> {
            launches.incrementAndGet(); launching.countDown(); await(release);
            capture.discardBeforeLaunch(); callback.finished(0, false); return new FakeProcess();
        });
        JobCoordinator coordinator = new JobCoordinator(registry, adapter, (job, epoch) -> new JobCoordinator.Resolution("/bin/echo", "/home"), 1);
        try {
            JobCoordinator.StartResult first = coordinator.start(spec("one", 0), 3);
            assertEquals(JobCoordinator.StartStatus.ACCEPTED, first.status);
            assertTrue(launching.await(2, TimeUnit.SECONDS));
            JobCoordinator.StartResult queued = coordinator.start(spec("two", 0), 3);
            assertEquals(JobRecord.State.QUEUED, queued.job.state);
            assertEquals(JobCoordinator.CancelStatus.NOT_FOUND, coordinator.cancel(queued.job.id, 4));
            assertEquals(JobCoordinator.CancelStatus.ACCEPTED, coordinator.cancel(queued.job.id, 3));
            assertEquals(JobRecord.State.CANCELLED, registry.get(queued.job.id, 3).record().snapshot().state);
            JobCoordinator.StartResult replay = coordinator.start(spec("two", 0), 3);
            assertEquals(JobCoordinator.StartStatus.REPLAY, replay.status); assertEquals(queued.job.id, replay.job.id);
            release.countDown();
            waitForTerminal(registry.get(first.job.id, 3).record());
            assertEquals(1, launches.get());
        } finally { release.countDown(); coordinator.stopAccepting(); closeEventually(coordinator); }
    }

    @Test public void revokedPolicyPreventsQueuedLaunch() throws Exception {
        JobRegistry registry = registry(); AtomicBoolean allowed = new AtomicBoolean(true);
        CountDownLatch launching = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, callback) -> {
            launches.incrementAndGet(); launching.countDown(); await(release);
            capture.discardBeforeLaunch(); callback.finished(0, false); return new FakeProcess();
        });
        JobCoordinator coordinator = new JobCoordinator(registry, adapter,
            (job, epoch) -> allowed.get() ? new JobCoordinator.Resolution("/bin/echo", "/home") : null, 1);
        try {
            JobCoordinator.StartResult first = coordinator.start(spec("one", 0), 1);
            assertTrue(launching.await(2, TimeUnit.SECONDS));
            JobCoordinator.StartResult second = coordinator.start(spec("two", 0), 1);
            allowed.set(false); release.countDown();
            waitForTerminal(registry.get(second.job.id, 1).record());
            assertEquals(JobRecord.State.FAILED, registry.get(second.job.id, 1).record().snapshot().state);
            assertEquals(1, launches.get());
            assertEquals(JobCoordinator.StartStatus.DENIED, coordinator.start(spec("one", 0), 1).status);
            waitForTerminal(registry.get(first.job.id, 1).record());
        } finally { release.countDown(); coordinator.stopAccepting(); closeEventually(coordinator); }
    }

    @Test public void unsupportedTimeoutDoesNotAdmitAndRunningJobRemainsOwnedAtShutdown() throws Exception {
        JobRegistry registry = registry(); AtomicReference<AppShellJobAdapter.Completion> callback = new AtomicReference<>();
        CountDownLatch launched = new CountDownLatch(1);
        AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, done) -> {
            capture.discardBeforeLaunch(); callback.set(done); launched.countDown(); return new FakeProcess();
        });
        JobCoordinator coordinator = new JobCoordinator(registry, adapter, (job, epoch) -> new JobCoordinator.Resolution("/bin/echo", "/home"));
        try {
            assertEquals(JobCoordinator.StartStatus.UNSUPPORTED, coordinator.start(spec("timeout", 100), 1).status);
            assertTrue(registry.list(1).isEmpty());
            JobCoordinator.StartResult running = coordinator.start(spec("run", 0), 1);
            assertTrue(launched.await(2, TimeUnit.SECONDS));
            assertEquals(JobCoordinator.CancelStatus.UNSUPPORTED, coordinator.cancel(running.job.id, 1));
            coordinator.stopAccepting(); assertFalse(coordinator.closeIfIdle());
            callback.get().finished(9, false);
            assertEquals(Integer.valueOf(9), registry.get(running.job.id, 1).record().snapshot().exitCode);
            closeEventually(coordinator);
        } finally { coordinator.stopAccepting(); if (callback.get() != null) callback.get().finished(9, false); closeEventually(coordinator); }
    }

    private static void waitForTerminal(JobRecord record) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!JobRecord.isTerminal(record.snapshot().state) && System.nanoTime() < deadline) Thread.yield();
        assertTrue(JobRecord.isTerminal(record.snapshot().state));
    }
    private static void closeEventually(JobCoordinator coordinator) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!coordinator.closeIfIdle() && System.nanoTime() < deadline) Thread.yield();
        assertTrue(coordinator.closeIfIdle());
    }
    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static final class FakeProcess extends Process {
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { throw new IllegalThreadStateException(); }
        @Override public void destroy() { fail("unexpected signal"); }
    }
}

package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class JobRecordTest {
    @Test public void immediateExitCannotBeOverwrittenByLateLaunch() {
        JobRecord job = new JobRecord("job", null, 7);
        assertTrue(job.begin());
        assertTrue(job.complete(JobRecord.State.EXITED, 3, null, 10));
        assertFalse(job.started(123, 11));
        assertFalse(job.complete(JobRecord.State.EXITED, 0, null, 12));
        assertEquals(Integer.valueOf(3), job.snapshot().exitCode);
    }

    @Test public void stopRequestDoesNotTurnNaturalExitIntoCancellation() {
        JobRecord job = new JobRecord("job", "ws", 4);
        assertTrue(job.begin());
        assertTrue(job.requestStop(JobRecord.StopReason.CANCEL));
        assertTrue(job.started(123, 1));
        assertEquals(JobRecord.State.CANCELLING, job.snapshot().state);
        assertTrue(job.complete(JobRecord.State.EXITED, 0, null, 2));
        assertEquals(JobRecord.State.EXITED, job.snapshot().state);
        assertFalse(job.requestStop(JobRecord.StopReason.TIMEOUT));
    }

    @Test public void terminalRaceHasExactlyOneWinner() throws Exception {
        JobRecord job = new JobRecord("job", null, 0);
        assertTrue(job.begin());
        assertTrue(job.requestStop(JobRecord.StopReason.TIMEOUT));
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        Thread exit = new Thread(() -> { await(gate); if (job.complete(JobRecord.State.EXITED, 0, null, 2)) wins.incrementAndGet(); });
        Thread timeout = new Thread(() -> { await(gate); if (job.complete(JobRecord.State.TIMED_OUT, null, null, 2)) wins.incrementAndGet(); });
        exit.start(); timeout.start(); gate.countDown(); exit.join(); timeout.join();
        assertEquals(1, wins.get());
        assertTrue(JobRecord.isTerminal(job.snapshot().state));
    }

    @Test public void cancellationRequiresMatchingRequestAndFailureIsStructured() {
        JobRecord job = new JobRecord("job", null, 0);
        assertThrows(IllegalStateException.class, () -> job.complete(JobRecord.State.CANCELLED, null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> job.complete(JobRecord.State.FAILED, null, null, 1));
        assertTrue(job.complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, 2));
        assertEquals(JobRecord.Failure.START_FAILED, job.snapshot().failure);
    }

    @Test public void captureFailureCanRetainObservedExitCode() {
        JobRecord job = new JobRecord("job", null, 0);
        assertTrue(job.begin());
        assertTrue(job.complete(JobRecord.State.FAILED, 7, JobRecord.Failure.CAPTURE_FAILED, 1));
        assertEquals(Integer.valueOf(7), job.snapshot().exitCode);
        assertEquals(JobRecord.Failure.CAPTURE_FAILED, job.snapshot().failure);
    }

    @Test public void startupFailureCannotInventExitCode() {
        JobRecord job = new JobRecord("job", null, 0);
        assertThrows(IllegalArgumentException.class,
            () -> job.complete(JobRecord.State.FAILED, 0, JobRecord.Failure.START_FAILED, 1));
        assertEquals(JobRecord.State.QUEUED, job.snapshot().state);
    }

    @Test public void duplicateFailureCannotReplaceObservedExit() {
        JobRecord job = new JobRecord("job", null, 0);
        assertTrue(job.begin());
        assertTrue(job.complete(JobRecord.State.EXITED, 9, null, 10));
        assertFalse(job.complete(JobRecord.State.FAILED, 0, JobRecord.Failure.CAPTURE_FAILED, 11));
        assertEquals(JobRecord.State.EXITED, job.snapshot().state);
        assertEquals(Integer.valueOf(9), job.snapshot().exitCode);
        assertEquals(Long.valueOf(10), job.snapshot().endedAt);
    }

    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}

package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class JobRegistryTest {
    private static JobSpec spec(String id, String argument) {
        return new JobSpec("echo", Collections.singletonList(argument), null, null,
            Collections.emptyMap(), new byte[] { 1, 2 }, 0, JobSpec.Presentation.BACKGROUND, id);
    }
    private static JobRegistry registry(int workers, int records) {
        return new JobRegistry(new JobIoPool(workers), new JobOutputStore(16, records, 32 * records), records);
    }

    @Test public void duplicatesConflictAndEpochsAreIsolated() {
        try (JobRegistry registry = registry(2, 2)) {
            JobRegistry.Admission first = registry.admit(spec("request", "a"), 1, 1000);
            assertEquals(JobRegistry.Status.ACCEPTED, first.status);
            JobRegistry.Admission replay = registry.admit(spec("request", "a"), 1, 1000);
            assertEquals(JobRegistry.Status.REPLAY, replay.status);
            assertSame(first.entry, replay.entry);
            assertEquals(JobRegistry.Status.CONFLICT, registry.admit(spec("request", "b"), 1, 1000).status);
            assertNull(registry.get(first.entry.record().snapshot().id, 2));
            assertEquals(JobRegistry.Status.CONFLICT, registry.admit(spec("request", "a"), 2, 1000).status);
            assertEquals(JobRegistry.Status.ACCEPTED, registry.admit(spec("another", "b"), 2, 1000).status);
            assertEquals(1, registry.list(1).size());
            assertEquals(1, registry.list(2).size());
            assertEquals(JobRegistry.Status.REPLAY, registry.admit(spec("request", "a"), 1, 1000).status);
            assertEquals(JobRegistry.Status.LIMIT_REACHED, registry.admit(spec("new", "a"), 1, 1000).status);
        }
    }

    @Test public void releasedPipeReservationAllowsAnotherJobButRecordsRemainBounded() {
        try (JobRegistry registry = registry(1, 2)) {
            JobRegistry.Entry first = registry.admit(spec("one", "a"), 1, 1000).entry;
            assertEquals(JobRegistry.Status.LIMIT_REACHED, registry.admit(spec("two", "a"), 1, 1000).status);
            first.capture().discardBeforeLaunch();
            assertEquals(JobRegistry.Status.ACCEPTED, registry.admit(spec("two", "a"), 1, 1000).status);
            assertEquals(JobRegistry.Status.LIMIT_REACHED, registry.admit(spec("three", "a"), 1, 1000).status);
            assertEquals(JobRegistry.Status.REPLAY, registry.admit(spec("one", "a"), 1, 1000).status);
        }
    }

    /** Finishes a record the way the coordinator does, then lets its capture release. */
    private static void finish(JobRegistry.Entry entry) {
        entry.capture().discardBeforeLaunch();
        assertTrue(entry.record().begin());
        entry.record().complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, 5);
        entry.output().finish();
    }

    @Test public void finishedRecordsAreEvictedSoCapacityIsNeverPermanentlyExhausted() {
        try (JobRegistry registry = registry(2, 3)) {
            for (int i = 0; i < 40; i++) {
                JobRegistry.Admission admission = registry.admit(spec("job-" + i, "a"), 1, 1000);
                assertEquals("job " + i, JobRegistry.Status.ACCEPTED, admission.status);
                finish(admission.entry);
            }
            assertTrue(registry.retainedEntries().size() <= 3);
        }
    }

    @Test public void runningAndQueuedRecordsAreNeverEvicted() {
        try (JobRegistry registry = registry(4, 2)) {
            JobRegistry.Entry running = registry.admit(spec("a", "x"), 1, 1000).entry;
            assertTrue(running.record().begin());
            JobRegistry.Entry queued = registry.admit(spec("b", "x"), 1, 1000).entry;
            assertEquals(JobRegistry.Status.LIMIT_REACHED, registry.admit(spec("c", "x"), 1, 1000).status);
            assertSame(running, registry.getRetained(running.record().snapshot().id));
            assertSame(queued, registry.getRetained(queued.record().snapshot().id));
        }
    }

    @Test public void aFinishedRecordIsKeptUntilItsPipeWorkersHaveStopped() {
        try (JobRegistry registry = registry(1, 1)) {
            JobRegistry.Entry first = registry.admit(spec("a", "x"), 1, 1000).entry;
            assertTrue(first.record().begin());
            first.record().complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, 5);
            // Terminal, but the reserved pipe workers are still held: nothing may be evicted yet.
            assertEquals(JobRegistry.Status.LIMIT_REACHED, registry.admit(spec("b", "x"), 1, 1000).status);
            first.capture().discardBeforeLaunch();
            assertEquals(JobRegistry.Status.ACCEPTED, registry.admit(spec("b", "x"), 1, 1000).status);
        }
    }

    @Test public void aRetryAfterEvictionReturnsTheOriginalFinalStateNotASecondExecution() {
        try (JobRegistry registry = registry(2, 1)) {
            JobRegistry.Entry first = registry.admit(spec("key", "a"), 1, 1000).entry;
            String id = first.record().snapshot().id;
            finish(first);
            assertEquals(JobRegistry.Status.ACCEPTED, registry.admit(spec("other", "b"), 1, 1000).status); // evicts "key"
            assertNull("the record and its output are gone", registry.getRetained(id));
            JobRegistry.Admission replay = registry.admit(spec("key", "a"), 1, 1000);
            assertEquals(JobRegistry.Status.REPLAY, replay.status);
            assertNull(replay.entry);
            assertEquals(id, replay.snapshot().id);
            assertEquals(JobRecord.State.FAILED, replay.snapshot().state);
            assertEquals(JobRegistry.Status.REPLAY, registry.replay(spec("key", "a"), 1).status);
            assertEquals("a different request under the key still conflicts", JobRegistry.Status.CONFLICT, registry.admit(spec("key", "b"), 1, 1000).status);
            assertEquals("another consent epoch still conflicts", JobRegistry.Status.CONFLICT, registry.admit(spec("key", "a"), 2, 1000).status);
        }
    }

    @Test public void aReplayKeyIsForgottenAfterTheRetentionWindow() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(0);
        try (JobRegistry registry = new JobRegistry(new JobIoPool(2), new JobOutputStore(16, 1, 32), 1, clock::get)) {
            finish(registry.admit(spec("key", "a"), 1, 1000).entry);
            finish(registry.admit(spec("other", "b"), 1, 1000).entry); // evicts "key"
            clock.set(JobRegistry.REPLAY_RETENTION_MILLIS - 1);
            assertEquals(JobRegistry.Status.REPLAY, registry.replay(spec("key", "a"), 1).status);
            clock.set(JobRegistry.REPLAY_RETENTION_MILLIS + 1);
            assertNull(registry.replay(spec("key", "a"), 1));
        }
    }

    @Test public void tombstonesAreBounded() {
        try (JobRegistry registry = new JobRegistry(new JobIoPool(2), new JobOutputStore(16, 1, 32), 1)) {
            int total = JobRegistry.MAX_TOMBSTONES + 50;
            for (int i = 0; i < total; i++) finish(registry.admit(spec("k" + i, "a"), 1, 1000).entry);
            assertNull("the oldest key was dropped to keep memory finite", registry.replay(spec("k0", "a"), 1));
            assertEquals(JobRegistry.Status.REPLAY, registry.replay(spec("k" + (total - 2), "a"), 1).status);
        }
    }

    @Test public void concurrentSameKeyPublishesOnlyOneEntry() throws Exception {
        try (JobRegistry registry = registry(1, 2)) {
            CountDownLatch gate = new CountDownLatch(1);
            AtomicReference<JobRegistry.Admission> a = new AtomicReference<>(), b = new AtomicReference<>();
            Thread one = new Thread(() -> { await(gate); a.set(registry.admit(spec("key", "x"), 8, 1000)); });
            Thread two = new Thread(() -> { await(gate); b.set(registry.admit(spec("key", "x"), 8, 1000)); });
            one.start(); two.start(); gate.countDown(); one.join(); two.join();
            assertSame(a.get().entry, b.get().entry);
            assertNotEquals(a.get().status, b.get().status);
            assertEquals(1, registry.list(8).size());
            a.get().entry.capture().discardBeforeLaunch();
            assertEquals(JobRegistry.Status.ACCEPTED, registry.admit(spec("new", "x"), 8, 1000).status);
        }
    }

    @Test public void shutdownDiscardsUnstartedCaptureAndInvalidatesOutput() {
        JobRegistry registry = registry(1, 1);
        JobRegistry.Entry entry = registry.admit(spec("id", "x"), 1, 1000).entry;
        registry.close();
        assertFalse(entry.capture().discardIfUnstarted());
        assertThrows(IllegalStateException.class, () -> entry.output().read(JobOutputStore.Stream.STDOUT, 0, 1));
        assertThrows(IllegalStateException.class, () -> registry.admit(spec("other", "x"), 1, 1000));
    }

    private static void await(CountDownLatch gate) {
        try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}

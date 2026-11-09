package com.termux.app.control.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ResourceAdmissionControllerTest {
    private static final long GIB = 1L << 30;
    private final AtomicLong now = new AtomicLong(1000);
    private final ResourceAdmissionController.Config config = new ResourceAdmissionController.Config(3, 10_000, 15_000, 768L << 20, 256L << 20);

    private ResourceAdmissionController controller() { return new ResourceAdmissionController(config, 4, now::get); }
    private static JobResourcePolicy balanced() { return JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED); }
    private static JobResourcePolicy protectedProfile() { return JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.PROTECTED); }

    /** 12 GiB device, plenty free: four slots. */
    private static void roomy(ResourceAdmissionController c) { c.update(PressureTracker.Level.NORMAL, 9 * GIB, 12 * GIB); }

    @Test public void grantsUpToTheSlotCountThenQueuesInOrder() {
        ResourceAdmissionController c = controller(); roomy(c);
        List<String> started = new ArrayList<>();
        List<ResourceAdmissionController.Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ResourceAdmissionController.Ticket t = c.newTicket(balanced()); tickets.add(t);
            assertEquals(ResourceAdmissionController.Result.GRANTED, c.submit(t, () -> { }));
        }
        assertEquals(4, c.activeCount());
        ResourceAdmissionController.Ticket a = c.newTicket(balanced()), b = c.newTicket(balanced());
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(a, () -> started.add("a")));
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(b, () -> started.add("b")));
        assertEquals(1, c.queuePosition(a)); assertEquals(2, c.queuePosition(b));
        c.release(tickets.get(0));
        assertEquals(List.of("a"), started);
        assertEquals(0, c.queuePosition(a)); assertEquals(1, c.queuePosition(b));
        c.release(tickets.get(1));
        assertEquals(List.of("a", "b"), started);
    }

    @Test public void queueIsBoundedAndFullQueueRefusesWithoutLeakingATicket() {
        ResourceAdmissionController c = controller();
        c.update(PressureTracker.Level.CRITICAL, 100L << 20, 4 * GIB); // nothing is admitted
        for (int i = 0; i < 3; i++) assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), () -> { }));
        assertFalse(c.hasRoom());
        ResourceAdmissionController.Ticket overflow = c.newTicket(balanced());
        assertEquals(ResourceAdmissionController.Result.QUEUE_FULL, c.submit(overflow, () -> { }));
        assertEquals(3, c.queuedCount());
        c.release(overflow); // releasing a refused ticket is harmless
        assertEquals(3, c.queuedCount());
    }

    @Test public void releaseIsExactlyOnce() {
        ResourceAdmissionController c = controller(); roomy(c);
        ResourceAdmissionController.Ticket t = c.newTicket(balanced());
        c.submit(t, () -> { });
        c.release(t); c.release(t); c.release(t);
        assertEquals(0, c.activeCount());
        ResourceAdmissionController.Ticket u = c.newTicket(balanced());
        c.submit(u, () -> { });
        assertEquals(1, c.activeCount());
    }

    @Test public void cancellingAQueuedJobRemovesItFromTheQueue() {
        ResourceAdmissionController c = controller();
        c.update(PressureTracker.Level.CRITICAL, 100L << 20, 4 * GIB);
        ResourceAdmissionController.Ticket t = c.newTicket(balanced());
        AtomicInteger ran = new AtomicInteger();
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(t, ran::incrementAndGet));
        c.release(t);
        assertEquals(0, c.queuedCount());
        roomy(c);
        assertEquals("a cancelled job is never started", 0, ran.get());
    }

    @Test public void criticalPressureBlocksAndRecoveryWaitsForTheCooldown() {
        ResourceAdmissionController c = controller(); roomy(c);
        c.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        AtomicInteger started = new AtomicInteger();
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), started::incrementAndGet));
        now.addAndGet(60_000);
        c.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        assertEquals(0, started.get());
        c.update(PressureTracker.Level.NORMAL, 9 * GIB, 12 * GIB);
        assertEquals("recovered but still inside the cooldown", 0, started.get());
        now.addAndGet(14_000); c.update(PressureTracker.Level.NORMAL, 9 * GIB, 12 * GIB);
        assertEquals(0, started.get());
        now.addAndGet(2_000); c.update(PressureTracker.Level.NORMAL, 9 * GIB, 12 * GIB);
        assertEquals(1, started.get());
    }

    @Test public void elevatedPressureHalvesConcurrencyButNeverStarvesALoneJob() {
        ResourceAdmissionController c = controller();
        c.update(PressureTracker.Level.ELEVATED, 3 * GIB, 12 * GIB);
        assertEquals(ResourceAdmissionController.Result.GRANTED, c.submit(c.newTicket(balanced()), () -> { }));
        assertEquals(ResourceAdmissionController.Result.GRANTED, c.submit(c.newTicket(balanced()), () -> { }));
        assertEquals("limit is 4/2", ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), () -> { }));
        ResourceAdmissionController lone = controller();
        lone.update(PressureTracker.Level.ELEVATED, 600L << 20, 2 * GIB);
        assertEquals(ResourceAdmissionController.Result.GRANTED, lone.submit(lone.newTicket(balanced()), () -> { }));
    }

    @Test public void pauseAdmissionOnARunningJobBlocksNewAdmissionUnderElevatedPressure() {
        ResourceAdmissionController c = controller(); roomy(c);
        JobResourcePolicy pausing = JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.BALANCED)
            .onPressure(JobResourcePolicy.PressureAction.PAUSE_ADMISSION).build();
        c.submit(c.newTicket(pausing), () -> { });
        c.update(PressureTracker.Level.ELEVATED, 3 * GIB, 12 * GIB);
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), () -> { }));
    }

    @Test public void aProtectedJobRunsAloneAndIsNotStartedBesideOthers() {
        ResourceAdmissionController c = controller(); roomy(c);
        ResourceAdmissionController.Ticket first = c.newTicket(protectedProfile());
        assertEquals(ResourceAdmissionController.Result.GRANTED, c.submit(first, () -> { }));
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), () -> { }));
        ResourceAdmissionController d = controller(); roomy(d);
        d.submit(d.newTicket(balanced()), () -> { });
        assertEquals("a protected job waits for the running one", ResourceAdmissionController.Result.QUEUED,
            d.submit(d.newTicket(protectedProfile()), () -> { }));
    }

    @Test public void unsettledAdmissionsHoldBackMemoryFromTheNextOne() {
        ResourceAdmissionController c = controller();
        c.update(PressureTracker.Level.NORMAL, 1500L << 20, 12 * GIB);
        assertEquals(ResourceAdmissionController.Result.GRANTED, c.submit(c.newTicket(balanced()), () -> { }));
        // 1500 MiB free, 768 MiB held for the job that has not ramped up, 768 + 256 needed: refused.
        assertEquals(ResourceAdmissionController.Result.QUEUED, c.submit(c.newTicket(balanced()), () -> { }));
        now.addAndGet(11_000);
        c.update(PressureTracker.Level.NORMAL, 1500L << 20, 12 * GIB);
        assertEquals("once settled, the first job's real use is already in 'available'", 2, c.activeCount());
    }

    @Test public void smallDevicesGetOneSlotAndUnknownPressureIsConservative() {
        assertEquals(1, ResourceAdmissionController.slotsFor(2 * GIB, PressureTracker.Level.NORMAL, 4));
        assertEquals(2, ResourceAdmissionController.slotsFor(4 * GIB, PressureTracker.Level.NORMAL, 4));
        assertEquals(3, ResourceAdmissionController.slotsFor(8 * GIB, PressureTracker.Level.NORMAL, 4));
        assertEquals(4, ResourceAdmissionController.slotsFor(16 * GIB, PressureTracker.Level.NORMAL, 4));
        assertEquals(1, ResourceAdmissionController.slotsFor(16 * GIB, PressureTracker.Level.UNKNOWN, 4));
        assertEquals(1, ResourceAdmissionController.slotsFor(null, PressureTracker.Level.NORMAL, 4));
        assertEquals(2, ResourceAdmissionController.slotsFor(16 * GIB, PressureTracker.Level.NORMAL, 2));
    }

    @Test public void concurrentSubmitAndReleaseNeverOversubscribeOrLeak() throws Exception {
        ResourceAdmissionController c = new ResourceAdmissionController(
            new ResourceAdmissionController.Config(64, 0, 0, 1L << 20, 0), 4, System::nanoTime);
        c.update(PressureTracker.Level.NORMAL, 100 * GIB, 100 * GIB);
        final int jobs = 400;
        final AtomicInteger running = new AtomicInteger(), peak = new AtomicInteger(), finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(jobs);
        for (int i = 0; i < jobs; i++) {
            pool.execute(() -> {
                ResourceAdmissionController.Ticket t = c.newTicket(JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.PERFORMANCE));
                Runnable work = () -> {
                    int now2 = running.incrementAndGet();
                    peak.accumulateAndGet(now2, Math::max);
                    running.decrementAndGet();
                    finished.incrementAndGet();
                    c.release(t);
                    c.release(t); // duplicate release from a racing completion path
                    done.countDown();
                };
                for (;;) {
                    if (!c.hasRoom()) { Thread.yield(); continue; }
                    ResourceAdmissionController.Result r = c.submit(t, work);
                    if (r == ResourceAdmissionController.Result.GRANTED) work.run();
                    if (r != ResourceAdmissionController.Result.QUEUE_FULL) break;
                }
            });
        }
        assertTrue("all jobs ran", done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(jobs, finished.get());
        assertEquals(0, c.activeCount());
        assertEquals(0, c.queuedCount());
        assertTrue("never more than the slot cap running", peak.get() <= 4);
    }
}

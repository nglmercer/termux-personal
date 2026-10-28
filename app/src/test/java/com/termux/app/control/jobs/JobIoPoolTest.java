package com.termux.app.control.jobs;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class JobIoPoolTest {
    @Test public void cancelledFutureDoesNotReleaseRunningWorkerReservation() throws Exception {
        JobIoPool pool = new JobIoPool(1);
        JobIoPool.Lease lease = pool.tryReserve();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> task = lease.submit(() -> {
                started.countDown();
                while (true) {
                    try { release.await(); break; }
                    catch (InterruptedException ignored) { /* Simulate an uninterruptible pipe reader. */ }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(task.cancel(true)); lease.shutdown();
            assertNull(pool.tryReserve());
            assertFalse(lease.awaitTermination(20, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(lease.awaitTermination(2, TimeUnit.SECONDS));
            JobIoPool.Lease next = pool.tryReserve(); assertNotNull(next); next.close();
        } finally { release.countDown(); pool.close(); }
    }

    @Test public void allReservedJobsHaveThreeConcurrentWorkerSlots() throws Exception {
        JobIoPool pool = new JobIoPool(4);
        CountDownLatch started = new CountDownLatch(12), release = new CountDownLatch(1);
        JobIoPool.Lease[] leases = new JobIoPool.Lease[4];
        try {
            for (int i = 0; i < leases.length; i++) {
                leases[i] = pool.tryReserve(); assertNotNull(leases[i]);
                for (int j = 0; j < 3; j++) leases[i].submit(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                try { leases[i].submit(() -> { }); fail(); } catch (RejectedExecutionException expected) { }
            }
            assertNull(pool.tryReserve());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (JobIoPool.Lease lease : leases) { lease.shutdown(); assertTrue(lease.awaitTermination(5, TimeUnit.SECONDS)); }
        } finally { release.countDown(); pool.close(); }
    }

    @Test public void closingPoolRejectsNewReservationsAndCancelsWorkers() throws Exception {
        JobIoPool pool = new JobIoPool(1);
        JobIoPool.Lease lease = pool.tryReserve();
        CountDownLatch started = new CountDownLatch(1);
        Future<?> task = lease.submit(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        pool.close(); pool.close();
        assertTrue(task.isCancelled()); assertTrue(lease.awaitTermination(2, TimeUnit.SECONDS));
        try { pool.tryReserve(); fail(); } catch (IllegalStateException expected) { }
    }
}

package com.termux.app.control.jobs;

import com.termux.terminal.OutputReplayBuffer;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class JobOutputStoreTest {
    @Test public void independentStreamsRetainTailAndReportGap() {
        JobOutputStore store = new JobOutputStore(4, 2, 16);
        JobOutputStore.Output output = store.reserve("job-1");
        assertTrue(output.append(JobOutputStore.Stream.STDOUT, new byte[]{0, 1, 2, 3, 4, 5}, 0, 6));
        assertTrue(output.append(JobOutputStore.Stream.STDERR, new byte[]{9, 8}, 0, 2));
        OutputReplayBuffer.ReadResult stdout = output.read(JobOutputStore.Stream.STDOUT, 0, 4);
        assertTrue(stdout.gap); assertEquals(2, stdout.offset); assertEquals(6, stdout.nextOffset);
        assertArrayEquals(new byte[]{2, 3, 4, 5}, stdout.data);
        OutputReplayBuffer.ReadResult stderr = output.read(JobOutputStore.Stream.STDERR, 0, 4);
        assertFalse(stderr.gap); assertEquals(2, stderr.endOffset); assertArrayEquals(new byte[]{9, 8}, stderr.data);
        output.finish();
        assertTrue(output.readSnapshot(JobOutputStore.Stream.STDOUT, 0, 4).complete);
        assertFalse(output.append(JobOutputStore.Stream.STDOUT, new byte[]{6}, 0, 1));
        assertArrayEquals(new byte[]{2, 3, 4, 5}, output.read(JobOutputStore.Stream.STDOUT, 0, 4).data);
        store.close();
    }

    @Test public void failedCaptureIsFinishedButNotComplete() {
        JobOutputStore store = new JobOutputStore(4, 1, 8);
        JobOutputStore.Output output = store.reserve("job");
        output.finish(false);
        output.finish(true);
        JobOutputStore.Read read = output.readSnapshot(JobOutputStore.Stream.STDOUT, 0, 4);
        assertTrue(read.finished); assertFalse(read.complete);
        store.close();
    }

    @Test public void quotasAndRemovalInvalidateHandles() {
        JobOutputStore store = new JobOutputStore(4, 3, 16);
        JobOutputStore.Output first = store.reserve("one");
        assertNotNull(store.reserve("two"));
        assertNull(store.reserve("three"));
        try { store.reserve("one"); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(store.remove("one"));
        assertFalse(first.append(JobOutputStore.Stream.STDOUT, new byte[]{1}, 0, 1));
        try { first.read(JobOutputStore.Stream.STDOUT, 0, 1); fail(); } catch (IllegalStateException expected) { }
        assertNotNull(store.reserve("three"));
        store.close(); store.close();
        try { store.reserve("four"); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void rejectsInvalidQuotaAndReadLimits() {
        try { new JobOutputStore(0, 1, 2); fail(); } catch (IllegalArgumentException expected) { }
        try { new JobOutputStore(4, 1, 7); fail(); } catch (IllegalArgumentException expected) { }
        try { new JobOutputStore(Integer.MAX_VALUE, 1, Integer.MAX_VALUE); fail(); } catch (IllegalArgumentException expected) { }
        try { new JobOutputStore(1, Integer.MAX_VALUE, Integer.MAX_VALUE); fail(); } catch (IllegalArgumentException expected) { }
        try { new JobOutputStore(1, 1, Integer.MIN_VALUE); fail(); } catch (IllegalArgumentException expected) { }
        JobOutputStore store = new JobOutputStore(4, 1, 8);
        JobOutputStore.Output output = store.reserve("job");
        try { output.read(JobOutputStore.Stream.STDOUT, 0, 0); fail(); } catch (IllegalArgumentException expected) { }
        try { output.read(JobOutputStore.Stream.STDOUT, 1, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { output.read(null, 0, 1); fail(); } catch (IllegalArgumentException expected) { }
        store.close();
    }

    @Test public void concurrentDrainsRemainBoundedAndDoNotWaitForReaders() throws Exception {
        JobOutputStore store = new JobOutputStore(257, 1, 514);
        JobOutputStore.Output output = store.reserve("job");
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (JobOutputStore.Stream stream : JobOutputStore.Stream.values()) {
            new Thread(() -> {
                try {
                    start.await();
                    byte[] chunk = new byte[128];
                    for (int n = 0; n < 2000; n++) {
                        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (n * chunk.length + i);
                        if (!output.append(stream, chunk, 0, chunk.length)) throw new AssertionError();
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { done.countDown(); }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        output.finish();
        for (JobOutputStore.Stream stream : JobOutputStore.Stream.values()) {
            OutputReplayBuffer.ReadResult result = output.read(stream, 0, 257);
            assertTrue(result.gap); assertEquals(256000, result.endOffset); assertEquals(257, result.data.length);
            for (int i = 0; i < result.data.length; i++) assertEquals((byte)(result.offset + i), result.data[i]);
        }
        store.close();
    }

    @Test public void finishedSnapshotNeverMissesFinalAppend() throws Exception {
        JobOutputStore store = new JobOutputStore(16, 1, 32);
        JobOutputStore.Output output = store.reserve("job");
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 10000; i++) output.append(JobOutputStore.Stream.STDOUT, new byte[]{1}, 0, 1);
                output.finish();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { done.countDown(); }
        });
        try {
            writer.start(); start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                JobOutputStore.Read read = output.readSnapshot(JobOutputStore.Stream.STDOUT, 0, 16);
                if (read.finished) {
                    assertEquals(10000, read.data.endOffset);
                    observed = true; break;
                }
            }
            assertTrue("finished snapshot observed", observed);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally { writer.join(5000); store.close(); }
    }
}

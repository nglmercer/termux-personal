package com.termux.app.control.jobs;

import org.junit.Test;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class AppShellJobCaptureTest {
    @Test public void emptyStdinIsClosedAndChildCanReadEof() throws Exception {
        JobIoPool io = new JobIoPool(1);
        JobOutputStore store = new JobOutputStore(32, 1, 64);
        JobOutputStore.Output output = store.reserve("job");
        Process process = new ProcessBuilder("/usr/bin/python3", "-c", "import sys; sys.stdin.buffer.read(); sys.stdout.write('done')").start();
        try {
            assertEquals(0, new AppShellJobCapture(io.tryReserve(), output, null, 5000).captureAndWait(process));
            assertArrayEquals(new byte[]{'d', 'o', 'n', 'e'}, output.read(JobOutputStore.Stream.STDOUT, 0, 32).data);
            assertTrue(output.isFinished());
        } finally { process.destroyForcibly(); io.close(); store.close(); }
    }

    @Test public void rejectedWorkersFailClosedAndFreezeOutput() throws Exception {
        JobIoPool io = new JobIoPool(1);
        JobIoPool.Lease lease = io.tryReserve(); lease.shutdown();
        JobOutputStore store = new JobOutputStore(32, 1, 64);
        JobOutputStore.Output output = store.reserve("job");
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        try {
            AppShellJobCapture capture = new AppShellJobCapture(lease, output, new byte[]{1}, 1000);
            try { capture.captureAndWait(process); fail(); } catch (IOException expected) { }
            assertTrue(output.isFinished());
            assertFalse(output.append(JobOutputStore.Stream.STDOUT, new byte[]{1}, 0, 1));
            try { capture.captureAndWait(process); fail(); } catch (IllegalStateException expected) { }
        } finally { process.destroyForcibly(); io.close(); store.close(); }
    }

    @Test public void rejectsOversizedStdin() {
        JobIoPool io = new JobIoPool(1);
        JobOutputStore store = new JobOutputStore(1, 1, 2);
        try {
            try { new AppShellJobCapture(io.tryReserve(), store.reserve("job"), new byte[AppShellJobCapture.MAX_STDIN_BYTES + 1], 1000); fail(); }
            catch (IllegalArgumentException expected) { }
        } finally { io.close(); store.close(); }
    }

    @Test public void failedLaunchCanDiscardUnusedCapture() {
        JobIoPool io = new JobIoPool(1);
        JobOutputStore store = new JobOutputStore(1, 1, 2);
        JobOutputStore.Output output = store.reserve("job");
        try {
            AppShellJobCapture capture = new AppShellJobCapture(io.tryReserve(), output, new byte[]{1, 2, 3}, 1000);
            capture.discardBeforeLaunch();
            assertTrue(output.isFinished());
            JobIoPool.Lease next = io.tryReserve(); assertNotNull(next); next.close();
            try { capture.discardBeforeLaunch(); fail(); } catch (IllegalStateException expected) { }
        } finally { io.close(); store.close(); }
    }

    @Test public void heldPipesDoNotBlockCaptureWaitIndefinitely() throws Exception {
        JobIoPool io = new JobIoPool(1);
        JobIoPool.Lease lease = io.tryReserve();
        JobOutputStore store = new JobOutputStore(32, 1, 64);
        JobOutputStore.Output output = store.reserve("job");
        PipedInputStream stdout = new PipedInputStream(), stderr = new PipedInputStream();
        PipedOutputStream stdoutOwner = new PipedOutputStream(stdout), stderrOwner = new PipedOutputStream(stderr);
        Process exited = new Process() {
            private final OutputStream stdin = new ByteArrayOutputStream();
            @Override public OutputStream getOutputStream() { return stdin; }
            @Override public InputStream getInputStream() { return stdout; }
            @Override public InputStream getErrorStream() { return stderr; }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
        try {
            long start = System.nanoTime();
            try { new AppShellJobCapture(lease, output, null, 100).captureAndWait(exited); fail(); }
            catch (IOException expected) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
            assertTrue(output.isFinished());
        } finally {
            stdoutOwner.close(); stderrOwner.close();
            assertTrue(lease.awaitTermination(5, TimeUnit.SECONDS)); io.close();
            store.close();
        }
    }

    @Test public void interruptedCaptureRetainsQuotaUntilChildExit() throws Exception {
        JobIoPool io = new JobIoPool(1);
        JobIoPool.Lease lease = io.tryReserve();
        JobOutputStore store = new JobOutputStore(32, 1, 64);
        JobOutputStore.Output output = store.reserve("job");
        CountDownLatch waitingForExit = new CountDownLatch(1), childExited = new CountDownLatch(1);
        AtomicInteger waits = new AtomicInteger();
        AtomicReference<Throwable> result = new AtomicReference<>();
        Process process = new Process() {
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public int waitFor() throws InterruptedException {
                if (waits.getAndIncrement() == 0) throw new InterruptedException();
                waitingForExit.countDown(); childExited.await(); return 0;
            }
            @Override public int exitValue() { if (childExited.getCount() > 0) throw new IllegalThreadStateException(); return 0; }
            @Override public void destroy() { fail("capture must not signal the process"); }
        };
        Thread worker = new Thread(() -> {
            try { new AppShellJobCapture(lease, output, null, 1000).captureAndWait(process); }
            catch (Throwable e) { result.set(e); }
        });
        try {
            worker.start(); assertTrue(waitingForExit.await(2, TimeUnit.SECONDS));
            assertNull(io.tryReserve());
            assertFalse(output.isFinished());
            childExited.countDown(); worker.join(2000); assertFalse(worker.isAlive());
            assertTrue(result.get() instanceof InterruptedException);
            assertTrue(output.isFinished());
            assertTrue(lease.awaitTermination(2, TimeUnit.SECONDS));
            JobIoPool.Lease next = io.tryReserve(); assertNotNull(next); next.close();
        } finally { childExited.countDown(); worker.join(2000); io.close(); store.close(); }
    }
}

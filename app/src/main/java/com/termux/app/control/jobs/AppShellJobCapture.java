package com.termux.app.control.jobs;

import com.termux.shared.shell.command.runner.app.AppShell;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Managed AppShell byte capture. The caller must reserve three non-starving I/O worker slots. */
public final class AppShellJobCapture implements AppShell.ManagedCapture {
    public static final int MAX_STDIN_BYTES = 64 * 1024;
    private final JobIoPool.Lease mIoWorkers;
    private final JobOutputStore.Output mOutput;
    private final byte[] mStdin;
    private final long mDrainTimeoutMillis;
    private final AtomicBoolean mStarted = new AtomicBoolean();

    public AppShellJobCapture(JobIoPool.Lease ioWorkers, JobOutputStore.Output output, byte[] stdin, long drainTimeoutMillis) {
        if (ioWorkers == null || output == null || (stdin != null && stdin.length > MAX_STDIN_BYTES) ||
            drainTimeoutMillis < 1 || drainTimeoutMillis > 30000) throw new IllegalArgumentException("Invalid managed capture configuration");
        mIoWorkers = ioWorkers; mOutput = output; mStdin = stdin == null ? new byte[0] : stdin.clone();
        mDrainTimeoutMillis = drainTimeoutMillis;
    }

    /** Release input and freeze output if process startup failed before this capture was handed off. */
    public void discardBeforeLaunch() {
        if (!discardIfUnstarted()) throw new IllegalStateException("Capture already used");
    }

    /** Used during shutdown, when startup may already be racing with cleanup. */
    public boolean discardIfUnstarted() {
        if (!mStarted.compareAndSet(false, true)) return false;
        Arrays.fill(mStdin, (byte) 0);
        mOutput.finish();
        mIoWorkers.shutdown();
        return true;
    }

    /** True only after every reserved pipe worker has actually stopped. */
    public boolean isReleased() { return mIoWorkers.isTerminated(); }

    /** Called on the AppShell worker, never the Android service thread. */
    @Override public int captureAndWait(Process process) throws IOException, InterruptedException {
        if (process == null || !mStarted.compareAndSet(false, true)) throw new IllegalStateException("Capture already used or missing process");
        AtomicInteger inputState = new AtomicInteger(0); // 0 = pending, 1 = writer owns bytes, 2 = wiped before start
        Future<?> outFuture = null, errFuture = null, inputFuture = null;
        boolean exited = false;
        try {
            JobStreamPump stdout = new JobStreamPump(process.getInputStream(), mOutput, JobOutputStore.Stream.STDOUT);
            JobStreamPump stderr = new JobStreamPump(process.getErrorStream(), mOutput, JobOutputStore.Stream.STDERR);
            outFuture = mIoWorkers.submit(stdout);
            errFuture = mIoWorkers.submit(stderr);
            inputFuture = mIoWorkers.submit(() -> {
                if (!inputState.compareAndSet(0, 1)) return null;
                try (OutputStream input = process.getOutputStream()) {
                    input.write(mStdin); input.flush();
                } finally { Arrays.fill(mStdin, (byte) 0); }
                return null;
            });
            int exitCode = process.waitFor();
            exited = true;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(mDrainTimeoutMillis);
            await(inputFuture, deadline);
            await(outFuture, deadline);
            await(errFuture, deadline);
            if (stdout.await(0, TimeUnit.MILLISECONDS) != JobStreamPump.Result.COMPLETE ||
                stderr.await(0, TimeUnit.MILLISECONDS) != JobStreamPump.Result.COMPLETE)
                throw new IOException("Managed output capture failed");
            return exitCode;
        } catch (RejectedExecutionException | CancellationException e) {
            throw new IOException("Managed I/O workers unavailable", e);
        } finally {
            // Do not release the job reservation while a child may still be running. On a capture
            // failure there is currently no verified identity-safe way to terminate it on Android.
            if (!exited) awaitExitUninterruptibly(process);
            if (outFuture != null && !outFuture.isDone()) outFuture.cancel(true);
            if (errFuture != null && !errFuture.isDone()) errFuture.cancel(true);
            if (inputFuture != null && !inputFuture.isDone()) inputFuture.cancel(true);
            if (inputState.compareAndSet(0, 2)) Arrays.fill(mStdin, (byte) 0);
            mOutput.finish();
            mIoWorkers.shutdown();
        }
    }

    private static void awaitExitUninterruptibly(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            for (;;) {
                try { process.waitFor(); return; }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void await(Future<?> future, long deadline) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Managed stream drain timed out");
        try { future.get(remaining, TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException e) { throw new IOException("Managed stream drain failed", e); }
    }
}

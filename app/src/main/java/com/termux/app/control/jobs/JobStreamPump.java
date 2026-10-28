package com.termux.app.control.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Drain one owned process pipe on a worker; never decodes or logs command output. */
public final class JobStreamPump implements Runnable {
    public enum Result { COMPLETE, CAPTURE_UNAVAILABLE, IO_ERROR }
    private static final int CHUNK_BYTES = 8192;
    private final InputStream mInput;
    private final JobOutputStore.Output mOutput;
    private final JobOutputStore.Stream mStream;
    private final AtomicBoolean mStarted = new AtomicBoolean();
    private final CountDownLatch mDone = new CountDownLatch(1);
    private volatile Result mResult;

    public JobStreamPump(InputStream input, JobOutputStore.Output output, JobOutputStore.Stream stream) {
        if (input == null || output == null || stream == null) throw new IllegalArgumentException("Missing stream configuration");
        mInput = input; mOutput = output; mStream = stream;
    }

    /** Must run off the service thread. Continue draining if retention was revoked or exhausted. */
    @Override public void run() {
        if (!mStarted.compareAndSet(false, true)) throw new IllegalStateException("Pump already started");
        Result result = Result.COMPLETE;
        boolean retaining = true;
        byte[] chunk = new byte[CHUNK_BYTES];
        try (InputStream input = mInput) {
            while (true) {
                int count = input.read(chunk);
                if (count < 0) break;
                if (count == 0) {
                    int value = input.read();
                    if (value < 0) break;
                    chunk[0] = (byte) value; count = 1;
                }
                if (retaining) {
                    try { retaining = mOutput.append(mStream, chunk, 0, count); }
                    catch (RuntimeException e) { retaining = false; }
                    if (!retaining) result = Result.CAPTURE_UNAVAILABLE;
                }
            }
        } catch (IOException | RuntimeException e) {
            result = Result.IO_ERROR;
        } catch (Error e) {
            result = Result.IO_ERROR;
            throw e;
        } finally {
            java.util.Arrays.fill(chunk, (byte) 0);
            mResult = result;
            mDone.countDown();
        }
    }

    /** Wait only on a background worker; null means the deadline elapsed. */
    public Result await(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0 || unit == null) throw new IllegalArgumentException("Invalid deadline");
        return mDone.await(timeout, unit) ? mResult : null;
    }
}

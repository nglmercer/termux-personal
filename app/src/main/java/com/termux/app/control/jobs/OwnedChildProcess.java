package com.termux.app.control.jobs;

import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A child this process forked and alone reaps. Exit is observed without reaping, marked under this
 * object's lock, and only then reaped; signals are sent under the same lock before the mark, so
 * they can never reach a recycled PID. Signals go to the child's process group, which can stop
 * descendants that stayed in it; descendants that left the group are not reached.
 */
public final class OwnedChildProcess extends Process {
    public static final int SIGINT = 2, SIGKILL = 9, SIGTERM = 15;

    interface Ops {
        /** Blocks until exit without reaping; 0 or a negative errno. */
        int waitExit(int pid);
        /** out receives {exited, exitCode, signaled, signal}; 0 or a negative errno. */
        int reap(int pid, int[] out);
        int signal(int pid, int signal);
    }

    private static final Ops NATIVE = new Ops() {
        @Override public int waitExit(int pid) { return ManagedNative.waitExit(pid); }
        @Override public int reap(int pid, int[] out) { return ManagedNative.reap(pid, out); }
        @Override public int signal(int pid, int signal) { return ManagedNative.signal(pid, signal); }
    };

    private final int mPid;
    private final Ops mOps;
    private final OutputStream mStdin;
    private final InputStream mStdout, mStderr;
    private boolean mWaiting, mExitObserved;
    private Integer mExitCode, mSignal;

    OwnedChildProcess(int pid, Ops ops, OutputStream stdin, InputStream stdout, InputStream stderr) {
        if (pid <= 0 || ops == null || stdin == null || stdout == null || stderr == null)
            throw new IllegalArgumentException("Invalid owned child");
        mPid = pid; mOps = ops; mStdin = stdin; mStdout = stdout; mStderr = stderr;
    }

    /** Worker-only. Returns null when the native launcher is unavailable; throws on a launch failure. */
    public static OwnedChildProcess launch(String[] command, String[] environment, String cwd) throws IOException {
        if (!ManagedNative.isLibraryAvailable()) return null;
        int[] out = new int[4];
        int result = ManagedNative.launch(command[0], cwd, command, environment, out);
        if (result != 0) throw new IOException("Managed launch failed: errno " + -result);
        return new OwnedChildProcess(out[0], NATIVE,
            new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(out[1])),
            new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(out[2])),
            new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(out[3])));
    }

    /** Observational only; never use it to signal. */
    public int pid() { return mPid; }

    /** Sends TERM, KILL or INT to the child's group; false once the child has exited. */
    public synchronized boolean signal(int signal) {
        if (signal != SIGTERM && signal != SIGKILL && signal != SIGINT) throw new IllegalArgumentException("Unsupported signal");
        if (mExitObserved) return false;
        return mOps.signal(mPid, signal) == 0;
    }

    /** The signal that terminated the child, or null if it exited normally or has not been reaped. */
    public synchronized Integer terminationSignal() { return mSignal; }

    /** Uninterruptible: one caller blocks in the native wait, others wait for its result. */
    @Override public int waitFor() {
        boolean interrupted = false;
        synchronized (this) {
            while (mExitCode == null && mWaiting) {
                try { wait(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (mExitCode != null) { if (interrupted) Thread.currentThread().interrupt(); return mExitCode; }
            mWaiting = true;
        }
        int waited = mOps.waitExit(mPid);
        int[] result = new int[4];
        synchronized (this) {
            // From here no signal is sent, so reaping cannot expose a recycled PID to one.
            mExitObserved = true;
            if (waited != 0 || mOps.reap(mPid, result) != 0) mExitCode = 255;
            else if (result[0] != 0) mExitCode = result[1];
            else { mSignal = result[3]; mExitCode = 128 + result[3]; }
            mWaiting = false;
            notifyAll();
        }
        if (interrupted) Thread.currentThread().interrupt();
        return mExitCode;
    }

    @Override public synchronized int exitValue() {
        if (mExitCode == null) throw new IllegalThreadStateException("Process has not exited");
        return mExitCode;
    }

    @Override public void destroy() { signal(SIGTERM); }
    @Override public OutputStream getOutputStream() { return mStdin; }
    @Override public InputStream getInputStream() { return mStdout; }
    @Override public InputStream getErrorStream() { return mStderr; }
}

package com.termux.app.control.jobs;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One job's stop requests. All signalling uses its owned Process, never an observed PID. */
public final class OwnedProcessControl {
    public enum Status { NOT_REQUESTED, PENDING_HANDLE, REQUESTED, ALREADY_EXITED, STOP_UNAVAILABLE, FORCE_UNAVAILABLE, FAILED }
    public enum Request { ACCEPTED, ALREADY_REQUESTED, NOT_STARTED, FINISHED, UNSUPPORTED }
    public enum SignalResult { SENT, NOT_STARTED, FINISHED, UNSUPPORTED, FAILED }
    interface SignalAction { void signal(Process process); }

    private final JobRecord mRecord;
    private final ScheduledExecutorService mTimers;
    private final long mGraceMillis;
    private final SignalAction mGraceful, mForce;
    private Process mProcess;
    private ScheduledFuture<?> mEscalation, mTimeout;
    private boolean mDone, mAttached;
    private Status mStatus = Status.NOT_REQUESTED;

    /** The owner must provide a bounded, service-owned scheduler and invoke stop off the main thread. */
    public static OwnedProcessControl forAndroid(JobRecord record, ScheduledExecutorService timers, long graceMillis,
                                                 boolean ownedChildren) {
        // Android Process.destroy implementations vary and some use a recyclable PID internally.
        // Only a child this process alone reaps can be signalled; otherwise refuse rather than guess.
        if (!ownedChildren) return new OwnedProcessControl(record, timers, graceMillis, null, null);
        return new OwnedProcessControl(record, timers, graceMillis,
            process -> ownedSignal(process, OwnedChildProcess.SIGTERM), process -> ownedSignal(process, OwnedChildProcess.SIGKILL));
    }

    /** An exited child returns false, which is not a failure; a foreign handle is never signalled. */
    private static void ownedSignal(Process process, int signal) {
        if (!(process instanceof OwnedChildProcess)) throw new IllegalStateException("Process identity is not owned");
        ((OwnedChildProcess) process).signal(signal);
    }

    OwnedProcessControl(JobRecord record, ScheduledExecutorService timers, long graceMillis,
                        SignalAction graceful, SignalAction force) {
        if (record == null || timers == null || graceMillis < 1 || graceMillis > 30000)
            throw new IllegalArgumentException("Invalid process control configuration");
        mRecord = record; mTimers = timers; mGraceMillis = graceMillis; mGraceful = graceful; mForce = force;
    }

    boolean owns(JobRecord record) { return mRecord == record; }

    /** Stop while launch is in progress; the request is applied when the owned handle arrives. */
    public synchronized Request requestStop(JobRecord.StopReason reason) {
        if (reason == null) throw new IllegalArgumentException("Missing stop reason");
        JobRecord.Snapshot snapshot = mRecord.snapshot();
        if (mDone || JobRecord.isTerminal(snapshot.state)) return Request.FINISHED;
        if (snapshot.state == JobRecord.State.QUEUED) return Request.NOT_STARTED;
        if (snapshot.stopReason != null) return Request.ALREADY_REQUESTED;
        if (mAttached && !alive(mProcess)) { mStatus = Status.ALREADY_EXITED; return Request.FINISHED; }
        if (mGraceful == null) { mStatus = Status.STOP_UNAVAILABLE; return Request.UNSUPPORTED; }
        if (!mRecord.requestStop(reason)) return Request.FINISHED;
        if (!mAttached) mStatus = Status.PENDING_HANDLE;
        else requestTermination();
        return Request.ACCEPTED;
    }

    /**
     * One signal to the attached owned child's group, without recording a stop. The job's outcome stays the
     * child's own; {@link #requestStop} is the path for cancellation and timeouts.
     */
    public synchronized SignalResult signal(int signal) {
        if (mDone || JobRecord.isTerminal(mRecord.snapshot().state)) return SignalResult.FINISHED;
        if (mGraceful == null) return SignalResult.UNSUPPORTED;
        if (!mAttached) return SignalResult.NOT_STARTED;
        if (!(mProcess instanceof OwnedChildProcess)) return SignalResult.UNSUPPORTED;
        try { return ((OwnedChildProcess) mProcess).signal(signal) ? SignalResult.SENT : SignalResult.FINISHED; }
        catch (RuntimeException e) { return SignalResult.FAILED; }
    }

    /** True when stop requests can reach a running child. */
    public boolean canStop() { return mGraceful != null; }

    /** Arms a wall-clock stop; completion cancels it. False when stopping is unsupported or already armed. */
    public synchronized boolean scheduleTimeout(long timeoutMillis) {
        if (timeoutMillis < 1 || mGraceful == null || mTimeout != null || mDone) return false;
        try { mTimeout = mTimers.schedule(() -> requestStop(JobRecord.StopReason.TIMEOUT), timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { return false; }
        return true;
    }

    /** Called once by the launcher, including when completion raced with launch return. */
    synchronized void attach(Process process) {
        if (process == null || mAttached) throw new IllegalArgumentException("Invalid process handle");
        mAttached = true;
        if (mDone || JobRecord.isTerminal(mRecord.snapshot().state)) return;
        mProcess = process;
        if (mRecord.snapshot().stopReason != null) requestTermination();
    }

    /** Called after the process result is observed; an already queued escalation becomes a no-op. */
    synchronized void completed() {
        mDone = true; mProcess = null;
        if (mEscalation != null) { mEscalation.cancel(false); mEscalation = null; }
        if (mTimeout != null) { mTimeout.cancel(false); mTimeout = null; }
    }

    public synchronized Status status() { return mStatus; }

    private void requestTermination() {
        if (!alive(mProcess)) { mStatus = Status.ALREADY_EXITED; return; }
        try { mGraceful.signal(mProcess); mStatus = Status.REQUESTED; }
        catch (RuntimeException e) { mStatus = Status.FAILED; }
        if (mForce == null) { if (mStatus != Status.FAILED) mStatus = Status.FORCE_UNAVAILABLE; return; }
        try { mEscalation = mTimers.schedule(this::escalate, mGraceMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { if (mStatus != Status.FAILED) mStatus = Status.FORCE_UNAVAILABLE; }
    }

    private synchronized void escalate() {
        mEscalation = null;
        if (mDone || mProcess == null || JobRecord.isTerminal(mRecord.snapshot().state)) return;
        if (!alive(mProcess)) { mStatus = Status.ALREADY_EXITED; return; }
        try { mForce.signal(mProcess); }
        catch (RuntimeException e) { mStatus = Status.FAILED; }
    }

    private static boolean alive(Process process) {
        try { process.exitValue(); return false; }
        catch (IllegalThreadStateException e) { return true; }
    }
}

package com.termux.app.control.jobs;

/** Thread-safe, service-lifetime lifecycle metadata. A PID is observational only. */
public final class JobRecord {
    public enum State { QUEUED, STARTING, RUNNING, CANCELLING, EXITED, FAILED, CANCELLED, TIMED_OUT, LOST }
    public enum StopReason { CANCEL, TIMEOUT, SHUTDOWN, RESOURCE_GUARD }
    public enum Failure { START_FAILED, CAPTURE_FAILED, SERVICE_STOPPED, LIMIT_APPLY_FAILED }

    private final String mId, mWorkspaceId;
    private final long mAuthorizationEpoch;
    private State mState = State.QUEUED;
    private StopReason mStopReason;
    private Long mStartedAt, mEndedAt;
    private Integer mPid, mExitCode, mSignal;
    private Failure mFailure;

    public JobRecord(String id, String workspaceId, long authorizationEpoch) {
        if (id == null || id.isEmpty() || id.length() > 128 || authorizationEpoch < 0)
            throw new IllegalArgumentException("Invalid job identity");
        mId = id; mWorkspaceId = workspaceId; mAuthorizationEpoch = authorizationEpoch;
    }

    public synchronized boolean begin() {
        if (mState != State.QUEUED) return false;
        mState = State.STARTING;
        return true;
    }

    /** Records a successfully launched process; a pending stop remains pending. */
    public synchronized boolean started(Integer pid, long at) {
        if ((mState != State.STARTING && mState != State.CANCELLING) || mStartedAt != null ||
            (pid != null && pid <= 0) || at < 0) return false;
        mPid = pid; mStartedAt = at;
        if (mState == State.STARTING) mState = State.RUNNING;
        return true;
    }

    /** Requests a stop; the caller must still operate on the owned live handle. */
    public synchronized boolean requestStop(StopReason reason) {
        if (reason == null) throw new IllegalArgumentException("Missing stop reason");
        if (isTerminal(mState) || mStopReason != null) return false;
        mStopReason = reason; mState = State.CANCELLING;
        return true;
    }

    /** A terminal outcome is supplied by the owner after observing the actual result. */
    public synchronized boolean complete(State outcome, Integer exitCode, Failure failure, long at) {
        if (!isTerminal(outcome) || at < 0) throw new IllegalArgumentException("Invalid terminal outcome");
        if (outcome == State.EXITED && exitCode == null || outcome != State.EXITED && outcome != State.FAILED && exitCode != null ||
            outcome == State.FAILED && failure == null || outcome != State.FAILED && failure != null ||
            outcome == State.FAILED && exitCode != null && failure != Failure.CAPTURE_FAILED)
            throw new IllegalArgumentException("Invalid terminal metadata");
        if (isTerminal(mState)) return false;
        if ((outcome == State.CANCELLED && mStopReason != StopReason.CANCEL && mStopReason != StopReason.RESOURCE_GUARD) ||
            (outcome == State.TIMED_OUT && mStopReason != StopReason.TIMEOUT))
            throw new IllegalStateException("No matching stop request");
        mState = outcome; mExitCode = exitCode; mFailure = failure; mEndedAt = at;
        return true;
    }

    /**
     * The signal that terminated the child, as observed when it was reaped. Informational: recorded before the terminal
     * state so a snapshot of a finished job always includes it. Ignored once the record is terminal.
     */
    public synchronized void observedSignal(Integer signal) {
        if (signal == null || signal <= 0 || signal > 64 || isTerminal(mState) || mSignal != null) return;
        mSignal = signal;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mId, mWorkspaceId, mAuthorizationEpoch, mState, mStopReason,
            mStartedAt, mEndedAt, mPid, mExitCode, mFailure, mSignal);
    }

    public static boolean isTerminal(State state) {
        return state == State.EXITED || state == State.FAILED || state == State.CANCELLED ||
            state == State.TIMED_OUT || state == State.LOST;
    }

    /** Contains no executable, arguments, environment, stdin, or output. */
    public static final class Snapshot {
        public final String id, workspaceId;
        public final long authorizationEpoch;
        public final State state;
        public final StopReason stopReason;
        public final Long startedAt, endedAt;
        public final Integer pid, exitCode;
        public final Failure failure;
        /** Terminating signal of the child when one was observed; the exit code then reads 128 + signal. */
        public final Integer signal;
        private Snapshot(String id, String workspaceId, long epoch, State state, StopReason stopReason,
                         Long startedAt, Long endedAt, Integer pid, Integer exitCode, Failure failure, Integer signal) {
            this.signal = signal;
            this.id = id; this.workspaceId = workspaceId; this.authorizationEpoch = epoch; this.state = state;
            this.stopReason = stopReason; this.startedAt = startedAt; this.endedAt = endedAt;
            this.pid = pid; this.exitCode = exitCode; this.failure = failure;
        }
    }
}

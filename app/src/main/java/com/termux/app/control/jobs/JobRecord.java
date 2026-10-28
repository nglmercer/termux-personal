package com.termux.app.control.jobs;

/** Thread-safe, service-lifetime lifecycle metadata. A PID is observational only. */
public final class JobRecord {
    public enum State { QUEUED, STARTING, RUNNING, CANCELLING, EXITED, FAILED, CANCELLED, TIMED_OUT, LOST }
    public enum StopReason { CANCEL, TIMEOUT, SHUTDOWN }
    public enum Failure { START_FAILED, CAPTURE_FAILED, SERVICE_STOPPED }

    private final String mId, mWorkspaceId;
    private final long mAuthorizationEpoch;
    private State mState = State.QUEUED;
    private StopReason mStopReason;
    private Long mStartedAt, mEndedAt;
    private Integer mPid, mExitCode;
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
        if (outcome == State.EXITED && exitCode == null || outcome != State.EXITED && exitCode != null ||
            outcome == State.FAILED && failure == null || outcome != State.FAILED && failure != null)
            throw new IllegalArgumentException("Invalid terminal metadata");
        if (isTerminal(mState)) return false;
        if ((outcome == State.CANCELLED && mStopReason != StopReason.CANCEL) ||
            (outcome == State.TIMED_OUT && mStopReason != StopReason.TIMEOUT))
            throw new IllegalStateException("No matching stop request");
        mState = outcome; mExitCode = exitCode; mFailure = failure; mEndedAt = at;
        return true;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(mId, mWorkspaceId, mAuthorizationEpoch, mState, mStopReason,
            mStartedAt, mEndedAt, mPid, mExitCode, mFailure);
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
        private Snapshot(String id, String workspaceId, long epoch, State state, StopReason stopReason,
                         Long startedAt, Long endedAt, Integer pid, Integer exitCode, Failure failure) {
            this.id = id; this.workspaceId = workspaceId; this.authorizationEpoch = epoch; this.state = state;
            this.stopReason = stopReason; this.startedAt = startedAt; this.endedAt = endedAt;
            this.pid = pid; this.exitCode = exitCode; this.failure = failure;
        }
    }
}

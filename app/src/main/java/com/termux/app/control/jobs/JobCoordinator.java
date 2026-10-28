package com.termux.app.control.jobs;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Internal admission and worker orchestration; not an authorization or IPC entry point. */
public final class JobCoordinator {
    public enum StartStatus { ACCEPTED, REPLAY, CONFLICT, DENIED, LIMIT_REACHED, UNSUPPORTED, UNAVAILABLE }
    public enum CancelStatus { ACCEPTED, DENIED, NOT_FOUND, FINISHED, ALREADY_REQUESTED, UNSUPPORTED }

    /** Implementations must check server-derived authority and resolve executable and cwd on this worker. */
    public interface Policy { Resolution authorizeAndResolve(JobSpec spec, long authorizationEpoch); }
    public static final class Resolution {
        public final String executable, cwd;
        public Resolution(String executable, String cwd) { this.executable = executable; this.cwd = cwd; }
    }
    public static final class StartResult {
        public final StartStatus status;
        public final JobRecord.Snapshot job;
        private StartResult(StartStatus status, JobRecord.Snapshot job) { this.status = status; this.job = job; }
    }

    private final JobRegistry mRegistry;
    private final AppShellJobAdapter mAdapter;
    private final Policy mPolicy;
    private final JobAuthority mAuthority;
    private final ThreadPoolExecutor mLaunchers;
    private final ScheduledThreadPoolExecutor mTimers;
    private final Map<String, Work> mWork = new HashMap<>();
    private boolean mStopping, mClosed;

    public JobCoordinator(JobRegistry registry, AppShellJobAdapter adapter, Policy policy, JobAuthority authority) {
        this(registry, adapter, policy, authority, JobIoPool.MAX_JOBS);
    }

    JobCoordinator(JobRegistry registry, AppShellJobAdapter adapter, Policy policy, JobAuthority authority, int launchWorkers) {
        if (registry == null || adapter == null || policy == null || authority == null) throw new IllegalArgumentException("Missing job service dependency");
        if (launchWorkers < 1 || launchWorkers > JobIoPool.MAX_JOBS) throw new IllegalArgumentException("Invalid launcher count");
        mRegistry = registry; mAdapter = adapter; mPolicy = policy; mAuthority = authority;
        mLaunchers = new ThreadPoolExecutor(launchWorkers, launchWorkers, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(JobIoPool.MAX_JOBS), task -> {
                Thread thread = new Thread(task, "TermuxJobLaunch"); thread.setDaemon(true); return thread;
            });
        mLaunchers.allowCoreThreadTimeOut(true);
        mTimers = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "TermuxJobTimer"); thread.setDaemon(true); return thread;
        });
        mTimers.setRemoveOnCancelPolicy(true);
    }

    /** Must run off the service/main thread; policy resolution may do filesystem I/O. */
    public StartResult start(JobSpec spec, JobAuthority.Lease lease) {
        if (spec == null) throw new IllegalArgumentException("Invalid request");
        if (!mAuthority.allows(lease, JobAuthority.Permission.EXECUTE)) return new StartResult(StartStatus.DENIED, null);
        long authorizationEpoch = lease.epoch();
        final Resolution resolution;
        try { resolution = mPolicy.authorizeAndResolve(spec, authorizationEpoch); }
        catch (SecurityException e) { return new StartResult(StartStatus.DENIED, null); }
        catch (RuntimeException e) { return new StartResult(StartStatus.UNAVAILABLE, null); }
        if (resolution == null) return new StartResult(StartStatus.DENIED, null);
        // Never accept a wall timeout that cannot currently terminate a live Android child safely.
        if (spec.timeoutMillis() != 0) return new StartResult(StartStatus.UNSUPPORTED, null);
        synchronized (this) {
            if (mStopping) return new StartResult(StartStatus.UNAVAILABLE, null);
            if (!mAuthority.allows(lease, JobAuthority.Permission.EXECUTE)) return new StartResult(StartStatus.DENIED, null);
            JobRegistry.Admission admission = mRegistry.admit(spec, authorizationEpoch, 5000);
            if (admission.status != JobRegistry.Status.ACCEPTED) {
                StartStatus status = admission.status == JobRegistry.Status.REPLAY ? StartStatus.REPLAY :
                    admission.status == JobRegistry.Status.CONFLICT ? StartStatus.CONFLICT : StartStatus.LIMIT_REACHED;
                return new StartResult(status, admission.entry == null ? null : admission.entry.record().snapshot());
            }
            Work work = new Work(admission.entry, spec, resolution, lease);
            mWork.put(admission.entry.record().snapshot().id, work);
            try { mLaunchers.execute(work); }
            catch (RejectedExecutionException e) {
                work.failQueued();
            }
            return new StartResult(StartStatus.ACCEPTED, admission.entry.record().snapshot());
        }
    }

    /** Scope checks must occur before calling; only the current epoch can access a job. */
    public CancelStatus cancel(String id, JobAuthority.Lease lease) {
        synchronized (this) {
            if (!mAuthority.allows(lease, JobAuthority.Permission.CANCEL)) return CancelStatus.DENIED;
            JobRegistry.Entry entry = mRegistry.get(id, lease.epoch());
            if (entry == null) return CancelStatus.NOT_FOUND;
            Work work = mWork.get(id);
            if (work == null || JobRecord.isTerminal(entry.record().snapshot().state)) return CancelStatus.FINISHED;
            if (!work.claimed) {
                work.cancelQueued(); mLaunchers.remove(work); return CancelStatus.ACCEPTED;
            }
            OwnedProcessControl.Request request = work.control.requestStop(JobRecord.StopReason.CANCEL);
            switch (request) {
                case ACCEPTED: return CancelStatus.ACCEPTED;
                case ALREADY_REQUESTED: return CancelStatus.ALREADY_REQUESTED;
                case UNSUPPORTED: return CancelStatus.UNSUPPORTED;
                default: return CancelStatus.FINISHED;
            }
        }
    }

    /** Stop admission and abandon only queued work. Live children are never forgotten or signalled by PID. */
    public synchronized void stopAccepting() {
        if (mStopping) return;
        mStopping = true;
        for (Work work : mWork.values()) if (!work.claimed && !work.done) { work.failQueued(); mLaunchers.remove(work); }
        mLaunchers.shutdown();
    }

    /** Close only after all launched children have reported an outcome; false means ownership remains live. */
    public synchronized boolean closeIfIdle() {
        if (mClosed) return true;
        if (!mStopping) return false;
        for (Work work : mWork.values()) if (!JobRecord.isTerminal(work.entry.record().snapshot().state)) return false;
        mClosed = true; mTimers.shutdown(); mRegistry.close(); mWork.clear();
        return true;
    }

    private final class Work implements Runnable {
        final JobRegistry.Entry entry;
        final OwnedProcessControl control;
        final JobAuthority.Lease lease;
        JobSpec spec;
        Resolution resolution;
        boolean claimed, done;
        Work(JobRegistry.Entry entry, JobSpec spec, Resolution resolution, JobAuthority.Lease lease) {
            this.entry = entry; this.spec = spec; this.resolution = resolution; this.lease = lease;
            this.control = OwnedProcessControl.forAndroid(entry.record(), mTimers, 10000);
        }
        @Override public void run() {
            synchronized (JobCoordinator.this) {
                if (done) return;
                claimed = true;
                if (!entry.record().begin()) { failQueued(); return; }
            }
            try {
                Resolution current = mPolicy.authorizeAndResolve(spec, entry.record().snapshot().authorizationEpoch);
                if (current == null || !Objects.equals(current.executable, resolution.executable) ||
                    !Objects.equals(current.cwd, resolution.cwd)) throw new SecurityException("Job authorization changed");
                // This short state check marks the operation in flight; revocation cannot undo a
                // launch already past this point, so its outcome may need reconciliation.
                if (!mAuthority.allows(lease, JobAuthority.Permission.EXECUTE)) throw new SecurityException("Job consent revoked");
                mAdapter.launchBegun(entry, spec, current.executable, current.cwd, control);
            }
            catch (RuntimeException | Error e) {
                entry.capture().discardIfUnstarted();
                entry.record().complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, System.currentTimeMillis());
                control.completed();
                if (e instanceof Error) throw (Error) e;
            } finally { synchronized (JobCoordinator.this) { spec = null; resolution = null; done = true; } }
        }
        void cancelQueued() {
            done = true; spec = null; resolution = null;
            entry.record().requestStop(JobRecord.StopReason.CANCEL);
            entry.record().complete(JobRecord.State.CANCELLED, null, null, System.currentTimeMillis());
            entry.capture().discardIfUnstarted(); control.completed();
        }
        void failQueued() {
            done = true; spec = null; resolution = null;
            entry.record().complete(JobRecord.State.FAILED, null, JobRecord.Failure.START_FAILED, System.currentTimeMillis());
            entry.capture().discardIfUnstarted(); control.completed();
        }
    }
}

package com.termux.app.control.jobs;

/** Enforces the Android thread boundary for a filesystem-backed job policy. */
public final class WorkerJobPolicy implements JobCoordinator.Policy {
    private final JobCoordinator.Policy delegate;
    public WorkerJobPolicy(JobCoordinator.Policy delegate) {
        if (delegate == null) throw new IllegalArgumentException("Missing policy");
        this.delegate = delegate;
    }
    @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) {
        requireWorker();
        return delegate.authorizeAndResolve(spec, epoch);
    }
    @Override public boolean authorizeReplay(JobSpec spec, long epoch) {
        requireWorker();
        return delegate.authorizeReplay(spec, epoch);
    }
    private static void requireWorker() {
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (main != null && android.os.Looper.myLooper() == main)
            throw new IllegalStateException("Job policy must run off the main thread");
    }
}

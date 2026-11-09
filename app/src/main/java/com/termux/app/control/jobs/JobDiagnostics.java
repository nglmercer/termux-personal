package com.termux.app.control.jobs;

import com.termux.app.monitor.CrashReasonClassifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Connects a job's lifecycle to termination evidence and the persistent history. It only observes: it never signals a
 * process, and a failure to record history never affects the job. Calls arrive on whichever thread completes the job,
 * never the main thread.
 */
public final class JobDiagnostics {
    private final JobHistoryStore mStore;
    private final JobResourceSupervisor mSupervisor;
    private final Integer mSdkInt;
    private final LongSupplier mWallClock;
    private final Map<String, Long> mStartedAt = new HashMap<>();

    /** Either collaborator may be null: no supervisor means no samples, no store means nothing is persisted. */
    public JobDiagnostics(JobHistoryStore store, JobResourceSupervisor supervisor, Integer sdkInt) {
        this(store, supervisor, sdkInt, System::currentTimeMillis);
    }

    JobDiagnostics(JobHistoryStore store, JobResourceSupervisor supervisor, Integer sdkInt, LongSupplier wallClock) {
        mStore = store; mSupervisor = supervisor; mSdkInt = sdkInt; mWallClock = wallClock;
    }

    /** Records that the job exists and is unfinished, so that a restart can recognise it as lost. */
    public synchronized void started(JobRecord record, JobResourcePolicy policy) {
        JobRecord.Snapshot snapshot = record.snapshot();
        // A job that already finished (a fast exit that raced with this call) must not be overwritten by a running record.
        if (JobRecord.isTerminal(snapshot.state)) return;
        long now = mWallClock.getAsLong();
        mStartedAt.put(snapshot.id, now);
        if (mStore != null) mStore.started(snapshot, profile(policy), now);
    }

    /** Classifies how the job ended and stores the result. */
    public synchronized JobTerminationEvidence finished(JobRecord record, JobResourcePolicy policy) {
        JobRecord.Snapshot snapshot = record.snapshot();
        Long startedWall = mStartedAt.remove(snapshot.id);
        long now = mWallClock.getAsLong();
        JobResourceSupervisor.Metrics metrics = mSupervisor == null ? null : mSupervisor.metrics(snapshot.id);
        List<JobResourceSupervisor.Event> events = mSupervisor == null ? new ArrayList<>() : new ArrayList<>(mSupervisor.events(null));
        List<String> types = new ArrayList<>();
        List<JobHistoryStore.EventRecord> stored = new ArrayList<>();
        for (JobResourceSupervisor.Event event : events) {
            boolean ours = snapshot.id.equals(event.jobId);
            // Device-wide events (no job) count only if they happened while this job could have been affected.
            boolean concurrent = event.jobId == null && (startedWall == null || event.wallMillis >= startedWall);
            if (!ours && !concurrent) continue;
            types.add(event.type);
            stored.add(new JobHistoryStore.EventRecord(event.wallMillis, event.type, event.detail));
        }
        Long cpuLimit = policy == null || policy.cpuTimeSeconds() == 0 ? null : policy.cpuTimeSeconds();
        JobTerminationEvidence evidence = CrashReasonClassifier.classify(snapshot,
            new CrashReasonClassifier.Context(mSdkInt, cpuLimit, metrics, types, false));
        if (mStore != null)
            mStore.finished(snapshot, profile(policy), evidence, metrics == null ? null : metrics.peakRssBytes, stored, startedWall, now);
        return evidence;
    }

    private static String profile(JobResourcePolicy policy) { return policy == null ? null : policy.profile().wireName(); }
}

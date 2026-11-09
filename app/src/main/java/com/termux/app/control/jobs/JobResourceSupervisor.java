package com.termux.app.control.jobs;

import com.termux.app.monitor.ProcessSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Watches the managed jobs that carry a resource policy and feeds device pressure to the admission controller.
 *
 * <p>What it is and is not:
 * <ul>
 * <li>It samples; it cannot prevent Android's low-memory killer from acting between two samples.</li>
 * <li>It samples only while a tracked job exists or jobs wait for admission, on one background thread, never
 * overlapping scans. When idle it schedules nothing.</li>
 * <li>It only ever stops a job through that job's {@link OwnedProcessControl}, and only when that job's policy says
 * {@code stop_job}. It never signals an observed PID and never touches interactive terminals or unrelated processes.</li>
 * <li>A process is attributed to a job by PID <em>and</em> start time; a recycled PID is detected and ignored.</li>
 * <li>A value it could not measure stays unknown (null) instead of becoming zero.</li>
 * </ul>
 */
public final class JobResourceSupervisor implements AutoCloseable {
    /** Everything platform specific, so the supervision logic is testable on the JVM. */
    public interface Sampler {
        /** Device memory, low-memory and thermal state; may be slow, called only from the supervisor thread. */
        PressureTracker.Reading device();
        /** Process table restricted to the app's UID with descendants of {@code roots}. */
        ProcessSnapshot processes(Set<Integer> roots);
        long monotonicMillis();
        long ticksPerSecond();
        long pageSizeBytes();
    }

    /** Observed state of one job; unknown values are null. */
    public static final class Metrics {
        public final Long rssBytes, peakRssBytes;
        public final Double cpuPercent;
        public final Integer processCount;
        public final boolean incomplete, memorySoftExceeded, identityLost;
        private Metrics(Long rss, Long peak, Double cpu, Integer count, boolean incomplete, boolean exceeded, boolean lost) {
            rssBytes = rss; peakRssBytes = peak; cpuPercent = cpu; processCount = count;
            this.incomplete = incomplete; memorySoftExceeded = exceeded; identityLost = lost;
        }
    }

    /** A bounded diagnostic record; never contains command lines, environment or output. */
    public static final class Event {
        public final long wallMillis;
        public final String jobId, type, detail;
        Event(long wallMillis, String jobId, String type, String detail) {
            this.wallMillis = wallMillis; this.jobId = jobId; this.type = type; this.detail = detail;
        }
    }

    /** Notified once per tracked job when it reaches a terminal state, with the last metrics seen. */
    public interface FinishListener { void finished(String jobId, Metrics metrics, List<Event> events); }

    static final int MAX_EVENTS = 256, MAX_FINISHED = 32, MAX_EVENTS_PER_JOB = 32;
    static final long ACTIVE_INTERVAL_MILLIS = 2000, PRESSURE_INTERVAL_MILLIS = 1000, GUARD_COOLDOWN_MILLIS = 30_000;
    static final int GUARD_CONSECUTIVE_SAMPLES = 2;

    private final Sampler mSampler;
    private final ResourceAdmissionController mAdmission;
    private final ScheduledExecutorService mExecutor;
    private final PressureTracker mTracker = new PressureTracker();
    private final Map<String, Tracked> mTracked = new LinkedHashMap<>();
    private final Map<String, Metrics> mFinished = new LinkedHashMap<String, Metrics>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Metrics> eldest) { return size() > MAX_FINISHED; }
    };
    private final ArrayDeque<Event> mEvents = new ArrayDeque<>();
    private FinishListener mListener;
    private boolean mScheduled, mClosed, mScanning;
    private long mLastGuardStop = Long.MIN_VALUE;
    private int mCriticalStreak;
    private PressureTracker.Level mReportedLevel = PressureTracker.Level.UNKNOWN;

    public JobResourceSupervisor(Sampler sampler, ResourceAdmissionController admission) {
        this(sampler, admission, newExecutor());
    }

    JobResourceSupervisor(Sampler sampler, ResourceAdmissionController admission, ScheduledExecutorService executor) {
        if (sampler == null || admission == null || executor == null) throw new IllegalArgumentException("Missing supervisor dependency");
        mSampler = sampler; mAdmission = admission; mExecutor = executor;
    }

    private static ScheduledExecutorService newExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "TermuxJobSupervisor"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Production sampler: ActivityManager/PowerManager for the device and /proc for the app's own UID. */
    public static JobResourceSupervisor forAndroid(android.content.Context context, ResourceAdmissionController admission) {
        if (context == null) throw new IllegalArgumentException("Missing context");
        final android.content.Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final long ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
        final long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGE_SIZE);
        final int uid = android.os.Process.myUid();
        return new JobResourceSupervisor(new Sampler() {
            @Override public PressureTracker.Reading device() {
                com.termux.app.monitor.DeviceSnapshot device = com.termux.app.monitor.DeviceSnapshot.collect(app);
                return new PressureTracker.Reading(device.availableMemoryBytes, device.totalMemoryBytes, device.lowMemory, device.thermalStatus);
            }
            @Override public ProcessSnapshot processes(Set<Integer> roots) {
                return ProcessSnapshot.collect(new java.io.File("/proc"), uid, roots);
            }
            @Override public long monotonicMillis() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public long ticksPerSecond() { return ticks; }
            @Override public long pageSizeBytes() { return pageSize; }
        }, admission);
    }

    public synchronized void setFinishListener(FinishListener listener) { mListener = listener; }

    /** Starts watching a launched job. A job that already finished is ignored. */
    void track(JobRecord record, OwnedProcessControl control, OwnedChildProcess process, JobResourcePolicy policy) {
        if (record == null || control == null || process == null || policy == null) throw new IllegalArgumentException("Invalid tracked job");
        synchronized (this) {
            if (mClosed || JobRecord.isTerminal(record.snapshot().state)) return;
            mTracked.put(record.snapshot().id, new Tracked(record, control, process, policy, mSampler.monotonicMillis()));
        }
        wake();
    }

    /** Ensures a scan is scheduled; called when a job is tracked or waits for admission. */
    public void wake() {
        synchronized (this) {
            if (mClosed || mScheduled) return;
            mScheduled = true;
        }
        schedule(0);
    }

    private void schedule(long delayMillis) {
        try { mExecutor.schedule(this::scan, delayMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException e) { synchronized (this) { mScheduled = false; } }
    }

    /** Last observed metrics of a tracked or recently finished job; null if unknown to the supervisor. */
    public synchronized Metrics metrics(String jobId) {
        Tracked tracked = mTracked.get(jobId);
        return tracked != null ? tracked.metrics() : mFinished.get(jobId);
    }

    public synchronized List<Event> events(String jobId) {
        List<Event> result = new ArrayList<>();
        for (Event event : mEvents) if (jobId == null || jobId.equals(event.jobId)) result.add(event);
        return Collections.unmodifiableList(result);
    }

    public synchronized PressureTracker.Level pressure() { return mTracker.level(); }

    @Override public void close() {
        synchronized (this) { if (mClosed) return; mClosed = true; mTracked.clear(); }
        mExecutor.shutdownNow();
    }

    // ---- scan ----

    private void scan() {
        // One scan at a time; the single thread already guarantees this, the flag makes the invariant explicit.
        synchronized (this) { if (mClosed || mScanning) { mScheduled = false; return; } mScanning = true; }
        long next = ACTIVE_INTERVAL_MILLIS;
        List<Runnable> deliveries = new ArrayList<>();
        try {
            final PressureTracker.Reading reading = mSampler.device();
            final Set<Integer> roots = new HashSet<>();
            synchronized (this) {
                pruneFinished(deliveries);
                for (Tracked tracked : mTracked.values()) roots.add(tracked.pid);
            }
            // The device read and the /proc scan run outside the monitor so metrics() never waits on I/O.
            final ProcessSnapshot processes = roots.isEmpty() ? null : mSampler.processes(roots);
            PressureTracker.Level level;
            synchronized (this) {
                level = mTracker.update(reading);
                long now = mSampler.monotonicMillis();
                if (processes != null) for (Tracked tracked : mTracked.values()) tracked.sample(processes, mSampler, now);
                evaluate(level, now);
                pruneFinished(deliveries);
                boolean idle = mTracked.isEmpty() && mAdmission.queuedCount() == 0 && mAdmission.activeCount() == 0;
                if (idle) { mScheduled = false; next = -1; }
                else if (level == PressureTracker.Level.ELEVATED || level == PressureTracker.Level.CRITICAL) next = PRESSURE_INTERVAL_MILLIS;
            }
            // Outside the monitor: admitting a queued job runs the owner's callback, which takes the coordinator's
            // lock, and the coordinator calls wake()/track() on this class while holding that lock.
            mAdmission.update(level, reading == null ? null : reading.availableBytes, reading == null ? null : reading.totalBytes);
        } catch (RuntimeException e) {
            // A failed sample must not stop supervision; unknown stays unknown.
            synchronized (this) { event(null, "SAMPLE_FAILED", e.getClass().getSimpleName()); }
        } finally {
            synchronized (this) { mScanning = false; }
        }
        for (Runnable delivery : deliveries) delivery.run();
        if (next >= 0) schedule(next);
    }

    /** Monitor held. Records pressure changes and applies each job's pressure action. */
    private void evaluate(PressureTracker.Level level, long now) {
        if (level != mReportedLevel) {
            event(null, "PRESSURE_" + level.name(), "");
            mReportedLevel = level;
        }
        mCriticalStreak = level == PressureTracker.Level.CRITICAL ? mCriticalStreak + 1 : 0;
        Tracked victim = null;
        for (Tracked tracked : mTracked.values()) {
            if (tracked.exceededSoft(now) && tracked.softEventAt == Long.MIN_VALUE) {
                tracked.softEventAt = now;
                event(tracked.id, "MEMORY_SOFT_EXCEEDED", "rss>" + tracked.policy.memorySoftBytes());
            } else if (!tracked.exceededSoft(now)) tracked.softEventAt = Long.MIN_VALUE;
            if (tracked.policy.onPressure() != JobResourcePolicy.PressureAction.STOP_JOB) continue;
            // A job that is already being stopped must not shadow the others while it winds down.
            if (tracked.record.snapshot().stopReason != null) continue;
            boolean pressure = level == PressureTracker.Level.CRITICAL && mCriticalStreak >= GUARD_CONSECUTIVE_SAMPLES;
            boolean overBudget = tracked.exceededSoft(now) && level != PressureTracker.Level.NORMAL && level != PressureTracker.Level.UNKNOWN;
            if (!pressure && !overBudget) continue;
            if (victim == null || tracked.lastRss() > victim.lastRss()) victim = tracked;
        }
        // At most one owned job per cooldown, the largest that opted in: never a cascade of stops.
        if (victim != null && (mLastGuardStop == Long.MIN_VALUE || now - mLastGuardStop >= GUARD_COOLDOWN_MILLIS)) {
            OwnedProcessControl.Request request = victim.control.requestStop(JobRecord.StopReason.RESOURCE_GUARD);
            if (request == OwnedProcessControl.Request.ACCEPTED) {
                mLastGuardStop = now;
                event(victim.id, "RESOURCE_GUARD_STOP", "pressure=" + level.name() + " rss=" + victim.lastRss());
            }
        }
    }

    /** Monitor held. Releases finished jobs from tracking and queues their listener delivery for after the scan. */
    private void pruneFinished(List<Runnable> deliveries) {
        for (Iterator<Tracked> it = mTracked.values().iterator(); it.hasNext();) {
            Tracked tracked = it.next();
            if (!JobRecord.isTerminal(tracked.record.snapshot().state)) continue;
            it.remove();
            final Metrics last = tracked.metrics();
            mFinished.put(tracked.id, last);
            final FinishListener listener = mListener;
            final List<Event> jobEvents = new ArrayList<>();
            for (Event event : mEvents) if (tracked.id.equals(event.jobId)) jobEvents.add(event);
            if (listener != null) deliveries.add(() -> listener.finished(tracked.id, last, jobEvents));
        }
    }

    private void event(String jobId, String type, String detail) {
        if (jobId != null) {
            int count = 0;
            for (Event event : mEvents) if (jobId.equals(event.jobId)) count++;
            if (count >= MAX_EVENTS_PER_JOB) return;
        }
        if (mEvents.size() >= MAX_EVENTS) mEvents.removeFirst();
        mEvents.addLast(new Event(System.currentTimeMillis(), jobId, type, detail));
    }

    /** Per-job accumulated observation; mutated only on the supervisor thread under the monitor. */
    private static final class Tracked {
        final String id;
        final JobRecord record;
        final OwnedProcessControl control;
        final int pid;
        final JobResourcePolicy policy;
        long startTicks = -1, softEventAt = Long.MIN_VALUE;
        long peakRss = -1, rss = -1, lastTicks = -1, lastSampleAt;
        Double cpu;
        Integer count;
        boolean incomplete, identityLost;

        Tracked(JobRecord record, OwnedProcessControl control, OwnedChildProcess process, JobResourcePolicy policy, long now) {
            this.record = record; this.control = control; this.pid = process.pid(); this.policy = policy;
            this.id = record.snapshot().id; this.lastSampleAt = now;
        }

        long lastRss() { return Math.max(rss, 0); }

        boolean exceededSoft(long now) { return policy.memorySoftBytes() != 0 && rss >= policy.memorySoftBytes(); }

        void sample(ProcessSnapshot snapshot, Sampler sampler, long now) {
            if (identityLost) return;
            incomplete = snapshot.incomplete;
            Map<Integer, ProcessSnapshot.Entry> byPid = new HashMap<>();
            Map<Integer, List<ProcessSnapshot.Entry>> children = new HashMap<>();
            for (ProcessSnapshot.Entry entry : snapshot.visibleEntries) {
                byPid.put(entry.pid, entry);
                children.computeIfAbsent(entry.ppid, ignored -> new ArrayList<>()).add(entry);
            }
            ProcessSnapshot.Entry root = byPid.get(pid);
            if (root == null) { rss = -1; count = null; cpu = null; return; } // exited, or hidden by Android: unknown, not zero
            if (startTicks < 0) startTicks = root.startTicks;
            else if (root.startTicks != startTicks) {
                // The PID now belongs to a different process: stop attributing anything to this job.
                identityLost = true; rss = -1; count = null; cpu = null; return;
            }
            long pages = 0, ticks = 0;
            int members = 0;
            ArrayDeque<ProcessSnapshot.Entry> pending = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                ProcessSnapshot.Entry entry = pending.removeFirst();
                if (!seen.add(entry.pid)) continue;
                pages += entry.rssPages; ticks += entry.userTicks + entry.systemTicks; members++;
                List<ProcessSnapshot.Entry> kids = children.get(entry.pid);
                if (kids != null) for (ProcessSnapshot.Entry kid : kids) pending.addLast(kid);
            }
            Long bytes = pages < 0 || pages > Long.MAX_VALUE / sampler.pageSizeBytes() ? null : pages * sampler.pageSizeBytes();
            rss = bytes == null ? -1 : bytes;
            if (bytes != null && bytes > peakRss) peakRss = bytes;
            count = members;
            long elapsed = now - lastSampleAt;
            // A tree that lost a member can show fewer ticks than before; that is not a usable comparison.
            cpu = lastTicks >= 0 && elapsed > 0 && ticks >= lastTicks && sampler.ticksPerSecond() > 0 ?
                100000.0 * (ticks - lastTicks) / sampler.ticksPerSecond() / elapsed : null;
            lastTicks = ticks; lastSampleAt = now;
        }

        Metrics metrics() {
            return new Metrics(rss < 0 ? null : rss, peakRss < 0 ? null : peakRss, cpu, count, incomplete,
                policy.memorySoftBytes() != 0 && peakRss >= policy.memorySoftBytes(), identityLost);
        }
    }
}

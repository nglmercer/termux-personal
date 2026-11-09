package com.termux.app.control.jobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Decides when a heavy managed job may start. It never launches anything itself: a job that is admitted is
 * handed back to its owner through a callback, so there is still exactly one process launcher.
 *
 * <p>Rules, in order, for the head of a bounded FIFO queue:
 * <ol>
 * <li>CRITICAL pressure admits nothing, and nothing is admitted until a cooldown has passed since pressure last
 * dropped below ELEVATED (the hysteresis in {@link PressureTracker} stops flapping inside a level; the cooldown stops it
 * across levels).</li>
 * <li>ELEVATED pressure halves the slot count (never below one, so a lone build is not starved), and
 * {@link JobResourcePolicy.PressureAction#PAUSE_ADMISSION} on a running job blocks admission entirely.</li>
 * <li>The number of running heavy jobs stays under a slot count derived from total RAM and the I/O worker capacity;
 * a PROTECTED job additionally requires to run alone.</li>
 * <li>Recently admitted jobs have not ramped up yet, so their estimated memory is still held back from what is
 * considered available; a burst of admissions cannot all see the same free RAM.</li>
 * </ol>
 * A reservation is released exactly once, whether the job finished, failed to launch or was cancelled while queued.
 * The queue is bounded; callers must check {@link #hasRoom()} before accepting work. Thread safe; callbacks run on the
 * calling thread after the internal lock is released, so they may take other locks.
 */
public final class ResourceAdmissionController {
    public enum Result { GRANTED, QUEUED, QUEUE_FULL }

    /** Tunables; the defaults are starting heuristics, not device classifications. */
    public static final class Config {
        public final int maxQueued;
        public final long settleMillis, cooldownMillis, defaultEstimateBytes, headroomBytes;
        public Config(int maxQueued, long settleMillis, long cooldownMillis, long defaultEstimateBytes, long headroomBytes) {
            if (maxQueued < 1 || maxQueued > 64 || settleMillis < 0 || cooldownMillis < 0 || defaultEstimateBytes < 0 || headroomBytes < 0)
                throw new IllegalArgumentException("Invalid admission configuration");
            this.maxQueued = maxQueued; this.settleMillis = settleMillis; this.cooldownMillis = cooldownMillis;
            this.defaultEstimateBytes = defaultEstimateBytes; this.headroomBytes = headroomBytes;
        }
        public static Config defaults() { return new Config(8, 10_000, 15_000, 768L << 20, 256L << 20); }
    }

    /** One job's claim. Obtain with {@link #newTicket}; release with {@link #release}. */
    public static final class Ticket {
        private enum State { NEW, QUEUED, ACTIVE, RELEASED }
        private final long mEstimateBytes;
        private final int mMaxActive;
        private final boolean mPausesAdmission;
        private State mState = State.NEW;
        private long mGrantedAt;
        private Runnable mOnGrant;
        private Ticket(long estimateBytes, int maxActive, boolean pausesAdmission) {
            mEstimateBytes = estimateBytes; mMaxActive = maxActive; mPausesAdmission = pausesAdmission;
        }
    }

    private final Config mConfig;
    private final LongSupplier mClock; // monotonic milliseconds
    private final int mHardSlotCap;
    private final ArrayDeque<Ticket> mQueue = new ArrayDeque<>();
    private final List<Ticket> mActive = new ArrayList<>();
    private PressureTracker.Level mLevel = PressureTracker.Level.UNKNOWN;
    private Long mAvailableBytes, mTotalBytes;
    private long mCalmSince = Long.MIN_VALUE; // when the level last became NORMAL; MIN_VALUE: never pressured or unknown

    public ResourceAdmissionController(Config config, int hardSlotCap, LongSupplier monotonicMillis) {
        if (config == null || monotonicMillis == null || hardSlotCap < 1 || hardSlotCap > JobIoPool.MAX_JOBS)
            throw new IllegalArgumentException("Invalid admission controller");
        mConfig = config; mHardSlotCap = hardSlotCap; mClock = monotonicMillis;
    }

    public ResourceAdmissionController() {
        this(Config.defaults(), JobIoPool.MAX_JOBS, () -> android.os.SystemClock.elapsedRealtime());
    }

    /** True when {@link #submit} can accept another job without overflowing the queue. */
    public synchronized boolean hasRoom() { return mQueue.size() < mConfig.maxQueued; }

    /** The estimate and limits derive from the job's resolved policy; a null policy never reaches this class. */
    public Ticket newTicket(JobResourcePolicy policy) {
        if (policy == null) throw new IllegalArgumentException("Missing resource policy");
        long estimate = policy.memorySoftBytes() != 0 ? policy.memorySoftBytes() : mConfig.defaultEstimateBytes;
        int maxActive = policy.profile() == JobResourcePolicy.Profile.PROTECTED ? 1 : Integer.MAX_VALUE;
        return new Ticket(estimate, maxActive, policy.onPressure() == JobResourcePolicy.PressureAction.PAUSE_ADMISSION);
    }

    /**
     * Admits immediately (GRANTED: the caller proceeds), queues the job and runs {@code onGrant} later (QUEUED), or
     * refuses. A ticket is admitted or queued at most once; one refused with QUEUE_FULL may be submitted again.
     */
    public Result submit(Ticket ticket, Runnable onGrant) {
        if (ticket == null || onGrant == null) throw new IllegalArgumentException("Invalid submission");
        Result result;
        synchronized (this) {
            if (ticket.mState != Ticket.State.NEW) throw new IllegalStateException("Ticket already submitted");
            if (mQueue.isEmpty() && canAdmit(ticket)) { activate(ticket); result = Result.GRANTED; }
            else if (mQueue.size() >= mConfig.maxQueued) result = Result.QUEUE_FULL; // the ticket stays NEW: a later retry is allowed
            else { ticket.mState = Ticket.State.QUEUED; ticket.mOnGrant = onGrant; mQueue.addLast(ticket); result = Result.QUEUED; }
        }
        return result;
    }

    /** Idempotent: only the first call has an effect. Frees a running slot or removes a queued job. */
    public void release(Ticket ticket) {
        if (ticket == null) return;
        List<Runnable> grants = new ArrayList<>();
        synchronized (this) {
            if (ticket.mState == Ticket.State.RELEASED) return;
            if (ticket.mState == Ticket.State.ACTIVE) mActive.remove(ticket);
            else if (ticket.mState == Ticket.State.QUEUED) mQueue.remove(ticket);
            ticket.mState = Ticket.State.RELEASED; ticket.mOnGrant = null;
            drain(grants);
        }
        run(grants);
    }

    /** Feeds the latest device reading and pressure level, then admits whatever the new conditions allow. */
    public void update(PressureTracker.Level level, Long availableBytes, Long totalBytes) {
        if (level == null) throw new IllegalArgumentException("Missing level");
        List<Runnable> grants = new ArrayList<>();
        synchronized (this) {
            boolean wasPressured = mLevel == PressureTracker.Level.ELEVATED || mLevel == PressureTracker.Level.CRITICAL;
            if (level == PressureTracker.Level.NORMAL && wasPressured) mCalmSince = mClock.getAsLong();
            else if (level != PressureTracker.Level.NORMAL) mCalmSince = Long.MIN_VALUE;
            mLevel = level; mAvailableBytes = availableBytes; mTotalBytes = totalBytes;
            drain(grants);
        }
        run(grants);
    }

    /** One-based position among queued jobs, or 0 when the ticket is not queued. */
    public synchronized int queuePosition(Ticket ticket) {
        int position = 1;
        for (Ticket queued : mQueue) { if (queued == ticket) return position; position++; }
        return 0;
    }

    public synchronized int activeCount() { return mActive.size(); }
    public synchronized int queuedCount() { return mQueue.size(); }
    public synchronized PressureTracker.Level level() { return mLevel; }

    /** Number of heavy jobs this device may run at once right now; always at least one. */
    public synchronized int slots() { return slotsFor(mTotalBytes, mLevel, mHardSlotCap); }

    static int slotsFor(Long totalBytes, PressureTracker.Level level, int hardCap) {
        int base;
        if (totalBytes == null || level == PressureTracker.Level.UNKNOWN) base = 1; // no data: be conservative
        else if (totalBytes < (3L << 30)) base = 1;
        else if (totalBytes < (6L << 30)) base = 2;
        else if (totalBytes < (10L << 30)) base = 3;
        else base = 4;
        return Math.max(1, Math.min(base, hardCap));
    }

    // ---- internals; the monitor is held ----

    private boolean canAdmit(Ticket ticket) {
        long now = mClock.getAsLong();
        if (mLevel == PressureTracker.Level.CRITICAL) return false;
        // Cooldown after pressure, so a level that only just recovered does not immediately refill the device.
        if (mLevel == PressureTracker.Level.NORMAL && mCalmSince != Long.MIN_VALUE && now - mCalmSince < mConfig.cooldownMillis) return false;
        int running = mActive.size();
        int limit = slots();
        if (mLevel == PressureTracker.Level.ELEVATED) {
            for (Ticket active : mActive) if (active.mPausesAdmission) return false;
            limit = Math.max(1, limit / 2); // reduced concurrency, but a lone build is never starved
        }
        if (running >= limit || running >= ticket.mMaxActive) return false;
        for (Ticket active : mActive) if (active.mMaxActive == 1) return false; // a PROTECTED job runs alone
        if (mAvailableBytes != null && running > 0) {
            long held = 0;
            for (Ticket active : mActive) if (now - active.mGrantedAt < mConfig.settleMillis) held += active.mEstimateBytes;
            if (mAvailableBytes - held < ticket.mEstimateBytes + mConfig.headroomBytes) return false;
        }
        return true;
    }

    private void activate(Ticket ticket) {
        ticket.mState = Ticket.State.ACTIVE; ticket.mGrantedAt = mClock.getAsLong(); ticket.mOnGrant = null;
        mActive.add(ticket);
    }

    /** Admits queued jobs in order for as long as the head fits; strict FIFO, so a large job is not overtaken. */
    private void drain(List<Runnable> grants) {
        for (Iterator<Ticket> it = mQueue.iterator(); it.hasNext();) {
            Ticket head = it.next();
            if (!canAdmit(head)) break;
            it.remove();
            Runnable onGrant = head.mOnGrant;
            activate(head);
            if (onGrant != null) grants.add(onGrant);
        }
    }

    private static void run(List<Runnable> grants) {
        for (Runnable grant : grants) grant.run();
    }
}

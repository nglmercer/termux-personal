package com.termux.app.control.jobs;

/**
 * Turns noisy device readings into a stable pressure level. Escalation needs consecutive worse samples and
 * recovery needs consecutive better samples that also clear a wider exit threshold, so one jittery reading
 * cannot flip admission on and off. Pure logic: no I/O, clock or Android types, and not thread safe.
 */
public final class PressureTracker {
    public enum Level { NORMAL, ELEVATED, CRITICAL, UNKNOWN }

    /** One observation; null fields are unknown, never zero. */
    public static final class Reading {
        public final Long availableBytes, totalBytes;
        public final Boolean androidLowMemory;
        /** {@code PowerManager.THERMAL_STATUS_*}, or null when the platform does not report it. */
        public final Integer thermalStatus;
        public Reading(Long availableBytes, Long totalBytes, Boolean androidLowMemory, Integer thermalStatus) {
            this.availableBytes = availableBytes; this.totalBytes = totalBytes;
            this.androidLowMemory = androidLowMemory; this.thermalStatus = thermalStatus;
        }
    }

    static final long ELEVATED_MIN_BYTES = 512L << 20, CRITICAL_MIN_BYTES = 256L << 20;
    static final int THERMAL_SEVERE = 3, THERMAL_CRITICAL = 4;
    /** Exit thresholds are this much above the enter thresholds, in percent. */
    static final int EXIT_MARGIN_PERCENT = 25;

    private final int mEscalateSamples, mRecoverSamples;
    private Level mLevel = Level.UNKNOWN;
    private Level mCandidate = Level.UNKNOWN;
    private int mCandidateCount;

    public PressureTracker() { this(2, 3); }

    public PressureTracker(int escalateSamples, int recoverSamples) {
        if (escalateSamples < 1 || recoverSamples < 1) throw new IllegalArgumentException("Invalid sample counts");
        mEscalateSamples = escalateSamples; mRecoverSamples = recoverSamples;
    }

    public Level level() { return mLevel; }

    /** Feeds one reading and returns the (possibly unchanged) level. */
    public Level update(Reading reading) {
        Level raw = classify(reading, mLevel);
        if (raw == Level.UNKNOWN) {
            // Missing data neither proves nor clears pressure: keep a known level, only start as UNKNOWN.
            mCandidate = Level.UNKNOWN; mCandidateCount = 0;
            return mLevel;
        }
        if (mLevel == Level.UNKNOWN) { mLevel = raw; mCandidate = raw; mCandidateCount = 0; return mLevel; }
        if (raw == mLevel) { mCandidate = raw; mCandidateCount = 0; return mLevel; }
        if (raw != mCandidate) { mCandidate = raw; mCandidateCount = 0; }
        mCandidateCount++;
        boolean worse = raw.ordinal() > mLevel.ordinal();
        if (mCandidateCount >= (worse ? mEscalateSamples : mRecoverSamples)) {
            mLevel = raw; mCandidateCount = 0;
        }
        return mLevel;
    }

    /**
     * Classifies one reading. Against a currently elevated or critical level the exit thresholds apply, which is
     * the hysteresis: a reading must be clearly better, not merely just under the line, to count as recovery.
     */
    static Level classify(Reading r, Level current) {
        if (r == null) return Level.UNKNOWN;
        Level memory = Level.UNKNOWN;
        if (r.availableBytes != null && r.totalBytes != null && r.totalBytes > 0) {
            long elevatedAt = Math.max(ELEVATED_MIN_BYTES, r.totalBytes / 100 * 12);
            long criticalAt = Math.max(CRITICAL_MIN_BYTES, r.totalBytes / 100 * 6);
            if (current == Level.CRITICAL) criticalAt += criticalAt * EXIT_MARGIN_PERCENT / 100;
            if (current == Level.CRITICAL || current == Level.ELEVATED) elevatedAt += elevatedAt * EXIT_MARGIN_PERCENT / 100;
            memory = r.availableBytes < criticalAt ? Level.CRITICAL : r.availableBytes < elevatedAt ? Level.ELEVATED : Level.NORMAL;
        }
        // Android's own low-memory flag is direct evidence from the platform, so it is never discounted.
        if (Boolean.TRUE.equals(r.androidLowMemory)) memory = Level.CRITICAL;
        Level thermal = Level.UNKNOWN;
        if (r.thermalStatus != null)
            thermal = r.thermalStatus >= THERMAL_CRITICAL ? Level.CRITICAL : r.thermalStatus >= THERMAL_SEVERE ? Level.ELEVATED : Level.NORMAL;
        if (memory == Level.UNKNOWN) return thermal;
        if (thermal == Level.UNKNOWN) return memory;
        return memory.ordinal() >= thermal.ordinal() ? memory : thermal;
    }
}

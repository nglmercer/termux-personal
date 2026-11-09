package com.termux.app.monitor;

import com.termux.app.control.jobs.JobResourcePolicy;
import com.termux.app.control.jobs.PressureTracker;

/**
 * Device-aware recommendations for heavy managed jobs. Pure arithmetic over measured inputs; nothing here enforces anything.
 *
 * <p>Inspired by the idea of sizing a workload from the device instead of from constants, but the numbers are independent:
 * they depend on total and available RAM, the CPU count, thermal state and pressure, and every one of them is a starting
 * heuristic to be tuned with measurements, not a claim about performance. Thermal and low-memory pressure already reach
 * {@link PressureTracker}, so the slot count reacts to them through the pressure level.
 *
 * <p>Profiles: {@code unrestricted} and {@code custom} recommend nothing (the caller's explicit values stand);
 * {@code protected} prefers stability; {@code balanced} shares the device; {@code performance} favours throughput while
 * resources allow, and still cannot bypass Android's own limits.
 */
public final class DeviceResourcePolicy {
    private DeviceResourcePolicy() { }

    private static final long GIB = 1L << 30;

    /**
     * How many heavy jobs may run at once. Starts from total RAM, falls back to one when nothing is known, halves under
     * elevated pressure and is one under critical pressure. Always at least one and never above {@code hardCap}.
     */
    public static int heavySlots(Long totalBytes, PressureTracker.Level level, int hardCap) {
        int base;
        if (totalBytes == null || level == null || level == PressureTracker.Level.UNKNOWN) base = 1;
        else if (totalBytes < 3 * GIB) base = 1;
        else if (totalBytes < 6 * GIB) base = 2;
        else if (totalBytes < 10 * GIB) base = 3;
        else base = 4;
        if (level == PressureTracker.Level.ELEVATED) base = Math.max(1, base / 2);
        if (level == PressureTracker.Level.CRITICAL) base = 1;
        return Math.max(1, Math.min(base, Math.max(1, hardCap)));
    }

    /**
     * Recommended worker count for one job's build tool, or 0 for no recommendation. The CPUs are shared among the jobs
     * that may run together, so a bigger slot count means fewer workers each. More workers are not assumed to be faster.
     */
    public static int workers(JobResourcePolicy.Profile profile, int cpuCount, int slots, PressureTracker.Level level) {
        if (profile == null || cpuCount < 1) return 0;
        int shared = Math.max(1, cpuCount / Math.max(1, slots));
        int workers;
        switch (profile) {
            case PROTECTED: workers = 1; break;
            case BALANCED: workers = Math.min(4, Math.max(1, shared / 2)); break;
            case PERFORMANCE: workers = Math.max(1, shared); break;
            default: return 0;
        }
        if (level == PressureTracker.Level.ELEVATED) workers = Math.max(1, workers / 2);
        if (level == PressureTracker.Level.CRITICAL) workers = 1;
        return workers;
    }

    /**
     * Observation threshold for a job's memory when the caller did not give one, or null for none. It is a soft
     * threshold: crossing it is recorded and, only for a job that opted into {@code stop_job}, can matter under pressure.
     * Null when the data is missing or the result would be below the policy minimum.
     */
    public static Long memorySoftBytes(JobResourcePolicy.Profile profile, Long totalBytes, Long availableBytes, int slots) {
        if (profile == null || totalBytes == null || totalBytes <= 0) return null;
        long soft;
        switch (profile) {
            case PROTECTED: {
                long byTotal = totalBytes / 100 * 40;
                soft = availableBytes == null ? byTotal : Math.min(byTotal, availableBytes / 100 * 60);
                break;
            }
            case BALANCED: soft = totalBytes / 100 * 55 / Math.max(1, slots); break;
            case PERFORMANCE: soft = totalBytes / 100 * 70 / Math.max(1, slots); break;
            default: return null;
        }
        return soft < JobResourcePolicy.MIN_MEMORY_SOFT_BYTES ? null : soft;
    }
}

package com.termux.app.monitor;

import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobResourceSupervisor;
import com.termux.app.control.jobs.JobTerminationEvidence;
import com.termux.app.control.jobs.JobTerminationEvidence.Confidence;
import com.termux.app.control.jobs.JobTerminationEvidence.Reason;
import com.termux.app.control.jobs.PressureTracker;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a finished job's record and whatever the supervisor observed into {@link JobTerminationEvidence}.
 *
 * <p>Principles:
 * <ul>
 * <li>A cause the app itself performed or saw (cancel, timeout, resource-guard stop, exit code, failed launch) is
 * CONFIRMED. A bare SIGKILL is not: it is reported as UNKNOWN_SIGKILL unless sampled evidence supports a hypothesis, and
 * even then only as a POSSIBLE_* reason with low or medium confidence.</li>
 * <li>{@code ApplicationExitInfo} describes the application process, not one native child, and privileged logcat is not
 * generally readable, so both are listed as unavailable instead of being guessed at.</li>
 * <li>Without samples the answer is UNKNOWN_SIGKILL and {@code observationsIncomplete} is true.</li>
 * </ul>
 * Pure logic with injected inputs, so it is deterministic and testable.
 */
public final class CrashReasonClassifier {
    private CrashReasonClassifier() { }

    static final int SIGKILL = 9, SIGXCPU = 24;
    private static final int ANDROID_12 = 31;

    /** Facts about the run that are not in the job record. */
    public static final class Context {
        public final Integer sdkInt;
        /** The job's CPU-time limit in seconds, or null when none was set. */
        public final Long cpuLimitSeconds;
        /** Last observed metrics; null when the job was never supervised. */
        public final JobResourceSupervisor.Metrics metrics;
        /** Supervisor event types recorded for this job and the device (for example PRESSURE_CRITICAL). */
        public final List<String> eventTypes;
        public final boolean logTruncated;
        public Context(Integer sdkInt, Long cpuLimitSeconds, JobResourceSupervisor.Metrics metrics, List<String> eventTypes, boolean logTruncated) {
            this.sdkInt = sdkInt; this.cpuLimitSeconds = cpuLimitSeconds; this.metrics = metrics;
            this.eventTypes = eventTypes == null ? new ArrayList<>() : eventTypes; this.logTruncated = logTruncated;
        }
    }

    public static JobTerminationEvidence classify(JobRecord.Snapshot job, Context ctx) {
        if (job == null || ctx == null) throw new IllegalArgumentException("Missing input");
        List<String> observed = new ArrayList<>(), inferred = new ArrayList<>(), unavailable = new ArrayList<>();
        JobResourceSupervisor.Metrics m = ctx.metrics;
        boolean sampled = m != null && m.samples > 0 && !m.identityLost;
        boolean incomplete = !sampled || m.incomplete;
        if (m == null) unavailable.add("no resource samples: the job ran without a resource policy");
        else if (m.samples == 0) unavailable.add("the job ended before its first resource sample");
        if (m != null && m.identityLost) unavailable.add("process identity lost (PID reused); later samples discarded");
        unavailable.add("ApplicationExitInfo does not describe individual native child jobs");
        unavailable.add("system logcat is not readable by the app");
        if (job.exitCode != null) observed.add("exit code " + job.exitCode);
        if (job.signal != null) observed.add("terminated by signal " + job.signal + " (" + signalName(job.signal) + ")");
        if (job.stopReason != null) observed.add("stop requested by the app: " + job.stopReason);

        Reason reason; Confidence confidence;
        switch (job.state) {
            case EXITED:
                if (job.signal == null) { reason = Reason.NORMAL_EXIT; confidence = Confidence.CONFIRMED; break; }
                // A signalled child is reported as exited when the app did not ask it to stop.
                // fall through
            case FAILED:
                if (job.failure == JobRecord.Failure.START_FAILED || job.failure == JobRecord.Failure.LIMIT_APPLY_FAILED) {
                    reason = Reason.START_FAILURE; confidence = Confidence.CONFIRMED;
                    if (job.failure == JobRecord.Failure.LIMIT_APPLY_FAILED) observed.add("a required resource limit could not be applied");
                } else if (job.failure == JobRecord.Failure.CAPTURE_FAILED) { reason = Reason.CAPTURE_FAILURE; confidence = Confidence.CONFIRMED; }
                else if (job.failure == JobRecord.Failure.SERVICE_STOPPED) { reason = Reason.SERVICE_LOST; confidence = Confidence.CONFIRMED; }
                else if (job.signal != null) { Reason[] r = signalled(job, ctx, m, sampled, observed, inferred); reason = r[0]; confidence = confidenceFor(reason, m, sampled); }
                else { reason = Reason.UNKNOWN; confidence = Confidence.NONE; }
                break;
            case CANCELLED:
                if (job.stopReason == JobRecord.StopReason.RESOURCE_GUARD) { reason = Reason.RESOURCE_GUARD_STOP; confidence = Confidence.CONFIRMED; }
                else { reason = Reason.USER_CANCELLED; confidence = Confidence.CONFIRMED; }
                break;
            case TIMED_OUT: reason = Reason.WALL_TIMEOUT; confidence = Confidence.CONFIRMED; break;
            case LOST:
                reason = Reason.SERVICE_LOST; confidence = Confidence.CONFIRMED;
                unavailable.add("the process outcome is unknown: the service restarted while the job was unfinished");
                break;
            default: reason = Reason.UNKNOWN; confidence = Confidence.NONE; break;
        }
        // A job stopped because the service was shutting down is a service event, whatever the child's signal said.
        if (job.stopReason == JobRecord.StopReason.SHUTDOWN && reason != Reason.START_FAILURE) { reason = Reason.SERVICE_LOST; confidence = Confidence.CONFIRMED; }

        return new JobTerminationEvidence(job.id, job.state, job.exitCode, job.signal, reason, confidence,
            m == null ? null : m.peakRssBytes, m == null ? null : m.availableBytesAtLastSample,
            m == null ? null : m.androidLowMemoryAtLastSample, incomplete, ctx.logTruncated, observed, inferred, unavailable);
    }

    /** Returns {reason}; fills the inference lists. Only reached for a signalled child nobody asked to stop. */
    private static Reason[] signalled(JobRecord.Snapshot job, Context ctx, JobResourceSupervisor.Metrics m, boolean sampled,
                                      List<String> observed, List<String> inferred) {
        int signal = job.signal;
        if (signal == SIGXCPU && ctx.cpuLimitSeconds != null) {
            observed.add("SIGXCPU with a configured CPU-time limit of " + ctx.cpuLimitSeconds + " s");
            return new Reason[] {Reason.CPU_LIMIT};
        }
        if (signal != SIGKILL) return new Reason[] {Reason.UNKNOWN};
        if (ctx.cpuLimitSeconds != null) inferred.add("a CPU-time limit was configured and its hard limit also ends in SIGKILL; not distinguishable");
        if (!sampled) return new Reason[] {Reason.UNKNOWN_SIGKILL};
        boolean pressure = Boolean.TRUE.equals(m.androidLowMemoryAtLastSample) ||
            m.pressureAtLastSample == PressureTracker.Level.CRITICAL || m.pressureAtLastSample == PressureTracker.Level.ELEVATED ||
            ctx.eventTypes.contains("PRESSURE_CRITICAL");
        if (pressure) {
            inferred.add("the device was under memory pressure around the last sample; the low-memory killer is a candidate, not proven");
            return new Reason[] {Reason.POSSIBLE_MEMORY_PRESSURE};
        }
        boolean ampleMemory = m.availableBytesAtLastSample != null && m.totalBytesAtLastSample != null &&
            m.availableBytesAtLastSample >= Math.max(512L << 20, m.totalBytesAtLastSample / 100 * 12);
        if (ctx.sdkInt != null && ctx.sdkInt >= ANDROID_12 && ampleMemory && !m.incomplete) {
            inferred.add("Android 12+ with ample free memory at the last sample; the phantom-process policy is a candidate, not proven");
            return new Reason[] {Reason.POSSIBLE_PHANTOM_PROCESS_POLICY};
        }
        return new Reason[] {Reason.UNKNOWN_SIGKILL};
    }

    private static Confidence confidenceFor(Reason reason, JobResourceSupervisor.Metrics m, boolean sampled) {
        switch (reason) {
            case CPU_LIMIT: return Confidence.MEDIUM; // the signal is observed, the limit is configured; still no kernel statement
            case POSSIBLE_MEMORY_PRESSURE: return Boolean.TRUE.equals(m == null ? null : m.androidLowMemoryAtLastSample) ? Confidence.MEDIUM : Confidence.LOW;
            case POSSIBLE_PHANTOM_PROCESS_POLICY: return Confidence.LOW;
            default: return Confidence.NONE;
        }
    }

    static String signalName(int signal) {
        switch (signal) {
            case 1: return "SIGHUP"; case 2: return "SIGINT"; case 3: return "SIGQUIT"; case 4: return "SIGILL"; case 6: return "SIGABRT";
            case 7: return "SIGBUS"; case 8: return "SIGFPE"; case 9: return "SIGKILL"; case 11: return "SIGSEGV"; case 13: return "SIGPIPE";
            case 15: return "SIGTERM"; case 24: return "SIGXCPU"; default: return "signal " + signal;
        }
    }
}

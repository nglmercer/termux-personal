package com.termux.app.monitor;

import static org.junit.Assert.*;

import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobResourcePolicy;
import com.termux.app.control.jobs.JobResourceSupervisor;
import com.termux.app.control.jobs.JobTerminationEvidence;
import com.termux.app.control.jobs.JobTerminationEvidence.Confidence;
import com.termux.app.control.jobs.JobTerminationEvidence.Reason;
import com.termux.app.control.jobs.PressureTracker;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class CrashReasonClassifierTest {
    private static final long MIB = 1L << 20, GIB = 1L << 30;

    private static JobRecord.Snapshot finish(JobRecord.State state, Integer exit, Integer signal, JobRecord.Failure failure,
                                             JobRecord.StopReason stop) {
        JobRecord record = new JobRecord("job_x", null, 1);
        if (state != JobRecord.State.LOST || true) record.begin();
        if (state != JobRecord.State.FAILED || failure == JobRecord.Failure.CAPTURE_FAILED || true) record.started(null, 1);
        if (stop != null) record.requestStop(stop);
        record.observedSignal(signal);
        record.complete(state, exit, failure, 9);
        return record.snapshot();
    }

    /** The metrics constructor is private to the supervisor; build the real thing through reflection to avoid a test-only seam. */
    private static JobResourceSupervisor.Metrics metrics(Long peak, Long avail, Long total, Boolean low, PressureTracker.Level level,
                                                         int samples, boolean incomplete) {
        try {
            Constructor<JobResourceSupervisor.Metrics> c = JobResourceSupervisor.Metrics.class.getDeclaredConstructor(Long.class, Long.class,
                Double.class, Integer.class, boolean.class, boolean.class, boolean.class, Long.class, Long.class, Boolean.class,
                PressureTracker.Level.class, int.class, List.class);
            c.setAccessible(true);
            return c.newInstance(peak, peak, null, 3, incomplete, false, false, avail, total, low, level, samples, Collections.<Integer>emptyList());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static CrashReasonClassifier.Context ctx(Integer sdk, Long cpu, JobResourceSupervisor.Metrics m, String... events) {
        return new CrashReasonClassifier.Context(sdk, cpu, m, java.util.Arrays.asList(events), false);
    }

    @Test public void actionsTheAppPerformedAreConfirmed() {
        assertEquals(Reason.NORMAL_EXIT, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 0, null, null, null), ctx(34, null, null)).reason);
        JobTerminationEvidence cancel = CrashReasonClassifier.classify(finish(JobRecord.State.CANCELLED, null, 15, null, JobRecord.StopReason.CANCEL), ctx(34, null, null));
        assertEquals(Reason.USER_CANCELLED, cancel.reason); assertEquals(Confidence.CONFIRMED, cancel.confidence);
        JobTerminationEvidence guard = CrashReasonClassifier.classify(finish(JobRecord.State.CANCELLED, null, 15, null, JobRecord.StopReason.RESOURCE_GUARD), ctx(34, null, null));
        assertEquals(Reason.RESOURCE_GUARD_STOP, guard.reason); assertEquals(Confidence.CONFIRMED, guard.confidence);
        JobTerminationEvidence timeout = CrashReasonClassifier.classify(finish(JobRecord.State.TIMED_OUT, null, 9, null, JobRecord.StopReason.TIMEOUT), ctx(34, null, null));
        assertEquals(Reason.WALL_TIMEOUT, timeout.reason);
        assertEquals("a SIGKILL the app sent after its own timeout is the timeout, not a mystery", Confidence.CONFIRMED, timeout.confidence);
    }

    @Test public void failuresAreClassifiedFromTheRecord() {
        assertEquals(Reason.START_FAILURE, CrashReasonClassifier.classify(finish(JobRecord.State.FAILED, null, null, JobRecord.Failure.START_FAILED, null), ctx(34, null, null)).reason);
        JobTerminationEvidence limit = CrashReasonClassifier.classify(finish(JobRecord.State.FAILED, null, null, JobRecord.Failure.LIMIT_APPLY_FAILED, null), ctx(34, null, null));
        assertEquals(Reason.START_FAILURE, limit.reason);
        assertTrue(limit.observed.toString().contains("resource limit"));
        assertEquals(Reason.CAPTURE_FAILURE, CrashReasonClassifier.classify(finish(JobRecord.State.FAILED, 0, null, JobRecord.Failure.CAPTURE_FAILED, null), ctx(34, null, null)).reason);
        assertEquals(Reason.SERVICE_LOST, CrashReasonClassifier.classify(finish(JobRecord.State.FAILED, null, null, JobRecord.Failure.SERVICE_STOPPED, null), ctx(34, null, null)).reason);
    }

    @Test public void aBareSigkillWithoutSamplesIsUnknownAndNeverOom() {
        JobTerminationEvidence e = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, null, null));
        assertEquals(Reason.UNKNOWN_SIGKILL, e.reason);
        assertNotEquals(Confidence.CONFIRMED, e.confidence);
        assertTrue(e.observationsIncomplete);
        assertTrue(e.unavailable.toString().contains("no resource samples"));
    }

    @Test public void memoryPressureEvidenceGivesAPossibleReasonNeverAConfirmedOne() {
        JobResourceSupervisor.Metrics m = metrics(780 * MIB, 210 * MIB, 4 * GIB, true, PressureTracker.Level.CRITICAL, 12, false);
        JobTerminationEvidence e = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, null, m));
        assertEquals(Reason.POSSIBLE_MEMORY_PRESSURE, e.reason);
        assertEquals("the platform's own low-memory flag raises confidence to medium, no further", Confidence.MEDIUM, e.confidence);
        assertEquals(Long.valueOf(780 * MIB), e.peakRssBytes);
        assertEquals(Boolean.TRUE, e.androidLowMemoryAtLastSample);
        assertFalse(e.inferred.isEmpty());
        JobResourceSupervisor.Metrics onlyElevated = metrics(780 * MIB, 600 * MIB, 4 * GIB, false, PressureTracker.Level.ELEVATED, 12, false);
        assertEquals(Confidence.LOW, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, null, onlyElevated)).confidence);
        JobResourceSupervisor.Metrics viaEvent = metrics(780 * MIB, 3 * GIB, 8 * GIB, false, PressureTracker.Level.NORMAL, 12, false);
        assertEquals(Reason.POSSIBLE_MEMORY_PRESSURE, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, null, viaEvent, "PRESSURE_CRITICAL")).reason);
    }

    @Test public void phantomProcessPolicyIsOnlyOfferedWhenMemoryWasAmpleOnAndroid12Plus() {
        JobResourceSupervisor.Metrics roomy = metrics(300 * MIB, 5 * GIB, 8 * GIB, false, PressureTracker.Level.NORMAL, 12, false);
        JobTerminationEvidence on13 = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(33, null, roomy));
        assertEquals(Reason.POSSIBLE_PHANTOM_PROCESS_POLICY, on13.reason); assertEquals(Confidence.LOW, on13.confidence);
        assertEquals("Android 11 has no phantom-process killer", Reason.UNKNOWN_SIGKILL,
            CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(30, null, roomy)).reason);
        JobResourceSupervisor.Metrics partial = metrics(300 * MIB, 5 * GIB, 8 * GIB, false, PressureTracker.Level.NORMAL, 12, true);
        assertEquals("a partial process table is not enough to point at a policy", Reason.UNKNOWN_SIGKILL,
            CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(33, null, partial)).reason);
        JobResourceSupervisor.Metrics neverSampled = metrics(null, null, null, null, null, 0, false);
        assertEquals(Reason.UNKNOWN_SIGKILL, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(33, null, neverSampled)).reason);
    }

    @Test public void cpuLimitNeedsTheSignalAndAConfiguredLimit() {
        assertEquals(Reason.CPU_LIMIT, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 152, 24, null, null), ctx(34, 60L, null)).reason);
        assertEquals("SIGXCPU with no configured limit is just a signal", Reason.UNKNOWN,
            CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 152, 24, null, null), ctx(34, null, null)).reason);
        JobTerminationEvidence kill = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, 60L, null));
        assertEquals("the CPU hard limit also ends in SIGKILL and cannot be told apart", Reason.UNKNOWN_SIGKILL, kill.reason);
        assertTrue(kill.inferred.toString().contains("not distinguishable"));
    }

    @Test public void otherSignalsAreReportedWithoutInventingACause() {
        JobTerminationEvidence segv = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 139, 11, null, null), ctx(34, null, null));
        assertEquals(Reason.UNKNOWN, segv.reason);
        assertTrue(segv.observed.toString().contains("SIGSEGV"));
    }

    @Test public void shutdownStopAndLostRecordsAreServiceEvents() {
        assertEquals(Reason.SERVICE_LOST, CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, null == null ? 143 : 0, 15, null, JobRecord.StopReason.SHUTDOWN), ctx(34, null, null)).reason);
        JobRecord lost = new JobRecord("job_l", null, 1);
        lost.begin(); lost.started(null, 1);
        lost.complete(JobRecord.State.LOST, null, null, 5);
        JobTerminationEvidence e = CrashReasonClassifier.classify(lost.snapshot(), ctx(34, null, null));
        assertEquals(Reason.SERVICE_LOST, e.reason);
        assertTrue(e.unavailable.toString().contains("outcome is unknown"));
    }

    @Test public void aConfirmedInferenceIsRejectedByConstruction() {
        try {
            new JobTerminationEvidence("j", JobRecord.State.EXITED, 137, 9, Reason.POSSIBLE_MEMORY_PRESSURE, Confidence.CONFIRMED,
                null, null, null, true, false, Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList());
            fail("must not be possible");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void jsonRoundTripsAndTolerantlyReadsUnknownReasons() {
        JobResourceSupervisor.Metrics m = metrics(780 * MIB, 210 * MIB, 4 * GIB, true, PressureTracker.Level.CRITICAL, 5, false);
        JobTerminationEvidence e = CrashReasonClassifier.classify(finish(JobRecord.State.EXITED, 137, 9, null, null), ctx(34, null, m));
        JobTerminationEvidence back = JobTerminationEvidence.fromJson(e.toJson());
        assertEquals(e.reason, back.reason); assertEquals(e.confidence, back.confidence); assertEquals(e.peakRssBytes, back.peakRssBytes);
        assertEquals(e.inferred, back.inferred); assertEquals(e.signal, back.signal);
        com.google.gson.JsonObject future = e.toJson(); future.addProperty("reason", "SOMETHING_FROM_THE_FUTURE");
        assertEquals(Reason.UNKNOWN, JobTerminationEvidence.fromJson(future).reason);
        assertNull(JobTerminationEvidence.fromJson(new com.google.gson.JsonObject()));
    }
}

package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

/** Admission and resource policy as seen through the coordinator, with a fake owned backend. */
public class JobCoordinatorResourceTest {
    private static final long GIB = 1L << 30;
    private final AtomicLong clock = new AtomicLong(1000);
    private final AtomicInteger launches = new AtomicInteger();
    private final AtomicBoolean owned = new AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicLong minAddressSpace = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean failLaunch = new AtomicBoolean(), failLimit = new AtomicBoolean();
    private final CountDownLatch finishJobs = new CountDownLatch(1);
    private JobRegistry registry;
    private JobCoordinator coordinator;
    private JobHistoryStore history;
    private java.io.File historyRoot;
    private final java.util.concurrent.atomic.AtomicInteger exitSignal = new java.util.concurrent.atomic.AtomicInteger();
    private ResourceAdmissionController admission;
    private final JobAuthority authority = new JobAuthority();
    private final JobAuthority.Lease lease = authority.grant(EnumSet.of(JobAuthority.Permission.EXECUTE, JobAuthority.Permission.CANCEL,
        JobAuthority.Permission.READ));

    private void create(int maxQueued) throws Exception {
        registry = new JobRegistry(new JobIoPool(4), new JobOutputStore(1024, 8, 32 * 1024), 8);
        admission = new ResourceAdmissionController(new ResourceAdmissionController.Config(maxQueued, 0, 0, 256L << 20, 0), 4, clock::get);
        admission.update(PressureTracker.Level.NORMAL, 10 * GIB, 12 * GIB);
        AppShellJobAdapter adapter = new AppShellJobAdapter(new AppShellJobAdapter.Backend() {
            @Override public Process launch(JobSpec job, String exe, String cwd, AppShellJobCapture capture, AppShellJobAdapter.Completion done) {
                launches.incrementAndGet();
                if (failLimit.get()) throw new AppShellJobAdapter.LimitApplyFailedException();
                if (failLaunch.get()) throw new IllegalStateException("launch failed");
                capture.discardBeforeLaunch();
                OwnedChildProcess child = new OwnedChildProcess(77, new OwnedChildProcess.Ops() {
                    @Override public int waitExit(int pid) { await(finishJobs); return 0; }
                    @Override public int reap(int pid, int[] out) { out[0] = 1; out[1] = 0; return 0; }
                    @Override public int signal(int pid, int signal) { finishJobs.countDown(); return 0; }
                }, new ByteArrayOutputStream(), new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]));
                Thread waiter = new Thread(() -> { int code = child.waitFor(); int sig = exitSignal.get(); done.finished(sig == 0 ? code : 128 + sig, false, sig == 0 ? null : sig); });
                waiter.setDaemon(true); waiter.start();
                return child;
            }
            @Override public boolean ownsChildIdentity() { return owned.get(); }
            @Override public long minAddressSpaceBytes() { return minAddressSpace.get(); }
        });
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec job, long epoch) { return new JobCoordinator.Resolution("/bin/echo", "/home"); }
            @Override public boolean authorizeReplay(JobSpec job, long epoch) { return true; }
        };
        historyRoot = java.nio.file.Files.createTempDirectory("coord-history").toFile();
        history = new JobHistoryStore(historyRoot);
        history.open();
        coordinator = new JobCoordinator(registry, adapter, policy, authority, 4, admission, null, new JobDiagnostics(history, null, 34));
    }

    @After public void tearDown() throws Exception {
        finishJobs.countDown();
        if (coordinator != null) { coordinator.stopAccepting(); for (int i = 0; i < 200 && !coordinator.closeIfIdle(); i++) Thread.sleep(10); }
    }

    private static JobSpec spec(String id, JobResourcePolicy resources) {
        return new JobSpec("echo", Collections.singletonList("x"), null, null, Collections.emptyMap(), null, 0,
            JobSpec.Presentation.BACKGROUND, id, resources);
    }
    private static JobResourcePolicy balanced() { return JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED); }
    private JobRecord record(JobCoordinator.StartResult r) { return registry.get(r.job.id, lease.epoch()).record(); }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static boolean until(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 400; i++) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }

    @Test public void aPolicyJobWaitsUnderCriticalPressureAndStartsAfterRecovery() throws Exception {
        create(4);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        JobCoordinator.StartResult started = coordinator.start(spec("a", balanced()), lease);
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, started.status);
        assertEquals(JobRecord.State.QUEUED, started.job.state);
        assertEquals(1, coordinator.queuePosition(started.job.id));
        Thread.sleep(100);
        assertEquals("nothing is launched while the device is under critical pressure", 0, launches.get());
        admission.update(PressureTracker.Level.NORMAL, 10 * GIB, 12 * GIB);
        assertTrue(until(() -> record(started).snapshot().state == JobRecord.State.RUNNING));
        assertEquals(0, coordinator.queuePosition(started.job.id));
        assertEquals(1, admission.activeCount());
        finishJobs.countDown();
        assertTrue(until(() -> JobRecord.isTerminal(record(started).snapshot().state)));
        assertTrue("the slot is released when the job ends", until(() -> admission.activeCount() == 0));
    }

    @Test public void cancellingAQueuedPolicyJobNeverLaunchesItAndFreesItsPlace() throws Exception {
        create(4);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        JobCoordinator.StartResult first = coordinator.start(spec("a", balanced()), lease);
        JobCoordinator.StartResult second = coordinator.start(spec("b", balanced()), lease);
        assertEquals(2, coordinator.queuePosition(second.job.id));
        assertEquals(JobCoordinator.CancelStatus.ACCEPTED, coordinator.cancel(first.job.id, lease));
        assertEquals(JobRecord.State.CANCELLED, record(first).snapshot().state);
        assertEquals("the next job moves up", 1, coordinator.queuePosition(second.job.id));
        assertEquals(1, admission.queuedCount());
        admission.update(PressureTracker.Level.NORMAL, 10 * GIB, 12 * GIB);
        assertTrue(until(() -> record(second).snapshot().state == JobRecord.State.RUNNING));
        assertEquals("only the surviving job was launched", 1, launches.get());
    }

    @Test public void jobsWithoutResourcesAndUnrestrictedJobsBypassAdmission() throws Exception {
        create(4);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        JobCoordinator.StartResult plain = coordinator.start(spec("plain", null), lease);
        JobCoordinator.StartResult free = coordinator.start(spec("free", JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.UNRESTRICTED)), lease);
        assertTrue(until(() -> record(plain).snapshot().state == JobRecord.State.RUNNING));
        assertTrue(until(() -> record(free).snapshot().state == JobRecord.State.RUNNING));
        assertEquals(0, admission.activeCount());
        assertEquals(0, admission.queuedCount());
    }

    @Test public void aFullQueueRefusesWithResourceLimitedAndLeavesNoRecord() throws Exception {
        create(2);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("a", balanced()), lease).status);
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("b", balanced()), lease).status);
        JobCoordinator.StartResult refused = coordinator.start(spec("c", balanced()), lease);
        assertEquals(JobCoordinator.StartStatus.RESOURCE_LIMITED, refused.status);
        assertNull(refused.job);
        assertEquals(2, registry.list(lease.epoch()).size());
        // A retry after room appears is a fresh admission, not a replay of a phantom record.
        admission.update(PressureTracker.Level.NORMAL, 10 * GIB, 12 * GIB);
        assertTrue(until(() -> admission.queuedCount() == 0));
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("c", balanced()), lease).status);
    }

    @Test public void aHardLimitThisDeviceCannotEnforceIsRefusedNotDropped() throws Exception {
        create(4);
        owned.set(false);
        JobResourcePolicy hard = JobResourcePolicy.builder().addressSpaceBytes(4L * GIB).build();
        assertEquals(JobCoordinator.StartStatus.UNSUPPORTED, coordinator.start(spec("hard", hard), lease).status);
        assertTrue(registry.list(lease.epoch()).isEmpty());
        assertEquals(0, launches.get());
        // Best effort priority alone is accepted even without native support.
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("nice", balanced()), lease).status);
    }

    @Test public void theReservationIsReleasedWhenLaunchFailsAndOnlyOnce() throws Exception {
        create(4);
        failLaunch.set(true);
        JobCoordinator.StartResult failed = coordinator.start(spec("f", balanced()), lease);
        assertTrue(until(() -> JobRecord.isTerminal(record(failed).snapshot().state)));
        assertEquals(JobRecord.Failure.START_FAILED, record(failed).snapshot().failure);
        assertTrue(until(() -> admission.activeCount() == 0));
        failLaunch.set(false);
        JobCoordinator.StartResult ok = coordinator.start(spec("g", balanced()), lease);
        assertTrue(until(() -> record(ok).snapshot().state == JobRecord.State.RUNNING));
        assertEquals("the failed job did not leave a leaked slot or a double release", 1, admission.activeCount());
    }

    @Test public void aFailedRequiredLimitIsReportedAsItsOwnFailure() throws Exception {
        create(4);
        failLimit.set(true);
        JobCoordinator.StartResult r = coordinator.start(spec("l", JobResourcePolicy.builder().addressSpaceBytes(2L * GIB).build()), lease);
        assertTrue(until(() -> JobRecord.isTerminal(record(r).snapshot().state)));
        assertEquals(JobRecord.State.FAILED, record(r).snapshot().state);
        assertEquals(JobRecord.Failure.LIMIT_APPLY_FAILED, record(r).snapshot().failure);
        assertTrue(until(() -> admission.activeCount() == 0));
    }

    @Test public void aReplayOfAQueuedPolicyJobReturnsTheSameRecordWithoutASecondTicket() throws Exception {
        create(4);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        JobCoordinator.StartResult first = coordinator.start(spec("same", balanced()), lease);
        JobCoordinator.StartResult replay = coordinator.start(spec("same", balanced()), lease);
        assertEquals(JobCoordinator.StartStatus.REPLAY, replay.status);
        assertEquals(first.job.id, replay.job.id);
        assertEquals("a replay does not queue a second job", 1, admission.queuedCount());
        JobCoordinator.StartResult conflict = coordinator.start(spec("same", JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.PROTECTED)), lease);
        assertEquals("different resources under the same key conflict", JobCoordinator.StartStatus.CONFLICT, conflict.status);
        assertEquals(1, admission.queuedCount());
    }

    @Test public void everyJobGetsEvidenceAndAPersistentRecordIncludingUnrestrictedOnes() throws Exception {
        create(4);
        JobCoordinator.StartResult plain = coordinator.start(spec("plain", null), lease);
        assertTrue(until(() -> record(plain).snapshot().state == JobRecord.State.RUNNING));
        assertTrue("an unfinished job is already on disk so that a restart can recognise it", until(() -> {
            JobHistoryStore.Record r = history.get(plain.job.id); return r != null && !r.finished();
        }));
        finishJobs.countDown();
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(plain.job.id); return r != null && r.finished(); }));
        JobHistoryStore.Record done = history.get(plain.job.id);
        assertEquals(JobTerminationEvidence.Reason.NORMAL_EXIT, done.evidence.reason);
        assertTrue("no supervisor was involved, so the evidence says it is incomplete", done.evidence.observationsIncomplete);
    }

    @Test public void aSignalledChildIsNeverLabelledAsConfirmedOom() throws Exception {
        create(4);
        exitSignal.set(9);
        JobCoordinator.StartResult killed = coordinator.start(spec("killed", balanced()), lease);
        assertTrue(until(() -> record(killed).snapshot().state == JobRecord.State.RUNNING));
        finishJobs.countDown();
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(killed.job.id); return r != null && r.finished(); }));
        JobHistoryStore.Record r = history.get(killed.job.id);
        assertEquals(Integer.valueOf(9), r.signal);
        assertEquals(JobTerminationEvidence.Reason.UNKNOWN_SIGKILL, r.evidence.reason);
        assertNotEquals(JobTerminationEvidence.Confidence.CONFIRMED, r.evidence.confidence);
        assertEquals("balanced", r.resourceProfile);
        assertTrue(until(() -> admission.activeCount() == 0));
    }

    @Test public void aCancelledQueuedJobStillLeavesAnHonestRecord() throws Exception {
        create(4);
        admission.update(PressureTracker.Level.CRITICAL, 100L << 20, 12 * GIB);
        JobCoordinator.StartResult queued = coordinator.start(spec("q", balanced()), lease);
        assertEquals(JobCoordinator.CancelStatus.ACCEPTED, coordinator.cancel(queued.job.id, lease));
        assertTrue(until(() -> { JobHistoryStore.Record r = history.get(queued.job.id); return r != null && r.finished(); }));
        assertEquals("a job that never ran has no stored start", null, history.get(queued.job.id).startedAtWall);
        assertEquals(JobTerminationEvidence.Reason.USER_CANCELLED, history.get(queued.job.id).evidence.reason);
    }

    @Test public void anAddressSpaceLimitBelowTheDeviceMinimumIsRefusedBeforeAnythingIsAdmitted() throws Exception {
        create(4);
        minAddressSpace.set(4L << 30);
        assertEquals(JobCoordinator.StartStatus.UNSUPPORTED, coordinator.start(spec("low", JobResourcePolicy.builder().addressSpaceBytes(1L << 30).build()), lease).status);
        assertTrue(registry.list(lease.epoch()).isEmpty());
        assertEquals(0, launches.get());
        assertEquals(JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("ok", JobResourcePolicy.builder().addressSpaceBytes(8L << 30).build()), lease).status);
        assertEquals("a job that sets no address-space limit is unaffected", JobCoordinator.StartStatus.ACCEPTED, coordinator.start(spec("none", balanced()), lease).status);
    }
}

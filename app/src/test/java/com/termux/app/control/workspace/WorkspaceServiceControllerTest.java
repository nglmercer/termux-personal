package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import com.termux.app.control.jobs.JobAuthority;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.jobs.JobOutputStore;
import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobResourcePolicy;
import com.termux.app.control.jobs.JobSpec;
import com.termux.app.control.jobs.JobTerminationEvidence;
import com.termux.app.control.workspace.WorkspaceServiceController.Outcome;
import com.termux.app.control.workspace.WorkspaceServiceController.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

public class WorkspaceServiceControllerTest {
    private static final String WS = "ws_00000000000000aa";

    /** A fake coordinator slice: jobs finish when the test says so. */
    private static final class FakeJobs implements WorkspaceServiceController.Jobs {
        final List<JobSpec> specs = new ArrayList<>();
        final Map<String, JobRecord> records = new HashMap<>();
        final Map<String, List<Runnable>> callbacks = new HashMap<>();
        final List<String> cancelled = new ArrayList<>();
        JobCoordinator.StartStatus nextStatus = JobCoordinator.StartStatus.ACCEPTED;
        int counter;

        @Override public synchronized Started start(JobSpec spec, JobAuthority.Lease lease) {
            if (nextStatus != JobCoordinator.StartStatus.ACCEPTED) return new Started(nextStatus, null);
            specs.add(spec);
            String id = "job_" + String.format("%032x", ++counter);
            JobRecord r = new JobRecord(id, WS, 1); r.begin(); r.started(null, 1);
            records.put(id, r);
            return new Started(JobCoordinator.StartStatus.ACCEPTED, id);
        }
        @Override public synchronized JobRecord.Snapshot snapshot(String id) { return records.get(id).snapshot(); }
        @Override public void onFinished(String id, Runnable cb) {
            boolean done;
            synchronized (this) { done = JobRecord.isTerminal(records.get(id).snapshot().state); if (!done) callbacks.computeIfAbsent(id, k -> new ArrayList<>()).add(cb); }
            if (done) cb.run();
        }
        @Override public synchronized void cancel(String id) { cancelled.add(id); }
        @Override public JobCoordinator.OutputResult output(String id, JobOutputStore.Stream s, long o, int l) { return null; }

        String lastId() { return "job_" + String.format("%032x", counter); }
        void finish(String id, JobRecord.State state, Integer exit, Integer signal, JobRecord.StopReason stop) {
            JobRecord r = records.get(id);
            if (stop != null) r.requestStop(stop);
            r.observedSignal(signal);
            r.complete(state, exit, state == JobRecord.State.FAILED ? JobRecord.Failure.START_FAILED : null, 9);
            List<Runnable> list; synchronized (this) { list = callbacks.remove(id); }
            if (list != null) for (Runnable cb : list) cb.run();
        }
    }

    private static final class Defs implements WorkspaceServiceController.Definitions {
        final Map<String, WorkspaceRepository.ServiceDefinition> services = new HashMap<>();
        JobResourcePolicy.Profile profile; boolean available = true;
        @Override public Resolved resolve(String ws, String svc) throws WorkspaceRepository.WorkspaceException {
            if (!WS.equals(ws)) throw ex("NOT_FOUND");
            WorkspaceRepository.ServiceDefinition d = services.get(svc);
            if (d == null) throw ex("NOT_FOUND");
            return new Resolved(d, profile, available);
        }
        @Override public List<WorkspaceRepository.ServiceDefinition> list(String ws) { return new ArrayList<>(services.values()); }
        private static WorkspaceRepository.WorkspaceException ex(String code) { return new WorkspaceRepository.WorkspaceException(code); }
    }

    private final FakeJobs jobs = new FakeJobs();
    private final Defs defs = new Defs();
    private final AtomicLong clock = new AtomicLong(1000);
    private final Map<String, JobTerminationEvidence.Reason> reasons = new HashMap<>();
    private WorkspaceServiceController controller;
    private final JobAuthority.Lease lease = new JobAuthority().grant(EnumSet.of(JobAuthority.Permission.EXECUTE));

    private WorkspaceRepository.ServiceDefinition def(String id, WorkspaceRepository.RestartPolicy policy) throws Exception {
        WorkspaceRepository.ServiceDefinition d = new WorkspaceRepository.ServiceDefinition(id, "node", Arrays.asList("server.js"), policy, null, Arrays.asList(3000));
        defs.services.put(id, d);
        return d;
    }

    private void controller(long base, int maxRestarts) {
        controller = new WorkspaceServiceController(defs, jobs, id -> {
            JobTerminationEvidence.Reason r = reasons.get(id);
            return r == null ? null : new JobTerminationEvidence(id, JobRecord.State.EXITED, 137, 9, r,
                r == JobTerminationEvidence.Reason.POSSIBLE_MEMORY_PRESSURE ? JobTerminationEvidence.Confidence.LOW : JobTerminationEvidence.Confidence.CONFIRMED,
                null, null, null, false, false, Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList());
        }, new WorkspaceServiceController.Config(base, base * 8, 600_000, maxRestarts), clock::get);
    }

    @After public void tearDown() { if (controller != null) controller.close(); }

    private static boolean until(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 300; i++) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }
    private String state(String svc) {
        try { return (String) controller.status(WS, svc).get("state"); }
        catch (WorkspaceRepository.WorkspaceException e) { throw new AssertionError(e); }
    }

    @Test public void startUsesTheStoredDefinitionAndNeverClientSuppliedCommands() throws Exception {
        controller(20, 3); def("dev", WorkspaceRepository.RestartPolicy.NEVER);
        assertEquals(Outcome.STARTED, controller.start(WS, "dev", lease));
        JobSpec spec = jobs.specs.get(0);
        assertEquals("node", spec.executable()); assertEquals(Arrays.asList("server.js"), spec.arguments()); assertEquals(WS, spec.workspaceId());
        assertNull("no profile anywhere: historical behavior", spec.resources());
        assertEquals("running", state("dev"));
        assertEquals(Outcome.ALREADY_RUNNING, controller.start(WS, "dev", lease));
        assertEquals(1, jobs.specs.size());
        assertEquals(Outcome.NOT_FOUND, controller.start(WS, "missing", lease));
    }

    @Test public void theServiceOrWorkspaceProfileSelectsTheResourcePolicy() throws Exception {
        controller(20, 3);
        defs.profile = JobResourcePolicy.Profile.BALANCED;
        def("a", WorkspaceRepository.RestartPolicy.NEVER);
        controller.start(WS, "a", lease);
        assertEquals(JobResourcePolicy.Profile.BALANCED, jobs.specs.get(0).resources().profile());
        WorkspaceRepository.ServiceDefinition own = new WorkspaceRepository.ServiceDefinition("b", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER,
            JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.PROTECTED), Collections.<Integer>emptyList());
        defs.services.put("b", own);
        controller.start(WS, "b", lease);
        assertEquals("the service's own policy wins", JobResourcePolicy.Profile.PROTECTED, jobs.specs.get(1).resources().profile());
    }

    @Test public void neverRestartsByDefault() throws Exception {
        controller(10, 3); def("dev", WorkspaceRepository.RestartPolicy.NEVER);
        controller.start(WS, "dev", lease);
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        Thread.sleep(80);
        assertEquals("stopped", state("dev")); assertEquals(1, jobs.specs.size());
        assertEquals(Integer.valueOf(1), controller.status(WS, "dev").get("lastExitCode"));
    }

    @Test public void onFailureRestartsAfterBackoffButNotAfterACleanExit() throws Exception {
        controller(20, 5); def("dev", WorkspaceRepository.RestartPolicy.ON_FAILURE);
        controller.start(WS, "dev", lease);
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        assertEquals("backoff", state("dev"));
        assertNotNull(controller.status(WS, "dev").get("nextStartInMillis"));
        assertTrue(until(() -> jobs.specs.size() == 2));
        assertTrue(until(() -> "running".equals(state("dev"))));
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 0, null, null);
        Thread.sleep(100);
        assertEquals("a clean exit is not a failure", 2, jobs.specs.size());
        assertEquals("stopped", state("dev"));
    }

    @Test public void alwaysRestartsEvenAfterACleanExit() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 0, null, null);
        assertTrue(until(() -> jobs.specs.size() == 2));
    }

    @Test public void backoffGrowsAndRepeatedCrashesEndInACrashLoop() throws Exception {
        controller(10, 2); def("dev", WorkspaceRepository.RestartPolicy.ON_FAILURE);
        controller.start(WS, "dev", lease);
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);               // failure 1 -> backoff 10 ms
        assertTrue(until(() -> jobs.specs.size() == 2));
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);               // failure 2 -> backoff 20 ms
        assertTrue(until(() -> jobs.specs.size() == 3));
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);               // window already holds 2 restarts
        assertEquals("crash_loop", state("dev"));
        Thread.sleep(120);
        assertEquals("no further automatic attempts", 3, jobs.specs.size());
        assertEquals(Outcome.STARTED, controller.start(WS, "dev", lease)); // an explicit start is a fresh decision
        assertEquals(4, jobs.specs.size());
    }

    @Test public void memoryRelatedEndsAreNotRetried() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        String id = jobs.lastId();
        reasons.put(id, JobTerminationEvidence.Reason.POSSIBLE_MEMORY_PRESSURE);
        jobs.finish(id, JobRecord.State.EXITED, 137, 9, null);
        Thread.sleep(100);
        assertEquals("memory_stopped", state("dev"));
        assertEquals(1, jobs.specs.size());
        assertEquals("possible_memory_pressure", controller.status(WS, "dev").get("lastReason"));
    }

    @Test public void anExplicitStopCancelsThroughTheCoordinatorAndBeatsAPendingRestart() throws Exception {
        controller(200, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        String id = jobs.lastId();
        assertEquals(Outcome.STOPPED, controller.stop(WS, "dev"));
        assertEquals(Arrays.asList(id), jobs.cancelled);
        assertEquals("stopping", state("dev"));
        jobs.finish(id, JobRecord.State.CANCELLED, null, 15, JobRecord.StopReason.CANCEL);
        Thread.sleep(80);
        assertEquals("stopped", state("dev"));
        assertEquals("never restarted after an explicit stop", 1, jobs.specs.size());
        // Stop during backoff cancels the timer.
        controller.start(WS, "dev", lease);
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        assertEquals("backoff", state("dev"));
        assertEquals(Outcome.STOPPED, controller.stop(WS, "dev"));
        Thread.sleep(300);
        assertEquals(2, jobs.specs.size());
        assertEquals("stopped", state("dev"));
    }

    @Test public void restartWaitsForTheOldJobToEndAndNeverRunsTwoCopies() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.NEVER);
        controller.start(WS, "dev", lease);
        String old = jobs.lastId();
        assertEquals(Outcome.RESTARTING, controller.restart(WS, "dev", lease));
        assertEquals(Arrays.asList(old), jobs.cancelled);
        assertEquals("the new copy waits for the old one", 1, jobs.specs.size());
        jobs.finish(old, JobRecord.State.CANCELLED, null, 15, JobRecord.StopReason.CANCEL);
        assertTrue(until(() -> jobs.specs.size() == 2));
        assertEquals("running", state("dev"));
        assertEquals("a restart is not a crash", Integer.valueOf(0), controller.status(WS, "dev").get("restartsInWindow"));
        // Restarting a service that is not running is simply a start.
        assertEquals(Outcome.STOPPED, controller.stop(WS, "dev"));
        jobs.finish(jobs.lastId(), JobRecord.State.CANCELLED, null, 15, JobRecord.StopReason.CANCEL);
        assertTrue(until(() -> "stopped".equals(state("dev"))));
        int before = jobs.specs.size();
        assertEquals(Outcome.STARTED, controller.restart(WS, "dev", lease));
        assertEquals(before + 1, jobs.specs.size());
    }

    @Test public void aRevokedConsentStopsTheRestartLoop() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        jobs.nextStatus = JobCoordinator.StartStatus.DENIED;
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        assertTrue(until(() -> "denied".equals(state("dev"))));
        Thread.sleep(80);
        assertEquals(1, jobs.specs.size());
        assertEquals("consent_revoked", controller.status(WS, "dev").get("lastReason"));
    }

    @Test public void aRemovedDefinitionIsNotRestarted() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        defs.services.remove("dev");
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        Thread.sleep(80);
        assertEquals(1, jobs.specs.size());
    }

    @Test public void startRefusalsAreReportedNotThrown() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.NEVER);
        jobs.nextStatus = JobCoordinator.StartStatus.RESOURCE_LIMITED;
        assertEquals(Outcome.LIMIT, controller.start(WS, "dev", lease));
        assertEquals("stopped", state("dev"));
        jobs.nextStatus = JobCoordinator.StartStatus.UNSUPPORTED;
        assertEquals(Outcome.UNSUPPORTED, controller.start(WS, "dev", lease));
        jobs.nextStatus = JobCoordinator.StartStatus.DENIED;
        assertEquals(Outcome.DENIED, controller.start(WS, "dev", lease));
        defs.available = false;
        jobs.nextStatus = JobCoordinator.StartStatus.ACCEPTED;
        assertEquals(Outcome.UNAVAILABLE, controller.start(WS, "dev", lease));
    }

    @Test public void healthProbesOnlyLoopbackAndReportsDeclaredPorts() throws Exception {
        controller(10, 5);
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            defs.services.put("web", new WorkspaceRepository.ServiceDefinition("web", "node", Collections.<String>emptyList(),
                WorkspaceRepository.RestartPolicy.NEVER, null, Arrays.asList(port)));
            Map<String, Object> down = controller.health(WS, "web", 200);
            assertEquals(Boolean.FALSE, down.get("healthy"));
            controller.start(WS, "web", lease);
            Map<String, Object> up = controller.health(WS, "web", 500);
            assertEquals(Boolean.TRUE, up.get("healthy"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) ((List<?>) up.get("portChecks")).get(0)).get("listening"));
        }
    }

    @Test public void closingStopsAllAutomaticActivity() throws Exception {
        controller(10, 5); def("dev", WorkspaceRepository.RestartPolicy.ALWAYS);
        controller.start(WS, "dev", lease);
        controller.close();
        jobs.finish(jobs.lastId(), JobRecord.State.EXITED, 1, null, null);
        Thread.sleep(80);
        assertEquals(1, jobs.specs.size());
        assertEquals(Outcome.UNAVAILABLE, controller.start(WS, "dev", lease));
    }
}

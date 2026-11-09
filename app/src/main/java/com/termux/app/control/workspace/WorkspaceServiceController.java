package com.termux.app.control.workspace;

import com.termux.app.control.jobs.JobAuthority;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobResourcePolicy;
import com.termux.app.control.jobs.JobSpec;
import com.termux.app.control.jobs.JobTerminationEvidence;
import com.termux.app.control.jobs.JobOutputStore;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Starts, stops and supervises the approved development services of workspaces. A service is just a managed job started
 * through the one {@link JobCoordinator}: there is no second launcher, so limits, admission, history and ownership-safe
 * stopping all apply. Its state lives in memory only. Nothing is started after the service or the device restarts, and
 * only definitions already stored in the workspace can be started.
 *
 * <p>Restart policy (default {@code never}): {@code on-failure} restarts after a non-zero exit, a signal or a failed
 * start; {@code always} restarts after any exit. Restarts back off exponentially and are limited to a few per window; a service that
 * keeps failing ends in {@code crash_loop} and stays down until started again. A service whose last run looks memory related (resource
 * guard stop, or a possible memory-pressure kill) is not retried either, since retrying a persistent leak only repeats it.
 * An explicit stop, a revoked consent or a removed definition always wins over a pending restart.
 */
public final class WorkspaceServiceController implements AutoCloseable {
    /** The slice of the job coordinator this class needs, so the state machine is testable. */
    public interface Jobs {
        /** Admission outcome with the job ID when a job was created. */
        final class Started { public final JobCoordinator.StartStatus status; public final String jobId;
            public Started(JobCoordinator.StartStatus status, String jobId) { this.status = status; this.jobId = jobId; } }
        Started start(JobSpec spec, JobAuthority.Lease lease);
        JobRecord.Snapshot snapshot(String jobId);
        void onFinished(String jobId, Runnable callback);
        void cancel(String jobId);
        JobCoordinator.OutputResult output(String jobId, JobOutputStore.Stream stream, long offset, int limit);
    }

    /** Approved definitions, resolved fresh on every start so a removed or edited service is honoured. */
    public interface Definitions {
        Resolved resolve(String workspaceId, String serviceId) throws WorkspaceRepository.WorkspaceException;
        List<WorkspaceRepository.ServiceDefinition> list(String workspaceId) throws WorkspaceRepository.WorkspaceException;
        final class Resolved {
            public final WorkspaceRepository.ServiceDefinition service; public final JobResourcePolicy.Profile workspaceProfile; public final boolean available;
            public Resolved(WorkspaceRepository.ServiceDefinition service, JobResourcePolicy.Profile workspaceProfile, boolean available) {
                this.service = service; this.workspaceProfile = workspaceProfile; this.available = available; }
        }
    }

    public interface Evidence { JobTerminationEvidence of(String jobId); }

    public static final class Config {
        public final long baseBackoffMillis, maxBackoffMillis, windowMillis;
        public final int maxRestartsInWindow;
        public Config(long baseBackoffMillis, long maxBackoffMillis, long windowMillis, int maxRestartsInWindow) {
            if (baseBackoffMillis < 1 || maxBackoffMillis < baseBackoffMillis || windowMillis < 1 || maxRestartsInWindow < 0 || maxRestartsInWindow > 20)
                throw new IllegalArgumentException("Invalid restart configuration");
            this.baseBackoffMillis = baseBackoffMillis; this.maxBackoffMillis = maxBackoffMillis; this.windowMillis = windowMillis;
            this.maxRestartsInWindow = maxRestartsInWindow;
        }
        public static Config defaults() { return new Config(1000, 30_000, 10 * 60_000, 5); }
    }

    public enum State { STOPPED, RUNNING, BACKOFF, STOPPING, CRASH_LOOP, MEMORY_STOPPED, DENIED }

    /** Result of start, stop and restart; a refusal is an answer, not an exception. */
    public enum Outcome { STARTED, ALREADY_RUNNING, STOPPING, STOPPED, NOT_RUNNING, RESTARTING, NOT_FOUND, UNAVAILABLE, DENIED, LIMIT, UNSUPPORTED, INVALID }

    private static final class Entry {
        State state = State.STOPPED;
        String jobId, lastJobId;
        long generation, nextStartAt;
        boolean userStopped, restartRequested;
        JobAuthority.Lease lease;
        final ArrayDeque<Long> restarts = new ArrayDeque<>();
        int consecutiveFailures;
        String lastReason;
        Integer lastExitCode;
        ScheduledFuture<?> pending;
        /** Identity of the current run; a new one for every launch, null when nothing runs. */
        String instanceId;
        /** Credential of the current run when the service opted in; dropped as soon as the run ends. */
        String credential;
    }

    /** Environment names a service that opted into a credential receives; see {@link #endpoint}. */
    public static final String ENV_INSTANCE_ID = "TERMUX_SERVICE_INSTANCE_ID", ENV_TOKEN = "TERMUX_SERVICE_TOKEN",
        ENV_PORT = "TERMUX_SERVICE_PORT", ENV_ORIGIN = "TERMUX_SERVICE_ORIGIN";
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Definitions mDefinitions;
    private final Jobs mJobs;
    private final Evidence mEvidence;
    private final Config mConfig;
    private final LongSupplier mClock;
    private final ScheduledExecutorService mTimer;
    private final Map<String, Entry> mEntries = new HashMap<>();
    private boolean mClosed;

    public WorkspaceServiceController(Definitions definitions, Jobs jobs, Evidence evidence, Config config, LongSupplier monotonicMillis) {
        this(definitions, jobs, evidence, config, monotonicMillis, newTimer());
    }

    WorkspaceServiceController(Definitions definitions, Jobs jobs, Evidence evidence, Config config, LongSupplier monotonicMillis, ScheduledExecutorService timer) {
        if (definitions == null || jobs == null || config == null || monotonicMillis == null || timer == null) throw new IllegalArgumentException("Missing dependency");
        mDefinitions = definitions; mJobs = jobs; mEvidence = evidence; mConfig = config; mClock = monotonicMillis; mTimer = timer;
    }

    private static ScheduledExecutorService newTimer() {
        ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> { Thread th = new Thread(r, "TermuxServiceSupervisor"); th.setDaemon(true); return th; });
        t.setRemoveOnCancelPolicy(true);
        return t;
    }

    public static Jobs jobsOf(JobCoordinator coordinator) {
        return new Jobs() {
            @Override public Started start(JobSpec spec, JobAuthority.Lease lease) {
                JobCoordinator.StartResult r = coordinator.start(spec, lease);
                return new Started(r.status, r.job == null ? null : r.job.id);
            }
            @Override public JobRecord.Snapshot snapshot(String jobId) { return coordinator.localSnapshot(jobId); }
            @Override public void onFinished(String jobId, Runnable callback) { coordinator.onJobFinished(jobId, callback); }
            // The ID comes from this controller's own record, never from a client request.
            @Override public void cancel(String jobId) { coordinator.localCancel(jobId); }
            @Override public JobCoordinator.OutputResult output(String jobId, JobOutputStore.Stream stream, long offset, int limit) { return coordinator.localOutput(jobId, stream, offset, limit); }
        };
    }

    // ---- operations ----

    /** Starts an approved service with the caller's job lease. Restarts reuse that lease and stop when consent is revoked. */
    public Outcome start(String workspaceId, String serviceId, JobAuthority.Lease lease) {
        Definitions.Resolved resolved;
        try { resolved = mDefinitions.resolve(workspaceId, serviceId); }
        catch (WorkspaceRepository.WorkspaceException e) { return e.code.equals("NOT_FOUND") ? Outcome.NOT_FOUND : Outcome.INVALID; }
        if (!resolved.available) return Outcome.UNAVAILABLE;
        synchronized (this) {
            if (mClosed) return Outcome.UNAVAILABLE;
            Entry entry = entry(workspaceId, serviceId);
            if (entry.state == State.RUNNING || entry.state == State.BACKOFF) return Outcome.ALREADY_RUNNING;
            if (entry.state == State.STOPPING) return Outcome.STOPPING;
            // An explicit start is a fresh decision: it clears the crash history and any earlier stop.
            entry.userStopped = false; entry.restartRequested = false; entry.restarts.clear(); entry.consecutiveFailures = 0;
            entry.lease = lease; entry.lastReason = null;
            return launch(workspaceId, serviceId, entry, resolved);
        }
    }

    /** Stops a running service, cancels a pending restart, and prevents any automatic restart until started again. */
    public Outcome stop(String workspaceId, String serviceId) {
        synchronized (this) {
            Entry entry = mEntries.get(key(workspaceId, serviceId));
            if (entry == null) return known(workspaceId, serviceId) ? Outcome.NOT_RUNNING : Outcome.NOT_FOUND;
            entry.userStopped = true; entry.restartRequested = false;
            cancelPending(entry);
            if (entry.state == State.RUNNING) {
                entry.state = State.STOPPING;
                String jobId = entry.jobId;
                mJobs.cancel(jobId);
                return Outcome.STOPPED;
            }
            if (entry.state == State.BACKOFF || entry.state == State.CRASH_LOOP || entry.state == State.MEMORY_STOPPED || entry.state == State.DENIED) {
                entry.state = State.STOPPED; entry.jobId = null; return Outcome.STOPPED;
            }
            return entry.state == State.STOPPING ? Outcome.STOPPING : Outcome.NOT_RUNNING;
        }
    }

    /** Stop, then start once the old job has actually ended; it never runs two copies. */
    public Outcome restart(String workspaceId, String serviceId, JobAuthority.Lease lease) {
        synchronized (this) {
            Entry entry = mEntries.get(key(workspaceId, serviceId));
            if (entry != null && (entry.state == State.RUNNING || entry.state == State.STOPPING)) {
                entry.userStopped = false; entry.restartRequested = true; entry.lease = lease;
                cancelPending(entry);
                if (entry.state == State.RUNNING) { entry.state = State.STOPPING; mJobs.cancel(entry.jobId); }
                return Outcome.RESTARTING;
            }
        }
        return start(workspaceId, serviceId, lease);
    }

    public synchronized Map<String, Object> status(String workspaceId, String serviceId) throws WorkspaceRepository.WorkspaceException {
        WorkspaceRepository.ServiceDefinition def = mDefinitions.resolve(workspaceId, serviceId).service;
        Entry entry = mEntries.get(key(workspaceId, serviceId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId); result.put("serviceId", serviceId);
        result.put("state", (entry == null ? State.STOPPED : entry.state).name().toLowerCase(java.util.Locale.ROOT));
        result.put("restartPolicy", def.restartPolicy.wireName);
        result.put("jobId", entry == null ? null : entry.jobId != null ? entry.jobId : entry.lastJobId);
        result.put("restartsInWindow", entry == null ? 0 : prune(entry).size());
        result.put("nextStartInMillis", entry != null && entry.state == State.BACKOFF ? Math.max(0, entry.nextStartAt - mClock.getAsLong()) : null);
        result.put("lastReason", entry == null ? null : entry.lastReason);
        result.put("lastExitCode", entry == null ? null : entry.lastExitCode);
        result.put("ports", new ArrayList<Object>(def.ports));
        return result;
    }

    public List<Map<String, Object>> list(String workspaceId) throws WorkspaceRepository.WorkspaceException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkspaceRepository.ServiceDefinition def : mDefinitions.list(workspaceId)) result.add(status(workspaceId, def.id));
        return result;
    }

    /** Output of the current or most recent run; null when no run is retained. Needs the caller's output consent. */
    public JobCoordinator.OutputResult logs(String workspaceId, String serviceId, JobOutputStore.Stream stream, long offset, int limit) {
        String jobId;
        synchronized (this) {
            Entry entry = mEntries.get(key(workspaceId, serviceId));
            jobId = entry == null ? null : entry.jobId != null ? entry.jobId : entry.lastJobId;
        }
        return jobId == null ? null : mJobs.output(jobId, stream, offset, limit);
    }

    /** Running state plus a loopback TCP probe of each declared port. Blocking: call off the main thread. */
    public Map<String, Object> health(String workspaceId, String serviceId, int probeTimeoutMillis) throws WorkspaceRepository.WorkspaceException {
        Map<String, Object> status = status(workspaceId, serviceId);
        List<Object> ports = new ArrayList<>();
        boolean allListening = true;
        for (Integer port : mDefinitions.resolve(workspaceId, serviceId).service.ports) {
            boolean listening = probe(port, probeTimeoutMillis);
            allListening &= listening;
            Map<String, Object> p = new LinkedHashMap<>(); p.put("port", port); p.put("listening", listening);
            ports.add(p);
        }
        boolean running = "running".equals(status.get("state"));
        status.put("healthy", running && allListening);
        status.put("portChecks", ports);
        return status;
    }

    /**
     * How a local client reaches the current run of a service. The URL always uses the IPv4 loopback literal (never
     * {@code localhost}, which is {@code ::1} on some devices). A port is not an identity: the client must compare
     * {@code instanceId} and authenticate with the credential, and a changed {@code instanceId} means a different run,
     * whose previous credential is no longer valid. Nothing is retried or replayed here. The credential is returned only
     * for a running service that opted in; same-UID processes can still read it from the service's environment, so it
     * separates browsers, other apps and pages from the backend, not Termux processes from each other. Blocking probe.
     */
    public Map<String, Object> endpoint(String workspaceId, String serviceId, int probeTimeoutMillis) throws WorkspaceRepository.WorkspaceException {
        WorkspaceRepository.ServiceDefinition def = mDefinitions.resolve(workspaceId, serviceId).service;
        String state, instanceId, credential;
        synchronized (this) {
            Entry entry = mEntries.get(key(workspaceId, serviceId));
            state = (entry == null ? State.STOPPED : entry.state).name().toLowerCase(java.util.Locale.ROOT);
            boolean running = entry != null && entry.state == State.RUNNING;
            instanceId = running ? entry.instanceId : null;
            credential = running ? entry.credential : null;
        }
        Integer port = def.ports.isEmpty() ? null : def.ports.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceId", workspaceId); result.put("serviceId", serviceId); result.put("state", state);
        result.put("instanceId", instanceId);
        result.put("port", port);
        result.put("url", port == null ? null : origin(port) + "/");
        result.put("origin", port == null ? null : origin(port));
        result.put("listening", port != null && instanceId != null && probe(port, probeTimeoutMillis));
        result.put("credentialRequired", def.credential);
        result.put("credential", credential);
        return result;
    }

    static String origin(int port) { return "http://127.0.0.1:" + port; }

    private static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte b : raw) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        java.util.Arrays.fill(raw, (byte) 0);
        return hex.toString();
    }

    static boolean probe(int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), Math.max(50, Math.min(timeoutMillis, 2000)));
            return true;
        } catch (java.io.IOException | RuntimeException e) { return false; }
    }

    @Override public void close() {
        synchronized (this) {
            mClosed = true;
            for (Entry entry : mEntries.values()) { entry.userStopped = true; cancelPending(entry); }
        }
        mTimer.shutdownNow();
    }

    // ---- internals ----

    private Entry entry(String ws, String svc) { return mEntries.computeIfAbsent(key(ws, svc), k -> new Entry()); }
    private static String key(String ws, String svc) { return ws + "/" + svc; }

    private boolean known(String ws, String svc) {
        try { mDefinitions.resolve(ws, svc); return true; } catch (WorkspaceRepository.WorkspaceException e) { return false; }
    }

    /** Monitor held. Creates the managed job from the stored definition. */
    private Outcome launch(String ws, String svc, Entry entry, Definitions.Resolved resolved) {
        WorkspaceRepository.ServiceDefinition def = resolved.service;
        JobResourcePolicy resources = def.resources != null ? def.resources : resolved.workspaceProfile == null ? null : JobResourcePolicy.ofProfile(resolved.workspaceProfile);
        String instanceId = randomToken(16), credential = def.credential ? randomToken(32) : null;
        // Without the opt-in the environment stays exactly as before; with it, the backend gets what it needs to check
        // its own clients. Values live only in memory: job history and diagnostics never store the environment.
        java.util.Map<String, String> env = Collections.<String, String>emptyMap();
        if (credential != null) {
            env = new java.util.TreeMap<>();
            env.put(ENV_INSTANCE_ID, instanceId); env.put(ENV_TOKEN, credential);
            env.put(ENV_PORT, Integer.toString(def.ports.get(0))); env.put(ENV_ORIGIN, origin(def.ports.get(0)));
        }
        JobSpec spec;
        try {
            spec = new JobSpec(def.executable, def.args, null, ws, env, null, 0, JobSpec.Presentation.BACKGROUND, null, resources);
        } catch (IllegalArgumentException e) { return Outcome.INVALID; }
        Jobs.Started started = mJobs.start(spec, entry.lease);
        switch (started.status) {
            case ACCEPTED: case REPLAY: break;
            case DENIED: entry.state = State.DENIED; entry.lastReason = "consent_revoked"; return Outcome.DENIED;
            case UNSUPPORTED: return Outcome.UNSUPPORTED;
            case LIMIT_REACHED: case RESOURCE_LIMITED: return Outcome.LIMIT;
            default: return Outcome.UNAVAILABLE;
        }
        entry.state = State.RUNNING; entry.jobId = started.jobId; entry.lastJobId = started.jobId; entry.generation++;
        entry.instanceId = instanceId; entry.credential = credential;
        final long generation = entry.generation;
        mJobs.onFinished(started.jobId, () -> onExit(ws, svc, generation));
        return Outcome.STARTED;
    }

    /** Runs on whichever thread completed the job. Never holds a coordinator lock. */
    private void onExit(String ws, String svc, long generation) {
        synchronized (this) {
            Entry entry = mEntries.get(key(ws, svc));
            if (entry == null || entry.generation != generation || mClosed) return;
            String finishedJob = entry.jobId;
            JobRecord.Snapshot snap = mJobs.snapshot(finishedJob);
            entry.jobId = null;
            entry.instanceId = null; entry.credential = null; // a credential never outlives its run
            entry.lastExitCode = snap == null ? null : snap.exitCode;
            JobTerminationEvidence evidence = mEvidence == null ? null : mEvidence.of(finishedJob);
            entry.lastReason = evidence != null ? evidence.reason.name().toLowerCase(java.util.Locale.ROOT) : snap == null ? "unknown" : snap.state.name().toLowerCase(java.util.Locale.ROOT);
            if (entry.userStopped) { entry.state = State.STOPPED; return; }
            if (entry.restartRequested) { entry.restartRequested = false; entry.state = State.STOPPED; startAgain(ws, svc, entry, false); return; }
            WorkspaceRepository.ServiceDefinition def;
            Definitions.Resolved resolved;
            try { resolved = mDefinitions.resolve(ws, svc); def = resolved.service; }
            catch (WorkspaceRepository.WorkspaceException e) { entry.state = State.STOPPED; return; } // the definition was removed
            boolean failed = snap == null || snap.state == JobRecord.State.FAILED || snap.state == JobRecord.State.TIMED_OUT || snap.signal != null ||
                (snap.exitCode != null && snap.exitCode != 0);
            boolean memoryRelated = evidence != null && (evidence.reason == JobTerminationEvidence.Reason.POSSIBLE_MEMORY_PRESSURE ||
                evidence.reason == JobTerminationEvidence.Reason.RESOURCE_GUARD_STOP);
            boolean wantsRestart = def.restartPolicy == WorkspaceRepository.RestartPolicy.ALWAYS ||
                (def.restartPolicy == WorkspaceRepository.RestartPolicy.ON_FAILURE && failed);
            if (snap != null && snap.state == JobRecord.State.CANCELLED) wantsRestart = false; // cancelled by someone: not a crash
            if (!wantsRestart) { entry.state = State.STOPPED; return; }
            if (memoryRelated) { entry.state = State.MEMORY_STOPPED; return; } // retrying a persistent leak repeats it
            entry.consecutiveFailures++;
            long now = mClock.getAsLong();
            if (prune(entry).size() >= mConfig.maxRestartsInWindow) { entry.state = State.CRASH_LOOP; return; }
            long delay = Math.min(mConfig.maxBackoffMillis, mConfig.baseBackoffMillis << Math.min(20, entry.consecutiveFailures - 1));
            entry.state = State.BACKOFF; entry.nextStartAt = now + delay;
            try {
                entry.pending = mTimer.schedule(() -> backoffElapsed(ws, svc, generation), delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) { entry.state = State.STOPPED; }
        }
    }

    private void backoffElapsed(String ws, String svc, long generation) {
        synchronized (this) {
            Entry entry = mEntries.get(key(ws, svc));
            if (entry == null || entry.generation != generation || entry.state != State.BACKOFF || entry.userStopped || mClosed) return;
            entry.state = State.STOPPED;
            startAgain(ws, svc, entry, true);
        }
    }

    /** Monitor held. Re-resolves the definition, so an edit or removal takes effect on the next start. */
    private void startAgain(String ws, String svc, Entry entry, boolean countsAsRestart) {
        Definitions.Resolved resolved;
        try { resolved = mDefinitions.resolve(ws, svc); }
        catch (WorkspaceRepository.WorkspaceException e) { entry.state = State.STOPPED; return; }
        if (!resolved.available) { entry.state = State.STOPPED; entry.lastReason = "workspace_unavailable"; return; }
        if (countsAsRestart) entry.restarts.addLast(mClock.getAsLong());
        Outcome outcome = launch(ws, svc, entry, resolved);
        if (outcome != Outcome.STARTED && entry.state != State.DENIED) entry.state = State.STOPPED;
    }

    private ArrayDeque<Long> prune(Entry entry) {
        long cutoff = mClock.getAsLong() - mConfig.windowMillis;
        while (!entry.restarts.isEmpty() && entry.restarts.peekFirst() < cutoff) entry.restarts.pollFirst();
        return entry.restarts;
    }

    private static void cancelPending(Entry entry) {
        if (entry.pending != null) { entry.pending.cancel(false); entry.pending = null; }
    }
}

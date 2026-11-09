package com.termux.app.control.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.jobs.JobHistoryStore;
import com.termux.app.control.jobs.JobResourcePolicy;
import com.termux.app.control.jobs.JobResourceSupervisor;
import com.termux.app.control.jobs.PressureTracker;
import com.termux.app.control.jobs.ResourceAdmissionController;
import com.termux.app.monitor.DeviceCompatibilityAdvisor;
import com.termux.app.monitor.DeviceResourcePolicy;
import com.termux.app.monitor.DiagnosticBundleExporter;
import com.termux.app.monitor.ProcessSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-mostly domain service behind {@code resource.*}, {@code job.resource.get}, {@code job.history.*},
 * {@code job.diagnostics.export} and {@code job.processes}. Authorization is the dispatcher's job; this class only
 * reports what the app really observed, with unknown values as null. All methods do I/O or sampling and must run on a
 * worker thread. Nothing here signals a process.
 */
public final class ResourceService {
    /** Everything platform specific, so the service is testable on the JVM. */
    public interface Device {
        /** Device memory, low-memory flag, thermal status; fields may be null. */
        PressureTracker.Reading reading();
        DeviceCompatibilityAdvisor.Inputs advisorInputs();
        int cpuCount();
        ProcessSnapshot processes(Set<Integer> roots);
        long ticksPerSecond();
        long pageSizeBytes();
        String systemSummary();
        String homePath();
        String prefixPath();
    }

    public static final int MAX_HISTORY_PAGE = 50;

    private final JobCoordinator mJobs;
    private final JobResourceSupervisor mSupervisor;
    private final ResourceAdmissionController mAdmission;
    private final JobHistoryStore mHistory;
    private final Device mDevice;

    public ResourceService(JobCoordinator jobs, JobResourceSupervisor supervisor, ResourceAdmissionController admission,
                           JobHistoryStore history, Device device) {
        if (jobs == null || supervisor == null || admission == null || history == null || device == null)
            throw new IllegalArgumentException("Missing resource dependency");
        mJobs = jobs; mSupervisor = supervisor; mAdmission = admission; mHistory = history; mDevice = device;
    }

    /** What this device can really do, from measured inputs. */
    public Map<String, Object> capabilities() {
        DeviceCompatibilityAdvisor.Report report = DeviceCompatibilityAdvisor.advise(mDevice.advisorInputs());
        Map<String, Object> result = plain(report.capabilitiesJson());
        List<Object> profiles = new ArrayList<>();
        for (JobResourcePolicy.Profile profile : JobResourcePolicy.Profile.values()) profiles.add(profile.wireName());
        result.put("profiles", profiles);
        result.put("pressureActions", java.util.Arrays.asList("warn", "pause_admission", "stop_job"));
        List<Object> items = new ArrayList<>();
        for (DeviceCompatibilityAdvisor.Item item : report.items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id); m.put("status", item.status.name().toLowerCase(java.util.Locale.ROOT)); m.put("detail", item.detail);
            items.add(m);
        }
        result.put("items", items);
        return result;
    }

    /** A fresh device reading plus the admission state. */
    public Map<String, Object> snapshot() {
        PressureTracker.Reading reading = mDevice.reading();
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("totalMemoryBytes", reading == null ? null : reading.totalBytes);
        device.put("availableMemoryBytes", reading == null ? null : reading.availableBytes);
        device.put("lowMemory", reading == null ? null : reading.androidLowMemory);
        device.put("thermalStatus", reading == null ? null : reading.thermalStatus);
        device.put("cpuCount", mDevice.cpuCount());
        Map<String, Object> admission = new LinkedHashMap<>();
        admission.put("slots", mAdmission.slots());
        admission.put("active", mAdmission.activeCount());
        admission.put("queued", mAdmission.queuedCount());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", device);
        // The supervisor's level is its tracked, debounced view; it is "unknown" until a managed job has been sampled.
        result.put("pressure", mSupervisor.pressure().name().toLowerCase(java.util.Locale.ROOT));
        result.put("admission", admission);
        return result;
    }

    /** Recommended workers for a build tool under this profile on this device right now; 0 for no recommendation. */
    public int recommendedWorkers(JobResourcePolicy.Profile profile) {
        return DeviceResourcePolicy.workers(profile, mDevice.cpuCount(), mAdmission.slots(), mSupervisor.pressure());
    }

    /** The limits a job was started with, what could not be enforced, its last metrics and its events. Null if unknown. */
    public Map<String, Object> jobResource(String jobId) {
        JobResourcePolicy policy = mJobs.resourcePolicy(jobId);
        JobResourceSupervisor.Metrics metrics = mSupervisor.metrics(jobId);
        JobHistoryStore.Record stored = mHistory.get(jobId);
        if (policy == null && metrics == null && stored == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("limits", policy == null ? null : plain(policy.toJson()));
        if (policy != null) {
            JobResourcePolicy.Effective effective = policy.resolve(new JobResourcePolicy.Capabilities(
                Boolean.TRUE.equals(mDevice.advisorInputs().nativeLauncherAvailable)));
            result.put("unsupported", new ArrayList<Object>(effective.unsupported));
        } else result.put("unsupported", new ArrayList<Object>());
        result.put("queuePosition", mJobs.queuePosition(jobId) == 0 ? null : mJobs.queuePosition(jobId));
        result.put("metrics", metrics == null ? null : metrics(metrics));
        List<Object> events = new ArrayList<>();
        for (JobResourceSupervisor.Event e : mSupervisor.events(jobId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", e.wallMillis); m.put("type", e.type); m.put("detail", e.detail);
            events.add(m);
        }
        result.put("events", events);
        result.put("evidence", stored == null || stored.evidence == null ? null : plain(stored.evidence.toJson()));
        return result;
    }

    private static Map<String, Object> metrics(JobResourceSupervisor.Metrics m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rssBytes", m.rssBytes); o.put("peakRssBytes", m.peakRssBytes); o.put("cpuPercent", m.cpuPercent);
        o.put("processCount", m.processCount); o.put("incomplete", m.incomplete); o.put("memorySoftExceeded", m.memorySoftExceeded);
        o.put("identityLost", m.identityLost); o.put("samples", m.samples);
        o.put("availableBytesAtLastSample", m.availableBytesAtLastSample); o.put("androidLowMemoryAtLastSample", m.androidLowMemoryAtLastSample);
        o.put("pressureAtLastSample", m.pressureAtLastSample == null ? null : m.pressureAtLastSample.name().toLowerCase(java.util.Locale.ROOT));
        o.put("possiblyDetachedProcesses", new ArrayList<Object>(m.orphanPossiblePids));
        return o;
    }

    /** Newest first. */
    public Map<String, Object> historyList(int limit) {
        if (limit < 1 || limit > MAX_HISTORY_PAGE) throw new IllegalArgumentException("Invalid limit");
        List<Object> items = new ArrayList<>();
        for (JobHistoryStore.Record record : mHistory.list(limit)) items.add(plain(record.toJson()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("history", items);
        result.put("available", mHistory.isAvailable());
        return result;
    }

    public Map<String, Object> historyGet(String jobId) {
        JobHistoryStore.Record record = mHistory.get(jobId);
        return record == null ? null : Collections.<String, Object>singletonMap("record", plain(record.toJson()));
    }

    /**
     * The redacted report for one job. {@code outputTail} is used only when the caller was authorized for output and
     * asked for it; the bundle builder then still applies redaction and bounds.
     */
    public Map<String, Object> exportDiagnostics(String jobId, boolean includeOutput, byte[] stdoutTail, byte[] stderrTail) {
        DiagnosticBundleExporter.Input in = new DiagnosticBundleExporter.Input();
        in.device = DeviceCompatibilityAdvisor.advise(mDevice.advisorInputs());
        in.systemSummary = mDevice.systemSummary(); in.homePath = mDevice.homePath(); in.prefixPath = mDevice.prefixPath();
        in.generatedAtWall = System.currentTimeMillis();
        JobHistoryStore.Record record = mHistory.get(jobId);
        if (record != null) in.jobs.add(record);
        JobResourceSupervisor.Metrics m = mSupervisor.metrics(jobId);
        if (m != null) in.liveMetrics.put(jobId, m);
        in.events = new ArrayList<>(mSupervisor.events(jobId));
        in.includeOutput = includeOutput; in.stdoutTail = stdoutTail; in.stderrTail = stderrTail;
        Map<String, String> files = DiagnosticBundleExporter.build(in);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", files.get("report.txt"));
        result.put("files", new ArrayList<Object>(files.keySet()));
        result.put("outputIncluded", files.containsKey("output-tail.txt"));
        result.put("found", record != null || m != null);
        return result;
    }

    /**
     * Best-effort process tree of one running owned job. The root is this app's own child, found by the coordinator, never by
     * name or by a client-supplied PID. Descendants are those still reachable by parent links; processes that left the tree
     * are listed separately as {@code orphan_possible}, because ownership cannot be established for them.
     */
    public Map<String, Object> jobProcesses(String jobId) {
        Integer root = mJobs.ownedPid(jobId);
        JobResourceSupervisor.Metrics m = mSupervisor.metrics(jobId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        List<Object> rows = new ArrayList<>();
        boolean incomplete = true;
        if (root != null) {
            ProcessSnapshot snapshot = mDevice.processes(Collections.singleton(root));
            incomplete = snapshot.incomplete || snapshot.missingRoots > 0;
            Set<Integer> detached = m == null ? new HashSet<Integer>() : new HashSet<>(m.orphanPossiblePids);
            for (ProcessSnapshot.Entry entry : snapshot.visibleEntries) {
                boolean inTree = false;
                for (ProcessSnapshot.Entry member : snapshot.entries) if (member.pid == entry.pid) inTree = true;
                String relation = entry.pid == root ? "root" : inTree ? "descendant" : detached.contains(entry.pid) ? "orphan_possible" : null;
                if (relation == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pid", entry.pid); row.put("ppid", entry.ppid); row.put("name", entry.name);
                row.put("state", String.valueOf(entry.state));
                row.put("rssKiB", ProcessSnapshot.rssKiB(entry.rssPages, mDevice.pageSizeBytes()));
                row.put("startTicks", entry.startTicks); row.put("relation", relation);
                rows.add(row);
                if (rows.size() >= 200) { incomplete = true; break; }
            }
        }
        result.put("running", root != null);
        result.put("processes", rows);
        result.put("incomplete", incomplete);
        return result;
    }

    // ---- JSON helpers ----

    static Map<String, Object> plain(JsonObject object) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) map.put(e.getKey(), plain(e.getValue()));
        return map;
    }

    private static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) return plain(e.getAsJsonObject());
        if (e.isJsonArray()) { List<Object> list = new ArrayList<>(); for (JsonElement item : (JsonArray) e) list.add(plain(item)); return list; }
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        if (e.getAsJsonPrimitive().isNumber()) {
            java.math.BigDecimal n = e.getAsBigDecimal();
            try { return n.longValueExact(); } catch (ArithmeticException ex) { return n.doubleValue(); }
        }
        return e.getAsString();
    }
}

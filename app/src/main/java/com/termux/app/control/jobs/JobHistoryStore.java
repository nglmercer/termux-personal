package com.termux.app.control.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bounded, app-private history of managed jobs, kept across restarts. Layout under the given root:
 * <pre>
 * v1/metadata.json     {"version":1}
 * v1/history/job_*.json   one record per job
 * v1/diagnostics/      reserved for exported bundles
 * </pre>
 *
 * <p>Persisted: job ID, workspace ID, state, wall-clock times, exit code, signal, stop reason, failure, resource profile name,
 * termination evidence, peak metrics and a bounded list of diagnostic events. Never persisted: command line, environment,
 * stdin, tokens, or any stdout/stderr.
 *
 * <p>Writes are atomic (temporary file, fsync, rename). An unreadable record is moved aside as {@code .corrupt-<time>}
 * and never blocks the rest. A store whose metadata declares a newer version disables itself and never writes.
 * On {@link #open()} every record that was still unfinished is rewritten as LOST: the process that owned it died with the
 * previous service, and this class never signals, reattaches to or trusts a historical PID (none is stored).
 * Count and total size are bounded; the oldest finished records go first and an unfinished record is only removed
 * once it has been recovered as LOST.
 */
public final class JobHistoryStore {
    public static final int VERSION = 1, MAX_RECORDS = 200, MAX_TOTAL_BYTES = 4 << 20, MAX_RECORD_BYTES = 32 << 10, MAX_EVENTS = 32;
    private static final int MAX_CORRUPT_FILES = 5;
    private static final Pattern ID = Pattern.compile("job_[0-9a-f]{32}");

    /** One persisted job. Immutable. */
    public static final class Record {
        public final String jobId, workspaceId, state, resourceProfile, stopReason, failure;
        public final Long startedAtWall, endedAtWall, peakRssBytes;
        public final Integer exitCode, signal;
        public final JobTerminationEvidence evidence;
        public final List<EventRecord> events;
        /** True when this record was finished by recovery rather than by observing the job end. */
        public final boolean recoveredAsLost;
        public Record(String jobId, String workspaceId, String state, String resourceProfile, String stopReason, String failure,
                      Long startedAtWall, Long endedAtWall, Long peakRssBytes, Integer exitCode, Integer signal,
                      JobTerminationEvidence evidence, List<EventRecord> events, boolean recoveredAsLost) {
            this.jobId = jobId; this.workspaceId = workspaceId; this.state = state; this.resourceProfile = resourceProfile;
            this.stopReason = stopReason; this.failure = failure; this.startedAtWall = startedAtWall; this.endedAtWall = endedAtWall;
            this.peakRssBytes = peakRssBytes; this.exitCode = exitCode; this.signal = signal; this.evidence = evidence;
            this.events = Collections.unmodifiableList(new ArrayList<>(events)); this.recoveredAsLost = recoveredAsLost;
        }
        /** Same shape as the stored file; contains no command line, environment, stdin or output. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("version", VERSION);
            o.addProperty("jobId", jobId); o.addProperty("workspaceId", workspaceId); o.addProperty("state", state);
            o.addProperty("resourceProfile", resourceProfile); o.addProperty("stopReason", stopReason); o.addProperty("failure", failure);
            o.addProperty("startedAtWall", startedAtWall); o.addProperty("endedAtWall", endedAtWall);
            o.addProperty("peakRssBytes", peakRssBytes); o.addProperty("exitCode", exitCode); o.addProperty("signal", signal);
            o.addProperty("recoveredAsLost", recoveredAsLost);
            if (evidence != null) o.add("evidence", evidence.toJson());
            JsonArray array = new JsonArray();
            for (EventRecord e : events) {
                JsonObject ev = new JsonObject();
                ev.addProperty("at", e.wallMillis); ev.addProperty("type", e.type); ev.addProperty("detail", e.detail.length() > 200 ? e.detail.substring(0, 200) : e.detail);
                array.add(ev);
            }
            o.add("events", array);
            return o;
        }
        public boolean finished() { return isTerminal(state); }
    }

    public static final class EventRecord {
        public final long wallMillis; public final String type, detail;
        public EventRecord(long wallMillis, String type, String detail) {
            this.wallMillis = wallMillis; this.type = type; this.detail = detail == null ? "" : detail;
        }
    }

    public static final class OpenResult {
        public final boolean available;
        public final int recoveredAsLost, corruptMoved, removed;
        OpenResult(boolean available, int recoveredAsLost, int corruptMoved, int removed) {
            this.available = available; this.recoveredAsLost = recoveredAsLost; this.corruptMoved = corruptMoved; this.removed = removed;
        }
    }

    private final File mRoot, mVersionDir, mHistory, mDiagnostics;
    private boolean mAvailable;

    public JobHistoryStore(File root) {
        if (root == null) throw new IllegalArgumentException("Missing root");
        mRoot = root; mVersionDir = new File(root, "v1"); mHistory = new File(mVersionDir, "history"); mDiagnostics = new File(mVersionDir, "diagnostics");
    }

    public synchronized boolean isAvailable() { return mAvailable; }
    public File diagnosticsDirectory() { return mDiagnostics; }

    /** Creates the layout, validates metadata, removes leftovers of interrupted writes and recovers unfinished records. Worker thread. */
    public synchronized OpenResult open() {
        mAvailable = false;
        int lost = 0, corrupt = 0, removed = 0;
        try {
            if (!mHistory.isDirectory() && !mHistory.mkdirs()) return new OpenResult(false, 0, 0, 0);
            if (!mDiagnostics.isDirectory() && !mDiagnostics.mkdirs()) return new OpenResult(false, 0, 0, 0);
            File metadata = new File(mVersionDir, "metadata.json");
            if (metadata.exists()) {
                try {
                    JsonObject o = JsonParser.parseString(new String(read(metadata, 4096), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.get("version").getAsInt() != VERSION) return new OpenResult(false, 0, 0, 0); // newer layout: do not touch it
                } catch (IOException | RuntimeException e) {
                    moveAside(metadata); corrupt++;
                    write(metadata, "{\"version\":" + VERSION + "}");
                }
            } else write(metadata, "{\"version\":" + VERSION + "}");
            File[] files = mHistory.listFiles();
            if (files != null) for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".tmp")) { if (file.delete()) removed++; continue; }
                if (!name.endsWith(".json") || !ID.matcher(name.substring(0, name.length() - 5)).matches()) continue;
                Record record = load(file);
                if (record == null) { corrupt++; continue; }
                if (!record.finished()) { writeRecord(recoverAsLost(record)); lost++; }
            }
            mAvailable = true;
            removed += trim();
            trimCorrupt();
        } catch (IOException e) { mAvailable = false; }
        return new OpenResult(mAvailable, lost, corrupt, removed);
    }

    /** Records that a job is running so that a restart can recognise it as unfinished. Never throws. */
    public synchronized void started(JobRecord.Snapshot job, String resourceProfile, long wallMillis) {
        if (!mAvailable || job == null || !ID.matcher(job.id).matches()) return;
        try { writeRecord(new Record(job.id, job.workspaceId, job.state.name(), resourceProfile, null, null, wallMillis, null, null, null, null, null,
            Collections.<EventRecord>emptyList(), false)); trim(); }
        catch (IOException e) { /* history is best effort and must never break a job */ }
    }

    /** Stores the final record. Never throws. */
    public synchronized void finished(JobRecord.Snapshot job, String resourceProfile, JobTerminationEvidence evidence, Long peakRssBytes,
                                      List<EventRecord> events, Long startedAtWall, long endedAtWall) {
        if (!mAvailable || job == null || !ID.matcher(job.id).matches()) return;
        List<EventRecord> bounded = events == null ? Collections.<EventRecord>emptyList() : events.subList(Math.max(0, events.size() - MAX_EVENTS), events.size());
        try {
            writeRecord(new Record(job.id, job.workspaceId, job.state.name(), resourceProfile, job.stopReason == null ? null : job.stopReason.name(),
                job.failure == null ? null : job.failure.name(), startedAtWall, endedAtWall, peakRssBytes, job.exitCode, job.signal, evidence, bounded, false));
            trim();
        } catch (IOException e) { /* best effort */ }
    }

    /** Newest first, at most {@code limit}. */
    public synchronized List<Record> list(int limit) {
        List<Record> result = new ArrayList<>();
        if (!mAvailable || limit < 1) return result;
        File[] files = mHistory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json") || !ID.matcher(name.substring(0, name.length() - 5)).matches()) continue;
            Record record = load(file);
            if (record != null) result.add(record);
        }
        Collections.sort(result, (a, b) -> Long.compare(sortKey(b), sortKey(a)));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    public synchronized Record get(String jobId) {
        if (!mAvailable || jobId == null || !ID.matcher(jobId).matches()) return null;
        File file = new File(mHistory, jobId + ".json");
        return file.isFile() ? load(file) : null;
    }

    // ---- internals ----

    private static long sortKey(Record r) { return r.endedAtWall != null ? r.endedAtWall : r.startedAtWall != null ? r.startedAtWall : 0; }

    static boolean isTerminal(String state) {
        return "EXITED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state) || "TIMED_OUT".equals(state) || "LOST".equals(state);
    }

    private static Record recoverAsLost(Record old) {
        JobTerminationEvidence evidence = new JobTerminationEvidence(old.jobId, JobRecord.State.LOST, null, null,
            JobTerminationEvidence.Reason.SERVICE_LOST, JobTerminationEvidence.Confidence.CONFIRMED, old.peakRssBytes, null, null, true, false,
            Arrays.asList("the service restarted while this job was recorded as " + old.state.toLowerCase()), Collections.<String>emptyList(),
            Arrays.asList("what happened to the process after the restart", "exit code and signal"));
        return new Record(old.jobId, old.workspaceId, "LOST", old.resourceProfile, old.stopReason, old.failure, old.startedAtWall, null, old.peakRssBytes,
            null, null, evidence, old.events, true);
    }

    private void writeRecord(Record r) throws IOException {
        String text = r.toJson().toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) throw new IOException("Record too large");
        write(new File(mHistory, r.jobId + ".json"), text);
    }

    private Record load(File file) {
        try {
            JsonObject o = JsonParser.parseString(new String(read(file, MAX_RECORD_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
            String id = o.get("jobId").getAsString();
            if (!ID.matcher(id).matches() || !file.getName().equals(id + ".json")) throw new IllegalArgumentException("Identity mismatch");
            String state = o.get("state").getAsString();
            JobRecord.State.valueOf(state);
            List<EventRecord> events = new ArrayList<>();
            if (o.has("events") && o.get("events").isJsonArray()) for (JsonElement e : o.getAsJsonArray("events")) {
                if (events.size() >= MAX_EVENTS) break;
                JsonObject ev = e.getAsJsonObject();
                events.add(new EventRecord(ev.get("at").getAsLong(), ev.get("type").getAsString(), ev.has("detail") && !ev.get("detail").isJsonNull() ? ev.get("detail").getAsString() : ""));
            }
            return new Record(id, text(o, "workspaceId"), state, text(o, "resourceProfile"), text(o, "stopReason"), text(o, "failure"),
                num(o, "startedAtWall"), num(o, "endedAtWall"), num(o, "peakRssBytes"),
                num(o, "exitCode") == null ? null : num(o, "exitCode").intValue(), num(o, "signal") == null ? null : num(o, "signal").intValue(),
                o.has("evidence") && o.get("evidence").isJsonObject() ? JobTerminationEvidence.fromJson(o.getAsJsonObject("evidence")) : null,
                events, o.has("recoveredAsLost") && o.get("recoveredAsLost").getAsBoolean());
        } catch (IOException | RuntimeException e) {
            moveAside(file);
            return null;
        }
    }

    private static String text(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null; }
    private static Long num(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : null; }

    /** Oldest finished records first, until both the count and the size bound hold. Returns how many were removed. */
    private int trim() {
        File[] files = mHistory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return 0;
        List<File> ordered = new ArrayList<>(Arrays.asList(files));
        Collections.sort(ordered, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File f : ordered) total += f.length();
        int removed = 0;
        for (File f : ordered) {
            if (ordered.size() - removed <= MAX_RECORDS && total <= MAX_TOTAL_BYTES) break;
            // A record that is still unfinished belongs to a job this service may still be running: keep it.
            String name = f.getName();
            if (!ID.matcher(name.substring(0, name.length() - 5)).matches()) continue;
            Record record = peek(f);
            if (record != null && !record.finished()) continue;
            total -= f.length();
            if (f.delete()) removed++;
        }
        return removed;
    }

    /** Like load() but never moves a file aside. */
    private Record peek(File file) {
        try {
            JsonObject o = JsonParser.parseString(new String(read(file, MAX_RECORD_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
            String state = o.get("state").getAsString();
            return new Record(file.getName().substring(0, file.getName().length() - 5), null, state, null, null, null, null, null, null, null, null, null,
                Collections.<EventRecord>emptyList(), false);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private void trimCorrupt() {
        File[] files = mHistory.listFiles((dir, name) -> name.contains(".corrupt-"));
        if (files == null || files.length <= MAX_CORRUPT_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_CORRUPT_FILES; i++) files[i].delete();
    }

    private void moveAside(File file) {
        File moved = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        if (!file.renameTo(moved)) file.delete();
        trimCorrupt();
    }

    private static byte[] read(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > max) throw new IOException("File too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Temporary file, fsync, rename: a crash leaves the old content or the new, never a partial file. */
    private static void write(File target, String text) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush(); out.getFD().sync();
        }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Cannot replace " + target.getName()); }
    }
}

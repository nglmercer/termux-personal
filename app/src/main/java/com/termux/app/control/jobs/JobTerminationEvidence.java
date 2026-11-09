package com.termux.app.control.jobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Why a managed job ended, with how sure we are and what we could not see. Immutable. It holds no command line,
 * environment, stdin or output.
 *
 * <p>{@link Confidence#CONFIRMED} is reserved for causes the app itself observed or performed (it sent the stop, its timer
 * fired, the process exited with a code, the launch failed). Every {@code POSSIBLE_*} and {@code UNKNOWN_*} reason is an
 * inference or a gap and is never confirmed.
 */
public final class JobTerminationEvidence {
    public enum Reason {
        NORMAL_EXIT, USER_CANCELLED, WALL_TIMEOUT, CPU_LIMIT, RESOURCE_GUARD_STOP, START_FAILURE, CAPTURE_FAILURE,
        UNKNOWN_SIGKILL, POSSIBLE_MEMORY_PRESSURE, POSSIBLE_PHANTOM_PROCESS_POLICY, SERVICE_LOST, UNKNOWN
    }
    public enum Confidence {
        CONFIRMED, MEDIUM, LOW, NONE;
        public String wireName() { return name().toLowerCase(Locale.ROOT); }
        static Confidence parse(String value) {
            for (Confidence c : values()) if (c.wireName().equals(value)) return c;
            return NONE;
        }
    }

    public final String jobId;
    public final JobRecord.State outcome;
    public final Integer exitCode, signal;
    public final Reason reason;
    public final Confidence confidence;
    public final Long peakRssBytes, availableRamBytesAtLastSample;
    public final Boolean androidLowMemoryAtLastSample;
    /** True when the job was never sampled or the process table was partial, so a missing cause may just be unseen. */
    public final boolean observationsIncomplete;
    public final boolean logTruncated;
    /** What was directly observed. */
    public final List<String> observed;
    /** What was inferred from it; each entry is a guess, not a fact. */
    public final List<String> inferred;
    /** Evidence that does not exist on this device or was not collected. */
    public final List<String> unavailable;

    public JobTerminationEvidence(String jobId, JobRecord.State outcome, Integer exitCode, Integer signal, Reason reason,
                           Confidence confidence, Long peakRssBytes, Long availableRamBytesAtLastSample,
                           Boolean androidLowMemoryAtLastSample, boolean observationsIncomplete, boolean logTruncated,
                           List<String> observed, List<String> inferred, List<String> unavailable) {
        if (jobId == null || outcome == null || reason == null || confidence == null) throw new IllegalArgumentException("Invalid evidence");
        if (confidence == Confidence.CONFIRMED && (reason.name().startsWith("POSSIBLE_") || reason.name().startsWith("UNKNOWN")))
            throw new IllegalArgumentException("An inference or a gap cannot be confirmed");
        this.jobId = jobId; this.outcome = outcome; this.exitCode = exitCode; this.signal = signal; this.reason = reason;
        this.confidence = confidence; this.peakRssBytes = peakRssBytes; this.availableRamBytesAtLastSample = availableRamBytesAtLastSample;
        this.androidLowMemoryAtLastSample = androidLowMemoryAtLastSample; this.observationsIncomplete = observationsIncomplete;
        this.logTruncated = logTruncated;
        this.observed = copy(observed); this.inferred = copy(inferred); this.unavailable = copy(unavailable);
    }

    private static List<String> copy(List<String> source) {
        List<String> result = new ArrayList<>();
        if (source != null) for (String item : source) { if (result.size() >= 16) break; result.add(item.length() > 200 ? item.substring(0, 200) : item); }
        return Collections.unmodifiableList(result);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("jobId", jobId);
        o.addProperty("outcome", outcome.name());
        o.addProperty("exitCode", exitCode); o.addProperty("signal", signal);
        o.addProperty("reason", reason.name());
        o.addProperty("confidence", confidence.wireName());
        o.addProperty("peakRssBytes", peakRssBytes);
        o.addProperty("availableRamBytesAtLastSample", availableRamBytesAtLastSample);
        o.addProperty("androidLowMemoryAtLastSample", androidLowMemoryAtLastSample);
        o.addProperty("observationsIncomplete", observationsIncomplete);
        o.addProperty("logTruncated", logTruncated);
        o.add("observed", array(observed)); o.add("inferred", array(inferred)); o.add("unavailable", array(unavailable));
        return o;
    }

    /** Tolerant reader: an unknown or missing member becomes "unknown", never an exception. Null if the object is unusable. */
    public static JobTerminationEvidence fromJson(JsonObject o) {
        try {
            return new JobTerminationEvidence(str(o, "jobId"), JobRecord.State.valueOf(str(o, "outcome")), intOrNull(o, "exitCode"), intOrNull(o, "signal"),
                reasonOrUnknown(o), Confidence.parse(o.has("confidence") && !o.get("confidence").isJsonNull() ? o.get("confidence").getAsString() : null),
                longOrNull(o, "peakRssBytes"), longOrNull(o, "availableRamBytesAtLastSample"),
                o.has("androidLowMemoryAtLastSample") && !o.get("androidLowMemoryAtLastSample").isJsonNull() ? o.get("androidLowMemoryAtLastSample").getAsBoolean() : null,
                bool(o, "observationsIncomplete", true), bool(o, "logTruncated", false), list(o, "observed"), list(o, "inferred"), list(o, "unavailable"));
        } catch (RuntimeException e) { return null; }
    }

    private static Reason reasonOrUnknown(JsonObject o) {
        try { return Reason.valueOf(str(o, "reason")); } catch (RuntimeException e) { return Reason.UNKNOWN; }
    }
    private static String str(JsonObject o, String key) { return o.get(key).getAsString(); }
    private static Integer intOrNull(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : null; }
    private static Long longOrNull(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : null; }
    private static boolean bool(JsonObject o, String key, boolean fallback) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback; }
    private static JsonArray array(List<String> items) { JsonArray a = new JsonArray(); for (String i : items) a.add(i); return a; }
    private static List<String> list(JsonObject o, String key) {
        List<String> result = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonPrimitive()) result.add(e.getAsString());
        return result;
    }
}

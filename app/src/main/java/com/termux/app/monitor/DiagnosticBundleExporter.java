package com.termux.app.monitor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.termux.app.control.jobs.JobHistoryStore;
import com.termux.app.control.jobs.JobResourceSupervisor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a bounded diagnostic bundle for a user to share. Every file is size-capped, free text is redacted, and process
 * output is included only when the caller says the user agreed to it: output can hold private data that no pattern can
 * recognise. Command lines, environment values and stdin are never in the input, so they cannot be in the bundle.
 *
 * <pre>
 * diagnostics/system.txt        device and Android facts
 * diagnostics/capabilities.json what this device can enforce, plus advice and ADB commands to copy
 * diagnostics/jobs.json         job records with termination evidence
 * diagnostics/resources.json    last observed metrics for live jobs
 * diagnostics/events.json       supervisor events
 * diagnostics/output-tail.txt   only with explicit consent
 * diagnostics/report.txt        short human summary
 * </pre>
 * System logcat is not collected: the app cannot read it, and the report says so instead of omitting the fact.
 */
public final class DiagnosticBundleExporter {
    private DiagnosticBundleExporter() { }

    static final int MAX_REDACT_CHARS = 96 << 10;
    public static final int MAX_FILE_BYTES = 128 << 10, MAX_TOTAL_BYTES = 512 << 10, MAX_JOBS = 50, MAX_EVENTS = 200;

    public static final class Input {
        public DeviceCompatibilityAdvisor.Report device;
        public String systemSummary = "";
        public List<JobHistoryStore.Record> jobs = new ArrayList<>();
        public Map<String, JobResourceSupervisor.Metrics> liveMetrics = new LinkedHashMap<>();
        public List<JobResourceSupervisor.Event> events = new ArrayList<>();
        /** Tail bytes of the selected job's streams; used only when {@link #includeOutput} is true. */
        public byte[] stdoutTail, stderrTail;
        public boolean includeOutput;
        public String homePath, prefixPath;
        public long generatedAtWall;
    }

    public static Map<String, String> build(Input in) {
        if (in == null || in.device == null) throw new IllegalArgumentException("Missing input");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("system.txt", cap(redact(in.systemSummary, in) + "\n"));
        files.put("capabilities.json", cap(json(in.device.toJson())));
        JsonArray jobs = new JsonArray();
        for (JobHistoryStore.Record record : in.jobs) { if (jobs.size() >= MAX_JOBS) break; jobs.add(redactJson(record.toJson(), in)); }
        files.put("jobs.json", cap(json(jobsObject(jobs))));
        files.put("resources.json", cap(json(resources(in))));
        files.put("events.json", cap(json(events(in))));
        if (in.includeOutput) files.put("output-tail.txt", cap(outputText(in)));
        files.put("report.txt", cap(report(in, files.containsKey("output-tail.txt"))));
        // Whole-bundle bound: shrink the largest optional file first.
        int total = 0;
        for (String v : files.values()) total += v.getBytes(StandardCharsets.UTF_8).length;
        if (total > MAX_TOTAL_BYTES && files.containsKey("output-tail.txt")) files.put("output-tail.txt", "(omitted: bundle size limit)\n");
        return files;
    }

    /** Writes the files under {@code diagnostics/} in a zip. The stream is not closed. */
    public static void writeZip(Map<String, String> files, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        for (Map.Entry<String, String> file : files.entrySet()) {
            zip.putNextEntry(new ZipEntry("diagnostics/" + file.getKey()));
            zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.finish();
    }

    // ---- content ----

    private static JsonObject jobsObject(JsonArray jobs) { JsonObject o = new JsonObject(); o.add("jobs", jobs); return o; }

    private static JsonObject resources(Input in) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, JobResourceSupervisor.Metrics> e : in.liveMetrics.entrySet()) {
            JobResourceSupervisor.Metrics m = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("rssBytes", m.rssBytes); o.addProperty("peakRssBytes", m.peakRssBytes); o.addProperty("cpuPercent", m.cpuPercent);
            o.addProperty("processCount", m.processCount); o.addProperty("incomplete", m.incomplete); o.addProperty("memorySoftExceeded", m.memorySoftExceeded);
            o.addProperty("identityLost", m.identityLost); o.addProperty("samples", m.samples);
            o.addProperty("availableBytesAtLastSample", m.availableBytesAtLastSample);
            o.addProperty("androidLowMemoryAtLastSample", m.androidLowMemoryAtLastSample);
            o.addProperty("pressure", m.pressureAtLastSample == null ? null : m.pressureAtLastSample.name());
            o.addProperty("possiblyDetachedProcesses", m.orphanPossiblePids.size());
            root.add(e.getKey(), o);
        }
        return root;
    }

    private static JsonObject events(Input in) {
        JsonArray array = new JsonArray();
        for (JobResourceSupervisor.Event e : in.events) {
            if (array.size() >= MAX_EVENTS) break;
            JsonObject o = new JsonObject();
            o.addProperty("at", e.wallMillis); o.addProperty("job", e.jobId); o.addProperty("type", e.type); o.addProperty("detail", redact(e.detail, in));
            array.add(o);
        }
        JsonObject root = new JsonObject(); root.add("events", array); return root;
    }

    private static String outputText(Input in) {
        StringBuilder text = new StringBuilder("Output was included because the user chose to share it. It is redacted by pattern only and may still contain private data.\n");
        text.append("\n--- stdout (tail) ---\n").append(in.stdoutTail == null ? "(none)" : redact(tail(in.stdoutTail), in));
        text.append("\n\n--- stderr (tail) ---\n").append(in.stderrTail == null ? "(none)" : redact(tail(in.stderrTail), in)).append('\n');
        return text.toString();
    }

    private static String report(Input in, boolean outputIncluded) {
        StringBuilder r = new StringBuilder("Termux diagnostic report\nGenerated: ").append(new Date(in.generatedAtWall)).append("\n\n");
        r.append(redact(in.systemSummary, in)).append("\n\nDevice capabilities:\n");
        for (DeviceCompatibilityAdvisor.Item item : in.device.items)
            r.append(" - ").append(item.id).append(" [").append(item.status.name().toLowerCase(Locale.ROOT)).append("] ").append(item.detail).append('\n');
        r.append("\nJobs (").append(in.jobs.size()).append("):\n");
        for (JobHistoryStore.Record j : in.jobs) {
            if (r.length() > MAX_FILE_BYTES / 2) { r.append(" ...\n"); break; }
            r.append(" - ").append(j.jobId).append(' ').append(j.state);
            if (j.exitCode != null) r.append(" exit ").append(j.exitCode);
            if (j.signal != null) r.append(" signal ").append(j.signal);
            if (j.evidence != null) {
                r.append("\n     reason: ").append(j.evidence.reason).append(" (confidence ").append(j.evidence.confidence.wireName()).append(')');
                for (String s : j.evidence.inferred) r.append("\n     inferred: ").append(redact(s, in));
                for (String s : j.evidence.unavailable) r.append("\n     not available: ").append(redact(s, in));
            }
            r.append('\n');
        }
        r.append("\nNot collected: system logcat (not readable by the app), ApplicationExitInfo for individual jobs.\n");
        r.append(outputIncluded ? "Process output is included in output-tail.txt at the user's request.\n" : "Process output is NOT included.\n");
        return r.toString();
    }

    // ---- redaction ----

    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]{0,31}://)[^/\\s:@]{1,128}:[^/\\s@]{1,256}@");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]{0,40}(?:password|passwd|passphrase|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|authorization)[A-Za-z0-9_.-]{0,40})(\\s{0,4}[=:]\\s{0,4})(\"[^\"]{0,512}\"|'[^']{0,512}'|\\S{1,512})");
    private static final Pattern FLAG_VALUE = Pattern.compile("(?i)(--?(?:password|passwd|token|secret|api-key|apikey|auth)(?:=|\\s+))(\\S+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,512}");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]{0,20}PRIVATE KEY-----[\\s\\S]{0,16384}?(-----END [A-Z ]*PRIVATE KEY-----|$)");
    private static final Pattern LONG_SECRET = Pattern.compile("(?<![A-Za-z0-9_])(?!job_)[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9_])");

    /** Pattern-based redaction of free text. It cannot recognise every secret, which is why output is opt-in. */
    public static String redact(String text, Input in) {
        if (text == null) return "";
        // Bounded before any pattern runs, so a hostile or huge input cannot make redaction expensive.
        if (text.length() > MAX_REDACT_CHARS) text = text.substring(0, MAX_REDACT_CHARS);
        String result = PRIVATE_KEY.matcher(text).replaceAll("<redacted private key>");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1<redacted>@");
        result = BEARER.matcher(result).replaceAll("$1 <redacted>");
        result = FLAG_VALUE.matcher(result).replaceAll("$1<redacted>");
        Matcher kv = KEY_VALUE.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (kv.find()) kv.appendReplacement(sb, Matcher.quoteReplacement(kv.group(1) + kv.group(2) + "<redacted>"));
        kv.appendTail(sb);
        result = LONG_SECRET.matcher(sb.toString()).replaceAll("<redacted>");
        if (in != null) {
            // Unneeded absolute paths: keep the shape, drop the app's directory names.
            if (in.homePath != null && !in.homePath.isEmpty()) result = result.replace(in.homePath, "~");
            if (in.prefixPath != null && !in.prefixPath.isEmpty()) result = result.replace(in.prefixPath, "$PREFIX");
        }
        return result;
    }

    private static com.google.gson.JsonElement redactJson(com.google.gson.JsonElement element, Input in) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return new com.google.gson.JsonPrimitive(redact(element.getAsString(), in));
        if (element.isJsonArray()) { JsonArray out = new JsonArray(); for (com.google.gson.JsonElement e : element.getAsJsonArray()) out.add(redactJson(e, in)); return out; }
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : element.getAsJsonObject().entrySet()) {
                // Identifiers are structural and stay readable; free text is redacted.
                out.add(e.getKey(), e.getKey().equals("jobId") || e.getKey().equals("workspaceId") ? e.getValue() : redactJson(e.getValue(), in));
            }
            return out;
        }
        return element;
    }

    /** The last bytes of a stream, the part that explains a failure; invalid UTF-8 at the cut becomes a replacement character. */
    private static String tail(byte[] bytes) {
        int keep = Math.min(bytes.length, MAX_REDACT_CHARS);
        return new String(bytes, bytes.length - keep, keep, StandardCharsets.UTF_8);
    }

    private static String json(JsonObject o) { return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o) + "\n"; }

    private static String cap(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_FILE_BYTES) return text;
        // Keep the start; cut on a character boundary and say so.
        String cut = new String(bytes, 0, MAX_FILE_BYTES - 64, StandardCharsets.UTF_8);
        if (!cut.isEmpty() && cut.charAt(cut.length() - 1) == '�') cut = cut.substring(0, cut.length() - 1);
        return cut + "\n[truncated at " + MAX_FILE_BYTES + " bytes]\n";
    }
}

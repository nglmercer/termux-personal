package com.termux.app.monitor;

import static org.junit.Assert.*;

import com.termux.app.control.jobs.JobHistoryStore;
import com.termux.app.control.jobs.JobRecord;
import com.termux.app.control.jobs.JobTerminationEvidence;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;

public class DiagnosticBundleExporterTest {
    private static DiagnosticBundleExporter.Input input() {
        DeviceCompatibilityAdvisor.Inputs d = new DeviceCompatibilityAdvisor.Inputs();
        d.sdkInt = 34; d.release = "14"; d.manufacturer = "Xiaomi"; d.nativeLauncherAvailable = true;
        DiagnosticBundleExporter.Input in = new DiagnosticBundleExporter.Input();
        in.device = DeviceCompatibilityAdvisor.advise(d);
        in.systemSummary = "Android 14, home /data/data/com.termux/files/home";
        in.homePath = "/data/data/com.termux/files/home"; in.prefixPath = "/data/data/com.termux/files/usr";
        in.generatedAtWall = 1_700_000_000_000L;
        return in;
    }

    private static JobHistoryStore.Record job(String id) {
        JobTerminationEvidence e = new JobTerminationEvidence(id, JobRecord.State.EXITED, 137, 9, JobTerminationEvidence.Reason.UNKNOWN_SIGKILL,
            JobTerminationEvidence.Confidence.NONE, 123L, null, null, true, false, Arrays.asList("exit code 137"),
            Arrays.asList("candidate only; password=hunter2 seen"), Arrays.asList("system logcat is not readable by the app"));
        return new JobHistoryStore.Record(id, "ws", "EXITED", "balanced", null, null, 1L, 2L, 123L, 137, 9, e,
            Arrays.asList(new JobHistoryStore.EventRecord(5, "SAMPLE_FAILED", "token=abc123secret")), false);
    }

    @Test public void redactsCredentialsInFreeText() {
        DiagnosticBundleExporter.Input in = input();
        String text = "password=hunter2 TOKEN: abcdef Authorization: Bearer abcdefghijklmnop git clone https://user:pa55@github.com/x/y --token=ghp_xxxxxxxx "
            + "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY key " + "a".repeat(64) + " ok-text -----BEGIN RSA PRIVATE KEY-----\nMIIBOg\n-----END RSA PRIVATE KEY-----";
        String out = DiagnosticBundleExporter.redact(text, in);
        for (String leaked : new String[] {"hunter2", "abcdefghijklmnop", "pa55", "ghp_xxxxxxxx", "wJalrXUtnFEMI", "MIIBOg", "a".repeat(40)})
            assertFalse(leaked, out.contains(leaked));
        assertTrue(out.contains("ok-text"));
        assertTrue(out.contains("github.com/x/y"));
    }

    @Test public void shortensTheAppsOwnPathsAndKeepsJobIds() {
        DiagnosticBundleExporter.Input in = input();
        assertEquals("~/proj and $PREFIX/bin/cargo", DiagnosticBundleExporter.redact("/data/data/com.termux/files/home/proj and /data/data/com.termux/files/usr/bin/cargo", in));
        String id = "job_" + "0123456789abcdef".repeat(2);
        assertEquals("job id stays readable", id, DiagnosticBundleExporter.redact(id, in));
    }

    @Test public void bundleHasTheDocumentedFilesAndNeverIncludesOutputWithoutConsent() {
        DiagnosticBundleExporter.Input in = input();
        in.jobs = Collections.singletonList(job("job_" + "0123456789abcdef".repeat(2)));
        in.stdoutTail = "build ok; password=leaky".getBytes(StandardCharsets.UTF_8);
        Map<String, String> files = DiagnosticBundleExporter.build(in);
        assertEquals(Arrays.asList("system.txt", "capabilities.json", "jobs.json", "resources.json", "events.json", "report.txt"), new java.util.ArrayList<>(files.keySet()));
        for (String content : files.values()) { assertFalse(content.contains("leaky")); assertFalse(content.contains("hunter2")); assertFalse(content.contains("abc123secret")); }
        assertTrue(files.get("report.txt").contains("Process output is NOT included"));
        assertTrue(files.get("report.txt").contains("system logcat"));
        assertTrue(files.get("jobs.json").contains("UNKNOWN_SIGKILL"));
        assertTrue(files.get("jobs.json").contains("job_0123456789abcdef"));
        assertFalse("raw home path is not shown", files.get("system.txt").contains("/data/data/com.termux"));
        in.includeOutput = true;
        Map<String, String> withOutput = DiagnosticBundleExporter.build(in);
        assertTrue(withOutput.get("output-tail.txt").contains("build ok"));
        assertFalse(withOutput.get("output-tail.txt").contains("leaky"));
        assertTrue(withOutput.get("report.txt").contains("included in output-tail.txt"));
    }

    @Test public void everyFileIsBounded() {
        DiagnosticBundleExporter.Input in = input();
        in.includeOutput = true;
        in.stdoutTail = new byte[2 << 20]; Arrays.fill(in.stdoutTail, (byte) 'x');
        for (String content : DiagnosticBundleExporter.build(in).values())
            assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= DiagnosticBundleExporter.MAX_FILE_BYTES);
        // A 2 MiB run of one character is cut to the redaction bound before any pattern runs: bounded and fast.
        String tail = DiagnosticBundleExporter.build(in).get("output-tail.txt");
        assertTrue(tail.length() < 130 * 1024);
        assertTrue(tail.contains("xxxxxxxx") || tail.contains("<redacted>"));
    }

    @Test public void theZipHoldsEveryFileUnderDiagnostics() throws Exception {
        DiagnosticBundleExporter.Input in = input();
        Map<String, String> files = DiagnosticBundleExporter.build(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiagnosticBundleExporter.writeZip(files, out);
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
        int count = 0;
        for (ZipEntry e; (e = zip.getNextEntry()) != null;) { assertTrue(e.getName().startsWith("diagnostics/")); assertFalse(e.getName().contains("..")); count++; }
        assertEquals(files.size(), count);
    }
}

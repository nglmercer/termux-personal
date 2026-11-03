package com.termux.app.compat.network;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CertificateBundleDiagnosticsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void reportsFixedLocationsWithoutReadingOrExposingContents() throws Exception {
        File prefix = temp.newFolder();
        File tls = new File(prefix, "etc/tls"); assertTrue(tls.mkdirs());
        Files.write(new File(tls, "cert.pem").toPath(), "private contents must stay private".getBytes(StandardCharsets.UTF_8));
        File legacy = new File(prefix, "etc/ssl/certs/ca-certificates.crt");
        assertTrue(legacy.mkdirs()); // A directory is not a readable bundle file.
        String report = CertificateBundleDiagnostics.describe(prefix);
        assertTrue(report.contains("etc/tls/cert.pem: readable"));
        assertTrue(report.contains("etc/ssl/cert.pem: absent or unreadable"));
        assertTrue(report.contains("etc/ssl/certs/ca-certificates.crt: absent or unreadable"));
        assertTrue(report.contains("does not validate"));
        assertFalse(report.contains("private contents"));
        assertFalse(report.contains(prefix.getAbsolutePath()));
    }
}

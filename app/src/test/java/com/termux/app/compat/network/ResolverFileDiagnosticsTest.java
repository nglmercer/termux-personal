package com.termux.app.compat.network;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ResolverFileDiagnosticsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private byte[] data(String text) { return text.getBytes(StandardCharsets.US_ASCII); }

    @Test public void comparesNumericAddressesWithoutReturningThem() throws Exception {
        byte[] v6 = new byte[16]; v6[15] = 1;
        java.util.List<InetAddress> dns = Arrays.asList(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), InetAddress.getByAddress(v6));
        assertEquals(ResolverFileDiagnostics.Status.MATCHES_NETWORK,
            ResolverFileDiagnostics.parse(data("# private comment\nnameserver 127.0.0.1\nnameserver ::1 ; private\n"), dns));
        assertEquals(ResolverFileDiagnostics.Status.DIFFERS_FROM_NETWORK,
            ResolverFileDiagnostics.parse(data("nameserver 127.0.0.1\n"), dns));
        assertEquals(ResolverFileDiagnostics.Status.UNCOMPARED,
            ResolverFileDiagnostics.parse(data("nameserver ::1\n"), Collections.emptyList()));
        NetworkCompatibilityReport report = new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.UNKNOWN, 0)
            .withResolverFiles(ResolverFileDiagnostics.Status.MATCHES_NETWORK, ResolverFileDiagnostics.Status.DIFFERS_FROM_NETWORK, ResolverFileDiagnostics.Status.UNCOMPARED);
        assertTrue(report.describe().contains("DIFFERS_FROM_NETWORK"));
        assertFalse(report.describe().contains("127.0.0.1"));
        assertFalse(report.describe().contains("private comment"));
    }

    @Test public void reportsUnsupportedOrMissingInputConservatively() throws Exception {
        assertEquals(ResolverFileDiagnostics.Status.NO_NAMESERVERS, ResolverFileDiagnostics.parse(data("search private.example\n"), null));
        for (String line : new String[]{"nameserver private.example", "nameserver 999.1.1.1", "nameserver 01.2.3.4", "nameserver fe80::1%wlan0", "nameserver ::1 extra", "nameserver"})
            assertEquals(line, ResolverFileDiagnostics.Status.UNRECOGNIZED, ResolverFileDiagnostics.parse(data(line), null));
        assertEquals(ResolverFileDiagnostics.Status.TOO_LARGE, ResolverFileDiagnostics.parse(new byte[8193], null));
        assertEquals(ResolverFileDiagnostics.Status.ABSENT_OR_UNREADABLE, ResolverFileDiagnostics.inspect(new File(temp.getRoot(), "missing"), null));
        assertEquals(ResolverFileDiagnostics.Status.NOT_REGULAR, ResolverFileDiagnostics.inspect(temp.getRoot(), null));
        File file = temp.newFile();
        Files.write(file.toPath(), data("nameserver 127.0.0.1\n"));
        assertEquals(ResolverFileDiagnostics.Status.UNCOMPARED, ResolverFileDiagnostics.inspect(file, null));
    }
}

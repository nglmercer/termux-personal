package com.termux.app.compat.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkInfo;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LegacyNetworkDiagnosticsTest {
    @Test public void legacyStateDoesNotTreatDisconnectedAsConnected() {
        NetworkInfo info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true);
        assertTrue(NetworkDiagnostics.legacyConnected(info));
        assertFalse(NetworkDiagnostics.legacyConnected(null));
        org.robolectric.Shadows.shadowOf(info).setConnectionStatus(false);
        assertFalse(NetworkDiagnostics.legacyConnected(info));
    }

    @Test public void api22RunsIndependentDnsAndHttpsWithoutAnActiveNetworkObject() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AtomicBoolean resolved = new AtomicBoolean();
        HttpURLConnection connection = new HttpURLConnection(new URL("https://example.com/")) {
            @Override public int getResponseCode() { return 403; }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws java.io.IOException {
                resolved.set(true);
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
            }
            @Override public HttpURLConnection open() { return connection; }
        };
        NetworkCompatibilityReport report = NetworkDiagnostics.runProbe(context, new AtomicBoolean(), 22, backend);
        assertTrue(resolved.get());
        assertEquals(NetworkCompatibilityReport.DnsStatus.RESOLVED, report.dnsStatus);
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, report.status);
        assertEquals(403, report.httpStatus);
        assertEquals(NetworkCompatibilityReport.FamilyStatus.NOT_TESTED, report.ipv4Tcp);
    }
}

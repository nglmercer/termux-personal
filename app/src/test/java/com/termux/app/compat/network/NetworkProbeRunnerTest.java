package com.termux.app.compat.network;

import org.junit.Test;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class NetworkProbeRunnerTest {
    private static class FakeConnection extends HttpURLConnection {
        boolean disconnected;
        FakeConnection() throws Exception { super(new URL("https://example.com/")); }
        @Override public int getResponseCode() { return 403; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return true; }
        @Override public void connect() { }
    }
    @Test public void reportsDnsFailureAndHttpResponseIndependently() throws Exception {
        FakeConnection connection = new FakeConnection();
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws IOException { throw new UnknownHostException("secret"); }
            @Override public HttpURLConnection open() { return connection; }
        };
        NetworkCompatibilityReport result = NetworkProbeRunner.run(backend, new AtomicBoolean());
        assertEquals("failed (DNS_FAILURE)", result.dns);
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, result.status);
        assertEquals(403, result.httpStatus);
        assertFalse(result.describe().contains("secret"));
        assertEquals("HEAD", connection.getRequestMethod());
        assertEquals(3000, connection.getConnectTimeout());
        assertEquals(3000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }
    @Test public void deadlinePreventsStartingHttpsAfterResolverReturns() {
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() { return new InetAddress[0]; }
            @Override public HttpURLConnection open() { throw new AssertionError("must not open"); }
        };
        assertEquals(NetworkCompatibilityReport.Status.TIMEOUT, NetworkProbeRunner.run(backend, new AtomicBoolean(true)).status);
    }
}

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
        assertEquals(NetworkCompatibilityReport.DnsStatus.DNS_FAILURE, result.dnsStatus);
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, result.status);
        assertEquals(403, result.httpStatus);
        assertFalse(result.describe().contains("secret"));
        assertEquals("HEAD", connection.getRequestMethod());
        assertEquals(3000, connection.getConnectTimeout());
        assertEquals(3000, connection.getReadTimeout());
        assertFalse(connection.getInstanceFollowRedirects());
        assertTrue(connection.disconnected);
    }
    @Test public void reportsResolverSuccessSeparatelyFromTheHttpsResult() throws Exception {
        FakeConnection connection = new FakeConnection();
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws IOException {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
            }
            @Override public HttpURLConnection open() { return connection; }
        };
        NetworkCompatibilityReport result = NetworkProbeRunner.run(backend, new AtomicBoolean());
        assertEquals(NetworkCompatibilityReport.DnsStatus.RESOLVED, result.dnsStatus);
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, result.status);
        assertEquals(403, result.httpStatus);
    }
    @Test public void samplesIpv4AndIpv6SeparatelyAndStopsAfterDeadline() throws Exception {
        FakeConnection connection = new FakeConnection();
        AtomicBoolean delivered = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        byte[] v6 = new byte[16]; v6[15] = 1;
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws IOException {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), InetAddress.getByAddress(v6)};
            }
            @Override public HttpURLConnection open() { return connection; }
            @Override public NetworkCompatibilityReport.FamilyStatus tcpSample(InetAddress address) {
                if (calls.getAndIncrement() == 0) return NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED;
                return NetworkCompatibilityReport.FamilyStatus.CONNECT_TIMEOUT;
            }
        };
        NetworkCompatibilityReport report = NetworkProbeRunner.run(backend, delivered);
        assertEquals(NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED, report.ipv4Tcp);
        assertEquals(NetworkCompatibilityReport.FamilyStatus.CONNECT_TIMEOUT, report.ipv6Tcp);
        assertEquals(2, calls.get());
        assertFalse(report.describe().contains("127.0.0.1"));
        assertTrue(report.describe().contains("does not test TLS or other addresses"));
        calls.set(0);
        NetworkProbeRunner.Backend cancelAfterFirst = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws IOException { return backend.resolve(); }
            @Override public HttpURLConnection open() { return connection; }
            @Override public NetworkCompatibilityReport.FamilyStatus tcpSample(InetAddress address) {
                calls.incrementAndGet(); delivered.set(true); return NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED;
            }
        };
        report = NetworkProbeRunner.run(cancelAfterFirst, delivered);
        assertEquals(1, calls.get());
        assertEquals(NetworkCompatibilityReport.FamilyStatus.NOT_TESTED, report.ipv6Tcp);
    }
    @Test public void returnsTypedCertificateDetailsWithoutExceptionMessages() throws Exception {
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() throws IOException { return new InetAddress[0]; }
            @Override public boolean supportsCertificateReason() { return true; }
            @Override public HttpURLConnection open() throws IOException {
                javax.net.ssl.SSLHandshakeException error = new javax.net.ssl.SSLHandshakeException("secret");
                error.initCause(new java.security.cert.CertPathValidatorException("secret", null, null, -1,
                    java.security.cert.CertPathValidatorException.BasicReason.NOT_YET_VALID));
                throw error;
            }
        };
        NetworkCompatibilityReport result = NetworkProbeRunner.run(backend, new AtomicBoolean());
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, result.status);
        assertEquals(TlsDiagnostics.Detail.NOT_YET_VALID, result.tlsDetail);
        assertTrue(result.describe().contains("clock has not been independently verified"));
        assertFalse(result.describe().contains("secret"));
    }
    @Test public void deadlinePreventsStartingHttpsAfterResolverReturns() {
        NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
            @Override public InetAddress[] resolve() { return new InetAddress[0]; }
            @Override public HttpURLConnection open() { throw new AssertionError("must not open"); }
        };
        assertEquals(NetworkCompatibilityReport.Status.TIMEOUT, NetworkProbeRunner.run(backend, new AtomicBoolean(true)).status);
    }
}

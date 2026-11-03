package com.termux.app.compat.auth;

import org.junit.Test;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class BrowserCallbackProbeTest {
    @Test public void acceptsOnlyExactOneTimePathAndDoesNotRequireOauthData() throws Exception {
        try (BrowserCallbackProbe probe = new BrowserCallbackProbe(3000)) {
            URL url = new URL(probe.url());
            assertEquals("127.0.0.1", url.getHost());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BrowserCallbackProbe.Status> result = executor.submit(probe::await);
                try (Socket wrong = new Socket(url.getHost(), url.getPort())) {
                    OutputStream out = wrong.getOutputStream();
                    out.write(("GET " + url.getPath() + "?code=secret HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    assertTrue(new String(wrong.getInputStream().readNBytes(24), StandardCharsets.US_ASCII).contains("404"));
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
                assertEquals(200, connection.getResponseCode());
                connection.disconnect();
                assertEquals(BrowserCallbackProbe.Status.REQUEST_RECEIVED, result.get(4, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void boundsUnrelatedRequestsWithoutCallingThemTimeouts() throws Exception {
        try (BrowserCallbackProbe probe = new BrowserCallbackProbe(5000)) {
            URL url = new URL(probe.url());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BrowserCallbackProbe.Status> result = executor.submit(probe::await);
                for (int i = 0; i < 8; i++) {
                    try (Socket wrong = new Socket(url.getHost(), url.getPort())) {
                        wrong.setSoTimeout(1000);
                        wrong.getOutputStream().write("GET /wrong HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        assertTrue(new String(wrong.getInputStream().readNBytes(24), StandardCharsets.US_ASCII).contains("404"));
                    }
                }
                assertEquals(BrowserCallbackProbe.Status.LIMIT_REACHED, result.get(2, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void timesOutAndCanBeCancelled() throws Exception {
        try (BrowserCallbackProbe probe = new BrowserCallbackProbe(50)) {
            assertEquals(BrowserCallbackProbe.Status.TIMED_OUT, probe.await());
        }
        BrowserCallbackProbe probe = new BrowserCallbackProbe(1000);
        probe.close();
        assertEquals(BrowserCallbackProbe.Status.CANCELLED, probe.await());
    }
    @Test public void ipv6ProbeUsesLoopbackAndAcceptsOnlyTheExactPath() throws Exception {
        BrowserCallbackProbe created;
        try { created = new BrowserCallbackProbe(3000, BrowserCallbackProbe.Family.IPV6); }
        catch (SocketException unavailable) { org.junit.Assume.assumeNoException("IPv6 loopback unavailable", unavailable); return; }
        try (BrowserCallbackProbe probe = created) {
            URL url = new URL(probe.url());
            assertTrue(probe.url().startsWith("http://[::1]:"));
            assertTrue(com.termux.app.compat.UrlPolicy.isWebUrl(probe.url()));
            byte[] loopback = new byte[16]; loopback[15] = 1;
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BrowserCallbackProbe.Status> result = executor.submit(probe::await);
                try (Socket wrong = new Socket(InetAddress.getByAddress(loopback), url.getPort())) {
                    wrong.setSoTimeout(1000);
                    wrong.getOutputStream().write(("GET " + url.getPath() + "?code=secret HTTP/1.1\r\nHost: [::1]\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertTrue(new String(wrong.getInputStream().readNBytes(24), StandardCharsets.US_ASCII).contains("404"));
                }
                try (Socket client = new Socket(InetAddress.getByAddress(loopback), url.getPort())) {
                    client.setSoTimeout(1000);
                    client.getOutputStream().write(("GET " + url.getPath() + " HTTP/1.1\r\nHost: [::1]\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    assertTrue(new String(client.getInputStream().readNBytes(24), StandardCharsets.US_ASCII).contains("200"));
                }
                assertEquals(BrowserCallbackProbe.Status.REQUEST_RECEIVED, result.get(4, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
        }
    }
}

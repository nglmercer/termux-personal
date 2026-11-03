package com.termux.app.compat.auth;

import org.junit.Test;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
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
}

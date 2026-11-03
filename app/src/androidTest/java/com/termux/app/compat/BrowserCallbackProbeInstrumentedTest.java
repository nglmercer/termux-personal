package com.termux.app.compat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.termux.app.compat.auth.BrowserCallbackProbe;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Exercises a real Android loopback socket, not an external browser. */
@RunWith(AndroidJUnit4.class)
public class BrowserCallbackProbeInstrumentedTest {
    @Test public void receivesOwnLocalHttpRequest() throws Exception {
        try (BrowserCallbackProbe probe = new BrowserCallbackProbe()) {
            URL url = new URL(probe.url());
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<BrowserCallbackProbe.Status> result = worker.submit(probe::await);
                try (Socket client = new Socket(url.getHost(), url.getPort())) {
                    client.setSoTimeout(3000);
                    client.getOutputStream().write(("GET " + url.getPath() + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] reply = new byte[32];
                    int count = client.getInputStream().read(reply);
                    assertTrue(count > 0);
                    assertTrue(new String(reply, 0, count, StandardCharsets.US_ASCII).contains("200"));
                }
                assertEquals(BrowserCallbackProbe.Status.REQUEST_RECEIVED, result.get(3, TimeUnit.SECONDS));
            } finally { worker.shutdownNow(); }
        }
    }
}

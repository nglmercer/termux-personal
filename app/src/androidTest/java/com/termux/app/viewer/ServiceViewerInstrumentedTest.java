package com.termux.app.viewer;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Issue #14 on a real phone: the service viewer opens the reference backend (tools/termux-service-example) running under
 * the real Node.js, authenticates the first load with the credential header only, and the page's own fetch and WebSocket
 * then work through the backend's session cookie. Skipped (NOT_TESTED) without Node.js in the Termux prefix.
 */
@RunWith(AndroidJUnit4.class)
public class ServiceViewerInstrumentedTest {
    private static final File NODE = new File("/data/data/com.termux/files/usr/bin/node");
    private File dir;
    private int port;
    private Process backend;
    private volatile ServiceViewerActivity.Endpoint current;

    @Before public void setUp() throws Exception {
        assumeTrue("Node.js is not installed in the Termux prefix: NOT_TESTED", NODE.canExecute());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "viewer-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("server.mjs");
             OutputStream out = new FileOutputStream(new File(dir, "server.mjs"))) {
            byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        }
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = s.getLocalPort(); }
        startBackend();
        ServiceViewerActivity.sEndpointSourceForTesting = (ws, svc) -> current;
    }

    @After public void tearDown() {
        ServiceViewerActivity.sEndpointSourceForTesting = null;
        if (backend != null) backend.destroy();
        if (dir != null) delete(dir);
    }

    private void startBackend() throws Exception {
        String token = hex(32), instance = hex(16), origin = "http://127.0.0.1:" + port;
        ProcessBuilder pb = new ProcessBuilder(NODE.getPath(), new File(dir, "server.mjs").getPath()).redirectErrorStream(true);
        pb.environment().put("TERMUX_SERVICE_TOKEN", token); pb.environment().put("TERMUX_SERVICE_INSTANCE_ID", instance);
        pb.environment().put("TERMUX_SERVICE_PORT", Integer.toString(port)); pb.environment().put("TERMUX_SERVICE_ORIGIN", origin);
        pb.environment().put("LD_LIBRARY_PATH", "/data/data/com.termux/files/usr/lib");
        backend = pb.start();
        assertTrue("backend listens", until(this::listening, 100));
        current = new ServiceViewerActivity.Endpoint("running", instance, origin + "/", origin, true, token);
    }

    @Test public void theViewerAuthenticatesOnceAndThePageWorksThroughItsOwnSession() throws Exception {
        String instance = current.instanceId;
        try (ActivityScenario<ServiceViewerActivity> scenario = ActivityScenario.launch(ServiceViewerActivity.intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), "ws_0123456789abcdef", "api"))) {
            assertTrue("the page fetched its instance with the session cookie",
                until(() -> instance.equals(js(scenario, "document.getElementById('i').textContent")), 200));
            boolean pong = until(() -> ("WebSocket: pong " + instance).equals(js(scenario, "document.getElementById('ws').textContent")), 200);
            assertTrue("the page's WebSocket was accepted with the same session; page shows: " + js(scenario, "document.getElementById('ws').textContent"), pong);
            assertEquals("the credential is not in the URL", current.url, onMain(scenario, a -> a.webViewForTesting().getUrl()));
            assertFalse("nor in anything the page can read", js(scenario, "document.cookie + location.href + document.title").contains(current.credential));

            // file: from an http page is refused by Chromium itself; intent: reaches the viewer's own policy.
            js(scenario, "location.href = 'file:///data/data/com.termux/files/home/'; 'x'");
            Thread.sleep(1500);
            assertEquals("a file URL does not load", current.url, onMain(scenario, a -> a.webViewForTesting().getUrl()));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                // The viewer's own client decides every navigation Chromium hands to it.
                @SuppressWarnings("deprecation") boolean intentHandled = onMain(scenario, a -> a.webViewForTesting().getWebViewClient()
                    .shouldOverrideUrlLoading(a.webViewForTesting(), "intent://scan#Intent;scheme=zxing;end"));
                assertTrue("an intent URL is not loaded", intentHandled);
                assertTrue(onMain(scenario, a -> a.statusForTesting().toString()).startsWith("Blocked"));
                @SuppressWarnings("deprecation") boolean fileHandled = onMain(scenario, a -> a.webViewForTesting().getWebViewClient()
                    .shouldOverrideUrlLoading(a.webViewForTesting(), "file:///data/data/com.termux/files/home/.bashrc"));
                assertTrue("a file URL is not loaded", fileHandled);
                @SuppressWarnings("deprecation") boolean sameHandled = onMain(scenario, a -> a.webViewForTesting().getWebViewClient()
                    .shouldOverrideUrlLoading(a.webViewForTesting(), current.url + "page"));
                assertFalse("the service's own pages load inside", sameHandled);
            }
            assertEquals(current.url, onMain(scenario, a -> a.webViewForTesting().getUrl()));

            // The service restarts: the viewer reports it on the next resume and does not reload by itself.
            backend.destroy(); backend.waitFor(5, TimeUnit.SECONDS);
            assertTrue(until(() -> !listening(), 50));
            startBackend();
            scenario.onActivity(ServiceViewerActivity::checkCurrentRun); // what every onResume does
            assertTrue("the restart is reported", until(() -> onMainQuiet(scenario, a -> a.statusForTesting().toString()).startsWith("The service restarted"), 50));
            assertEquals("the old page is still shown until the user reloads", instance, js(scenario, "document.getElementById('i').textContent"));
        }
    }

    // ---- helpers ----

    private boolean listening() {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", port), 200); return true; } catch (Exception e) { return false; }
    }
    private interface OnMain<T> { T apply(ServiceViewerActivity a); }
    private static <T> T onMain(ActivityScenario<ServiceViewerActivity> scenario, OnMain<T> f) {
        AtomicReference<T> out = new AtomicReference<>();
        scenario.onActivity(a -> out.set(f.apply(a)));
        return out.get();
    }
    private static String onMainQuiet(ActivityScenario<ServiceViewerActivity> scenario, OnMain<String> f) {
        try { return onMain(scenario, f); } catch (RuntimeException e) { return ""; }
    }
    /** Evaluates JavaScript in the viewer's page and returns the string result (unquoted). */
    private static String js(ActivityScenario<ServiceViewerActivity> scenario, String script) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>("");
        scenario.onActivity(a -> a.webViewForTesting().evaluateJavascript("(function(){try{return String(" + script + ")}catch(e){return ''}})()", v -> {
            out.set(v == null || v.equals("null") ? "" : com.google.gson.JsonParser.parseString(v).getAsString()); done.countDown();
        }));
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out.get();
    }
    private static boolean until(java.util.function.BooleanSupplier c, int tenths) throws Exception {
        for (int i = 0; i < tenths; i++) { if (c.getAsBoolean()) return true; Thread.sleep(100); }
        return c.getAsBoolean();
    }
    private static String hex(int bytes) {
        byte[] raw = new byte[bytes]; new SecureRandom().nextBytes(raw);
        StringBuilder s = new StringBuilder(); for (byte b : raw) s.append(String.format("%02x", b)); return s.toString();
    }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
}

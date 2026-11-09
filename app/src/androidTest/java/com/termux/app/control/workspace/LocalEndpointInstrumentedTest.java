package com.termux.app.control.workspace;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
import com.termux.app.control.ipc.RpcDispatcher;
import com.termux.app.control.ipc.RpcRequestParser;
import com.termux.app.control.jobs.AppShellJobAdapter;
import com.termux.app.control.jobs.JobAuthority;
import com.termux.app.control.jobs.JobCoordinator;
import com.termux.app.control.jobs.JobDiagnostics;
import com.termux.app.control.jobs.JobHistoryStore;
import com.termux.app.control.jobs.JobIoPool;
import com.termux.app.control.jobs.JobOutputStore;
import com.termux.app.control.jobs.JobRegistry;
import com.termux.app.control.jobs.JobResourceSupervisor;
import com.termux.app.control.jobs.JobSpec;
import com.termux.app.control.jobs.ResourceAdmissionController;
import com.termux.app.control.resource.AndroidResourceDevice;
import com.termux.app.control.resource.ResourceService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Issue #16 on a real phone: the reference backend (tools/termux-service-example/server.mjs) runs under the real Node.js as
 * a workspace service with the endpoint credential, started by the production coordinator and launcher. A client follows
 * the contract: it asks {@code workspace.service.endpoint}, authenticates, and treats a changed instance as a new run.
 * Skipped (NOT_TESTED) when Node.js is not installed in the Termux prefix.
 */
@RunWith(AndroidJUnit4.class)
public class LocalEndpointInstrumentedTest {
    private static final Set<ControlCore.Scope> ALL = EnumSet.of(ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_EXECUTE, ControlCore.Scope.JOBS_OUTPUT,
        ControlCore.Scope.JOBS_CANCEL, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.WORKSPACES_WRITE);
    private static final File NODE = new File("/data/data/com.termux/files/usr/bin/node");
    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] d) { }
        @Override public void rename(String id, String n) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private File base, server;
    private RpcDispatcher dispatcher;
    private final RpcRequestParser parser = new RpcRequestParser();
    private JobCoordinator coordinator;
    private JobResourceSupervisor supervisor;
    private WorkspaceServiceController services;
    private WorkspacePortService ports;
    private int ids = 10;
    private String ws;
    private int port;

    @Before public void setUp() throws Exception {
        assumeTrue("Node.js is not installed in the Termux prefix: NOT_TESTED", NODE.canExecute());
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        base = new File(context.getCacheDir(), "endpoint-device-" + System.nanoTime());
        File home = new File(base, "home");
        assertTrue(new File(home, "projects/web").mkdirs());
        server = new File(home, "projects/web/server.mjs");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("server.mjs");
             OutputStream out = new FileOutputStream(server)) {
            byte[] buffer = new byte[8192]; for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
        Map<String, File> roots = new HashMap<>(); roots.put("home", home);
        WorkspaceRepository repository = onWorker(() -> new WorkspaceRepository(new File(base, "workspaces.json"), new FileService(roots)));
        JobHistoryStore history = new JobHistoryStore(new File(base, "history")); onWorker(history::open);
        ResourceAdmissionController admission = new ResourceAdmissionController();
        supervisor = JobResourceSupervisor.forAndroid(context, admission);
        JobAuthority authority = new JobAuthority();
        authority.grantScopes(ALL);
        JobCoordinator.Policy policy = new JobCoordinator.Policy() {
            @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long epoch) { return new JobCoordinator.Resolution(spec.executable(), "/"); }
            @Override public boolean authorizeReplay(JobSpec spec, long epoch) { return true; }
        };
        coordinator = new JobCoordinator(new JobRegistry(new JobIoPool(JobIoPool.MAX_JOBS), new JobOutputStore(), JobOutputStore.DEFAULT_RECORD_LIMIT),
            new AppShellJobAdapter(context), policy, authority, admission, supervisor, new JobDiagnostics(history, supervisor, android.os.Build.VERSION.SDK_INT));
        ResourceService resource = new ResourceService(coordinator, supervisor, admission, history, new AndroidResourceDevice(context));
        services = new WorkspaceServiceController(new WorkspaceServiceController.Definitions() {
            @Override public Resolved resolve(String w, String svc) throws WorkspaceRepository.WorkspaceException {
                WorkspaceRepository.Workspace x = repository.get(w); return new Resolved(repository.service(w, svc), x.resourceProfile, x.available);
            }
            @Override public List<WorkspaceRepository.ServiceDefinition> list(String w) throws WorkspaceRepository.WorkspaceException { return repository.get(w).services; }
        }, WorkspaceServiceController.jobsOf(coordinator), id -> { JobHistoryStore.Record r = history.get(id); return r == null ? null : r.evidence; },
            WorkspaceServiceController.Config.defaults(), android.os.SystemClock::elapsedRealtime);
        ports = new WorkspacePortService();
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), coordinator, authority, repository, resource, services, null, ports);
        dispatcher.dispatch(parser.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8)), Collections.<ControlCore.Scope>emptySet());
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = probe.getLocalPort(); }
        ws = ok(rpc("workspace.create", "{\"name\":\"web\",\"rootId\":\"home\",\"path\":\"projects/web\",\"services\":[{\"id\":\"api\",\"executable\":\"" + NODE
            + "\",\"args\":[\"" + server + "\"],\"ports\":[" + port + "],\"credential\":true}]}")).getAsJsonObject("workspace").get("id").getAsString();
    }

    @After public void tearDown() {
        if (services == null) return;
        try { rpc("workspace.service.stop", ref()); } catch (Exception ignored) { }
        services.close(); ports.close(); coordinator.stopAccepting(); supervisor.close(); delete(base);
    }

    @Test public void anOccupiedPortIsNotMistakenForTheService() throws Exception {
        try (ServerSocket squatter = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))) {
            ok(rpc("workspace.service.start", ref()));
            assertTrue("the backend refuses another port and exits", until(() -> !"running".equals(endpointq().get("state").getAsString()), 150));
            JsonObject e = endpoint();
            assertTrue("something accepts connections on the port", onWorker(() -> { try (Socket c = new Socket()) { c.connect(new InetSocketAddress("127.0.0.1", port), 300); return true; } }));
            assertFalse("but the contract does not call it the service", e.get("listening").getAsBoolean());
            assertTrue(e.get("instanceId").isJsonNull()); assertTrue(e.get("credential").isJsonNull());
        }
    }

    @Test public void theBackendChecksCredentialOriginAndHostAndARestartRevokesTheOldCredential() throws Exception {
        JsonObject e = startAndWait();
        String token = e.get("credential").getAsString(), instance = e.get("instanceId").getAsString();
        String authorization = "Authorization: Bearer " + token + "\r\n";
        assertTrue(http("GET", "/__termux/instance", host(), authorization).contains("\"instanceId\":\"" + instance + "\""));
        assertEquals(401, status(http("GET", "/", host(), "")));
        assertEquals(403, status(http("GET", "/", host(), authorization + "Origin: http://evil.example\r\n")));
        assertEquals("DNS rebinding", 421, status(http("GET", "/", "evil.example:" + port, authorization)));
        String upgrade = "Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
        assertEquals(101, status(http("GET", "/ws", host(), upgrade + "Sec-WebSocket-Protocol: termux.bearer." + token + "\r\n")));
        assertEquals(403, status(http("GET", "/ws", host(), upgrade + "Origin: http://evil.example\r\nSec-WebSocket-Protocol: termux.bearer." + token + "\r\n")));

        // The app's network policy: cleartext to the loopback literal only.
        HttpURLConnection c = (HttpURLConnection) new URL(e.get("url").getAsString() + "__termux/instance").openConnection();
        c.setRequestProperty("Authorization", "Bearer " + token);
        assertEquals(200, onWorker(c::getResponseCode).intValue()); c.disconnect();
        try {
            HttpURLConnection other = (HttpURLConnection) new URL("http://localhost:" + port + "/").openConnection();
            onWorker(other::getResponseCode);
            fail("cleartext to any other host name stays blocked");
        } catch (java.io.IOException expected) { /* UnknownServiceException: cleartext not permitted */ }

        // A connection cut in the middle of a non-idempotent operation: the client sees an error, nothing is replayed.
        AtomicReference<String> cut = new AtomicReference<>();
        Thread op = new Thread(() -> { try { cut.set(http("POST", "/api/operation", host(), authorization + "Content-Length: 0\r\n")); } catch (Exception x) { cut.set("error: " + x); } });
        op.start(); Thread.sleep(500);
        ok(rpc("workspace.service.restart", ref()));
        op.join(15000);
        assertFalse("the operation did not complete: " + cut.get(), cut.get().contains("\"operations\":1"));
        JsonObject next = waitForNewInstance(instance);
        assertNotEquals(token, next.get("credential").getAsString());
        assertEquals("the old credential is dead with its run", 401, status(http("GET", "/__termux/instance", host(), authorization)));
        String fresh = http("GET", "/__termux/instance", host(), "Authorization: Bearer " + next.get("credential").getAsString() + "\r\n");
        assertTrue("a new instance, and the cut operation was not replayed: " + fresh,
            fresh.contains("\"instanceId\":\"" + next.get("instanceId").getAsString() + "\"") && fresh.contains("\"operations\":0"));
    }

    @Test public void theServiceIsNotReachableFromTheLan() throws Exception {
        startAndWait();
        InetAddress lan = null;
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces()))
            for (InetAddress a : Collections.list(nif.getInetAddresses())) if (a instanceof Inet4Address && !a.isLoopbackAddress()) lan = a;
        assumeTrue("no non-loopback IPv4 address on this device", lan != null);
        final InetAddress address = lan;
        try (Socket s = new Socket()) {
            onWorker(() -> { s.connect(new InetSocketAddress(address, port), 1000); return null; });
            fail("reachable on " + address);
        } catch (java.io.IOException expected) { /* refused: the backend binds the loopback literal only */ }
    }

    // ---- helpers ----

    private String ref() { return "{\"workspaceId\":\"" + ws + "\",\"serviceId\":\"api\"}"; }
    private String host() { return "127.0.0.1:" + port; }
    private JsonObject endpoint() throws Exception { return ok(rpc("workspace.service.endpoint", ref())).getAsJsonObject("endpoint"); }
    private JsonObject endpointq() { try { return endpoint(); } catch (Exception e) { throw new AssertionError(e); } }
    private JsonObject startAndWait() throws Exception {
        ok(rpc("workspace.service.start", ref()));
        assertTrue("the backend listens", until(() -> endpointq().get("listening").getAsBoolean(), 300));
        return endpoint();
    }
    private JsonObject waitForNewInstance(String old) throws Exception {
        assertTrue(until(() -> {
            JsonObject e = endpointq();
            return e.get("listening").getAsBoolean() && !e.get("instanceId").isJsonNull() && !old.equals(e.get("instanceId").getAsString());
        }, 300));
        return endpoint();
    }
    /** Raw HTTP/1.1 over a socket, so the test can set Host and Origin exactly as a hostile page would. */
    private String http(String method, String path, String hostHeader, String headers) throws Exception {
        return onWorker(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 2000); s.setSoTimeout(8000);
                s.getOutputStream().write((method + " " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\n" + headers + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder(); char[] buf = new char[1024];
                InputStreamReader in = new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8);
                try { for (int n; (n = in.read(buf)) > 0; ) { out.append(buf, 0, n); if (path.equals("/ws") && out.indexOf("\r\n\r\n") >= 0) break; } }
                catch (java.net.SocketTimeoutException timeout) { /* WebSocket stays open */ }
                return out.toString();
            }
        });
    }
    private static int status(String response) { return Integer.parseInt(response.substring(9, 12)); }
    private JsonObject rpc(String method, String params) throws Exception {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":" + (++ids) + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
        return onWorker(() -> JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(json.getBytes(StandardCharsets.UTF_8)), ALL), StandardCharsets.UTF_8)).getAsJsonObject());
    }
    private static JsonObject ok(JsonObject r) { assertTrue(r.toString(), r.has("result")); return r.getAsJsonObject("result"); }
    private static boolean until(java.util.function.BooleanSupplier c, int tenths) throws Exception {
        for (int i = 0; i < tenths; i++) { if (c.getAsBoolean()) return true; Thread.sleep(100); }
        return c.getAsBoolean();
    }
    private interface Call<T> { T call() throws Exception; }
    private static <T> T onWorker(Call<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { result.set(call.call()); } catch (Exception e) { failure.set(e); } });
        worker.start(); worker.join(60000);
        assertFalse("worker timed out", worker.isAlive());
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
}

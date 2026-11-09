package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class WorkspacePortServiceTest {
    private final WorkspacePortService ports = new WorkspacePortService();
    @After public void tearDown() { ports.close(); }

    private static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    private static void assertCode(String code, java.util.concurrent.Callable<?> action) {
        try { action.call(); fail("expected " + code); }
        catch (WorkspacePortService.PortException e) { assertEquals(code, e.code); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void describeReportsListeningConflictsAndNeverClaimsLanExposureForLoopbackOnlyListeners() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort(), closed = freePort();
            Map<String, List<Integer>> declared = new LinkedHashMap<>();
            declared.put("web", Arrays.asList(port)); declared.put("api", Arrays.asList(closed));
            Map<Integer, List<String>> byPort = new LinkedHashMap<>();
            byPort.put(port, Arrays.asList("ws_00000000000000aa/web"));
            // Not running: the open port belongs to somebody else.
            List<Map<String, Object>> rows = WorkspacePortService.describe("ws_00000000000000aa", declared, Collections.<String>emptySet(), byPort, 300);
            Map<String, Object> web = rows.get(0), api = rows.get(1);
            assertEquals(Boolean.TRUE, web.get("listening")); assertEquals("in_use_by_other_process", web.get("conflict"));
            assertEquals("loopback-only listener is not LAN exposed", Boolean.FALSE, web.get("lanExposed"));
            assertEquals(Boolean.FALSE, api.get("listening")); assertNull(api.get("conflict"));
            // Running: the same listener is the service itself.
            Map<String, Object> ok = WorkspacePortService.describe("ws_00000000000000aa", declared, new HashSet<>(Arrays.asList("web")), byPort, 300).get(0);
            assertNull(ok.get("conflict")); assertEquals(Boolean.TRUE, ok.get("serviceRunning"));
            // Two services declaring one port conflict even when nothing listens.
            Map<Integer, List<String>> dup = new LinkedHashMap<>();
            dup.put(closed, Arrays.asList("ws_00000000000000aa/api", "ws_00000000000000bb/other"));
            Map<String, Object> d = WorkspacePortService.describe("ws_00000000000000aa", declared, Collections.<String>emptySet(), dup, 300).get(1);
            assertEquals("declared_by_other_service", d.get("conflict"));
            assertEquals(Arrays.asList("ws_00000000000000bb/other"), d.get("conflictsWith"));
        }
    }

    @Test public void aProxyRelaysTrafficOverLoopbackOnly() throws Exception {
        try (ServerSocket target = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread echo = new Thread(() -> {
                try (Socket s = target.accept()) { InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream(); int b; while ((b = in.read()) != -1) { out.write(b); out.flush(); } }
                catch (Exception ignored) { }
            });
            echo.setDaemon(true); echo.start();
            int listen = freePort();
            WorkspacePortService.Proxy proxy = ports.open(null, listen, target.getLocalPort(), false);
            assertFalse(proxy.lan); assertEquals("127.0.0.1", proxy.listenAddress);
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listen)) {
                client.setSoTimeout(3000);
                client.getOutputStream().write("ping".getBytes()); client.getOutputStream().flush();
                byte[] got = new byte[4]; int off = 0;
                while (off < 4) { int n = client.getInputStream().read(got, off, 4 - off); if (n < 0) break; off += n; }
                assertEquals("ping", new String(got));
            }
        }
    }

    @Test public void listeningBeyondLoopbackNeedsExplicitConfirmationAndTargetsAreNeverRemote() throws Exception {
        int target = freePort();
        assertCode("PERMISSION_REQUIRED", () -> ports.open("0.0.0.0", freePort(), target, false));
        assertCode("PERMISSION_REQUIRED", () -> ports.open("192.168.1.10", freePort(), target, false));
        WorkspacePortService.Proxy lan = ports.open("0.0.0.0", freePort(), target, true);
        assertTrue(lan.lan);
        // There is no way to name a target host at all: the relay always dials 127.0.0.1.
        for (java.lang.reflect.Method m : WorkspacePortService.class.getMethods())
            if (m.getName().equals("open")) for (Class<?> type : m.getParameterTypes()) assertNotEquals("no target address parameter", java.net.InetAddress.class, type);
    }

    @Test public void addressesMustBeLiteralsAndPortsMustBeValid() throws Exception {
        int target = freePort();
        for (String bad : new String[] {"localhost", "example.com", "127.0.0.1; rm -rf /", "$(id)", "", "1.2.3.4.5", "::1%eth0", "0x7f.1"})
            assertCode("INVALID_REQUEST", () -> ports.open(bad, freePort(), target, true));
        assertCode("INVALID_REQUEST", () -> ports.open(null, 80, target, false));        // privileged
        assertCode("INVALID_REQUEST", () -> ports.open(null, 70000, target, false));
        assertCode("INVALID_REQUEST", () -> ports.open(null, freePort(), 0, false));
        assertCode("INVALID_REQUEST", () -> ports.open(null, freePort(), -1, false));
        int same = freePort();
        assertCode("INVALID_REQUEST", () -> ports.open(null, same, same, false));
    }

    @Test public void proxiesAreBoundedAndPortsCannotBeBoundTwice() throws Exception {
        int target = freePort();
        List<WorkspacePortService.Proxy> opened = new java.util.ArrayList<>();
        for (int i = 0; i < WorkspacePortService.MAX_PROXIES; i++) opened.add(ports.open(null, freePort(), target, false));
        assertCode("LIMIT_REACHED", () -> ports.open(null, freePort(), target, false));
        assertTrue(ports.closeProxy(opened.get(0).id));
        assertFalse("closing twice is harmless", ports.closeProxy(opened.get(0).id));
        WorkspacePortService.Proxy again = ports.open(null, opened.get(0).listenPort, target, false);
        assertNotNull(again);
        assertTrue(ports.closeProxy(opened.get(1).id)); // make room so the port check, not the limit, is what refuses
        assertCode("CONFLICT", () -> ports.open(null, again.listenPort, target, false));
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertCode("CONFLICT", () -> { ports.closeProxy(again.id); return ports.open(null, taken.getLocalPort(), target, false); });
        }
    }

    @Test public void closingReleasesEveryListener() throws Exception {
        int listen = freePort();
        ports.open(null, listen, freePort(), false);
        ports.close();
        assertTrue(ports.proxies().isEmpty());
        assertFalse(WorkspacePortService.listeningOnLoopback(listen, 200));
        assertCode("UNAVAILABLE", () -> ports.open(null, freePort(), 5000, false));
    }
}

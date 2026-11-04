package com.termux.app.compat.network;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackDiagnosticsTest {
    @Test public void testsIpv4UsingAnActualEphemeralListener() {
        assertEquals(LoopbackDiagnostics.Status.AVAILABLE, LoopbackDiagnostics.probeAddress("127.0.0.1"));
    }
    @Test public void refusesNonLoopbackAddresses() {
        assertEquals(LoopbackDiagnostics.Status.UNAVAILABLE, LoopbackDiagnostics.probeAddress("192.0.2.1"));
    }
    @Test public void describesOnlyWhatWasActuallyTested() {
        LoopbackDiagnostics.Result result = LoopbackDiagnostics.probe();
        assertEquals(LoopbackDiagnostics.Status.AVAILABLE, result.ipv4);
        assertNotNull(result.ipv6);
        assertTrue(result.describe().contains("external browser"));
        assertTrue(result.describe().contains("have not been tested"));
    }
    @Test public void diagnosesAReservedIpv4PortWithoutConnectingToItsListener() throws Exception {
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        try (java.net.ServerSocket listener = new java.net.ServerSocket()) {
            listener.setReuseAddress(false);
            listener.bind(new java.net.InetSocketAddress(loopback, 0));
            int port = listener.getLocalPort();
            LoopbackDiagnostics.PortResult result = LoopbackDiagnostics.probePort(port);
            assertEquals(LoopbackDiagnostics.PortStatus.BIND_FAILED, result.ipv4);
            assertTrue(result.describe().contains("including by the intended CLI"));
            assertTrue(result.describe().contains("No existing listener was contacted"));
            listener.setSoTimeout(100);
            try { listener.accept().close(); fail("unexpected connection"); }
            catch (java.net.SocketTimeoutException expected) { }
        }
    }
    @Test public void rejectsInvalidPortNumbers() {
        for (int port : new int[]{-1, 0, 65536}) {
            try { LoopbackDiagnostics.probePort(port); fail("accepted " + port); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void releasesThePortAfterTesting() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        LoopbackDiagnostics.PortResult result = LoopbackDiagnostics.probePort(port);
        assertEquals(LoopbackDiagnostics.PortStatus.BIND_AVAILABLE, result.ipv4);
        try (java.net.ServerSocket again = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertTrue(again.isBound());
        }
    }
}

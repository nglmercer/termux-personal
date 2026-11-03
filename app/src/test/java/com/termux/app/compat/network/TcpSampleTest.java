package com.termux.app.compat.network;

import org.junit.Test;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class TcpSampleTest {
    @Test public void connectsToAnActualLocalListenerWithoutSendingData() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(loopback, 0));
            server.setSoTimeout(1000);
            assertEquals(NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED,
                TcpSample.connect(new Socket(), loopback, server.getLocalPort(), 1000));
            try (Socket accepted = server.accept()) {
                accepted.setSoTimeout(1000);
                assertEquals(-1, accepted.getInputStream().read());
            }
        }
    }
    @Test public void identifiesTimeoutWithoutExposingErrorText() throws Exception {
        Socket timeout = new Socket() {
            @Override public void connect(SocketAddress endpoint, int time) throws SocketTimeoutException {
                throw new SocketTimeoutException("private diagnostic detail");
            }
        };
        assertEquals(NetworkCompatibilityReport.FamilyStatus.CONNECT_TIMEOUT,
            TcpSample.connect(timeout, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 443, 50));
    }
    @Test public void refusesProxyVpnAndUnknownPolicyAndRejectsMidTestChange() {
        AtomicInteger attempts = new AtomicInteger();
        for (NetworkCompatibilityReport.FamilyStatus denial : new NetworkCompatibilityReport.FamilyStatus[]{
            NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY, NetworkCompatibilityReport.FamilyStatus.SKIPPED_VPN,
            NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN, NetworkCompatibilityReport.FamilyStatus.NETWORK_CHANGED}) {
            assertEquals(denial, TcpSample.checked(() -> denial, () -> { attempts.incrementAndGet(); return NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED; }));
        }
        assertEquals(0, attempts.get());
        AtomicInteger checks = new AtomicInteger();
        assertEquals(NetworkCompatibilityReport.FamilyStatus.NETWORK_CHANGED,
            TcpSample.checked(() -> checks.getAndIncrement() == 0 ? null : NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY,
                () -> NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED));
        assertEquals(NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN,
            TcpSample.checked(() -> { throw new SecurityException(); }, () -> NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED));
    }
}

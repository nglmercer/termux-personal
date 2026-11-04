package com.termux.app.compat.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** One bounded TCP connection; a success does not verify TLS or other addresses. */
final class TcpSample {
    interface Policy { NetworkCompatibilityReport.FamilyStatus check(); }
    interface Connection { NetworkCompatibilityReport.FamilyStatus connect(); }
    private TcpSample() { }
    /** A null policy result permits a sample; check again after connection for mid-test changes. */
    static NetworkCompatibilityReport.FamilyStatus checked(Policy policy, Connection connection) {
        try {
            NetworkCompatibilityReport.FamilyStatus before = policy.check();
            if (before != null) return before;
            NetworkCompatibilityReport.FamilyStatus result = connection.connect();
            NetworkCompatibilityReport.FamilyStatus after = policy.check();
            if (after == NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN) return after;
            if (after != null) return NetworkCompatibilityReport.FamilyStatus.NETWORK_CHANGED;
            return result == null ? NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN : result;
        } catch (RuntimeException e) { return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN; }
    }
    static NetworkCompatibilityReport.FamilyStatus connect(Socket socket, InetAddress address, int port, int timeoutMs) {
        try (Socket closeable = socket) {
            closeable.connect(new InetSocketAddress(address, port), timeoutMs);
            return NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED;
        } catch (SocketTimeoutException e) {
            return NetworkCompatibilityReport.FamilyStatus.CONNECT_TIMEOUT;
        } catch (SecurityException e) {
            return NetworkCompatibilityReport.FamilyStatus.SECURITY_RESTRICTED;
        } catch (IOException e) {
            if (NetworkErrorClassifier.classify(e) == NetworkCompatibilityReport.Status.CONNECTION_REFUSED)
                return NetworkCompatibilityReport.FamilyStatus.CONNECTION_REFUSED;
            return NetworkCompatibilityReport.FamilyStatus.CONNECT_FAILED;
        } catch (IllegalArgumentException e) {
            return NetworkCompatibilityReport.FamilyStatus.CONNECT_FAILED;
        }
    }
}

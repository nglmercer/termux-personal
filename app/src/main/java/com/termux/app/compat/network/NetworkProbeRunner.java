package com.termux.app.compat.network;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs independent native-DNS and HTTPS observations without interpreting either as the other. */
final class NetworkProbeRunner {
    interface Backend {
        InetAddress[] resolve() throws IOException;
        HttpURLConnection open() throws IOException;
        default boolean supportsCertificateReason() { return false; }
        default NetworkCompatibilityReport.FamilyStatus tcpSample(InetAddress address) {
            return NetworkCompatibilityReport.FamilyStatus.NOT_TESTED;
        }
    }
    private NetworkProbeRunner() { }

    static NetworkCompatibilityReport run(Backend backend, AtomicBoolean delivered) {
        return run(backend, delivered, 3000);
    }
    /** Injectable timeout keeps local TLS integration tests reliable on slower hosts. */
    static NetworkCompatibilityReport run(Backend backend, AtomicBoolean delivered, int socketTimeoutMs) {
        if (socketTimeoutMs < 1 || socketTimeoutMs > 30000) throw new IllegalArgumentException("Invalid socket timeout");
        String dns;
        InetAddress ipv4 = null, ipv6 = null;
        NetworkCompatibilityReport.DnsStatus dnsStatus;
        try {
            InetAddress[] addresses = backend.resolve();
            int v4 = 0, v6 = 0;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) { v4++; if (ipv4 == null) ipv4 = address; }
                if (address instanceof Inet6Address) { v6++; if (ipv6 == null) ipv6 = address; }
            }
            dns = addresses.length == 0 ? "no records" :
                "resolved (IPv4 records: " + v4 + ", IPv6 records: " + v6 + ")";
            dnsStatus = addresses.length == 0 ? NetworkCompatibilityReport.DnsStatus.NO_RECORDS : NetworkCompatibilityReport.DnsStatus.RESOLVED;
        } catch (Exception e) {
            NetworkCompatibilityReport.Status failure = NetworkErrorClassifier.classify(e);
            dns = "failed (" + failure + ")";
            dnsStatus = failure == NetworkCompatibilityReport.Status.DNS_FAILURE ? NetworkCompatibilityReport.DnsStatus.DNS_FAILURE : NetworkCompatibilityReport.DnsStatus.UNKNOWN;
        }
        // A proxy may resolve the destination remotely even when local DNS fails.
        if (delivered.get()) return new NetworkCompatibilityReport(dns, dnsStatus, NetworkCompatibilityReport.Status.TIMEOUT, 0);
        HttpURLConnection connection = null;
        NetworkCompatibilityReport.Status https;
        TlsDiagnostics.Detail tlsDetail = TlsDiagnostics.Detail.NOT_IDENTIFIED;
        int httpStatus = 0;
        try {
            connection = backend.open();
            connection.setConnectTimeout(socketTimeoutMs);
            connection.setReadTimeout(socketTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            httpStatus = connection.getResponseCode();
            https = httpStatus < 100 ? NetworkCompatibilityReport.Status.UNKNOWN : NetworkCompatibilityReport.Status.HTTP_RESPONSE;
        } catch (Exception e) {
            https = NetworkErrorClassifier.classify(e);
            if (https == NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE)
                tlsDetail = TlsDiagnostics.classify(e, backend.supportsCertificateReason());
        } finally {
            if (connection != null) connection.disconnect();
        }
        NetworkCompatibilityReport.FamilyStatus v4 = NetworkCompatibilityReport.FamilyStatus.NOT_TESTED;
        NetworkCompatibilityReport.FamilyStatus v6 = NetworkCompatibilityReport.FamilyStatus.NOT_TESTED;
        if (!delivered.get() && dnsStatus == NetworkCompatibilityReport.DnsStatus.RESOLVED) {
            v4 = ipv4 == null ? NetworkCompatibilityReport.FamilyStatus.NO_DNS_RECORD : sample(backend, ipv4);
            if (!delivered.get()) v6 = ipv6 == null ? NetworkCompatibilityReport.FamilyStatus.NO_DNS_RECORD : sample(backend, ipv6);
        }
        return new NetworkCompatibilityReport(dns, dnsStatus, https, httpStatus, v4, v6, tlsDetail);
    }
    private static NetworkCompatibilityReport.FamilyStatus sample(Backend backend, InetAddress address) {
        try {
            NetworkCompatibilityReport.FamilyStatus result = backend.tcpSample(address);
            return result == null ? NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN : result;
        } catch (RuntimeException e) { return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN; }
    }
}

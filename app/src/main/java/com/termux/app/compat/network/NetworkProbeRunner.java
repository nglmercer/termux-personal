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
    }
    private NetworkProbeRunner() { }

    static NetworkCompatibilityReport run(Backend backend, AtomicBoolean delivered) {
        String dns;
        NetworkCompatibilityReport.DnsStatus dnsStatus;
        try {
            InetAddress[] addresses = backend.resolve();
            int v4 = 0, v6 = 0;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) v4++;
                if (address instanceof Inet6Address) v6++;
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
        try {
            connection = backend.open();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            int status = connection.getResponseCode();
            return new NetworkCompatibilityReport(dns, dnsStatus, status < 100 ? NetworkCompatibilityReport.Status.UNKNOWN : NetworkCompatibilityReport.Status.HTTP_RESPONSE, status);
        } catch (Exception e) {
            return new NetworkCompatibilityReport(dns, dnsStatus, NetworkErrorClassifier.classify(e), 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

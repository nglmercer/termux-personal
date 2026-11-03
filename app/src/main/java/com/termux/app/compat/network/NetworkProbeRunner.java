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
        try {
            InetAddress[] addresses = backend.resolve();
            int v4 = 0, v6 = 0;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) v4++;
                if (address instanceof Inet6Address) v6++;
            }
            dns = addresses.length == 0 ? "no records" :
                "resolved (IPv4 records: " + v4 + ", IPv6 records: " + v6 + ")";
        } catch (Exception e) {
            dns = "failed (" + NetworkErrorClassifier.classify(e) + ")";
        }
        // A proxy may resolve the destination remotely even when local DNS fails.
        if (delivered.get()) return new NetworkCompatibilityReport(dns, NetworkCompatibilityReport.Status.TIMEOUT, 0);
        HttpURLConnection connection = null;
        try {
            connection = backend.open();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            int status = connection.getResponseCode();
            return new NetworkCompatibilityReport(dns, status < 100 ? NetworkCompatibilityReport.Status.UNKNOWN : NetworkCompatibilityReport.Status.HTTP_RESPONSE, status);
        } catch (Exception e) {
            return new NetworkCompatibilityReport(dns, NetworkErrorClassifier.classify(e), 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

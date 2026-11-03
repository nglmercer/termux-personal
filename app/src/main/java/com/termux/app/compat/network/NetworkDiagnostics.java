package com.termux.app.compat.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit network probes. A stuck resolver can occupy at most one daemon worker. */
public final class NetworkDiagnostics {
    private static final int SOCKET_TIMEOUT_MS = 3000;
    private static final long TOTAL_TIMEOUT_MS = 10000;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), task -> { Thread t = new Thread(task, "compat-network-probe"); t.setDaemon(true); return t; });
    private NetworkDiagnostics() {}

    public interface Callback { void onResult(NetworkCompatibilityReport result); }

    /** Returns only non-sensitive state; does not contact the network. */
    public static String snapshot(Context context) {
        StringBuilder out = new StringBuilder();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || Build.VERSION.SDK_INT < 23) {
            out.append("Active network: unavailable on this API\n");
        } else {
            try {
                Network network = manager.getActiveNetwork();
                if (network == null) out.append("Active network: none or blocked\n");
                else {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    LinkProperties link = manager.getLinkProperties(network);
                    out.append("Active network: present\nValidated: ")
                        .append(caps == null ? "unknown" : caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .append("\nVPN transport: ")
                        .append(caps == null ? "unknown" : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                    out.append("\nDNS resolver count: ").append(link == null ? "unknown" : link.getDnsServers().size());
                    if (Build.VERSION.SDK_INT >= 28)
                        out.append("\nPrivate DNS active: ").append(link == null ? "unknown" : link.isPrivateDnsActive());
                    out.append("\nHTTP proxy configured: ").append(manager.getDefaultProxy() != null).append('\n');
                }
            } catch (SecurityException | IllegalStateException e) {
                out.append("Active network: unavailable\n");
            }
        }
        File bundle = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/tls/cert.pem");
        out.append("Termux CA bundle (etc/tls/cert.pem): ")
            .append(bundle.isFile() && bundle.canRead() ? "readable" : "not readable or absent")
            .append("\nTrust stores, certificate contents and CLI environment overrides: not tested.");
        return out.toString();
    }

    /** Completion or deadline is delivered once on the main thread. DNS may outlive the deadline. */
    public static void probe(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicReference<Callback> target = new AtomicReference<>(callback);
        Runnable deadline = () -> {
            if (delivered.compareAndSet(false, true)) {
                Callback recipient = target.getAndSet(null);
                if (recipient != null) recipient.onResult(new NetworkCompatibilityReport(
                    "unfinished", NetworkCompatibilityReport.Status.TIMEOUT, 0));
            }
        };
        try {
            WORKER.execute(() -> {
                NetworkCompatibilityReport report = runProbe(app, delivered);
                handler.post(() -> {
                    if (delivered.compareAndSet(false, true)) {
                        handler.removeCallbacks(deadline);
                        Callback recipient = target.getAndSet(null);
                        if (recipient != null) recipient.onResult(report);
                    }
                });
            });
            handler.postDelayed(deadline, TOTAL_TIMEOUT_MS);
        } catch (RejectedExecutionException e) {
            Callback recipient = target.getAndSet(null);
            if (recipient != null) recipient.onResult(new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.UNAVAILABLE, 0));
        }
    }

    private static NetworkCompatibilityReport runProbe(Context context, AtomicBoolean delivered) {
        String dns = "not tested";
        HttpURLConnection connection = null;
        try {
            if (Build.VERSION.SDK_INT < 23) return new NetworkCompatibilityReport(dns, NetworkCompatibilityReport.Status.UNAVAILABLE, 0);
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return new NetworkCompatibilityReport(dns, NetworkCompatibilityReport.Status.UNAVAILABLE, 0);
            Network network = manager.getActiveNetwork();
            if (network == null) return new NetworkCompatibilityReport(dns, NetworkCompatibilityReport.Status.NO_NETWORK, 0);
            InetAddress[] addresses = network.getAllByName("example.com");
            if (addresses.length == 0) return new NetworkCompatibilityReport("no records", NetworkCompatibilityReport.Status.DNS_FAILURE, 0);
            int v4 = 0, v6 = 0;
            for (InetAddress address : addresses) {
                if (address instanceof Inet4Address) v4++;
                if (address instanceof Inet6Address) v6++;
            }
            dns = "resolved (IPv4 records: " + v4 + ", IPv6 records: " + v6 + ")";
            if (delivered.get()) return new NetworkCompatibilityReport(dns, NetworkCompatibilityReport.Status.TIMEOUT, 0);
            connection = (HttpURLConnection) network.openConnection(new URL("https://example.com/"));
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
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

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
import java.net.URL;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit network probes. A stuck resolver can occupy at most one daemon worker. */
public final class NetworkDiagnostics {
    private static final long TOTAL_TIMEOUT_MS = 12000;
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
                out.append("Process network binding: ").append(manager.getBoundNetworkForProcess() != null ? "explicit" : "default").append('\n');
            } catch (SecurityException | IllegalStateException e) {
                out.append("Active network: unavailable\n");
            }
        }
        out.append(CertificateBundleDiagnostics.describe(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)));
        return out.toString();
    }

    /** Completion or deadline is delivered once on the main thread. DNS may outlive the deadline. */
    public static void probe(Context context, Callback callback) {
        AndroidNetworkObservations.clear();
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
        try {
            if (Build.VERSION.SDK_INT < 23) return new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.UNAVAILABLE, 0);
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.UNAVAILABLE, 0);
            Network bound = manager.getBoundNetworkForProcess();
            Network network = bound != null ? bound : manager.getActiveNetwork();
            if (network == null) return new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.NO_NETWORK, 0);
            NetworkObservationStore.Ticket ticket = bound == null ? AndroidNetworkObservations.begin(context, network) : null;
            NetworkCompatibilityReport report = NetworkProbeRunner.run(new NetworkProbeRunner.Backend() {
                @Override public java.net.InetAddress[] resolve() throws java.io.IOException { return java.net.InetAddress.getAllByName("example.com"); }
                @Override public boolean supportsCertificateReason() { return Build.VERSION.SDK_INT >= 24; }
                @Override public java.net.HttpURLConnection open() throws java.io.IOException {
                    return (java.net.HttpURLConnection) new URL("https://example.com/").openConnection();
                }
                @Override public NetworkCompatibilityReport.FamilyStatus tcpSample(java.net.InetAddress address) {
                    return AndroidTcpSample.sample(manager, network, address, delivered);
                }
            }, delivered);
            if (!delivered.get()) {
                try {
                    LinkProperties link = manager.getLinkProperties(network);
                    java.util.List<java.net.InetAddress> dnsServers = link == null ? null : link.getDnsServers();
                    File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                    report = report.withResolverFiles(
                        ResolverFileDiagnostics.inspect(new File("/etc/resolv.conf"), dnsServers),
                        ResolverFileDiagnostics.inspect(new File(prefix, "etc/resolv.conf"), dnsServers),
                        ResolverFileDiagnostics.inspect(new File(prefix, "glibc/etc/resolv.conf"), dnsServers));
                } catch (RuntimeException ignored) { /* Do not discard completed network observations. */ }
            }
            if (!delivered.get()) {
                try { AndroidNetworkObservations.finish(ticket, report); }
                catch (RuntimeException ignored) { /* Tracking is optional; retain the probe result. */ }
            }
            return report;
        } catch (RuntimeException e) {
            return new NetworkCompatibilityReport("not tested", NetworkErrorClassifier.classify(e), 0);
        }
    }
}

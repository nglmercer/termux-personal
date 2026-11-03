package com.termux.app.compat.network;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Samples the process default route without explicitly binding around Android VPN routing. */
final class AndroidTcpSample {
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final URI TARGET = URI.create("https://example.com/");
    private static final URI SOCKET_TARGET = URI.create("socket://example.com:443");
    private AndroidTcpSample() { }

    static NetworkCompatibilityReport.FamilyStatus sample(ConnectivityManager manager, Network expected, InetAddress address,
                                                          AtomicBoolean delivered) {
        return TcpSample.checked(() -> policy(manager, expected), () -> delivered.get() ?
            NetworkCompatibilityReport.FamilyStatus.NOT_TESTED : TcpSample.connect(new Socket(), address, 443, CONNECT_TIMEOUT_MS));
    }
    static NetworkCompatibilityReport.FamilyStatus policy(ConnectivityManager manager, Network expected) {
        try {
            // Do not sample when another component has explicitly bound the process to a network.
            if (manager.getBoundNetworkForProcess() != null) return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN;
            Network active = manager.getActiveNetwork();
            if (active == null || !active.equals(expected)) return NetworkCompatibilityReport.FamilyStatus.NETWORK_CHANGED;
            NetworkCapabilities caps = manager.getNetworkCapabilities(active);
            if (caps == null) return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
                return NetworkCompatibilityReport.FamilyStatus.SKIPPED_VPN;
            if (manager.getDefaultProxy() != null || hasProperty("http.proxyHost") ||
                hasProperty("https.proxyHost") || hasProperty("socksProxyHost"))
                return NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY;
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN;
            NetworkCompatibilityReport.FamilyStatus httpsPolicy = selectedProxy(selector, TARGET);
            if (httpsPolicy != null) return httpsPolicy;
            return selectedProxy(selector, SOCKET_TARGET);
        } catch (RuntimeException e) { return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN; }
    }
    private static NetworkCompatibilityReport.FamilyStatus selectedProxy(ProxySelector selector, URI target) {
        List<Proxy> proxies = selector.select(target);
        if (proxies == null || proxies.isEmpty()) return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN;
        for (Proxy proxy : proxies) {
            if (proxy == null) return NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN;
            if (proxy.type() != Proxy.Type.DIRECT) return NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY;
        }
        return null;
    }
    private static boolean hasProperty(String name) {
        String value = System.getProperty(name);
        return value != null && !value.isEmpty();
    }
}

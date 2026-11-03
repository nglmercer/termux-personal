package com.termux.app.compat.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ProxyInfo;
import android.os.Build;
import android.os.SystemClock;
import java.util.Map;
import java.util.Objects;

/** Tracks only an explicitly initiated probe. Network events or configuration changes invalidate it. */
public final class AndroidNetworkObservations {
    private static final NetworkObservationStore STORE = new NetworkObservationStore();
    private static ConnectivityManager manager;
    private static boolean registered;
    private AndroidNetworkObservations() { }

    private static final class Key {
        final Network network;
        final int links, capabilities, proxy;
        final boolean hasProxy;
        Key(Network network, LinkProperties links, NetworkCapabilities capabilities, ProxyInfo proxy) {
            this.network = network; this.links = links.hashCode(); this.capabilities = capabilities.hashCode();
            this.hasProxy = proxy != null; this.proxy = proxy == null ? 0 : proxy.hashCode();
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key k = (Key) other;
            return network.equals(k.network) && links == k.links && capabilities == k.capabilities && proxy == k.proxy && hasProxy == k.hasProxy;
        }
        @Override public int hashCode() { return Objects.hash(network, links, capabilities, proxy, hasProxy); }
    }
    private static synchronized boolean register(Context context) {
        if (registered) return true;
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Context app = context.getApplicationContext();
            if (app == null) return false;
            ConnectivityManager candidate = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (candidate == null) return false;
            candidate.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { STORE.invalidate(); }
                @Override public void onLost(Network network) { STORE.invalidate(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { STORE.invalidate(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { STORE.invalidate(); }
            });
            manager = candidate;
            registered = true;
            return true;
        } catch (RuntimeException e) { return false; }
    }
    private static synchronized ConnectivityManager manager() { return registered ? manager : null; }
    private static Key currentKey(ConnectivityManager cm) {
        if (cm == null || Build.VERSION.SDK_INT < 24) return null;
        try {
            if (cm.getBoundNetworkForProcess() != null) return null;
            Network network = cm.getActiveNetwork();
            if (network == null) return null;
            LinkProperties links = cm.getLinkProperties(network);
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            if (links == null || capabilities == null) return null;
            return new Key(network, links, capabilities, cm.getDefaultProxy());
        } catch (RuntimeException e) { return null; }
    }
    public static void clear() { STORE.invalidate(); }
    public static NetworkObservationStore.Ticket begin(Context context, Network network) {
        if (!register(context)) return null;
        Key key = currentKey(manager());
        return key != null && key.network.equals(network) ? STORE.begin(key) : null;
    }
    public static void finish(NetworkObservationStore.Ticket ticket, NetworkCompatibilityReport report) {
        if (ticket == null) return;
        STORE.finish(ticket, currentKey(manager()), SystemClock.elapsedRealtime(), report);
    }
    /** Local Android state reads only; never initiates DNS, TLS, or an activity. Call off the service thread. */
    public static Map<String, Object> current() {
        if (!STORE.hasObservation()) return null;
        return STORE.current(currentKey(manager()), SystemClock.elapsedRealtime());
    }
}

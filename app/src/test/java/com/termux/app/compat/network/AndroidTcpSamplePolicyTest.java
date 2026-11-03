package com.termux.app.compat.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import static org.junit.Assert.*;

/** Mocks Android network metadata only; this is not OEM or actual VPN validation. */
@RunWith(RobolectricTestRunner.class)
public class AndroidTcpSamplePolicyTest {
    @Test public void detectsVpnExplicitBindingAndConfiguredProxy() {
        ConnectivityManager manager = (ConnectivityManager) RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager.getActiveNetwork();
        assertNotNull(active);
        NetworkCapabilities vpn = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(vpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        Shadows.shadowOf(manager).setNetworkCapabilities(active, vpn);
        assertEquals(NetworkCompatibilityReport.FamilyStatus.SKIPPED_VPN, AndroidTcpSample.policy(manager, active));
        NetworkCapabilities ordinary = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(ordinary).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(manager).setNetworkCapabilities(active, ordinary);
        String previous = System.getProperty("https.proxyHost");
        try {
            System.setProperty("https.proxyHost", "test-proxy.invalid");
            assertEquals(NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY, AndroidTcpSample.policy(manager, active));
        } finally {
            if (previous == null) System.clearProperty("https.proxyHost"); else System.setProperty("https.proxyHost", previous);
        }
        assertTrue(manager.bindProcessToNetwork(active));
        try { assertEquals(NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN, AndroidTcpSample.policy(manager, active)); }
        finally { manager.bindProcessToNetwork(null); }
        assertEquals(NetworkCompatibilityReport.FamilyStatus.NETWORK_CHANGED, AndroidTcpSample.policy(manager, null));
    }    @Test public void detectsSocketProxyAndFailsClosedOnUnknownSelector() {
        ConnectivityManager manager = (ConnectivityManager) RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = manager.getActiveNetwork();
        assertNotNull(active);
        NetworkCapabilities ordinary = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(ordinary).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        Shadows.shadowOf(manager).setNetworkCapabilities(active, ordinary);
        java.net.ProxySelector previousSelector = java.net.ProxySelector.getDefault();
        String[] names = {"http.proxyHost", "https.proxyHost", "socksProxyHost"};
        String[] previousProperties = new String[names.length];
        for (int i = 0; i < names.length; i++) { previousProperties[i] = System.getProperty(names[i]); System.clearProperty(names[i]); }
        try {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                    if ("socket".equals(uri.getScheme())) return java.util.Collections.singletonList(
                        new java.net.Proxy(java.net.Proxy.Type.SOCKS, new java.net.InetSocketAddress("127.0.0.1", 1080)));
                    return java.util.Collections.singletonList(java.net.Proxy.NO_PROXY);
                }
                @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            });
            assertEquals(NetworkCompatibilityReport.FamilyStatus.SKIPPED_PROXY, AndroidTcpSample.policy(manager, active));
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override public java.util.List<java.net.Proxy> select(java.net.URI uri) { throw new SecurityException(); }
                @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            });
            assertEquals(NetworkCompatibilityReport.FamilyStatus.POLICY_UNKNOWN, AndroidTcpSample.policy(manager, active));
        } finally {
            java.net.ProxySelector.setDefault(previousSelector);
            for (int i = 0; i < names.length; i++) {
                if (previousProperties[i] == null) System.clearProperty(names[i]); else System.setProperty(names[i], previousProperties[i]);
            }
        }
    }

}

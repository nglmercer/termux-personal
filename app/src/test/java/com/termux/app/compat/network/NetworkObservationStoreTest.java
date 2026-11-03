package com.termux.app.compat.network;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class NetworkObservationStoreTest {
    private static NetworkCompatibilityReport response() {
        return new NetworkCompatibilityReport("resolved", NetworkCompatibilityReport.DnsStatus.RESOLVED,
            NetworkCompatibilityReport.Status.HTTP_RESPONSE, 403);
    }
    @Test public void observationIsNarrowScopedAndExpires() {
        NetworkObservationStore store = new NetworkObservationStore();
        NetworkObservationStore.Ticket ticket = store.begin("network-A-config-1");
        assertTrue(store.finish(ticket, "network-A-config-1", 100, response()));
        Map<String, Object> report = store.current("network-A-config-1", 5100);
        assertEquals("ANDROID_PROBE_NETWORK_EXAMPLE_COM", report.get("scope"));
        assertEquals("RESOLVED", report.get("dns"));
        assertEquals("HTTP_RESPONSE", report.get("https"));
        assertEquals(403, report.get("httpStatus"));
        assertEquals(5L, report.get("ageSeconds"));
        assertNull(store.current("network-A-config-1", 60100));
    }
    @Test public void switchConfigAndCallbackInvalidateEvenIfNetworkReturns() {
        NetworkObservationStore store = new NetworkObservationStore();
        NetworkObservationStore.Ticket first = store.begin("A");
        store.invalidate();
        assertFalse(store.finish(first, "A", 1, response()));
        NetworkObservationStore.Ticket second = store.begin("A");
        assertFalse(store.finish(second, "B", 2, response()));
        assertFalse(store.finish(second, "A", 3, response()));
        assertTrue(store.finish(store.begin("A"), "A", 3, response()));
        assertNull(store.current("A-new-proxy-or-dns", 4));
        assertNull(store.current("A", 5));
    }
    @Test public void doesNotReportAResponseForErrorsOrWhenClockMovesBack() {
        NetworkObservationStore store = new NetworkObservationStore();
        NetworkCompatibilityReport failed = new NetworkCompatibilityReport("failed", NetworkCompatibilityReport.DnsStatus.DNS_FAILURE,
            NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, 403);
        assertTrue(store.finish(store.begin("A"), "A", 10, failed));
        assertNull(store.current("A", 11).get("httpStatus"));
        assertNull(store.current("A", 9));
        assertNull(store.begin(null));
    }
}

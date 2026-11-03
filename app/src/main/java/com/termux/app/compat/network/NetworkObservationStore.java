package com.termux.app.compat.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, in-memory history of one explicitly requested, network-scoped probe. */
public final class NetworkObservationStore {
    private static final long TTL_MS = 60_000;
    private long generation;
    private Object key;
    private long observedAt;
    private NetworkCompatibilityReport report;

    public static final class Ticket {
        private final long generation;
        private final Object key;
        private Ticket(long generation, Object key) { this.generation = generation; this.key = key; }
    }
    public synchronized Ticket begin(Object currentKey) {
        return currentKey == null ? null : new Ticket(generation, currentKey);
    }
    public synchronized void invalidate() {
        generation++;
        key = null;
        report = null;
    }
    public synchronized boolean finish(Ticket ticket, Object currentKey, long now, NetworkCompatibilityReport value) {
        if (ticket == null || value == null || now < 0 || ticket.generation != generation) return false;
        if (currentKey == null || !Objects.equals(ticket.key, currentKey)) { invalidate(); return false; }
        key = currentKey;
        report = value;
        observedAt = now;
        return true;
    }
    public synchronized boolean hasObservation() { return report != null; }
    public synchronized Map<String, Object> current(Object currentKey, long now) {
        if (report == null) return null;
        if (currentKey == null || !Objects.equals(key, currentKey) || now < observedAt || now - observedAt >= TTL_MS) {
            invalidate();
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "ANDROID_PROBE_NETWORK_EXAMPLE_COM");
        out.put("ageSeconds", (now - observedAt) / 1000);
        out.put("dns", report.dnsStatus.name());
        out.put("https", report.status.name());
        out.put("httpStatus", report.status == NetworkCompatibilityReport.Status.HTTP_RESPONSE && report.httpStatus >= 100 && report.httpStatus <= 999 ? report.httpStatus : null);
        return out;
    }
}

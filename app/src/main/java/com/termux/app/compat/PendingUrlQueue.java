package com.termux.app.compat;

import java.util.ArrayDeque;

/** Volatile, bounded queue: authorization URLs are never persisted. */
public final class PendingUrlQueue {
    private static final int LIMIT = 8;
    private static final long TTL_MS = 5 * 60 * 1000L;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private static final class Entry {
        final String url;
        final long time;
        Entry(String url, long time) { this.url = url; this.time = time; }
    }
    private void expire(long now) {
        while (!entries.isEmpty() && (now < entries.peekFirst().time || now - entries.peekFirst().time >= TTL_MS)) entries.removeFirst();
    }
    public synchronized boolean offer(String url, long now) {
        expire(now);
        if (!UrlPolicy.isWebUrl(url) || entries.size() >= LIMIT) return false;
        entries.addLast(new Entry(url, now));
        return true;
    }
    public synchronized String poll(long now) {
        expire(now);
        return entries.isEmpty() ? null : entries.removeFirst().url;
    }
}

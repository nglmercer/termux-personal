package com.termux.app.compat;

import java.util.ArrayDeque;

/** Volatile, bounded queue: authorization URLs are never persisted. */
public final class PendingUrlQueue {
    private static final int LIMIT = 8;
    private static final long TTL_MS = 5 * 60 * 1000L;
    private final ArrayDeque<Request> entries = new ArrayDeque<>();
    public static final class Request {
        private final String url;
        final long time;
        private Request(String url, long time) { this.url = url; this.time = time; }
        public String url() { return url; }
    }
    private void expire(long now) {
        while (!entries.isEmpty() && (now < entries.peekFirst().time || now - entries.peekFirst().time >= TTL_MS)) entries.removeFirst();
    }
    public synchronized boolean offer(String url, long now) {
        expire(now);
        if (!UrlPolicy.isWebUrl(url) || entries.size() >= LIMIT) return false;
        entries.addLast(new Request(url, now));
        return true;
    }
    public synchronized Request peek(long now) {
        expire(now);
        return entries.peekFirst();
    }
    public synchronized boolean isPending(Request request, long now) {
        expire(now);
        return request != null && entries.peekFirst() == request;
    }
    public synchronized String consume(Request request, long now) {
        if (!isPending(request, now)) return null;
        return entries.removeFirst().url;
    }
    public synchronized String poll(long now) {
        expire(now);
        return entries.isEmpty() ? null : entries.removeFirst().url;
    }
}

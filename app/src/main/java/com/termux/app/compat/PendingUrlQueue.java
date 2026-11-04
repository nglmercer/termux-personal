package com.termux.app.compat;

import java.util.ArrayDeque;

/** Volatile, bounded queue: authorization URLs are never persisted. */
public final class PendingUrlQueue {
    private static final int LIMIT = 8;
    private static final long TTL_MS = 5 * 60 * 1000L;
    private final ArrayDeque<Request> entries = new ArrayDeque<>();
    public static final class Request {
        private volatile String url;
        final long time;
        private Request(String url, long time) { this.url = url; this.time = time; }
        public String url() { return url; }
    }
    private void expire(long now) {
        while (!entries.isEmpty() && (now < entries.peekFirst().time || now - entries.peekFirst().time >= TTL_MS))
            entries.removeFirst().url = null;
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
        return removeFirst();
    }
    public synchronized String poll(long now) {
        expire(now);
        return entries.isEmpty() ? null : removeFirst();
    }
    private String removeFirst() {
        Request request = entries.removeFirst();
        String value = request.url;
        request.url = null;
        return value;
    }
    /** Expires stale requests and returns the delay until the next expiry, or -1 for an empty queue. */
    public synchronized long delayUntilExpiry(long now) {
        expire(now);
        return entries.isEmpty() ? -1 : TTL_MS - (now - entries.peekFirst().time);
    }
}

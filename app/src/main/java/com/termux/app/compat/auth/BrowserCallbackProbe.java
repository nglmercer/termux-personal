package com.termux.app.compat.auth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/** A one-time test request, not an OAuth endpoint. Never records request data. */
public final class BrowserCallbackProbe implements Closeable {
    public enum Status { REQUEST_RECEIVED, TIMED_OUT, LIMIT_REACHED, CANCELLED, FAILED }
    public enum Family { IPV4, IPV6 }
    private static final int MAX_HEADERS = 8192;
    private static final int MAX_CONNECTIONS = 8;
    private final ServerSocket listener;
    private final String path;
    private final String urlHost;
    private final long durationNanos;
    private volatile boolean closed;

    public BrowserCallbackProbe() throws IOException { this(30_000, Family.IPV4); }
    public BrowserCallbackProbe(Family family) throws IOException { this(30_000, family); }
    BrowserCallbackProbe(long timeoutMs) throws IOException { this(timeoutMs, Family.IPV4); }
    BrowserCallbackProbe(long timeoutMs, Family family) throws IOException {
        if (timeoutMs <= 0 || timeoutMs > 30_000) throw new IllegalArgumentException("Invalid timeout");
        if (family == null) throw new IllegalArgumentException("Missing address family");
        durationNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(33).append('/');
        char[] hex = "0123456789abcdef".toCharArray();
        for (byte b : random) value.append(hex[(b & 255) >>> 4]).append(hex[b & 15]);
        path = value.toString();
        urlHost = family == Family.IPV4 ? "127.0.0.1" : "[::1]";
        byte[] loopback = family == Family.IPV4 ? new byte[]{127, 0, 0, 1} : new byte[16];
        if (family == Family.IPV6) loopback[15] = 1;
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(InetAddress.getByAddress(loopback), 0), 4); }
        catch (IOException | RuntimeException e) { try { listener.close(); } catch (IOException ignored) { } throw e; }
    }
    public String url() { return "http://" + urlHost + ":" + listener.getLocalPort() + path; }

    /** Runs on a worker. No request headers, body, parameters or errors are retained. */
    public Status await() {
        long deadline = System.nanoTime() + durationNanos;
        try {
            for (int count = 0; count < MAX_CONNECTIONS && !closed; ) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return Status.TIMED_OUT;
                listener.setSoTimeout((int) Math.max(1, Math.min(1000, TimeUnit.NANOSECONDS.toMillis(left))));
                try (Socket socket = listener.accept()) {
                    count++;
                    if (!socket.getInetAddress().isLoopbackAddress()) continue;
                    socket.setSoTimeout(1000);
                    InputStream in = socket.getInputStream();
                    boolean match = matchesRequestLine(in, deadline);
                    try { drainHeaders(in, deadline); } catch (IOException ignored) { }
                    try { reply(socket.getOutputStream(), match); } catch (IOException ignored) { }
                    if (match) return Status.REQUEST_RECEIVED;
                } catch (SocketTimeoutException ignored) {
                    // The accept/read deadline is re-evaluated before trying another connection.
                }
            }
            if (closed) return Status.CANCELLED;
            return System.nanoTime() >= deadline ? Status.TIMED_OUT : Status.LIMIT_REACHED;
        } catch (SocketException e) {
            return closed ? Status.CANCELLED : Status.FAILED;
        } catch (IOException | SecurityException e) {
            return Status.FAILED;
        } finally { try { close(); } catch (IOException ignored) { } }
    }
    /** Compare as bytes; do not retain a request target or any header supplied by a peer. */
    private boolean matchesRequestLine(InputStream in, long deadline) throws IOException {
        String prefix = "GET " + path + " HTTP/1.";
        for (int i = 0; i < prefix.length(); i++) {
            if (System.nanoTime() >= deadline || in.read() != prefix.charAt(i)) return false;
        }
        if (System.nanoTime() >= deadline) return false;
        int version = in.read();
        if (version != '0' && version != '1') return false;
        if (System.nanoTime() >= deadline) return false;
        int end = in.read();
        if (end == '\r') {
            if (System.nanoTime() >= deadline) return false;
            end = in.read();
        }
        return end == '\n';
    }
    /** Discard remaining headers without storing them, bounded by size and the probe deadline. */
    private static void drainHeaders(InputStream in, long deadline) throws IOException {
        int lineSize = 0;
        for (int i = 0; i < MAX_HEADERS && System.nanoTime() < deadline; i++) {
            int c = in.read();
            if (c == -1) return;
            if (c == '\n') {
                if (lineSize == 0) return;
                lineSize = 0;
            } else if (c != '\r') lineSize++;
        }
    }
    private static void reply(OutputStream out, boolean match) throws IOException {
        byte[] body = (match ? "Termux received the test request. You may return to Termux.\n" : "Not found.\n").getBytes(StandardCharsets.US_ASCII);
        String header = "HTTP/1.1 " + (match ? "200 OK" : "404 Not Found") +
            "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }
    @Override public void close() throws IOException { closed = true; listener.close(); }
}

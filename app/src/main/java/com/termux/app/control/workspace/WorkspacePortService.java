package com.termux.app.control.workspace;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows where a workspace's services listen and, optionally, relays a local port to another one.
 *
 * <p>Native Termux processes already share the Android network, so no VM-style NAT forwarding is needed; this class only
 * reports and, on request, relays. Nothing here is built from a shell string: addresses are parsed as IP literals and ports are
 * integers.
 *
 * <ul>
 * <li>Port status is observed by connecting: {@code listening} on loopback, and {@code lanExposed} when the same port also
 * accepts connections on one of the device's own non-loopback addresses.</li>
 * <li>A proxy listens on loopback by default. Listening on another address (LAN exposure) needs an explicit flag that the
 * caller must have confirmed, and the target is always loopback, so a proxy can never be pointed at another host.</li>
 * <li>Proxies, and the connections they carry, are bounded; every socket has an idle timeout; closing stops everything.</li>
 * </ul>
 */
public final class WorkspacePortService implements Closeable {
    public static final int MAX_PROXIES = 4, MAX_CONNECTIONS = 32, IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000, MIN_LISTEN_PORT = 1024;

    public static final class Proxy {
        public final String id, listenAddress; public final int listenPort, targetPort; public final boolean lan;
        private final ServerSocket server;
        private Proxy(String id, String listenAddress, int listenPort, int targetPort, boolean lan, ServerSocket server) {
            this.id = id; this.listenAddress = listenAddress; this.listenPort = listenPort; this.targetPort = targetPort; this.lan = lan; this.server = server;
        }
    }

    /** Refusals carry a reserved v1 domain code. */
    public static final class PortException extends Exception {
        public final String code;
        PortException(String code) { super(code); this.code = code; }
    }

    private final Map<String, Proxy> mProxies = new LinkedHashMap<>();
    private final AtomicInteger mConnections = new AtomicInteger();
    private final AtomicInteger mCounter = new AtomicInteger();
    private boolean mClosed;

    // ---- observation ----

    /** True when something accepts connections on {@code 127.0.0.1:port}. Blocking, bounded by the timeout. */
    public static boolean listeningOnLoopback(int port, int timeoutMillis) { return connects(InetAddress.getLoopbackAddress(), port, timeoutMillis); }

    /** True when the port also accepts connections on a non-loopback address of this device. */
    public static boolean lanExposed(int port, int timeoutMillis) {
        for (InetAddress address : localAddresses()) if (connects(address, port, timeoutMillis)) return true;
        return false;
    }

    static boolean connects(InetAddress address, int port, int timeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), Math.max(50, Math.min(timeoutMillis, 2000)));
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /** Non-loopback, non-link-local addresses of up interfaces. */
    static List<InetAddress> localAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (Enumeration<InetAddress> e = nif.getInetAddresses(); e.hasMoreElements();) {
                    InetAddress a = e.nextElement();
                    if (!a.isLoopbackAddress() && !a.isLinkLocalAddress() && !a.isAnyLocalAddress()) result.add(a);
                }
            }
        } catch (SocketException | RuntimeException ignored) { /* no interface list: report nothing exposed rather than guess */ }
        return result;
    }

    /**
     * Describes the declared ports of services. {@code running} says whether the service itself is up, which lets a listener
     * without a running service be reported as a conflict with something else.
     *
     * @param declared service ID to its ports
     * @param running  service IDs whose job is running
     */
    public static List<Map<String, Object>> describe(String workspaceId, Map<String, List<Integer>> declared, java.util.Set<String> running,
                                                      Map<Integer, List<String>> declaredElsewhere, int timeoutMillis) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> service : declared.entrySet()) {
            for (Integer port : service.getValue()) {
                boolean listening = listeningOnLoopback(port, timeoutMillis);
                boolean serviceUp = running.contains(service.getKey());
                List<String> others = new ArrayList<>(declaredElsewhere.containsKey(port) ? declaredElsewhere.get(port) : Collections.<String>emptyList());
                others.remove(workspaceId + "/" + service.getKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("serviceId", service.getKey()); row.put("port", port);
                row.put("listening", listening);
                row.put("serviceRunning", serviceUp);
                row.put("lanExposed", listening && lanExposed(port, timeoutMillis));
                // In use by something that is not this service, or declared by another service too.
                String conflict = !others.isEmpty() ? "declared_by_other_service" : (listening && !serviceUp) ? "in_use_by_other_process" : null;
                row.put("conflict", conflict);
                row.put("conflictsWith", others);
                row.put("address", "127.0.0.1:" + port);
                rows.add(row);
            }
        }
        return rows;
    }

    // ---- proxies ----

    /**
     * Relays {@code listenAddress:listenPort} to {@code 127.0.0.1:targetPort}.
     *
     * @param lanConfirmed must be true to listen on anything other than loopback; the caller obtains it from the user
     */
    public synchronized Proxy open(String listenAddress, int listenPort, int targetPort, boolean lanConfirmed) throws PortException {
        if (mClosed) throw new PortException("UNAVAILABLE");
        if (listenPort < MIN_LISTEN_PORT || listenPort > 65535 || targetPort < 1 || targetPort > 65535) throw new PortException("INVALID_REQUEST");
        InetAddress address = parseLiteral(listenAddress == null ? "127.0.0.1" : listenAddress);
        boolean loopback = address.isLoopbackAddress();
        if (!loopback && !lanConfirmed) throw new PortException("PERMISSION_REQUIRED");
        if (loopback && listenPort == targetPort) throw new PortException("INVALID_REQUEST"); // would shadow the service itself
        if (mProxies.size() >= MAX_PROXIES) throw new PortException("LIMIT_REACHED");
        for (Proxy existing : mProxies.values()) if (existing.listenPort == listenPort) throw new PortException("CONFLICT");
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address, listenPort), 16);
        } catch (IOException e) { throw new PortException("CONFLICT"); }
        Proxy proxy = new Proxy("px_" + mCounter.incrementAndGet(), address.getHostAddress(), listenPort, targetPort, !loopback, server);
        mProxies.put(proxy.id, proxy);
        Thread acceptor = new Thread(() -> accept(proxy), "TermuxPortProxy-" + proxy.id);
        acceptor.setDaemon(true); acceptor.start();
        return proxy;
    }

    public synchronized boolean closeProxy(String id) {
        Proxy proxy = mProxies.remove(id);
        if (proxy == null) return false;
        closeQuietly(proxy.server);
        return true;
    }

    public synchronized List<Proxy> proxies() { return Collections.unmodifiableList(new ArrayList<>(mProxies.values())); }

    @Override public synchronized void close() {
        mClosed = true;
        for (Proxy proxy : mProxies.values()) closeQuietly(proxy.server);
        mProxies.clear();
    }

    /** Only literal IPv4 or IPv6 addresses: a host name would trigger a DNS lookup and could resolve anywhere. */
    static InetAddress parseLiteral(String text) throws PortException {
        if (text == null || text.isEmpty() || text.length() > 45) throw new PortException("INVALID_REQUEST");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(Character.digit(c, 16) >= 0 || c == '.' || c == ':')) throw new PortException("INVALID_REQUEST");
        }
        try { return InetAddress.getByName(text); } // a literal never resolves through DNS
        catch (IOException | RuntimeException e) { throw new PortException("INVALID_REQUEST"); }
    }

    private void accept(Proxy proxy) {
        while (!proxy.server.isClosed()) {
            final Socket client;
            try { client = proxy.server.accept(); } catch (IOException e) { return; }
            if (mConnections.incrementAndGet() > MAX_CONNECTIONS) { mConnections.decrementAndGet(); closeQuietly(client); continue; }
            Thread relay = new Thread(() -> relay(client, proxy.targetPort), "TermuxPortRelay");
            relay.setDaemon(true); relay.start();
        }
    }

    private void relay(Socket client, int targetPort) {
        Socket target = new Socket();
        try {
            target.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), 3000);
            client.setSoTimeout(IDLE_TIMEOUT_MILLIS); target.setSoTimeout(IDLE_TIMEOUT_MILLIS);
            Thread back = new Thread(() -> pump(target, client), "TermuxPortRelayBack");
            back.setDaemon(true); back.start();
            pump(client, target);
        } catch (IOException e) { /* target down: close the client */ }
        finally { closeQuietly(client); closeQuietly(target); mConnections.decrementAndGet(); }
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); out.flush(); }
        } catch (IOException e) { /* idle timeout or peer closed */ }
        finally { closeQuietly(from); closeQuietly(to); }
    }

    private static void closeQuietly(Closeable closeable) { try { closeable.close(); } catch (IOException ignored) { } }
}

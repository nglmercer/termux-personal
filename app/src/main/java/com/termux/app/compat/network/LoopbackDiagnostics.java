package com.termux.app.compat.network;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Tests our own ephemeral loopback listeners only; never connects to a CLI or reads authentication data. */
public final class LoopbackDiagnostics {
    public enum Status { AVAILABLE, BIND_FAILED, CONNECT_TIMEOUT, CONNECT_FAILED, ACCEPT_TIMEOUT, ACCEPT_FAILED, SECURITY_RESTRICTED, UNAVAILABLE }
    public enum PortStatus { BIND_AVAILABLE, BIND_FAILED, SECURITY_RESTRICTED, UNAVAILABLE }
    public static final class Result {
        public final Status ipv4;
        public final Status ipv6;
        Result(Status ipv4, Status ipv6) { this.ipv4 = ipv4; this.ipv6 = ipv6; }
        public String describe() {
            return "IPv4 loopback listener and connection: " + ipv4 +
                "\nIPv6 loopback listener and connection: " + ipv6 +
                "\n\nThis tests a temporary listener in Termux only. An external browser, a CLI's callback listener and an OAuth redirect have not been tested. No authorization data was read.";
        }
    }
    private static final int TIMEOUT_MS = 1500;
    private LoopbackDiagnostics() { }
    public static Result probe() { return new Result(probeAddress("127.0.0.1"), probeAddress("::1")); }

    public static final class PortResult {
        public final int port;
        public final PortStatus ipv4, ipv6;
        PortResult(int port, PortStatus ipv4, PortStatus ipv6) { this.port = port; this.ipv4 = ipv4; this.ipv6 = ipv6; }
        public String describe() {
            return "Port " + port + " IPv4 loopback bind: " + ipv4 + "\nPort " + port + " IPv6 loopback bind: " + ipv6 +
                "\n\nEach bind was brief and the port was released. A failed bind can mean the port is already in use, including by the intended CLI, the address family is unavailable, or another restriction. " +
                "A successful bind does not reserve the port or guarantee a CLI can bind later. No existing listener was contacted, and no authentication data was read.";
        }
    }

    /** Explicit per-port test; no connection is made to an existing service. */
    public static PortResult probePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return new PortResult(port, probePortAddress(new byte[]{127, 0, 0, 1}, port),
            probePortAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}, port));
    }

    private static PortStatus probePortAddress(byte[] address, int port) {
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(address), port), 1);
            return PortStatus.BIND_AVAILABLE;
        } catch (SecurityException e) { return PortStatus.SECURITY_RESTRICTED; }
        catch (BindException e) { return PortStatus.BIND_FAILED; }
        catch (IOException e) { return PortStatus.UNAVAILABLE; }
    }

    static Status probeAddress(String address) {
        InetAddress loopback;
        try {
            loopback = InetAddress.getByName(address);
            if (!loopback.isLoopbackAddress()) return Status.UNAVAILABLE;
        } catch (IOException | SecurityException e) { return Status.UNAVAILABLE; }
        try (ServerSocket listener = new ServerSocket()) {
            // Explicit address and an ephemeral port prevent wildcard exposure and collisions.
            try { listener.bind(new InetSocketAddress(loopback, 0), 1); }
            catch (BindException e) { return Status.BIND_FAILED; }
            catch (SecurityException e) { return Status.SECURITY_RESTRICTED; }
            catch (IOException e) { return Status.BIND_FAILED; }
            listener.setSoTimeout(TIMEOUT_MS);
            try (Socket client = new Socket()) {
                try { client.connect(new InetSocketAddress(loopback, listener.getLocalPort()), TIMEOUT_MS); }
                catch (SocketTimeoutException e) { return Status.CONNECT_TIMEOUT; }
                catch (SecurityException e) { return Status.SECURITY_RESTRICTED; }
                catch (IOException e) { return Status.CONNECT_FAILED; }
                try (Socket accepted = listener.accept()) {
                    return accepted.getInetAddress().isLoopbackAddress() && accepted.getPort() == client.getLocalPort()
                        ? Status.AVAILABLE : Status.ACCEPT_FAILED;
                } catch (SocketTimeoutException e) { return Status.ACCEPT_TIMEOUT; }
                catch (SecurityException e) { return Status.SECURITY_RESTRICTED; }
                catch (IOException e) { return Status.ACCEPT_FAILED; }
            }
        } catch (SecurityException e) { return Status.SECURITY_RESTRICTED; }
        catch (IOException e) { return Status.UNAVAILABLE; }
    }
}

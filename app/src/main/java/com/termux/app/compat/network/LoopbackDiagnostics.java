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

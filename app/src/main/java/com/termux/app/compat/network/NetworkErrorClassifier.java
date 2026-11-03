package com.termux.app.compat.network;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;

/** Classify only evidence carried by exception types, never exception messages. */
public final class NetworkErrorClassifier {
    private NetworkErrorClassifier() {}
    public static NetworkCompatibilityReport.Status classify(Throwable error) {
        if (TlsDiagnostics.classify(error, false) != TlsDiagnostics.Detail.NOT_IDENTIFIED)
            return NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE;
        boolean handshake = false, timeout = false, dns = false, refused = false;
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            handshake |= error instanceof SSLHandshakeException;
            timeout |= error instanceof SocketTimeoutException;
            dns |= error instanceof UnknownHostException;
            refused |= error instanceof ConnectException;
            if (error.getCause() == error) break;
        }
        if (handshake) return NetworkCompatibilityReport.Status.TLS_HANDSHAKE_FAILURE;
        if (dns) return NetworkCompatibilityReport.Status.DNS_FAILURE;
        // ConnectException can also mean an unreachable network, not only refusal.
        if (refused) return NetworkCompatibilityReport.Status.UNKNOWN;
        // A URLConnection timeout can happen during DNS, connection, handshake or read.
        if (timeout) return NetworkCompatibilityReport.Status.TIMEOUT;
        return NetworkCompatibilityReport.Status.UNKNOWN;
    }
}

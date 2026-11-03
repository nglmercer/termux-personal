package com.termux.app.compat.network;

import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import javax.net.ssl.SSLPeerUnverifiedException;

/** Classifies typed certificate evidence only; never parses or retains exception text. */
public final class TlsDiagnostics {
    public enum Detail { NOT_IDENTIFIED, CERTIFICATE_ERROR, CERTIFICATE_PATH_INVALID, PEER_UNVERIFIED,
        EXPIRED, NOT_YET_VALID, CONFLICTING_VALIDITY }
    private TlsDiagnostics() { }

    /** Set supportsReason only on Android API 24+ or a runtime implementing getReason(). */
    public static Detail classify(Throwable error, boolean supportsReason) {
        boolean expired = false, notYet = false, path = false, peer = false, certificate = false;
        for (int i = 0; error != null && i < 16; i++, error = error.getCause()) {
            expired |= error instanceof CertificateExpiredException;
            notYet |= error instanceof CertificateNotYetValidException;
            certificate |= error instanceof CertificateException;
            peer |= error instanceof SSLPeerUnverifiedException;
            if (error instanceof CertPathValidatorException) {
                path = true;
                if (supportsReason) {
                    int reason = Api24.reason((CertPathValidatorException) error);
                    expired |= reason == 1;
                    notYet |= reason == 2;
                }
            }
            if (error.getCause() == error) break;
        }
        if (expired && notYet) return Detail.CONFLICTING_VALIDITY;
        if (expired) return Detail.EXPIRED;
        if (notYet) return Detail.NOT_YET_VALID;
        if (path) return Detail.CERTIFICATE_PATH_INVALID;
        if (peer) return Detail.PEER_UNVERIFIED;
        if (certificate) return Detail.CERTIFICATE_ERROR;
        return Detail.NOT_IDENTIFIED;
    }

    /** Isolated so Android 21–23 never loads the API 24 reason type. */
    private static final class Api24 {
        static int reason(CertPathValidatorException exception) {
            CertPathValidatorException.Reason reason = exception.getReason();
            if (reason == CertPathValidatorException.BasicReason.EXPIRED) return 1;
            if (reason == CertPathValidatorException.BasicReason.NOT_YET_VALID) return 2;
            return 0;
        }
    }
}

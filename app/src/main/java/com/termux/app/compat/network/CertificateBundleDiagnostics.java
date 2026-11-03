package com.termux.app.compat.network;

import java.io.File;

/** Checks fixed Termux bundle locations without reading certificates or changing trust. */
public final class CertificateBundleDiagnostics {
    private static final String[] LOCATIONS = {
        "etc/tls/cert.pem", "etc/ssl/cert.pem", "etc/ssl/certs/ca-certificates.crt"
    };
    private CertificateBundleDiagnostics() { }
    public static String describe(File prefix) {
        if (prefix == null) throw new IllegalArgumentException("prefix required");
        StringBuilder result = new StringBuilder();
        for (String location : LOCATIONS) {
            File file = new File(prefix, location);
            boolean readable;
            try { readable = file.isFile() && file.canRead(); }
            catch (SecurityException e) { readable = false; }
            if (result.length() > 0) result.append('\n');
            result.append("Termux CA bundle ").append(location).append(": ")
                .append(readable ? "readable" : "absent or unreadable");
        }
        return result.append("\nReadability does not validate certificate contents or establish trust.")
            .append(" Android app trust, user-installed CAs and CLI trust stores may differ and have not been compared.")
            .append(" CLI certificate environment overrides have not been inspected.").toString();
    }
}

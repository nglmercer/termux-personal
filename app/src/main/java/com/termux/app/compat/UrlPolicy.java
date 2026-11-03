package com.termux.app.compat;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.IDN;

/** Policy for the generic web launcher, independent of Android's permissive Uri parser. */
public final class UrlPolicy {
    private static final int MAX_LENGTH = 8192;
    private UrlPolicy() {}

    public static boolean isWebUrl(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_LENGTH) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return false;
            if (uri.getHost() != null)
                return !uri.getHost().isEmpty() && uri.getRawUserInfo() == null && uri.getPort() <= 65535;
            // URI.getHost() rejects Unicode DNS names. Validate their IDNA form without changing the
            // original URL dispatched to Android, and reject ambiguous authorities and user info.
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') >= 0 || authority.indexOf('%') >= 0) return false;
            int colon = authority.indexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            String port = colon < 0 ? "" : authority.substring(colon);
            if (host.isEmpty() || (colon >= 0 && (port.length() == 1 || !port.substring(1).matches("[0-9]+")))) return false;
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            URI checked = new URI("http://" + ascii + port);
            return checked.getHost() != null && checked.getRawUserInfo() == null && checked.getPort() <= 65535;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }
}

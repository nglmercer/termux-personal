package com.termux.app.compat;

import java.net.URI;
import java.net.URISyntaxException;

/** Policy for the generic web launcher, independent of Android's permissive Uri parser. */
public final class UrlPolicy {
    private static final int MAX_LENGTH = 8192;
    private UrlPolicy() {}

    public static boolean isWebUrl(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_LENGTH) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null && uri.getPort() <= 65535;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

package com.termux.app.viewer;

import java.net.URI;
import java.util.Locale;

/**
 * Pure decisions of the service viewer, kept apart from the WebView so they can be tested on the JVM.
 * The viewer shows exactly one origin, the service's {@code http://127.0.0.1:PORT}; nothing else is loaded inside it.
 */
public final class ServiceViewerPolicy {
    private ServiceViewerPolicy() { }

    public enum Navigation {
        /** Same origin as the service: load it in the viewer. */
        LOAD,
        /** A web page elsewhere: hand it to the user's browser, which never receives the service credential. */
        EXTERNAL,
        /** Anything else ({@code file:}, {@code content:}, {@code intent:}, {@code javascript:}, malformed): refuse. */
        BLOCK
    }

    /** {@code origin} is the endpoint's {@code http://127.0.0.1:PORT}. */
    public static Navigation navigation(String origin, String url) {
        if (origin == null || url == null) return Navigation.BLOCK;
        URI target;
        try { target = new URI(url); } catch (Exception e) { return Navigation.BLOCK; }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return Navigation.BLOCK;
        if (target.getRawUserInfo() != null) return Navigation.BLOCK; // http://127.0.0.1:1@evil.example tricks
        return sameOrigin(origin, target) ? Navigation.LOAD : Navigation.EXTERNAL;
    }

    static boolean sameOrigin(String origin, URI target) {
        URI base;
        try { base = new URI(origin); } catch (Exception e) { return false; }
        String host = target.getHost();
        int port = target.getPort() != -1 ? target.getPort() : "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
        return "http".equalsIgnoreCase(target.getScheme()) && host != null && host.equals(base.getHost()) && port == base.getPort();
    }

    /**
     * What the viewer should tell the user after checking the endpoint again. The page is never reloaded automatically:
     * a reload could repeat an operation whose outcome is unknown, so the user decides.
     */
    public enum Check { SAME_RUN, RESTARTED, STOPPED }

    public static Check check(String shownInstanceId, String currentInstanceId) {
        if (currentInstanceId == null) return Check.STOPPED;
        return currentInstanceId.equals(shownInstanceId) ? Check.SAME_RUN : Check.RESTARTED;
    }
}

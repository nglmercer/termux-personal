package com.termux.app.viewer;

import static com.termux.app.viewer.ServiceViewerPolicy.Navigation.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ServiceViewerPolicyTest {
    private static final String ORIGIN = "http://127.0.0.1:3000";

    @Test public void onlyTheServiceOriginLoadsInside() {
        assertEquals(LOAD, ServiceViewerPolicy.navigation(ORIGIN, "http://127.0.0.1:3000/"));
        assertEquals(LOAD, ServiceViewerPolicy.navigation(ORIGIN, "http://127.0.0.1:3000/app?x=1#y"));
        assertEquals("another local port is another service", EXTERNAL, ServiceViewerPolicy.navigation(ORIGIN, "http://127.0.0.1:3001/"));
        assertEquals("localhost is not the same origin", EXTERNAL, ServiceViewerPolicy.navigation(ORIGIN, "http://localhost:3000/"));
        assertEquals(EXTERNAL, ServiceViewerPolicy.navigation(ORIGIN, "https://127.0.0.1:3000/"));
        assertEquals(EXTERNAL, ServiceViewerPolicy.navigation(ORIGIN, "https://example.com/docs"));
        assertEquals("default port 80 is not 3000", EXTERNAL, ServiceViewerPolicy.navigation(ORIGIN, "http://127.0.0.1/"));
    }

    @Test public void otherSchemesAndTricksAreRefused() {
        for (String url : new String[] {"file:///data/data/com.termux/files/home/.bashrc", "content://x/y", "intent://scan#Intent;end",
            "javascript:alert(1)", "data:text/html,hi", "http://127.0.0.1:3000@evil.example/", "http://evil.example@127.0.0.1:3000/", "::not a url", ""})
            assertEquals(url, BLOCK, ServiceViewerPolicy.navigation(ORIGIN, url));
        assertEquals(BLOCK, ServiceViewerPolicy.navigation(null, "http://127.0.0.1:3000/"));
    }

    @Test public void aChangedOrMissingInstanceIsReportedNeverReloaded() {
        assertEquals(ServiceViewerPolicy.Check.SAME_RUN, ServiceViewerPolicy.check("a", "a"));
        assertEquals(ServiceViewerPolicy.Check.RESTARTED, ServiceViewerPolicy.check("a", "b"));
        assertEquals(ServiceViewerPolicy.Check.STOPPED, ServiceViewerPolicy.check("a", null));
    }
}

package com.termux.app.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void acceptsWebAndRejectsUnsafeOrMalformedUris() {
        assertTrue(UrlPolicy.isWebUrl("https://example.com/auth?state=abc#fragment"));
        assertTrue(UrlPolicy.isWebUrl("http://127.0.0.1:1234/callback"));
        assertTrue(UrlPolicy.isWebUrl("https://[::1]:1234/"));
        for (String url : new String[]{"intent://example.com", "file:///tmp/a", "content://a/b", "https:///path", "https://user:pass@example.com", "https://example.com:99999", "https://example.com/\n"}) assertFalse(url, UrlPolicy.isWebUrl(url));
    }
    @Test public void queueIsBoundedExpiresAndNeverAcceptsInvalidValues() {
        PendingUrlQueue queue = new PendingUrlQueue();
        assertFalse(queue.offer("file:///private", 0));
        for (int i = 0; i < 8; i++) assertTrue(queue.offer("https://example.com/" + i, 1));
        assertFalse(queue.offer("https://example.com/full", 1));
        assertEquals("https://example.com/0", queue.poll(2));
        assertNull(queue.poll(300001));
        assertTrue(queue.offer("https://example.com/new", 300001));
        assertNull(queue.poll(0));
    }
}

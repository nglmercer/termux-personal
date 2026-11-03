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

    @Test public void requestSurvivesAViewRecreationButExpiresAndCannotConsumeAnotherRequest() {
        PendingUrlQueue queue = new PendingUrlQueue();
        String url = "https://example.com/auth?code=private";
        assertTrue(queue.offer(url, 10));
        PendingUrlQueue.Request oldView = queue.peek(11);
        assertSame(oldView, queue.peek(12)); // Showing or recreating a view does not consume it.
        assertTrue(queue.isPending(oldView, 13));
        assertNull(queue.peek(300010));
        assertTrue(queue.offer(url, 300010)); // A later identical URL is a distinct request.
        PendingUrlQueue.Request newView = queue.peek(300010);
        assertNotSame(oldView, newView);
        assertNull(queue.consume(oldView, 300011));
        assertEquals(url, queue.consume(newView, 300011));
        assertNull(queue.peek(300011));
    }
}

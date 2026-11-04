package com.termux.app.compat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real app-process queue behavior; it does not test background activity launch enforcement. */
@RunWith(AndroidJUnit4.class)
public class PendingUrlQueueInstrumentedTest {
    @After public void clearPending() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            while (AndroidUrlLauncher.takePending() != null) { }
        });
    }

    @Test public void backgroundRequestsAreBoundedAndInvalidSchemesAreRejected() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AndroidUrlLauncher.resumed(null);
            while (AndroidUrlLauncher.takePending() != null) { }
            assertEquals(AndroidUrlLauncher.Status.INVALID_URI, AndroidUrlLauncher.openFromReceiver("file:///private"));
            for (int i = 0; i < 8; i++)
                assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/" + i));
            assertEquals(AndroidUrlLauncher.Status.UNAVAILABLE, AndroidUrlLauncher.openFromReceiver("https://example.com/overflow"));
            for (int i = 0; i < 8; i++)
                assertEquals("https://example.com/" + i, AndroidUrlLauncher.takePending());
            assertNull(AndroidUrlLauncher.takePending());
        });
    }

    @Test public void expiredOrConsumedHandleCannotConsumeLaterIdenticalRequest() {
        PendingUrlQueue queue = new PendingUrlQueue();
        String url = "https://example.com/?state=instrumented-test";
        assertTrue(queue.offer(url, 10));
        PendingUrlQueue.Request stale = queue.peek(11);
        assertNull(queue.peek(300010));
        assertNull(stale.url());
        assertTrue(queue.offer(url, 300010));
        PendingUrlQueue.Request current = queue.peek(300011);
        assertNull(queue.consume(stale, 300011));
        assertEquals(url, queue.consume(current, 300011));
        assertNull(current.url());
    }
}

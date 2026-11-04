package com.termux.app.compat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class AndroidUrlLauncherTest {
    public static class TestActivity extends Activity implements AndroidUrlLauncher.PendingListener {
        Intent dispatched;
        RuntimeException failure;
        int pendingNotifications;
        @Override public void onPendingWebUrl() { pendingNotifications++; }
        @Override public void startActivity(Intent intent) {
            if (failure != null) throw failure;
            dispatched = intent;
        }
    }
    private TestActivity activity;
    private TestActivity activity() {
        if (activity == null) activity = Robolectric.buildActivity(TestActivity.class).setup().get();
        return activity;
    }
    @After public void cleanUp() {
        if (activity != null) AndroidUrlLauncher.paused(activity);
        while (AndroidUrlLauncher.takePending() != null) { }
    }
    @Test public void dispatchesWebIntentAndReportsExceptionsWithoutUri() {
        TestActivity a = activity();
        assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED, AndroidUrlLauncher.open(a, "https://example.com/auth?code=secret"));
        assertEquals(Intent.ACTION_VIEW, a.dispatched.getAction());
        assertEquals("https://example.com/auth?code=secret", a.dispatched.getDataString());
        assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED, AndroidUrlLauncher.open(a, "https://例え.テスト/auth?state=secret"));
        assertEquals("https://例え.テスト/auth?state=secret", a.dispatched.getDataString());
        assertEquals(AndroidUrlLauncher.Status.INVALID_URI, AndroidUrlLauncher.open(a, "file:///private"));
        a.failure = new ActivityNotFoundException();
        assertEquals(AndroidUrlLauncher.Status.NO_HANDLER, AndroidUrlLauncher.open(a, "https://example.com/"));
        a.failure = new SecurityException();
        assertEquals(AndroidUrlLauncher.Status.SECURITY_RESTRICTED, AndroidUrlLauncher.open(a, "https://example.com/"));
        a.failure = new IllegalStateException();
        assertEquals(AndroidUrlLauncher.Status.FAILED, AndroidUrlLauncher.open(a, "https://example.com/"));
    }
    @Test public void receiverQueuesWhenBackgroundAndDispatchesWhenResumed() {
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/a"));
        assertEquals("https://example.com/a", AndroidUrlLauncher.takePending());
        TestActivity a = activity();
        AndroidUrlLauncher.resumed(a);
        assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED, AndroidUrlLauncher.openFromReceiver("https://example.com/b"));
        assertNull(AndroidUrlLauncher.takePending());
        AndroidUrlLauncher.paused(a);
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/c"));
    }
    @Test public void pendingRequestRemainsAvailableUntilTheUserActs() {
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/auth?state=private"));
        PendingUrlQueue.Request request = AndroidUrlLauncher.peekPending();
        assertNotNull(request);
        assertSame(request, AndroidUrlLauncher.peekPending());
        assertTrue(AndroidUrlLauncher.isPending(request));
        assertEquals("https://example.com/auth?state=private", AndroidUrlLauncher.consumePending(request));
        assertFalse(AndroidUrlLauncher.isPending(request));
        assertNull(AndroidUrlLauncher.consumePending(request));
    }
    @Test public void pendingUrlReferenceExpiresWithoutAnotherQueueOperation() {
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/?code=secret"));
        PendingUrlQueue.Request request = AndroidUrlLauncher.peekPending();
        assertNotNull(request);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(5, java.util.concurrent.TimeUnit.MINUTES);
        assertNull(request.url());
        assertNull(AndroidUrlLauncher.peekPending());
    }
    @Test public void failedForegroundDispatchOffersDeferredUserAction() {
        TestActivity a = activity();
        a.failure = new ActivityNotFoundException();
        AndroidUrlLauncher.resumed(a);
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER, AndroidUrlLauncher.openFromReceiver("https://example.com/auth?code=secret"));
        assertEquals(1, a.pendingNotifications);
        assertEquals("https://example.com/auth?code=secret", AndroidUrlLauncher.takePending());
    }
    @Test public void webChooserAndContentTypeArePreservedInDispatchAndQueue() {
        TestActivity a = activity();
        AndroidUrlLauncher.resumed(a);
        assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED,
            AndroidUrlLauncher.openFromReceiver("https://example.com/a", "text/html", true));
        assertEquals(Intent.ACTION_CHOOSER, a.dispatched.getAction());
        Intent inner = a.dispatched.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(inner);
        assertEquals(Intent.ACTION_VIEW, inner.getAction());
        assertEquals("https://example.com/a", inner.getDataString());
        assertEquals("text/html", inner.getType());
        AndroidUrlLauncher.paused(a);
        assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER,
            AndroidUrlLauncher.openFromReceiver("https://example.com/b", "application/pdf", true));
        PendingUrlQueue.Request request = AndroidUrlLauncher.peekPending();
        assertEquals("application/pdf", request.contentType());
        assertTrue(request.chooser());
        assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED,
            AndroidUrlLauncher.open(a, request.url(), request.contentType(), request.chooser()));
        assertEquals("application/pdf", ((Intent) a.dispatched.getParcelableExtra(Intent.EXTRA_INTENT)).getType());
        assertEquals("https://example.com/b", AndroidUrlLauncher.consumePending(request));
        assertNull(request.contentType());
        assertEquals(AndroidUrlLauncher.Status.INVALID_URI,
            AndroidUrlLauncher.openFromReceiver("https://example.com/", "text/html\nprivate", false));
    }

}

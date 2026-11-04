package com.termux.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

import static org.junit.Assert.*;

/** Host intent-construction checks; these do not exercise another app's URI access. */
@RunWith(RobolectricTestRunner.class)
public class TermuxOpenReceiverTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final class CapturingContext extends ContextWrapper {
        Intent started;
        CapturingContext(Context base) { super(base); }
        @Override public void startActivity(Intent intent) { started = intent; }
    }
    private CapturingContext context() { return new CapturingContext(RuntimeEnvironment.getApplication()); }

    @Test public void fileViewUsesContentUriAndReadGrant() throws Exception {
        File file = temp.newFile("report.txt");
        CapturingContext context = context();
        new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_VIEW, Uri.fromFile(file)));
        assertNotNull(context.started);
        assertEquals(Intent.ACTION_VIEW, context.started.getAction());
        assertEquals("content", context.started.getData().getScheme());
        assertEquals(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, context.started.getData().getAuthority());
        assertEquals(file.getAbsolutePath(), context.started.getData().getPath());
        assertNotEquals(0, context.started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, context.started.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test public void fileSendChooserRetainsReadOnlyGrantOnInnerIntent() throws Exception {
        File file = temp.newFile("shared.txt");
        CapturingContext context = context();
        Intent input = new Intent(Intent.ACTION_SEND, Uri.fromFile(file));
        input.putExtra("chooser", true);
        new TermuxOpenReceiver().onReceive(context, input);
        assertNotNull(context.started);
        assertEquals(Intent.ACTION_CHOOSER, context.started.getAction());
        Intent inner = context.started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(inner);
        assertEquals(Intent.ACTION_SEND, inner.getAction());
        Uri shared = inner.getParcelableExtra(Intent.EXTRA_STREAM);
        assertNotNull(shared);
        assertEquals("content", shared.getScheme());
        assertEquals(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, shared.getAuthority());
        assertNotEquals(0, inner.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, inner.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test public void missingFileIsNotDispatched() {
        CapturingContext context = context();
        File missing = new File(temp.getRoot(), "missing.txt");
        new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_VIEW, Uri.fromFile(missing)));
        assertNull(context.started);
    }

    @Test public void unsupportedActionFallsBackToReadOnlyFileView() throws Exception {
        File file = temp.newFile("fallback.txt");
        CapturingContext context = context();
        new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_DELETE, Uri.fromFile(file)));
        assertNotNull(context.started);
        assertEquals(Intent.ACTION_VIEW, context.started.getAction());
        assertEquals("content", context.started.getData().getScheme());
        assertNotEquals(0, context.started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, context.started.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }
    @Test public void unsupportedWebActionUsesValidatedDeferredWebPolicy() {
        com.termux.app.compat.AndroidUrlLauncher.resumed(null);
        while (com.termux.app.compat.AndroidUrlLauncher.takePending() != null) { }
        CapturingContext context = context();
        String url = "https://example.com/auth?state=private";
        new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_DELETE, Uri.parse(url)));
        assertNull(context.started);
        assertEquals(url, com.termux.app.compat.AndroidUrlLauncher.takePending());
        String notice = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
        assertEquals(context.getString(com.termux.R.string.compat_url_queued_notice), notice);
        assertFalse(notice.contains("private"));
    }
}

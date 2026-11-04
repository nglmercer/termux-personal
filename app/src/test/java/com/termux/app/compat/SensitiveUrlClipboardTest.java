package com.termux.app.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SensitiveUrlClipboardTest {
    @Test @Config(sdk = 33)
    public void copiedUrlIsMarkedSensitiveAndRetainsText() {
        Context context = RuntimeEnvironment.getApplication();
        String url = "https://example.com/auth?state=private";
        assertTrue(SensitiveUrlClipboard.copy(context, url));
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        assertNotNull(clip);
        assertEquals(url, clip.getItemAt(0).getText().toString());
        assertTrue(clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));
        assertFalse(SensitiveUrlClipboard.copy(context, null));
        assertEquals(url, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        // Copying selected text is independent of the stricter web dispatch policy.
        assertTrue(SensitiveUrlClipboard.copy(context, "https://user:pass@example.com/"));
        assertEquals("https://user:pass@example.com/", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test @Config(sdk = 28)
    public void olderAndroidStillCopiesTheSelectedUrl() {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(SensitiveUrlClipboard.copy(context, "https://example.com/"));
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("https://example.com/", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test @Config(sdk = 28)
    public void unavailableClipboardIsReportedWithoutThrowing() {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Object getSystemService(String name) {
                if (Context.CLIPBOARD_SERVICE.equals(name)) throw new SecurityException("sensitive data");
                return super.getSystemService(name);
            }
        };
        assertFalse(SensitiveUrlClipboard.copy(context, "https://example.com/auth?state=private"));
    }
}

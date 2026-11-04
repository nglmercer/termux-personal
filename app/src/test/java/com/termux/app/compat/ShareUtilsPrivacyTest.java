package com.termux.app.compat;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import com.termux.shared.interact.ShareUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ShareUtilsPrivacyTest {
    private static final class FailingContext extends ContextWrapper {
        boolean chooserAttempted;
        FailingContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public void startActivity(Intent intent) {
            if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                chooserAttempted = true;
                throw new SecurityException("private-token-in-exception");
            }
            throw new ActivityNotFoundException("private-token-in-exception");
        }
    }
    @Test public void chooserFailureDoesNotLogUrlOrExceptionMessage() {
        ShadowLog.clear();
        FailingContext context = new FailingContext();
        ShareUtils.openUrl(context, "custom://example/?code=private-token-in-url");
        assertTrue(context.chooserAttempted);
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            assertFalse(String.valueOf(log.msg).contains("private-token"));
            assertTrue(log.throwable == null || !String.valueOf(log.throwable).contains("private-token"));
        }
    }
    @Test public void directFailureDoesNotLogUrlOrExceptionMessage() {
        ShadowLog.clear();
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public void startActivity(Intent intent) { throw new SecurityException("private-token-in-exception"); }
        };
        ShareUtils.openUrl(context, "custom://example/?code=private-token-in-url");
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            assertFalse(String.valueOf(log.msg).contains("private-token"));
            assertTrue(log.throwable == null || !String.valueOf(log.throwable).contains("private-token"));
        }
    }
}

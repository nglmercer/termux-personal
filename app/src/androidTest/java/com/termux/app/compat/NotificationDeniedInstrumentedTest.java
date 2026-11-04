package com.termux.app.compat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.app.NotificationManagerCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.app.TermuxOpenReceiver;
import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Run with app notifications already disabled; this test never changes device settings. */
@RunWith(AndroidJUnit4.class)
public class NotificationDeniedInstrumentedTest {
    @After public void cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            while (AndroidUrlLauncher.takePending() != null) { }
        });
    }

    @Test public void disabledNotificationsLeaveTheRequestInTheQueue() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeFalse("Disable app notifications manually before this test",
            NotificationManagerCompat.from(context).areNotificationsEnabled());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AndroidUrlLauncher.resumed(null);
            while (AndroidUrlLauncher.takePending() != null) { }
            String url = "https://example.com/test?state=instrumentation";
            new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            assertNotNull(AndroidUrlLauncher.peekPending());
            assertEquals(url, AndroidUrlLauncher.takePending());
        });
    }
}

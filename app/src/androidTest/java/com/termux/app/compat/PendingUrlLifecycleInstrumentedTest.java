package com.termux.app.compat;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.app.TermuxActivity;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Exercises the real activity lifecycle; it does not launch a browser. */
@RunWith(AndroidJUnit4.class)
public class PendingUrlLifecycleInstrumentedTest {
    @After public void clearPending() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            while (AndroidUrlLauncher.takePending() != null) { }
        });
    }

    @Test public void deferredRequestSurvivesActivityRecreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            while (AndroidUrlLauncher.takePending() != null) { }
            assertEquals(AndroidUrlLauncher.Status.QUEUED_FOR_USER,
                AndroidUrlLauncher.openFromReceiver("https://example.com/auth?state=lifecycle-test"));
        });
        try (ActivityScenario<TermuxActivity> scenario = ActivityScenario.launch(TermuxActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(AndroidUrlLauncher.isResumed(activity));
                assertTrue("initial pending dialog is visible", pendingDialogShowing(activity));
                assertNotNull(AndroidUrlLauncher.peekPending());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(AndroidUrlLauncher.isResumed(activity));
                assertTrue("pending dialog is shown again", pendingDialogShowing(activity));
                PendingUrlQueue.Request request = AndroidUrlLauncher.peekPending();
                assertNotNull(request);
                assertEquals("https://example.com/auth?state=lifecycle-test", AndroidUrlLauncher.consumePending(request));
            });
        }
    }

    private boolean pendingDialogShowing(TermuxActivity activity) {
        try {
            java.lang.reflect.Field field = TermuxActivity.class.getDeclaredField("mPendingWebDialogShowing");
            field.setAccessible(true);
            return field.getBoolean(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}

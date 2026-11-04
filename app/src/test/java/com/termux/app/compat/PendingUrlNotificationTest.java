package com.termux.app.compat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxOpenReceiver;
import com.termux.shared.termux.TermuxConstants;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PendingUrlNotificationTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    @After public void cleanUp() {
        PendingUrlNotification.cancel(context);
        while (AndroidUrlLauncher.takePending() != null) { }
    }

    @Test public void noticeContainsNoRequestAndExpires() {
        manager.createNotificationChannel(new NotificationChannel(TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            "existing", NotificationManager.IMPORTANCE_LOW));
        PendingUrlNotification.show(context);
        Notification notice = Shadows.shadowOf(manager).getNotification(PendingUrlNotification.TAG, PendingUrlNotification.ID);
        assertNotNull(notice);
        assertNotNull(notice.contentIntent);
        Intent intent = Shadows.shadowOf(notice.contentIntent).getSavedIntent();
        assertEquals(TermuxActivity.class.getName(), intent.getComponent().getClassName());
        assertNull(intent.getData());
        assertNull(intent.getExtras());
        assertTrue((notice.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.MINUTES);
        assertNull(Shadows.shadowOf(manager).getNotification(PendingUrlNotification.TAG, PendingUrlNotification.ID));
    }

    @Test public void missingChannelOrDeniedNotificationsDoNotPost() {
        PendingUrlNotification.show(context);
        assertNull(Shadows.shadowOf(manager).getNotification(PendingUrlNotification.TAG, PendingUrlNotification.ID));
        manager.createNotificationChannel(new NotificationChannel(TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            "existing", NotificationManager.IMPORTANCE_LOW));
        Shadows.shadowOf(manager).setNotificationsEnabled(false);
        PendingUrlNotification.show(context);
        assertNull(Shadows.shadowOf(manager).getNotification(PendingUrlNotification.TAG, PendingUrlNotification.ID));
    }

    @Test public void backgroundReceiverPostsGenericNoticeAndKeepsRequest() {
        manager.createNotificationChannel(new NotificationChannel(TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            "existing", NotificationManager.IMPORTANCE_LOW));
        AndroidUrlLauncher.resumed(null);
        String url = "https://example.com/auth?state=private-test-token";
        new TermuxOpenReceiver().onReceive(context, new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        Notification notice = Shadows.shadowOf(manager).getNotification(PendingUrlNotification.TAG, PendingUrlNotification.ID);
        assertNotNull(notice);
        assertFalse(notice.extras.toString().contains("private-test-token"));
        assertEquals(url, AndroidUrlLauncher.peekPending().url());
        assertNull(Shadows.shadowOf(notice.contentIntent).getSavedIntent().getData());
    }
}

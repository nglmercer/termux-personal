package com.termux.app.compat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationManagerCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

/** Generic notice for the in-memory queue; it never contains or launches a URL. */
public final class PendingUrlNotification {
    static final String TAG = "termux.compat.pending-url";
    static final int ID = 1;
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable expiry;
    private PendingUrlNotification() { }

    public static void show(Context context) {
        Context app = context.getApplicationContext();
        if (app == null) return;
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(app);
            if (!manager.areNotificationsEnabled()) return;
            NotificationManager nativeManager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nativeManager == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = nativeManager.getNotificationChannel(TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID);
                if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return;
            }
            Intent intent = new Intent(app, TermuxActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent action = PendingIntent.getActivity(app, 1127, intent, flags);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(app, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID) : new Notification.Builder(app);
            builder.setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle(app.getString(R.string.compat_pending_title))
                .setContentText(app.getString(R.string.compat_pending_notification))
                .setContentIntent(action).setAutoCancel(true).setLocalOnly(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(MAX_AGE_MS);
            manager.notify(TAG, ID, builder.build());
            if (expiry != null) MAIN.removeCallbacks(expiry);
            expiry = () -> cancel(app);
            MAIN.postDelayed(expiry, MAX_AGE_MS);
        } catch (RuntimeException ignored) {
            // Notifications may be denied or unsupported. The queue and toast remain available.
        }
    }

    public static void cancel(Context context) {
        if (expiry != null) { MAIN.removeCallbacks(expiry); expiry = null; }
        try { NotificationManagerCompat.from(context).cancel(TAG, ID); }
        catch (RuntimeException ignored) { }
    }
}

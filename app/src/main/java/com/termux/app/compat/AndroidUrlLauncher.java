package com.termux.app.compat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

/** Web dispatch and foreground-only deferred requests. Call on the main thread. */
public final class AndroidUrlLauncher {
    public interface PendingListener { void onPendingWebUrl(); }
    public enum Status { DISPATCHED_UNCONFIRMED, QUEUED_FOR_USER, NO_HANDLER, INVALID_URI, SECURITY_RESTRICTED, UNAVAILABLE, FAILED }
    private static final PendingUrlQueue PENDING = new PendingUrlQueue();
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private AndroidUrlLauncher() {}

    public static void resumed(Activity activity) { foreground = new WeakReference<>(activity); }
    public static void paused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }
    public static boolean isResumed(Activity activity) { return foreground.get() == activity; }
    public static String takePending() { return PENDING.poll(SystemClock.elapsedRealtime()); }

    public static Status openFromReceiver(String url) {
        if (!UrlPolicy.isWebUrl(url)) return Status.INVALID_URI;
        Activity activity = foreground.get();
        if (activity != null && !activity.isFinishing()) {
            Status status = open(activity, url);
            if (status == Status.DISPATCHED_UNCONFIRMED) return status;
        }
        if (!PENDING.offer(url, SystemClock.elapsedRealtime())) return Status.UNAVAILABLE;
        if (activity instanceof PendingListener && !activity.isFinishing())
            ((PendingListener) activity).onPendingWebUrl();
        return Status.QUEUED_FOR_USER;
    }

    public static Status open(Activity activity, String url) {
        if (!UrlPolicy.isWebUrl(url)) return Status.INVALID_URI;
        if (activity == null || activity.isFinishing()) return Status.UNAVAILABLE;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return Status.DISPATCHED_UNCONFIRMED;
        } catch (ActivityNotFoundException e) {
            return Status.NO_HANDLER;
        } catch (SecurityException e) {
            return Status.SECURITY_RESTRICTED;
        } catch (RuntimeException e) {
            return Status.FAILED;
        }
    }
}

package com.termux.app.compat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

/** Web dispatch and foreground-only deferred requests. Call on the main thread. */
public final class AndroidUrlLauncher {
    public interface PendingListener { void onPendingWebUrl(); }
    public enum Status { DISPATCHED_UNCONFIRMED, QUEUED_FOR_USER, NO_HANDLER, INVALID_URI, SECURITY_RESTRICTED, UNAVAILABLE, FAILED }
    private static final PendingUrlQueue PENDING = new PendingUrlQueue();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable EXPIRE_PENDING = AndroidUrlLauncher::scheduleExpiry;
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private AndroidUrlLauncher() {}

    public static void resumed(Activity activity) { foreground = new WeakReference<>(activity); }
    public static void paused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }
    public static Activity resumedActivity() { return foreground.get(); }
    public static boolean isResumed(Activity activity) { return foreground.get() == activity; }
    public static String takePending() { String value = PENDING.poll(SystemClock.elapsedRealtime()); scheduleExpiry(); return value; }
    public static PendingUrlQueue.Request peekPending() { PendingUrlQueue.Request value = PENDING.peek(SystemClock.elapsedRealtime()); scheduleExpiry(); return value; }
    public static boolean isPending(PendingUrlQueue.Request request) { boolean value = PENDING.isPending(request, SystemClock.elapsedRealtime()); scheduleExpiry(); return value; }
    public static String consumePending(PendingUrlQueue.Request request) { String value = PENDING.consume(request, SystemClock.elapsedRealtime()); scheduleExpiry(); return value; }

    private static void scheduleExpiry() {
        MAIN.removeCallbacks(EXPIRE_PENDING);
        long delay = PENDING.delayUntilExpiry(SystemClock.elapsedRealtime());
        if (delay >= 0) MAIN.postDelayed(EXPIRE_PENDING, delay);
    }

    public static Status openFromReceiver(String url) {
        return openFromReceiver(url, null, false);
    }
    public static Status openFromReceiver(String url, String contentType, boolean chooser) {
        return openOrQueue(url, contentType, chooser);
    }
    public static Status openOrQueue(String url) {
        return openOrQueue(url, null, false);
    }
    private static Status openOrQueue(String url, String contentType, boolean chooser) {
        if (!UrlPolicy.isWebUrl(url) || !UrlPolicy.isContentTypeSafe(contentType)) return Status.INVALID_URI;
        Activity activity = foreground.get();
        if (activity != null && !activity.isFinishing()) {
            Status status = open(activity, url, contentType, chooser);
            if (status == Status.DISPATCHED_UNCONFIRMED) return status;
        }
        if (!PENDING.offer(url, contentType, chooser, SystemClock.elapsedRealtime())) return Status.UNAVAILABLE;
        scheduleExpiry();
        if (activity instanceof PendingListener && !activity.isFinishing())
            ((PendingListener) activity).onPendingWebUrl();
        return Status.QUEUED_FOR_USER;
    }

    public static Status open(Activity activity, String url) {
        return open(activity, url, null, false);
    }
    public static Status open(Activity activity, String url, String contentType, boolean chooser) {
        if (!UrlPolicy.isWebUrl(url) || !UrlPolicy.isContentTypeSafe(contentType)) return Status.INVALID_URI;
        if (activity == null || activity.isFinishing()) return Status.UNAVAILABLE;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (contentType != null) intent.setDataAndType(Uri.parse(url), contentType);
            activity.startActivity(chooser ? Intent.createChooser(intent, null) : intent);
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

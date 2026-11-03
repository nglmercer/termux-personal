package com.termux.app.compat.profile;

import android.content.Context;
import android.os.Build;
import android.os.Process;

/** Passive platform metadata only: no network, package query, process launch or device-wide setting. */
public final class CompatibilityProbe {
    private CompatibilityProbe() { }
    public static CompatibilityProfile snapshot(Context context) {
        int uid = Process.myUid();
        String kernel;
        try { kernel = System.getProperty("os.version"); }
        catch (SecurityException e) { kernel = null; }
        return new CompatibilityProfile(Build.VERSION.SDK_INT, context.getApplicationInfo().targetSdkVersion,
            uid < 0 ? null : uid / 100000, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
            kernel, Build.SUPPORTED_ABIS);
    }
}

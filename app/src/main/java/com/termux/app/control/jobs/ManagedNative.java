package com.termux.app.control.jobs;

/** Internal library presence check; it does not imply managed execution is available. */
public final class ManagedNative {
    private static final int EXPECTED_ABI = 1;
    private static final boolean AVAILABLE;
    static {
        boolean available = false;
        try {
            System.loadLibrary("termux-managed-job");
            available = nativeAbi() == EXPECTED_ABI;
        } catch (LinkageError | SecurityException ignored) { }
        AVAILABLE = available;
    }
    private ManagedNative() { }
    public static boolean isLibraryAvailable() { return AVAILABLE; }
    /** Returns zero if atomic pidfd creation, owned SIGKILL and pidfd wait succeeded, otherwise negative errno.
     * This is a partial probe, not a guarantee of exec, close_range, or job availability. */
    public static int probePidfd() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Kernel probe must run off the main thread");
        if (!AVAILABLE) return -1;
        return nativeProbePidfd();
    }
    private static native int nativeAbi();
    private static native int nativeProbePidfd();
}

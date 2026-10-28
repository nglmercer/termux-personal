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
    private static native int nativeAbi();
}

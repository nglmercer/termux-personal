package com.termux.app.control.jobs;

/** Internal native primitives for managed jobs. Library presence alone does not enable a job API. */
public final class ManagedNative {
    private static final int EXPECTED_ABI = 3;
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
        requireWorker();
        if (!AVAILABLE) return -1;
        return nativeProbePidfd();
    }

    /** Worker-only; out receives {pid, stdin, stdout, stderr}. Returns 0 or a negative errno. */
    static int launch(String path, String cwd, String[] argv, String[] envp, int[] out) {
        return launch(path, cwd, argv, envp, out, NativeLimits.NONE);
    }

    /** Same, with limits applied to the child only. A failed required limit is a negative errno, as in {@link #launch(String, String, String[], String[], int[])}. */
    static int launch(String path, String cwd, String[] argv, String[] envp, int[] out, NativeLimits limits) {
        requireWorker();
        if (!AVAILABLE) return -38; // ENOSYS
        return nativeLaunch(path, cwd, argv, envp, out, limits.addressSpaceBytes, limits.cpuSeconds, limits.niceLevel, limits.requiredMask);
    }

    /** Limits for one child; zero (or a negative nice level) means none. Bits match TERMUX_LIMIT_* in managed-child.h. */
    static final class NativeLimits {
        static final int REQUIRE_ADDRESS_SPACE = 1, REQUIRE_CPU_TIME = 2, REQUIRE_NICE = 4;
        static final NativeLimits NONE = new NativeLimits(0, 0, -1, 0);
        final long addressSpaceBytes, cpuSeconds;
        final int niceLevel, requiredMask;
        NativeLimits(long addressSpaceBytes, long cpuSeconds, int niceLevel, int requiredMask) {
            if (addressSpaceBytes < 0 || cpuSeconds < 0 || niceLevel > 19 || (requiredMask & ~7) != 0)
                throw new IllegalArgumentException("Invalid native limits");
            this.addressSpaceBytes = addressSpaceBytes; this.cpuSeconds = cpuSeconds;
            this.niceLevel = niceLevel < 0 ? -1 : niceLevel; this.requiredMask = requiredMask;
        }
        boolean isNone() { return addressSpaceBytes == 0 && cpuSeconds == 0 && niceLevel < 0; }
    }
    static int waitExit(int pid) { requireWorker(); return nativeWaitExit(pid); }
    static int reap(int pid, int[] out) { return nativeReap(pid, out); }
    static int signal(int pid, int signal) { return nativeSignal(pid, signal); }

    private static void requireWorker() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Native job primitive must run off the main thread");
    }

    private static native int nativeAbi();
    private static native int nativeProbePidfd();
    private static native int nativeLaunch(String path, String cwd, String[] argv, String[] envp, int[] out,
                                           long addressSpaceBytes, long cpuSeconds, int niceLevel, int requiredMask);
    private static native int nativeWaitExit(int pid);
    private static native int nativeReap(int pid, int[] out);
    private static native int nativeSignal(int pid, int signal);
}

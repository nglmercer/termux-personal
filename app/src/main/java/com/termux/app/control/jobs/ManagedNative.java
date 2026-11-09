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
    public static final class NativeLimits {
        public static final int REQUIRE_ADDRESS_SPACE = 1, REQUIRE_CPU_TIME = 2, REQUIRE_NICE = 4;
        public static final NativeLimits NONE = new NativeLimits(0, 0, -1, 0);
        final long addressSpaceBytes, cpuSeconds;
        final int niceLevel, requiredMask;
        public NativeLimits(long addressSpaceBytes, long cpuSeconds, int niceLevel, int requiredMask) {
            if (addressSpaceBytes < 0 || cpuSeconds < 0 || niceLevel > 19 || (requiredMask & ~7) != 0)
                throw new IllegalArgumentException("Invalid native limits");
            this.addressSpaceBytes = addressSpaceBytes; this.cpuSeconds = cpuSeconds;
            this.niceLevel = niceLevel < 0 ? -1 : niceLevel; this.requiredMask = requiredMask;
        }
        boolean isNone() { return addressSpaceBytes == 0 && cpuSeconds == 0 && niceLevel < 0; }
    }
    private static volatile long sMinAddressSpace = -1;
    private static final long MIB = 1L << 20;
    private static final long[] PROBE_CANDIDATES = {256 * MIB, 512 * MIB, 1024 * MIB, 1536 * MIB, 2048 * MIB, 3072 * MIB, 4096 * MIB, 6144 * MIB, 8192 * MIB, 16384 * MIB};

    /**
     * Smallest address-space limit under which a trivial child still starts, measured once per process by launching
     * {@code /system/bin/sh -c :} under ascending limits. Zero when native launching is unavailable or no candidate worked, so
     * that "unknown" never blocks anything. Worker-only: it launches processes.
     */
    public static long minAddressSpaceBytes() {
        long cached = sMinAddressSpace;
        if (cached >= 0) return cached;
        requireWorker();
        long found = 0;
        if (AVAILABLE) {
            for (long candidate : PROBE_CANDIDATES) {
                if (startsUnder(candidate)) { found = candidate; break; }
            }
        }
        sMinAddressSpace = found;
        return found;
    }

    private static boolean startsUnder(long addressSpace) {
        int[] out = new int[4];
        int result = nativeLaunch("/system/bin/sh", "/", new String[] {"/system/bin/sh", "-c", ":"}, new String[] {"PATH=/system/bin"}, out,
            addressSpace, 0, -1, NativeLimits.REQUIRE_ADDRESS_SPACE);
        if (result != 0) return false;
        int[] reaped = new int[4];
        boolean ok = nativeWaitExit(out[0]) == 0 && nativeReap(out[0], reaped) == 0 && reaped[0] != 0 && reaped[1] == 0;
        // The parent ends of the pipes are not needed; closing them here avoids leaking descriptors on every probe.
        for (int i = 1; i < 4; i++) { try { android.os.ParcelFileDescriptor.adoptFd(out[i]).close(); } catch (java.io.IOException ignored) { } }
        return ok;
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

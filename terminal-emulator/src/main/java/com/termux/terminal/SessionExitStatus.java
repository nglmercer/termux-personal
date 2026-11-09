package com.termux.terminal;

/**
 * What the root process of a terminal session (normally the shell) reported to {@code waitpid}, decoded from the value
 * {@link TerminalSession#getExitStatus()} returns: an exit code {@code >= 0}, a negated signal number, or
 * {@link TerminalSession#EXIT_STATUS_UNKNOWN}.
 *
 * <p>It states only what was observed. An exit code is a number the process chose, so {@code 137} stays exit code 137
 * and is never turned into {@code SIGKILL}, and {@code 139} is never turned into {@code SIGSEGV}. A signal says how the
 * process ended, not who sent it or why: this class never infers out-of-memory, the phantom-process killer or any other
 * cause. Commands that the shell ran and that ended while the shell kept running are not observed here at all.
 */
public final class SessionExitStatus {
    public enum Kind {
        /** No status: {@code waitpid} failed or the session has not ended. */
        UNAVAILABLE,
        /** The process called exit with {@link #value}; a nonzero code does not by itself say what went wrong. */
        EXITED,
        /** The process was ended by signal number {@link #value}. */
        SIGNALED
    }

    public final Kind kind;
    /** The exit code for {@link Kind#EXITED}, the signal number for {@link Kind#SIGNALED}, otherwise 0. */
    public final int value;

    private SessionExitStatus(Kind kind, int value) { this.kind = kind; this.value = value; }

    private static final SessionExitStatus UNAVAILABLE = new SessionExitStatus(Kind.UNAVAILABLE, 0);

    /**
     * Equal to {@link TerminalSession#EXIT_STATUS_UNKNOWN} and to the JNI {@code waitFor} failure value; repeated here so
     * this class has no Android dependency and the host PTY smoke test can use it.
     */
    static final int UNKNOWN_RAW = Integer.MIN_VALUE;

    public static SessionExitStatus decode(int raw) {
        // Test the sentinel before negating: -Integer.MIN_VALUE overflows back to itself.
        if (raw == UNKNOWN_RAW) return UNAVAILABLE;
        if (raw >= 0) return new SessionExitStatus(Kind.EXITED, raw);
        return new SessionExitStatus(Kind.SIGNALED, -raw);
    }

    /** Linux signal names for 1..31, as used by Android on ARM and x86; null for realtime or unknown numbers. */
    private static final String[] SIGNALS = {null, "SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGABRT", "SIGBUS", "SIGFPE",
        "SIGKILL", "SIGUSR1", "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP",
        "SIGTSTP", "SIGTTIN", "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH", "SIGIO", "SIGPWR", "SIGSYS"};

    /** The signal's name, or null when this is not a signal or the number has no well-known name. */
    public String signalName() {
        if (kind != Kind.SIGNALED || value <= 0 || value >= SIGNALS.length) return null;
        return SIGNALS[value];
    }

    /** One short, cause-free description, for example {@code "signal 9 (SIGKILL)"} or {@code "exit code 137"}. */
    public String describe() {
        switch (kind) {
            case EXITED: return "exit code " + value;
            case SIGNALED: {
                String name = signalName();
                return "signal " + value + (name == null ? "" : " (" + name + ")");
            }
            default: return "status unavailable";
        }
    }

    @Override public String toString() { return describe(); }
}

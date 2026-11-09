package com.termux.terminal;

import junit.framework.TestCase;

public class SessionExitStatusTest extends TestCase {

    private static void check(int raw, SessionExitStatus.Kind kind, int value, String name, String description) {
        SessionExitStatus status = SessionExitStatus.decode(raw);
        assertEquals("kind of " + raw, kind, status.kind);
        assertEquals("value of " + raw, value, status.value);
        assertEquals("name of " + raw, name, status.signalName());
        assertEquals(description, status.describe());
    }

    public void testExitCodesStayExitCodes() {
        check(0, SessionExitStatus.Kind.EXITED, 0, null, "exit code 0");
        check(7, SessionExitStatus.Kind.EXITED, 7, null, "exit code 7");
        check(130, SessionExitStatus.Kind.EXITED, 130, null, "exit code 130");
        // A shell reports 128+n for a child it saw die by a signal; for the session's own root process that is only a code.
        check(137, SessionExitStatus.Kind.EXITED, 137, null, "exit code 137");
        check(139, SessionExitStatus.Kind.EXITED, 139, null, "exit code 139");
    }

    public void testNegativeValuesAreObservedSignals() {
        check(-9, SessionExitStatus.Kind.SIGNALED, 9, "SIGKILL", "signal 9 (SIGKILL)");
        check(-11, SessionExitStatus.Kind.SIGNALED, 11, "SIGSEGV", "signal 11 (SIGSEGV)");
        check(-15, SessionExitStatus.Kind.SIGNALED, 15, "SIGTERM", "signal 15 (SIGTERM)");
    }

    public void testUnknownSignalsKeepTheirNumber() {
        check(-34, SessionExitStatus.Kind.SIGNALED, 34, null, "signal 34");
        check(-99, SessionExitStatus.Kind.SIGNALED, 99, null, "signal 99");
    }

    public void testTheSentinelIsUnavailableAndNeverNegated() {
        assertEquals(TerminalSession.EXIT_STATUS_UNKNOWN, SessionExitStatus.UNKNOWN_RAW);
        check(TerminalSession.EXIT_STATUS_UNKNOWN, SessionExitStatus.Kind.UNAVAILABLE, 0, null, "status unavailable");
        check(Integer.MIN_VALUE + 1, SessionExitStatus.Kind.SIGNALED, Integer.MAX_VALUE, null, "signal " + Integer.MAX_VALUE);
    }
}

package com.termux.app.terminal;

import static org.junit.Assert.*;

import com.termux.terminal.SessionExitStatus;
import com.termux.terminal.TerminalSession;
import java.util.List;
import org.junit.Test;

public class SessionExitRegistryTest {
    @Test public void keepsTheNewestResultsUpToItsCapacityNewestFirst() {
        SessionExitRegistry registry = new SessionExitRegistry();
        for (int i = 0; i < SessionExitRegistry.CAPACITY + 5; i++) assertTrue(registry.record("s" + i, i, 1000L + i));
        List<SessionExitRegistry.Entry> recent = registry.recent();
        assertEquals(SessionExitRegistry.CAPACITY, recent.size());
        assertEquals(SessionExitRegistry.CAPACITY + 4, recent.get(0).status.value);
        assertEquals(5, recent.get(recent.size() - 1).status.value);
        assertTrue(recent.get(0).sequence > recent.get(1).sequence);
    }

    @Test public void aSessionReportedByBothClientsIsRecordedOnce() {
        SessionExitRegistry registry = new SessionExitRegistry();
        assertTrue(registry.record("handle", -9, 1));
        assertFalse(registry.record("handle", -9, 2));
        assertEquals(1, registry.recent().size());
        assertEquals(SessionExitStatus.Kind.SIGNALED, registry.recent().get(0).status.kind);
    }

    @Test public void theCopiedReportHoldsOnlyTimesStatusesAndTheApiLevel() {
        SessionExitRegistry registry = new SessionExitRegistry();
        registry.record("secret-handle", 137, 0);
        registry.record("other-handle", -11, 60_000);
        registry.record("third-handle", TerminalSession.EXIT_STATUS_UNKNOWN, 120_000);
        String report = SessionExitRegistry.report(registry.recent(), 34);
        assertTrue(report, report.contains("1970-01-01T00:00:00Z  exit code 137\n"));
        assertTrue(report, report.contains("1970-01-01T00:01:00Z  signal 11 (SIGSEGV)\n"));
        assertTrue(report, report.contains("1970-01-01T00:02:00Z  status unavailable\n"));
        assertTrue(report, report.contains("android api: 34\n"));
        assertTrue(report, report.contains("cause: unknown"));
        assertFalse("the dedup key is never shown", report.contains("handle"));
        assertEquals("five fixed header lines plus one line per result", 8, report.split("\n").length);
    }
}

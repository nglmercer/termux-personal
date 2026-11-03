package com.termux.app.compat.network;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoopbackDiagnosticsTest {
    @Test public void testsIpv4UsingAnActualEphemeralListener() {
        assertEquals(LoopbackDiagnostics.Status.AVAILABLE, LoopbackDiagnostics.probeAddress("127.0.0.1"));
    }
    @Test public void refusesNonLoopbackAddresses() {
        assertEquals(LoopbackDiagnostics.Status.UNAVAILABLE, LoopbackDiagnostics.probeAddress("192.0.2.1"));
    }
    @Test public void describesOnlyWhatWasActuallyTested() {
        LoopbackDiagnostics.Result result = LoopbackDiagnostics.probe();
        assertEquals(LoopbackDiagnostics.Status.AVAILABLE, result.ipv4);
        assertNotNull(result.ipv6);
        assertTrue(result.describe().contains("external browser"));
        assertTrue(result.describe().contains("have not been tested"));
    }
}

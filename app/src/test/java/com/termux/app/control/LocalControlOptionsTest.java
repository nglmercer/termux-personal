package com.termux.app.control;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class LocalControlOptionsTest {
    @Test public void existingConsentNeverGrantsNewDomainScopes() {
        for (int mask = 0; mask < 16; mask++) {
            Set<ControlCore.Scope> granted = LocalControlOptions.scopes((mask & 1) != 0, (mask & 2) != 0,
                (mask & 4) != 0, (mask & 8) != 0);
            for (ControlCore.Scope scope : ControlCore.Scope.values()) {
                if (!scope.name().startsWith("SESSIONS_")) assertFalse(granted.contains(scope));
            }
        }
    }
    @Test public void closeNeedsBothWriteModeAndExplicitConsent() {
        Set<ControlCore.Scope> read = LocalControlOptions.scopes(false, false, true);
        assertTrue(read.contains(ControlCore.Scope.SESSIONS_READ));
        assertFalse(read.contains(ControlCore.Scope.SESSIONS_WRITE));
        assertFalse(read.contains(ControlCore.Scope.SESSIONS_CLOSE));
        assertFalse(LocalControlOptions.scopes(true, false, false).contains(ControlCore.Scope.SESSIONS_CLOSE));
        Set<ControlCore.Scope> allowed = LocalControlOptions.scopes(true, true, true);
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_CLOSE));
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_OUTPUT));
        assertTrue(allowed.contains(ControlCore.Scope.SESSIONS_WRITE));
        assertFalse(LocalControlOptions.scopes(true, false, false, true).contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME));
        assertFalse(LocalControlOptions.scopes(true, true, false, false).contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME));
        assertTrue(LocalControlOptions.scopes(true, true, false, true).contains(ControlCore.Scope.SESSIONS_OUTPUT_RESUME));
    }
}

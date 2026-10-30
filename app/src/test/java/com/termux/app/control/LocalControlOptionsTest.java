package com.termux.app.control;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class LocalControlOptionsTest {
    @Test public void jobConsentIsIndependentOfSessionConsent() {
        assertTrue(LocalControlOptions.jobScopes(false, false, false, false).isEmpty());
        Set<ControlCore.Scope> execute = LocalControlOptions.jobScopes(false, false, true, false);
        assertEquals(java.util.EnumSet.of(ControlCore.Scope.JOBS_EXECUTE), execute);
        for (int mask = 0; mask < 16; mask++) {
            Set<ControlCore.Scope> jobs = LocalControlOptions.jobScopes((mask & 1) != 0, (mask & 2) != 0,
                (mask & 4) != 0, (mask & 8) != 0);
            for (ControlCore.Scope scope : jobs) assertTrue(scope.name().startsWith("JOBS_"));
            for (ControlCore.Scope scope : LocalControlOptions.scopes(true, true, true, true)) assertFalse(scope.name().startsWith("JOBS_"));
        }
    }
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

    @Test public void rememberedConsentRoundTripsAndCanOnlyNarrow() {
        java.util.EnumSet<ControlCore.Scope> chosen = LocalControlOptions.scopes(true, true, true, true);
        chosen.add(ControlCore.Scope.JOBS_EXECUTE); chosen.add(ControlCore.Scope.FILES_WRITE);
        org.junit.Assert.assertEquals(chosen, LocalControlOptions.decode(LocalControlOptions.encode(chosen)));
        org.junit.Assert.assertEquals("unknown names are dropped and read access is the floor",
            java.util.EnumSet.of(ControlCore.Scope.SESSIONS_READ, ControlCore.Scope.JOBS_READ),
            LocalControlOptions.decode("NOT_A_SCOPE, JOBS_READ,,jobs_execute"));
        org.junit.Assert.assertEquals("dependent scopes without their base are removed",
            java.util.EnumSet.of(ControlCore.Scope.SESSIONS_READ),
            LocalControlOptions.decode("SESSIONS_CLOSE,SESSIONS_OUTPUT_RESUME"));
        org.junit.Assert.assertEquals(java.util.EnumSet.of(ControlCore.Scope.SESSIONS_READ), LocalControlOptions.decode(null));
    }

    @Test public void quickGroupsGrantWholeGroupsAndKeepUntouchedCustomChoices() {
        java.util.Map<LocalControlOptions.Group, Boolean> chosen = new java.util.EnumMap<>(LocalControlOptions.Group.class);
        for (LocalControlOptions.Group group : LocalControlOptions.Group.values()) chosen.put(group, group.recommended);
        java.util.EnumSet<ControlCore.Scope> first = LocalControlOptions.applyGroups(java.util.EnumSet.of(ControlCore.Scope.SESSIONS_READ), chosen);
        org.junit.Assert.assertTrue(first.containsAll(LocalControlOptions.Group.FILES.scopes));
        org.junit.Assert.assertFalse("running commands is never recommended", first.contains(ControlCore.Scope.JOBS_EXECUTE));

        // A custom subset from the settings screen (read-only files) survives when the box is left as is.
        java.util.EnumSet<ControlCore.Scope> custom = java.util.EnumSet.of(ControlCore.Scope.SESSIONS_READ, ControlCore.Scope.FILES_READ,
            ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_CLOSE);
        chosen.put(LocalControlOptions.Group.FILES, true);
        chosen.put(LocalControlOptions.Group.TERMINAL, true);
        java.util.EnumSet<ControlCore.Scope> kept = LocalControlOptions.applyGroups(custom, chosen);
        org.junit.Assert.assertFalse(kept.contains(ControlCore.Scope.FILES_WRITE));
        org.junit.Assert.assertTrue(kept.contains(ControlCore.Scope.SESSIONS_CLOSE));

        chosen.put(LocalControlOptions.Group.TERMINAL, false);
        java.util.EnumSet<ControlCore.Scope> off = LocalControlOptions.applyGroups(custom, chosen);
        org.junit.Assert.assertFalse(off.contains(ControlCore.Scope.SESSIONS_WRITE));
        org.junit.Assert.assertFalse("turning terminal off also removes force-close", off.contains(ControlCore.Scope.SESSIONS_CLOSE));
        org.junit.Assert.assertTrue(off.contains(ControlCore.Scope.SESSIONS_READ));
    }
}

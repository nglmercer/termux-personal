package com.termux.app.control.jobs;

import static com.termux.app.control.jobs.PressureTracker.Level.CRITICAL;
import static com.termux.app.control.jobs.PressureTracker.Level.ELEVATED;
import static com.termux.app.control.jobs.PressureTracker.Level.NORMAL;
import static com.termux.app.control.jobs.PressureTracker.Level.UNKNOWN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PressureTrackerTest {
    private static final long GIB = 1L << 30;
    private static PressureTracker.Reading mem(long availableMiB, long totalGiB) {
        return new PressureTracker.Reading(availableMiB << 20, totalGiB * GIB, false, null);
    }

    @Test public void startsUnknownAndAdoptsTheFirstReading() {
        PressureTracker tracker = new PressureTracker();
        assertEquals(UNKNOWN, tracker.level());
        assertEquals(NORMAL, tracker.update(mem(3000, 8)));
    }

    @Test public void escalationNeedsConsecutiveSamples() {
        PressureTracker tracker = new PressureTracker(2, 3);
        tracker.update(mem(3000, 8));
        assertEquals("one low sample is noise", NORMAL, tracker.update(mem(300, 8)));
        assertEquals("a good sample resets the count", NORMAL, tracker.update(mem(3000, 8)));
        assertEquals(NORMAL, tracker.update(mem(300, 8)));
        assertEquals(CRITICAL, tracker.update(mem(300, 8)));
    }

    @Test public void recoveryNeedsClearlyBetterConsecutiveSamples() {
        PressureTracker tracker = new PressureTracker(1, 3);
        tracker.update(mem(3000, 8));
        assertEquals(ELEVATED, tracker.update(mem(700, 8))); // elevated below max(512 MiB, 12% of 8 GiB = ~983 MiB)
        // 1000 MiB is just above the enter threshold but inside the exit margin: still pressure.
        for (int i = 0; i < 5; i++) assertEquals(ELEVATED, tracker.update(mem(1000, 8)));
        assertEquals(ELEVATED, tracker.update(mem(2500, 8)));
        assertEquals(ELEVATED, tracker.update(mem(2500, 8)));
        assertEquals(NORMAL, tracker.update(mem(2500, 8)));
    }

    @Test public void androidLowMemoryFlagIsCritical() {
        PressureTracker tracker = new PressureTracker(1, 1);
        assertEquals(CRITICAL, tracker.update(new PressureTracker.Reading(4 * GIB, 8 * GIB, true, null)));
    }

    @Test public void severeThermalStateRaisesPressureWithoutMemoryEvidence() {
        PressureTracker tracker = new PressureTracker(1, 1);
        assertEquals(ELEVATED, tracker.update(new PressureTracker.Reading(null, null, null, 3)));
        assertEquals(CRITICAL, tracker.update(new PressureTracker.Reading(null, null, null, 4)));
    }

    @Test public void missingDataNeverClearsKnownPressure() {
        PressureTracker tracker = new PressureTracker(1, 1);
        tracker.update(mem(200, 8));
        assertEquals(CRITICAL, tracker.level());
        assertEquals(CRITICAL, tracker.update(new PressureTracker.Reading(null, null, null, null)));
        assertEquals(CRITICAL, tracker.update(null));
    }

    @Test public void smallDevicesUseTheAbsoluteFloors() {
        // 2 GiB device: 12% is ~245 MiB, below the 512 MiB floor.
        assertEquals(ELEVATED, PressureTracker.classify(mem(400, 2), NORMAL));
        assertEquals(NORMAL, PressureTracker.classify(mem(600, 2), NORMAL));
        assertEquals(CRITICAL, PressureTracker.classify(mem(200, 2), NORMAL));
    }
}

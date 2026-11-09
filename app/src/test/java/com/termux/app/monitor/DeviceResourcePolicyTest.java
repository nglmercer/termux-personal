package com.termux.app.monitor;

import static com.termux.app.control.jobs.JobResourcePolicy.Profile.*;
import static org.junit.Assert.*;

import com.termux.app.control.jobs.PressureTracker.Level;
import org.junit.Test;

public class DeviceResourcePolicyTest {
    private static final long GIB = 1L << 30;

    @Test public void slotsFollowTotalRamAndPressureAndNeverDropBelowOne() {
        assertEquals(1, DeviceResourcePolicy.heavySlots(2 * GIB, Level.NORMAL, 4));
        assertEquals(2, DeviceResourcePolicy.heavySlots(4 * GIB, Level.NORMAL, 4));
        assertEquals(3, DeviceResourcePolicy.heavySlots(8 * GIB, Level.NORMAL, 4));
        assertEquals(4, DeviceResourcePolicy.heavySlots(16 * GIB, Level.NORMAL, 4));
        assertEquals(2, DeviceResourcePolicy.heavySlots(16 * GIB, Level.ELEVATED, 4));
        assertEquals(1, DeviceResourcePolicy.heavySlots(16 * GIB, Level.CRITICAL, 4));
        assertEquals("no data: conservative", 1, DeviceResourcePolicy.heavySlots(null, Level.NORMAL, 4));
        assertEquals(1, DeviceResourcePolicy.heavySlots(16 * GIB, Level.UNKNOWN, 4));
        assertEquals("capped", 2, DeviceResourcePolicy.heavySlots(16 * GIB, Level.NORMAL, 2));
        assertEquals(1, DeviceResourcePolicy.heavySlots(16 * GIB, Level.NORMAL, 0));
    }

    @Test public void workersShareTheCpusAmongConcurrentJobs() {
        assertEquals(1, DeviceResourcePolicy.workers(PROTECTED, 8, 1, Level.NORMAL));
        assertEquals(4, DeviceResourcePolicy.workers(BALANCED, 16, 1, Level.NORMAL));
        assertEquals(2, DeviceResourcePolicy.workers(BALANCED, 8, 2, Level.NORMAL));
        assertEquals(8, DeviceResourcePolicy.workers(PERFORMANCE, 8, 1, Level.NORMAL));
        assertEquals(2, DeviceResourcePolicy.workers(PERFORMANCE, 8, 4, Level.NORMAL));
        assertEquals("pressure reduces it", 2, DeviceResourcePolicy.workers(PERFORMANCE, 8, 2, Level.ELEVATED));
        assertEquals(1, DeviceResourcePolicy.workers(PERFORMANCE, 8, 1, Level.CRITICAL));
        assertEquals(1, DeviceResourcePolicy.workers(BALANCED, 1, 4, Level.NORMAL));
    }

    @Test public void customAndUnrestrictedRecommendNothing() {
        assertEquals(0, DeviceResourcePolicy.workers(CUSTOM, 8, 1, Level.NORMAL));
        assertEquals(0, DeviceResourcePolicy.workers(UNRESTRICTED, 8, 1, Level.NORMAL));
        assertEquals(0, DeviceResourcePolicy.workers(null, 8, 1, Level.NORMAL));
        assertEquals(0, DeviceResourcePolicy.workers(BALANCED, 0, 1, Level.NORMAL));
        assertNull(DeviceResourcePolicy.memorySoftBytes(CUSTOM, 8 * GIB, 4 * GIB, 1));
        assertNull(DeviceResourcePolicy.memorySoftBytes(UNRESTRICTED, 8 * GIB, 4 * GIB, 1));
    }

    @Test public void memoryThresholdsDependOnTheDeviceNotFixedConstants() {
        long protectedSoft = DeviceResourcePolicy.memorySoftBytes(PROTECTED, 8 * GIB, 4 * GIB, 1);
        assertTrue("limited by what is available now", protectedSoft <= 4 * GIB / 100 * 60);
        long roomy = DeviceResourcePolicy.memorySoftBytes(PROTECTED, 8 * GIB, 7 * GIB, 1);
        assertTrue(roomy > protectedSoft);
        assertTrue(DeviceResourcePolicy.memorySoftBytes(BALANCED, 8 * GIB, null, 2) < DeviceResourcePolicy.memorySoftBytes(BALANCED, 8 * GIB, null, 1));
        assertTrue(DeviceResourcePolicy.memorySoftBytes(PERFORMANCE, 8 * GIB, null, 1) > DeviceResourcePolicy.memorySoftBytes(BALANCED, 8 * GIB, null, 1));
        assertNull("unknown total", DeviceResourcePolicy.memorySoftBytes(BALANCED, null, 1L, 1));
        assertNull("below the policy minimum", DeviceResourcePolicy.memorySoftBytes(BALANCED, 20L << 20, null, 4));
    }
}

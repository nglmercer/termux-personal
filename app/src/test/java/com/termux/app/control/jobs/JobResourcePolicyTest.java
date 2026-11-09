package com.termux.app.control.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import org.junit.Test;

public class JobResourcePolicyTest {
    private static void rejects(JobResourcePolicy.Builder builder) {
        try { builder.build(); fail("expected rejection"); } catch (IllegalArgumentException expected) { }
    }

    @Test public void validPolicyKeepsItsValues() {
        JobResourcePolicy p = JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.PROTECTED)
            .memorySoftBytes(1L << 30).cpuTimeSeconds(600L).niceLevel(7L).maxOutputBytes(65536L)
            .processConcurrencyHint(2L).onPressure(JobResourcePolicy.PressureAction.PAUSE_ADMISSION).build();
        assertEquals(1L << 30, p.memorySoftBytes()); assertEquals(600, p.cpuTimeSeconds()); assertEquals(7, p.niceLevel());
        assertEquals(65536, p.outputStreamBytes()); assertEquals(2, p.effectiveConcurrencyHint());
        assertEquals(0, p.addressSpaceBytes());
    }

    @Test public void invalidValuesAreRejectedNotClamped() {
        rejects(JobResourcePolicy.builder().memorySoftBytes(1L));
        rejects(JobResourcePolicy.builder().memorySoftBytes(JobResourcePolicy.MAX_BYTES + 1));
        rejects(JobResourcePolicy.builder().addressSpaceBytes(1L << 20));
        rejects(JobResourcePolicy.builder().cpuTimeSeconds(JobResourcePolicy.MAX_CPU_TIME_SECONDS + 1));
        rejects(JobResourcePolicy.builder().maxOutputBytes(512L));
        rejects(JobResourcePolicy.builder().maxOutputBytes(524288L)); // above the per-stream replay buffer
        rejects(JobResourcePolicy.builder().processConcurrencyHint(17L));
        rejects(JobResourcePolicy.builder().requireNice(true).profile(JobResourcePolicy.Profile.CUSTOM));
        rejects(JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.UNRESTRICTED).onPressure(JobResourcePolicy.PressureAction.STOP_JOB));
        rejects(JobResourcePolicy.builder().memorySoftBytes(2L << 30).addressSpaceBytes(1L << 30));
    }

    @Test public void zeroNegativeAndOverflowingNumbersFailExplicitly() {
        for (long bad : new long[] {0, -1, Long.MIN_VALUE}) {
            try { JobResourcePolicy.builder().memorySoftBytes(bad); fail("memory " + bad); } catch (IllegalArgumentException expected) { }
            try { JobResourcePolicy.builder().cpuTimeSeconds(bad); fail("cpu " + bad); } catch (IllegalArgumentException expected) { }
        }
        try { JobResourcePolicy.builder().niceLevel(-1L); fail("negative nice"); } catch (IllegalArgumentException expected) { }
        try { JobResourcePolicy.builder().niceLevel(20L); fail("nice 20"); } catch (IllegalArgumentException expected) { }
        // Values above Integer.MAX_VALUE saturate and are then rejected as out of range, never wrapped to a small valid value.
        rejects(JobResourcePolicy.builder().maxOutputBytes(Long.MAX_VALUE));
        rejects(JobResourcePolicy.builder().processConcurrencyHint((long) Integer.MAX_VALUE + 5));
        rejects(JobResourcePolicy.builder().maxOutputBytes((1L << 32) + 4096));
    }

    @Test public void profilesSupplyDefaultsButNeverHardLimits() {
        for (JobResourcePolicy.Profile profile : JobResourcePolicy.Profile.values()) {
            JobResourcePolicy.Effective e = JobResourcePolicy.ofProfile(profile).resolve(new JobResourcePolicy.Capabilities(true));
            assertEquals(0, e.nativeLimits.addressSpaceBytes);
            assertEquals(0, e.nativeLimits.cpuSeconds);
            assertFalse(e.blocked);
        }
        assertEquals(10, JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.PROTECTED).resolve(new JobResourcePolicy.Capabilities(true)).nativeLimits.niceLevel);
        assertEquals(5, JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED).resolve(new JobResourcePolicy.Capabilities(true)).nativeLimits.niceLevel);
        assertTrue(JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.UNRESTRICTED).resolve(new JobResourcePolicy.Capabilities(true)).nativeLimits.isNone());
        assertEquals("explicit beats the profile default", 3, JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.PROTECTED)
            .niceLevel(3L).build().resolve(new JobResourcePolicy.Capabilities(true)).nativeLimits.niceLevel);
    }

    @Test public void explicitHardLimitsAreRequiredAndBlockTheJobWhenUnsupported() {
        JobResourcePolicy p = JobResourcePolicy.builder().addressSpaceBytes(4L << 30).cpuTimeSeconds(60L).build();
        JobResourcePolicy.Effective ok = p.resolve(new JobResourcePolicy.Capabilities(true));
        assertEquals(ManagedNative.NativeLimits.REQUIRE_ADDRESS_SPACE | ManagedNative.NativeLimits.REQUIRE_CPU_TIME, ok.nativeLimits.requiredMask & 3);
        assertFalse(ok.blocked);
        JobResourcePolicy.Effective no = p.resolve(new JobResourcePolicy.Capabilities(false));
        assertTrue("a requested hard limit is never silently dropped", no.blocked);
        assertEquals(java.util.Arrays.asList("addressSpaceBytes", "cpuTimeSeconds", "niceLevel"), no.unsupported);
    }

    @Test public void bestEffortNiceDoesNotBlockWhenUnsupportedButIsReported() {
        JobResourcePolicy.Effective e = JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED).resolve(new JobResourcePolicy.Capabilities(false));
        assertFalse(e.blocked);
        assertEquals(Collections.singletonList("niceLevel"), e.unsupported);
        JobResourcePolicy required = JobResourcePolicy.builder().niceLevel(4L).requireNice(true).build();
        assertTrue(required.resolve(new JobResourcePolicy.Capabilities(false)).blocked);
    }

    private static JobSpec spec(JobResourcePolicy resources) {
        return new JobSpec("cargo", java.util.Arrays.asList("build"), null, null, Collections.emptyMap(), null, 0,
            JobSpec.Presentation.BACKGROUND, "req-1", resources);
    }

    @Test public void resourcesAreIncludedInTheIdempotencyFingerprint() {
        JobResourcePolicy a = JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED);
        JobResourcePolicy b = JobResourcePolicy.builder().profile(JobResourcePolicy.Profile.BALANCED).memorySoftBytes(1L << 30).build();
        assertTrue(spec(a).sameExecution(spec(JobResourcePolicy.ofProfile(JobResourcePolicy.Profile.BALANCED))));
        assertFalse(spec(a).sameExecution(spec(b)));
        assertFalse("a request without resources differs from one with them", spec(null).sameExecution(spec(a)));
        assertNotEquals(a, b);
    }

    @Test public void aRequestWithoutResourcesKeepsItsHistoricalFingerprint() {
        JobSpec legacy = new JobSpec("cargo", java.util.Arrays.asList("build"), null, null, Collections.emptyMap(), null, 0,
            JobSpec.Presentation.BACKGROUND, "req-1");
        assertTrue(legacy.sameExecution(spec(null)));
        assertEquals(null, legacy.resources());
    }

    @Test public void outputReservationHonoursThePerJobCapacityAndTheQuota() {
        JobOutputStore store = new JobOutputStore(1024, 4, 16 * 1024);
        assertTrue(store.reserve("a", 512) != null);
        try { store.reserve("b", 2048); fail("above default"); } catch (IllegalArgumentException expected) { }
        // 16 KiB quota: 8 jobs of 1 KiB streams would exceed 4 records; fill by quota instead.
        JobOutputStore small = new JobOutputStore(1024, 32, 4096);
        assertTrue(small.reserve("1") != null); assertTrue(small.reserve("2") != null);
        assertEquals("quota exhausted", null, small.reserve("3"));
        small.remove("1");
        assertTrue("removal returns the reserved bytes", small.reserve("3") != null);
    }
}

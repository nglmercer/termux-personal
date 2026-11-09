package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BuildPresetsTest {
    private static final List<String> NO_ENV = Collections.emptyList();
    private static BuildPresets.Preview run(String exe, List<String> args, List<String> env, int workers) {
        return BuildPresets.apply(exe, args, env, workers, 8, 0);
    }

    @Test public void cargoGetsJobsAfterTheSubcommandAndKeepsUserArguments() {
        BuildPresets.Preview p = run("cargo", Arrays.asList("build", "--release", "--features", "x"), NO_ENV, 2);
        assertEquals(Arrays.asList("build", "-j", "2", "--release", "--features", "x"), p.arguments);
        assertTrue(p.changed);
        assertEquals(Arrays.asList("+nightly", "build", "-j", "2"), run("/data/data/com.termux/files/usr/bin/cargo", Arrays.asList("+nightly", "build"), NO_ENV, 2).arguments);
    }

    @Test public void anExistingChoiceIsNeverOverriddenOrDuplicated() {
        for (List<String> args : Arrays.asList(Arrays.asList("build", "-j", "8"), Arrays.asList("build", "-j8"), Arrays.asList("build", "--jobs=4"), Arrays.asList("build", "--jobs", "4"))) {
            BuildPresets.Preview p = run("cargo", args, NO_ENV, 2);
            assertFalse(args.toString(), p.changed); assertEquals(args, p.arguments);
        }
        assertFalse(run("cargo", Arrays.asList("build"), Arrays.asList("CARGO_BUILD_JOBS"), 2).changed);
        assertFalse(run("make", Arrays.asList("all"), Arrays.asList("MAKEFLAGS"), 2).changed);
        assertFalse(run("gradle", Arrays.asList("build", "--max-workers=3"), NO_ENV, 2).changed);
        assertFalse(run("ninja", Arrays.asList("-j", "6"), NO_ENV, 2).changed);
    }

    @Test public void flagsAfterDoubleDashBelongToTheProgramBeingRunAndDoNotCount() {
        BuildPresets.Preview p = run("cargo", Arrays.asList("run", "--", "-j", "9"), NO_ENV, 2);
        assertTrue(p.changed);
        assertEquals(Arrays.asList("run", "-j", "2", "--", "-j", "9"), p.arguments);
    }

    @Test public void onlyCompilingCargoSubcommandsAreTouched() {
        assertFalse(run("cargo", Arrays.asList("fmt"), NO_ENV, 2).changed);
        assertFalse(run("cargo", Arrays.asList("--version"), NO_ENV, 2).changed);
        assertFalse(run("cargo", Collections.emptyList(), NO_ENV, 2).changed);
    }

    @Test public void gradleNinjaAndMake() {
        assertEquals(Arrays.asList("assembleDebug", "--max-workers=2"), run("./gradlew".substring(2), Arrays.asList("assembleDebug"), NO_ENV, 2).arguments);
        assertEquals(Arrays.asList("-j", "2", "all"), run("ninja", Arrays.asList("all"), NO_ENV, 2).arguments);
        assertEquals(Arrays.asList("-j2", "all"), run("make", Arrays.asList("all"), NO_ENV, 2).arguments);
    }

    @Test public void workersAreCappedAtTheCpuCountAndNeverBelowOne() {
        assertEquals(Arrays.asList("-j2", "all"), BuildPresets.apply("make", Arrays.asList("all"), NO_ENV, 8, 2, 0).arguments);
        assertEquals(Arrays.asList("-j1", "all"), BuildPresets.apply("make", Arrays.asList("all"), NO_ENV, 4, 0, 0).arguments);
    }

    @Test public void noRecommendationMeansNoChange() {
        assertFalse(run("cargo", Arrays.asList("build"), NO_ENV, 0).changed);
        assertFalse(run("make", Arrays.asList("all"), NO_ENV, -1).changed);
    }

    @Test public void nodeNeedsAnExplicitHeapRequestAndRespectsExistingSettings() {
        assertFalse(run("node", Arrays.asList("app.js"), NO_ENV, 2).changed);
        assertEquals(Arrays.asList("--max-old-space-size=1024", "app.js"), BuildPresets.apply("node", Arrays.asList("app.js"), NO_ENV, 0, 8, 1024).arguments);
        assertFalse(BuildPresets.apply("node", Arrays.asList("--max-old-space-size=512", "app.js"), NO_ENV, 0, 8, 1024).changed);
        assertFalse(BuildPresets.apply("node", Arrays.asList("app.js"), Arrays.asList("NODE_OPTIONS"), 0, 8, 1024).changed);
    }

    @Test public void unknownExecutablesAreNeverRewritten() {
        for (String exe : new String[] {"sh", "bash", "python3", "cargo-fmt", "mycargo", "/bin/make-it", "rustc"}) {
            List<String> args = Arrays.asList("-c", "make -j1");
            BuildPresets.Preview p = run(exe, args, NO_ENV, 4);
            assertFalse(exe, p.changed); assertEquals(args, p.arguments); assertFalse(BuildPresets.isKnown(exe));
        }
    }

    @Test public void theInputListIsNotModified() {
        List<String> original = Arrays.asList("build");
        run("cargo", original, NO_ENV, 2);
        assertEquals(Arrays.asList("build"), original);
    }
}

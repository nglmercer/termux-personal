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

    // ---- testConcurrency: explicit opt-in for the standard libtest harness and the Node test runner ----

    private static List<String> tests(String exe, List<String> env, int workers, String... args) {
        return BuildPresets.apply(exe, Arrays.asList(args), env, workers, 8, 0, 1).arguments;
    }

    @Test public void cargoTestGetsBuildJobsAndHarnessThreadsEachInItsOwnSection() {
        assertEquals(Arrays.asList("test", "-j", "1", "--", "--test-threads=1"), tests("cargo", NO_ENV, 1, "test"));
        assertEquals(Arrays.asList("t", "-j", "1", "--", "--test-threads=1"), tests("cargo", NO_ENV, 1, "t"));
        assertEquals(Arrays.asList("+nightly", "test", "-j", "1", "--", "--test-threads=1"), tests("cargo", NO_ENV, 1, "+nightly", "test"));
        assertEquals(Arrays.asList("test", "-j", "1", "my_filter", "--", "--test-threads=1", "--nocapture"),
            tests("cargo", NO_ENV, 1, "test", "my_filter", "--", "--nocapture"));
    }

    @Test public void anExistingBuildJobCountDoesNotStopTheHarnessLimit() {
        BuildPresets.Preview p = BuildPresets.apply("cargo", Arrays.asList("test", "-j", "4"), NO_ENV, 1, 8, 0, 1);
        assertEquals(Arrays.asList("test", "-j", "4", "--", "--test-threads=1"), p.arguments);
        assertTrue(p.changed); assertEquals("no workers were added", 0, p.workersApplied);
        assertEquals(Arrays.asList("test", "--", "--test-threads=1"), tests("cargo", Arrays.asList("CARGO_BUILD_JOBS"), 1, "test"));
    }

    @Test public void theUsersHarnessChoiceWinsWhileBuildJobsAreStillDecidedSeparately() {
        assertEquals(Arrays.asList("test", "-j", "1", "--", "--test-threads=8"), tests("cargo", NO_ENV, 1, "test", "--", "--test-threads=8"));
        assertEquals(Arrays.asList("test", "-j", "1", "--", "--test-threads", "8"), tests("cargo", NO_ENV, 1, "test", "--", "--test-threads", "8"));
        assertEquals(Arrays.asList("test", "-j", "1"), tests("cargo", Arrays.asList("RUST_TEST_THREADS"), 1, "test"));
        assertEquals("--no-run executes no tests", Arrays.asList("test", "-j", "1", "--no-run"), tests("cargo", NO_ENV, 1, "test", "--no-run"));
    }

    @Test public void ambiguousOrNonTestCargoCommandsKeepTheirArgumentsAndSayWhy() {
        BuildPresets.Preview p = BuildPresets.apply("cargo", Arrays.asList("--config", "test", "build"), NO_ENV, 0, 8, 0, 1);
        assertFalse(p.changed); assertEquals(Arrays.asList("--config", "test", "build"), p.arguments);
        assertTrue(p.notes.toString(), p.notes.toString().contains("ambiguous"));
        assertEquals(Arrays.asList("build", "-j", "1"), tests("cargo", NO_ENV, 1, "build"));
        assertEquals(Arrays.asList("nextest", "run"), tests("cargo", NO_ENV, 1, "nextest", "run"));
        assertEquals(Arrays.asList("run", "-j", "1", "--", "--test"), tests("cargo", NO_ENV, 1, "run", "--", "--test"));
    }

    @Test public void withoutTheOptInNothingIsAddedForTests() {
        assertEquals(Arrays.asList("test", "-j", "1"), BuildPresets.apply("cargo", Arrays.asList("test"), NO_ENV, 1, 8, 0, 0).arguments);
        assertEquals(Arrays.asList("--test"), BuildPresets.apply("node", Arrays.asList("--test"), NO_ENV, 1, 8, 0, 0).arguments);
    }

    @Test public void nodeTestRunnerIsRecognisedOnlyAmongNodesOwnOptions() {
        assertEquals(Arrays.asList("--test", "--test-concurrency=1"), tests("node", NO_ENV, 0, "--test"));
        assertEquals(Arrays.asList("--test", "--test-concurrency=1", "test/a.test.js"), tests("nodejs", NO_ENV, 0, "--test", "test/a.test.js"));
        assertEquals(Arrays.asList("--require", "x.js", "--test", "--test-concurrency=1", "t.js"), tests("node", NO_ENV, 0, "--require", "x.js", "--test", "t.js"));
        assertEquals(Arrays.asList("app.js", "--test"), tests("node", NO_ENV, 0, "app.js", "--test"));
        assertEquals(Arrays.asList("--", "app.js", "--test"), tests("node", NO_ENV, 0, "--", "app.js", "--test"));
        assertEquals(Arrays.asList("-e", "--test"), tests("node", NO_ENV, 0, "-e", "--test"));
    }

    @Test public void nodeKeepsTheUsersConcurrencyAndAnyNodeOptions() {
        assertEquals(Arrays.asList("--test", "--test-concurrency=4"), tests("node", NO_ENV, 0, "--test", "--test-concurrency=4"));
        assertEquals(Arrays.asList("--test-concurrency", "4", "--test"), tests("node", NO_ENV, 0, "--test-concurrency", "4", "--test"));
        BuildPresets.Preview p = BuildPresets.apply("node", Arrays.asList("--test"), Arrays.asList("NODE_OPTIONS"), 0, 8, 0, 1);
        assertFalse(p.changed); assertTrue(p.notes.toString(), p.notes.toString().contains("NODE_OPTIONS"));
    }

    @Test public void nodeHeapAndTestConcurrencyAreIndependent() {
        assertEquals(Arrays.asList("--max-old-space-size=512", "--test", "--test-concurrency=2", "t.js"),
            BuildPresets.apply("node", Arrays.asList("--test", "t.js"), NO_ENV, 0, 8, 512, 2).arguments);
        assertEquals(Arrays.asList("--max-old-space-size=256", "--test", "--test-concurrency=2"),
            BuildPresets.apply("node", Arrays.asList("--max-old-space-size=256", "--test"), NO_ENV, 0, 8, 512, 2).arguments);
    }

    @Test public void otherToolsIgnoreTestConcurrencyWithANote() {
        BuildPresets.Preview p = BuildPresets.apply("make", Arrays.asList("check"), NO_ENV, 2, 8, 0, 1);
        assertEquals(Arrays.asList("-j2", "check"), p.arguments); assertEquals(2, p.workersApplied);
        assertTrue(p.notes.toString(), p.notes.toString().contains("testConcurrency applies only"));
        assertFalse(BuildPresets.apply("npm", Arrays.asList("test"), NO_ENV, 2, 8, 0, 1).changed);
    }
}

package com.termux.app.control.jobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opt-in concurrency recommendations for a few well known build tools. It never rewrites an arbitrary command:
 * only an executable whose basename is exactly one of the known tools is touched, only when the caller explicitly
 * asked for a preset, and never when the user already chose the relevant option (a flag in the arguments or the
 * environment variable). The user's own arguments keep their order and are only ever added to, never edited.
 *
 * <p>The worker count is a recommendation derived from the resource profile, not a promise of speed: more workers
 * can be slower on a thermally limited phone. A profile that implies no count (custom, unrestricted, or no hint)
 * recommends nothing.
 */
public final class BuildPresets {
    private BuildPresets() { }

    /** The effective command a preset produces, to preview before running. {@link #changed} is false when nothing was added. */
    public static final class Preview {
        public final List<String> arguments;
        /** Human-readable reasons, one per addition or skipped addition. */
        public final List<String> notes;
        public final boolean changed;
        /** The worker count this preset really added to the command, or 0 when it added none. */
        public final int workersApplied;
        private Preview(List<String> arguments, List<String> notes, boolean changed, int workersApplied) {
            this.arguments = Collections.unmodifiableList(arguments); this.notes = Collections.unmodifiableList(notes); this.changed = changed;
            this.workersApplied = workersApplied;
        }
    }

    /** Upper bound of {@code testConcurrency}, as for the concurrency hint. */
    public static final int MAX_TEST_CONCURRENCY = JobResourcePolicy.MAX_CONCURRENCY_HINT;

    /** Whether {@code executable} names a tool this class knows. */
    public static boolean isKnown(String executable) {
        return kind(executable) != null;
    }

    /** Same as {@link #apply(String, List, List, int, int, int, int)} without a test-runner limit. */
    public static Preview apply(String executable, List<String> arguments, List<String> environmentNames,
                                int workers, int cpuCount, int nodeHeapMiB) {
        return apply(executable, arguments, environmentNames, workers, cpuCount, nodeHeapMiB, 0);
    }

    /**
     * @param executable the job executable, bare name or absolute path
     * @param arguments the user's arguments
     * @param environmentNames names (not values) of the job's environment variables, to avoid conflicting settings
     * @param workers recommended workers, or {@code <= 0} for none; capped at {@code cpuCount}
     * @param cpuCount available processors
     * @param nodeHeapMiB old-space limit for Node.js, or {@code <= 0} to leave Node alone
     * @param testConcurrency explicit opt-in limit for a recognised test runner ({@code cargo test} with the standard libtest
     *     harness, {@code node --test}), or {@code <= 0} to leave test runners alone. It is not a global thread or process cap.
     */
    public static Preview apply(String executable, List<String> arguments, List<String> environmentNames,
                                int workers, int cpuCount, int nodeHeapMiB, int testConcurrency) {
        if (executable == null || arguments == null || environmentNames == null) throw new IllegalArgumentException("Invalid preset input");
        if (testConcurrency > MAX_TEST_CONCURRENCY) throw new IllegalArgumentException("Invalid test concurrency");
        List<String> result = new ArrayList<>(arguments);
        List<String> notes = new ArrayList<>();
        String kind = kind(executable);
        if (kind == null) { notes.add("not a known build tool; command left unchanged"); return new Preview(result, notes, false, 0); }
        int n = workers > 0 ? Math.min(workers, Math.max(1, cpuCount)) : 0;
        boolean changed = false;
        int applied = 0;
        switch (kind) {
            case "cargo": {
                // Build jobs and test threads are independent decisions: an existing -j never stops the test-runner limit.
                if (n == 0) notes.add("no worker recommendation for this profile");
                else if (!isCargoBuildCommand(arguments)) notes.add("cargo subcommand does not compile; left unchanged");
                else if (hasFlag(arguments, "-j", "--jobs") || environmentNames.contains("CARGO_BUILD_JOBS")) notes.add("cargo job count already set; left unchanged");
                else {
                    int at = firstNonOption(arguments) + 1; // right after the subcommand
                    result.add(at, "-j"); result.add(at + 1, Integer.toString(n));
                    notes.add("cargo -j " + n); changed = true; applied = n;
                }
                if (testConcurrency > 0 && cargoTestThreads(result, environmentNames, testConcurrency, notes)) changed = true;
                break;
            }
            case "gradle": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "--max-workers")) { notes.add("gradle --max-workers already set; left unchanged"); break; }
                result.add("--max-workers=" + n); notes.add("gradle --max-workers=" + n); changed = true; applied = n; break;
            }
            case "ninja": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "-j")) { notes.add("ninja -j already set; left unchanged"); break; }
                result.add(0, "-j"); result.add(1, Integer.toString(n)); notes.add("ninja -j " + n); changed = true; applied = n; break;
            }
            case "make": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "-j", "--jobs") || environmentNames.contains("MAKEFLAGS")) { notes.add("make job count already set; left unchanged"); break; }
                result.add(0, "-j" + n); notes.add("make -j" + n); changed = true; applied = n; break;
            }
            case "node": {
                // The test-runner limit is decided on the user's own arguments, before a heap option is prepended.
                if (testConcurrency > 0 && nodeTestConcurrency(result, environmentNames, testConcurrency, notes)) changed = true;
                if (nodeHeapMiB <= 0) { notes.add("node heap limit not requested; left unchanged"); break; }
                if (hasPrefix(arguments, "--max-old-space-size") || environmentNames.contains("NODE_OPTIONS")) { notes.add("node heap limit already set; left unchanged"); break; }
                result.add(0, "--max-old-space-size=" + nodeHeapMiB); notes.add("node --max-old-space-size=" + nodeHeapMiB); changed = true; break;
            }
            default: break;
        }
        if (testConcurrency > 0 && !kind.equals("cargo") && !kind.equals("node"))
            notes.add("testConcurrency applies only to cargo test and node --test; left unchanged");
        return new Preview(result, notes, changed, applied);
    }

    /** Cargo options that take no value and may precede the subcommand; anything else there makes the subcommand ambiguous. */
    private static final Set<String> CARGO_GLOBAL_FLAGS = new HashSet<>(Arrays.asList(
        "-v", "-vv", "-vvv", "--verbose", "-q", "--quiet", "--offline", "--frozen", "--locked"));

    /**
     * Adds {@code --test-threads=N} to the libtest section of a direct {@code cargo test}/{@code cargo t}. {@code args} already
     * holds any {@code -j} this preset added. Returns true when it changed {@code args}; every skip is explained in {@code notes}.
     */
    private static boolean cargoTestThreads(List<String> args, List<String> environmentNames, int limit, List<String> notes) {
        int sub = -1;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("+") && i == 0) continue; // toolchain override such as +nightly
            if (CARGO_GLOBAL_FLAGS.contains(arg)) continue;
            if (arg.startsWith("-")) { notes.add("cargo options before the subcommand are ambiguous; test threads left unchanged"); return false; }
            sub = i; break;
        }
        if (sub < 0 || !(args.get(sub).equals("test") || args.get(sub).equals("t"))) {
            notes.add("testConcurrency: not a cargo test invocation; test threads left unchanged"); return false;
        }
        int separator = args.indexOf("--");
        List<String> cargoPart = separator < 0 ? args : args.subList(0, separator);
        if (cargoPart.contains("--no-run")) { notes.add("cargo test --no-run runs no tests; test threads left unchanged"); return false; }
        if (environmentNames.contains("RUST_TEST_THREADS")) { notes.add("RUST_TEST_THREADS already set; test threads left unchanged"); return false; }
        if (separator >= 0) {
            for (int i = separator + 1; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg.equals("--")) break; // after a second separator libtest takes plain filters
                if (arg.equals("--test-threads") || arg.startsWith("--test-threads=")) {
                    notes.add("--test-threads already set; left unchanged"); return false;
                }
            }
            args.add(separator + 1, "--test-threads=" + limit);
        } else {
            args.add("--"); args.add("--test-threads=" + limit);
        }
        notes.add("cargo test -- --test-threads=" + limit + " (standard libtest harness only; a target with harness = false or a custom runner may reject it)");
        return true;
    }

    /** Node options that consume the next argument when written without {@code =}. */
    private static final Set<String> NODE_VALUE_OPTIONS = new HashSet<>(Arrays.asList(
        "-r", "--require", "--import", "--loader", "--experimental-loader", "-e", "--eval", "-p", "--print", "--input-type",
        "-C", "--conditions", "--test-name-pattern", "--test-skip-pattern", "--test-reporter", "--test-reporter-destination",
        "--test-shard", "--watch-path", "--env-file", "--inspect-port", "--title", "--test-concurrency"));

    /**
     * Adds {@code --test-concurrency=N} right after {@code --test} when the test runner is selected among Node's own options
     * (before the script and before a {@code --}). Returns true when it changed {@code args}.
     */
    private static boolean nodeTestConcurrency(List<String> args, List<String> environmentNames, int limit, List<String> notes) {
        int test = -1; boolean existing = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--") || !arg.startsWith("-")) break; // the script or its arguments start here
            if (arg.equals("--test")) test = i;
            else if (arg.equals("--test-concurrency") || arg.startsWith("--test-concurrency=")) existing = true;
            if (NODE_VALUE_OPTIONS.contains(arg)) i++;
        }
        if (test < 0) { notes.add("testConcurrency: node --test is not among Node's own options; left unchanged"); return false; }
        if (existing) { notes.add("node --test-concurrency already set; left unchanged"); return false; }
        if (environmentNames.contains("NODE_OPTIONS")) { notes.add("NODE_OPTIONS is set and may conflict; node --test-concurrency left unchanged"); return false; }
        args.add(test + 1, "--test-concurrency=" + limit);
        notes.add("node --test-concurrency=" + limit + " (needs Node 18.19+, 20.10+ or 21+; limits concurrent test files, not threads inside tests)");
        return true;
    }

    private static String kind(String executable) {
        String name = executable.substring(executable.lastIndexOf('/') + 1);
        switch (name) {
            case "cargo": return "cargo";
            case "gradle": case "gradlew": return "gradle";
            case "ninja": return "ninja";
            case "make": case "gmake": return "make";
            case "node": case "nodejs": return "node";
            default: return null;
        }
    }

    private static boolean isCargoBuildCommand(List<String> args) {
        int at = firstNonOption(args);
        if (at < 0) return false;
        switch (args.get(at)) {
            case "build": case "b": case "check": case "c": case "test": case "t": case "run": case "r":
            case "clippy": case "doc": case "bench": case "install": return true;
            default: return false;
        }
    }

    /** Index of the first argument that is not an option (a toolchain override such as +nightly counts as an option). */
    private static int firstNonOption(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("-") || arg.startsWith("+")) continue;
            return i;
        }
        return -1;
    }

    /** True for {@code -j}, {@code -j4}, {@code --jobs}, {@code --jobs=4}; stops at {@code --}, after which arguments belong to another program. */
    private static boolean hasFlag(List<String> args, String... flags) {
        for (String arg : args) {
            if (arg.equals("--")) return false;
            for (String flag : flags) {
                if (arg.equals(flag) || (flag.startsWith("--") ? arg.startsWith(flag + "=") : arg.startsWith(flag) && arg.length() > flag.length()
                    && Character.isDigit(arg.charAt(flag.length())))) return true;
            }
        }
        return false;
    }

    private static boolean hasPrefix(List<String> args, String prefix) {
        for (String arg : args) { if (arg.equals("--")) return false; if (arg.startsWith(prefix)) return true; }
        return false;
    }
}

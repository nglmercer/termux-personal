package com.termux.app.control.jobs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        private Preview(List<String> arguments, List<String> notes, boolean changed) {
            this.arguments = Collections.unmodifiableList(arguments); this.notes = Collections.unmodifiableList(notes); this.changed = changed;
        }
    }

    /** Whether {@code executable} names a tool this class knows. */
    public static boolean isKnown(String executable) {
        return kind(executable) != null;
    }

    /**
     * @param executable the job executable, bare name or absolute path
     * @param arguments the user's arguments
     * @param environmentNames names (not values) of the job's environment variables, to avoid conflicting settings
     * @param workers recommended workers, or {@code <= 0} for none; capped at {@code cpuCount}
     * @param cpuCount available processors
     * @param nodeHeapMiB old-space limit for Node.js, or {@code <= 0} to leave Node alone
     */
    public static Preview apply(String executable, List<String> arguments, List<String> environmentNames,
                                int workers, int cpuCount, int nodeHeapMiB) {
        if (executable == null || arguments == null || environmentNames == null) throw new IllegalArgumentException("Invalid preset input");
        List<String> result = new ArrayList<>(arguments);
        List<String> notes = new ArrayList<>();
        String kind = kind(executable);
        if (kind == null) { notes.add("not a known build tool; command left unchanged"); return new Preview(result, notes, false); }
        int n = workers > 0 ? Math.min(workers, Math.max(1, cpuCount)) : 0;
        boolean changed = false;
        switch (kind) {
            case "cargo": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (!isCargoBuildCommand(arguments)) { notes.add("cargo subcommand does not compile; left unchanged"); break; }
                if (hasFlag(arguments, "-j", "--jobs") || environmentNames.contains("CARGO_BUILD_JOBS")) { notes.add("cargo job count already set; left unchanged"); break; }
                int at = firstNonOption(arguments) + 1; // right after the subcommand
                result.add(at, "-j"); result.add(at + 1, Integer.toString(n));
                notes.add("cargo -j " + n); changed = true; break;
            }
            case "gradle": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "--max-workers")) { notes.add("gradle --max-workers already set; left unchanged"); break; }
                result.add("--max-workers=" + n); notes.add("gradle --max-workers=" + n); changed = true; break;
            }
            case "ninja": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "-j")) { notes.add("ninja -j already set; left unchanged"); break; }
                result.add(0, "-j"); result.add(1, Integer.toString(n)); notes.add("ninja -j " + n); changed = true; break;
            }
            case "make": {
                if (n == 0) { notes.add("no worker recommendation for this profile"); break; }
                if (hasFlag(arguments, "-j", "--jobs") || environmentNames.contains("MAKEFLAGS")) { notes.add("make job count already set; left unchanged"); break; }
                result.add(0, "-j" + n); notes.add("make -j" + n); changed = true; break;
            }
            case "node": {
                if (nodeHeapMiB <= 0) { notes.add("node heap limit not requested; left unchanged"); break; }
                if (hasPrefix(arguments, "--max-old-space-size") || environmentNames.contains("NODE_OPTIONS")) { notes.add("node heap limit already set; left unchanged"); break; }
                result.add(0, "--max-old-space-size=" + nodeHeapMiB); notes.add("node --max-old-space-size=" + nodeHeapMiB); changed = true; break;
            }
            default: break;
        }
        return new Preview(result, notes, changed);
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

package com.termux.app.control;

import java.util.EnumSet;

/** Local UI consent selects scopes; peers cannot request or elevate them. */
public final class LocalControlOptions {
    private LocalControlOptions() { }
    public static EnumSet<ControlCore.Scope> scopes(boolean write, boolean output, boolean close) {
        return scopes(write, output, close, false);
    }
    public static EnumSet<ControlCore.Scope> scopes(boolean write, boolean output, boolean close, boolean resume) {
        EnumSet<ControlCore.Scope> result = EnumSet.of(ControlCore.Scope.SESSIONS_READ);
        if (write) result.add(ControlCore.Scope.SESSIONS_WRITE);
        if (output) result.add(ControlCore.Scope.SESSIONS_OUTPUT);
        if (output && resume) result.add(ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
        if (write && close) result.add(ControlCore.Scope.SESSIONS_CLOSE);
        return result;
    }

    /**
     * Stored form of a consent the user chose to remember: scope names separated by commas. Unknown names are
     * dropped and dependent scopes without their base (close without write, resume without output) are removed,
     * so a damaged or older value can only narrow access, never widen it. SESSIONS_READ is always included.
     */
    public static String encode(java.util.Set<ControlCore.Scope> scopes) {
        StringBuilder text = new StringBuilder();
        for (ControlCore.Scope scope : ControlCore.Scope.values())
            if (scopes.contains(scope)) text.append(text.length() == 0 ? "" : ",").append(scope.name());
        return text.toString();
    }

    public static EnumSet<ControlCore.Scope> decode(String stored) {
        EnumSet<ControlCore.Scope> result = EnumSet.of(ControlCore.Scope.SESSIONS_READ);
        if (stored == null) return result;
        for (String name : stored.split(",")) {
            for (ControlCore.Scope scope : ControlCore.Scope.values()) if (scope.name().equals(name.trim())) result.add(scope);
        }
        if (!result.contains(ControlCore.Scope.SESSIONS_WRITE)) result.remove(ControlCore.Scope.SESSIONS_CLOSE);
        if (!result.contains(ControlCore.Scope.SESSIONS_OUTPUT)) result.remove(ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
        return result;
    }

    /**
     * Separate, explicit managed-job consent. Each choice is independent; none is implied by session
     * consent. Execute runs commands with the app UID and can modify app-owned files.
     */
    public static EnumSet<ControlCore.Scope> jobScopes(boolean read, boolean output, boolean execute, boolean cancel) {
        EnumSet<ControlCore.Scope> result = EnumSet.noneOf(ControlCore.Scope.class);
        if (read) result.add(ControlCore.Scope.JOBS_READ);
        if (output) result.add(ControlCore.Scope.JOBS_OUTPUT);
        if (execute) result.add(ControlCore.Scope.JOBS_EXECUTE);
        if (cancel) result.add(ControlCore.Scope.JOBS_CANCEL);
        return result;
    }
}

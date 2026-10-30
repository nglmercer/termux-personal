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

    /** Simplified consent groups for the quick dialog; the settings screen keeps per-scope control. */
    public enum Group {
        TERMINAL(true, ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME),
        COMMANDS(false, ControlCore.Scope.JOBS_READ, ControlCore.Scope.JOBS_OUTPUT, ControlCore.Scope.JOBS_EXECUTE, ControlCore.Scope.JOBS_CANCEL),
        FILES(true, ControlCore.Scope.FILES_READ, ControlCore.Scope.FILES_WRITE),
        PROCESSES(true, ControlCore.Scope.PROCESSES_READ, ControlCore.Scope.PROCESSES_SIGNAL),
        WORKSPACES(true, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.WORKSPACES_WRITE);

        /** Pre-checked when nothing was chosen before. Running commands is never pre-checked. */
        public final boolean recommended;
        public final java.util.Set<ControlCore.Scope> scopes;
        Group(boolean recommended, ControlCore.Scope... scopes) {
            this.recommended = recommended;
            this.scopes = java.util.Collections.unmodifiableSet(EnumSet.of(scopes[0], scopes));
        }

        /** A group counts as on when any of its scopes is granted. */
        public boolean enabledIn(java.util.Set<ControlCore.Scope> granted) {
            for (ControlCore.Scope scope : scopes) if (granted.contains(scope)) return true;
            return false;
        }
    }

    /**
     * Applies quick-dialog choices. A group the user did not change keeps its exact scopes, so a custom
     * selection made on the settings screen survives; a changed group is granted or removed as a whole.
     */
    public static EnumSet<ControlCore.Scope> applyGroups(java.util.Set<ControlCore.Scope> current,
                                                          java.util.Map<Group, Boolean> chosen) {
        EnumSet<ControlCore.Scope> result = current.isEmpty() ? EnumSet.noneOf(ControlCore.Scope.class) : EnumSet.copyOf(current);
        result.add(ControlCore.Scope.SESSIONS_READ);
        for (java.util.Map.Entry<Group, Boolean> entry : chosen.entrySet()) {
            Group group = entry.getKey();
            if (entry.getValue() == group.enabledIn(current)) continue;
            if (entry.getValue()) result.addAll(group.scopes); else result.removeAll(group.scopes);
            if (group == Group.TERMINAL && !entry.getValue()) result.remove(ControlCore.Scope.SESSIONS_CLOSE);
        }
        return decode(encode(result));
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

package com.termux.app.control.ipc;

import com.termux.app.control.ControlCore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Discovery policy for implemented v1 methods. Authorization remains enforced by handlers. */
final class ControlMethodRegistry {
    static final class Descriptor {
        final String name, binary;
        final boolean mutates, output;
        final List<ControlCore.Scope> scopes;
        Descriptor(String name, boolean mutates, String binary, boolean output, ControlCore.Scope... scopes) {
            this.name = name; this.mutates = mutates; this.binary = binary; this.output = output;
            this.scopes = Collections.unmodifiableList(Arrays.asList(scopes));
        }
        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (ControlCore.Scope scope : scopes) required.add(scope.name());
            result.put("name", name); result.put("scope", required.isEmpty() ? null : required.get(0));
            result.put("scopes", required); result.put("mutates", mutates);
            result.put("binary", !binary.equals("none")); result.put("binaryDirection", binary);
            result.put("available", true); return result;
        }
    }
    private static final List<Descriptor> METHODS = Collections.unmodifiableList(Arrays.asList(
        new Descriptor("system.hello", false, "none", false),
        new Descriptor("system.capabilities", false, "none", false),
        new Descriptor("system.describe", false, "none", false),
        new Descriptor("session.list", false, "none", false, ControlCore.Scope.SESSIONS_READ),
        new Descriptor("session.create", true, "none", false, ControlCore.Scope.SESSIONS_WRITE),
        new Descriptor("session.write", true, "request", false, ControlCore.Scope.SESSIONS_WRITE),
        new Descriptor("session.rename", true, "none", false, ControlCore.Scope.SESSIONS_WRITE),
        new Descriptor("session.resize", true, "none", false, ControlCore.Scope.SESSIONS_WRITE),
        new Descriptor("session.focus", true, "none", false, ControlCore.Scope.SESSIONS_WRITE),
        new Descriptor("session.snapshot", false, "none", false, ControlCore.Scope.SESSIONS_OUTPUT),
        new Descriptor("session.close", true, "none", false, ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_CLOSE),
        new Descriptor("session.output.subscribe", true, "none", true, ControlCore.Scope.SESSIONS_OUTPUT),
        new Descriptor("session.output.read", false, "response", true, ControlCore.Scope.SESSIONS_OUTPUT),
        new Descriptor("session.output.unsubscribe", true, "none", true, ControlCore.Scope.SESSIONS_OUTPUT),
        new Descriptor("session.output.resume", true, "none", true, ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME),
        new Descriptor("job.start", true, "none", false, ControlCore.Scope.JOBS_EXECUTE),
        new Descriptor("job.list", false, "none", false, ControlCore.Scope.JOBS_READ),
        new Descriptor("job.get", false, "none", false, ControlCore.Scope.JOBS_READ),
        new Descriptor("job.output.read", false, "response", false, ControlCore.Scope.JOBS_OUTPUT),
        new Descriptor("job.cancel", true, "none", false, ControlCore.Scope.JOBS_CANCEL),
        new Descriptor("process.list", false, "none", false, ControlCore.Scope.PROCESSES_READ),
        new Descriptor("process.signal", true, "none", false, ControlCore.Scope.PROCESSES_SIGNAL),
        new Descriptor("file.roots", false, "none", false, ControlCore.Scope.FILES_READ),
        new Descriptor("file.list", false, "none", false, ControlCore.Scope.FILES_READ),
        new Descriptor("file.stat", false, "none", false, ControlCore.Scope.FILES_READ),
        new Descriptor("file.read", false, "response", false, ControlCore.Scope.FILES_READ),
        new Descriptor("file.mkdir", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.rename", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.delete", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.transfer.begin", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.transfer.write", true, "request", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.transfer.commit", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("file.transfer.abort", true, "none", false, ControlCore.Scope.FILES_WRITE),
        new Descriptor("workspace.list", false, "none", false, ControlCore.Scope.WORKSPACES_READ),
        new Descriptor("workspace.get", false, "none", false, ControlCore.Scope.WORKSPACES_READ),
        new Descriptor("workspace.create", true, "none", false, ControlCore.Scope.WORKSPACES_WRITE),
        new Descriptor("workspace.update", true, "none", false, ControlCore.Scope.WORKSPACES_WRITE),
        new Descriptor("workspace.remove", true, "none", false, ControlCore.Scope.WORKSPACES_WRITE),
        new Descriptor("workspace.select", true, "none", false, ControlCore.Scope.WORKSPACES_WRITE),
        new Descriptor("workspace.tasks", false, "none", false, ControlCore.Scope.WORKSPACES_READ),
        new Descriptor("workspace.task.run", true, "none", false, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.JOBS_EXECUTE),
        new Descriptor("workspace.git", true, "none", false, ControlCore.Scope.WORKSPACES_READ, ControlCore.Scope.JOBS_EXECUTE),
        new Descriptor("workspace.git.change", true, "none", false, ControlCore.Scope.WORKSPACES_WRITE, ControlCore.Scope.JOBS_EXECUTE)
    ));

    /** Feature names the server has actually wired: "output", "jobs", "processes" and "files". */
    static List<Descriptor> available(ControlCore core, Set<ControlCore.Scope> scopes, Set<String> features) {
        List<String> coreMethods = core.capabilities();
        List<Descriptor> result = new ArrayList<>();
        for (Descriptor descriptor : METHODS) {
            String feature = featureOf(descriptor.name);
            if (feature != null) {
                if (!features.contains(feature)) continue;
                // Running a preset needs both the workspace store and the job coordinator.
                if ((descriptor.name.equals("workspace.task.run") || descriptor.name.startsWith("workspace.git")) && !features.contains("jobs")) continue;
            } else if (!descriptor.name.equals("system.describe") && !descriptor.output && !coreMethods.contains(descriptor.name)) {
                continue;
            } else if (descriptor.output && !features.contains("output")) {
                continue;
            }
            if (!descriptor.scopes.isEmpty() && (scopes == null || !scopes.containsAll(descriptor.scopes))) continue;
            result.add(descriptor);
        }
        return result;
    }
    static List<Descriptor> all() { return METHODS; }

    /**
     * Job, process and file methods resolve paths, read /proc or touch storage. They run on the connection
     * worker, never through the service-thread dispatch used for session state.
     */
    static boolean runsOffServiceThread(String method) { return featureOf(method) != null; }

    /** Domain feature that gates a method, or null for core and session methods. */
    private static String featureOf(String method) {
        // process.signal acts on job handles, so the job coordinator gates it, not the /proc sampler.
        if (method.startsWith("job.") || method.equals("process.signal")) return "jobs";
        if (method.startsWith("process.")) return "processes";
        if (method.startsWith("file.")) return "files";
        if (method.startsWith("workspace.")) return "workspaces";
        return null;
    }
    private ControlMethodRegistry() { }
}

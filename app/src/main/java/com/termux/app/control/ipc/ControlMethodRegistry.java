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
        new Descriptor("session.output.resume", true, "none", true, ControlCore.Scope.SESSIONS_OUTPUT, ControlCore.Scope.SESSIONS_OUTPUT_RESUME)
    ));

    static List<Descriptor> available(ControlCore core, Set<ControlCore.Scope> scopes, boolean outputAvailable) {
        List<String> coreMethods = core.capabilities();
        List<Descriptor> result = new ArrayList<>();
        for (Descriptor descriptor : METHODS) {
            if (descriptor.output ? !outputAvailable :
                !descriptor.name.equals("system.describe") && !coreMethods.contains(descriptor.name)) continue;
            if (!descriptor.scopes.isEmpty() && (scopes == null || !scopes.containsAll(descriptor.scopes))) continue;
            result.add(descriptor);
        }
        return result;
    }
    static List<Descriptor> all() { return METHODS; }
    private ControlMethodRegistry() { }
}

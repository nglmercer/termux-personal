package com.termux.app.control.workspace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.files.FileService;
import com.termux.app.control.jobs.JobResourcePolicy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, app-private workspace definitions. A workspace names a directory under a configured file root.
 * It does not change process namespace, filesystem permissions, or any session's working directory. Writes are
 * atomic (temporary file, fsync, rename). Unreadable metadata is moved aside and the store starts empty.
 * Operations perform file I/O and must run on a worker thread.
 */
public final class WorkspaceRepository {
    public static final int MAX_WORKSPACES = 64, MAX_PRESETS = 16, MAX_ARGS = 32, MAX_NAME = 80, MAX_LABEL = 80,
        MAX_SERVICES = 8, MAX_PORTS = 8;
    public static final long MAX_TIMEOUT_MILLIS = 24L * 60 * 60 * 1000;
    private static final int VERSION = 1;

    /** Codes are reserved v1 domain names. */
    public static final class WorkspaceException extends Exception {
        public final String code;
        WorkspaceException(String code) { super(code); this.code = code; }
    }

    /** A preset is data only. Nothing runs when a preset is listed or stored. */
    public static final class TaskPreset {
        public final String id, label, executable;
        public final List<String> args;
        public final long timeoutMillis;
        /** Optional resource policy for jobs run from this preset; null keeps the historical behavior. */
        public final JobResourcePolicy resources;
        public TaskPreset(String id, String label, String executable, List<String> args, long timeoutMillis) throws WorkspaceException {
            this(id, label, executable, args, timeoutMillis, null);
        }
        public TaskPreset(String id, String label, String executable, List<String> args, long timeoutMillis, JobResourcePolicy resources) throws WorkspaceException {
            if (!validPresetId(id) || !validText(label, MAX_LABEL, false) || !validExecutable(executable) || args == null ||
                args.size() > MAX_ARGS || timeoutMillis < 0 || timeoutMillis > MAX_TIMEOUT_MILLIS) throw new WorkspaceException("INVALID_REQUEST");
            for (String arg : args) if (!validText(arg, 4096, true)) throw new WorkspaceException("INVALID_REQUEST");
            this.id = id; this.label = label; this.executable = executable;
            this.args = Collections.unmodifiableList(new ArrayList<>(args)); this.timeoutMillis = timeoutMillis; this.resources = resources;
        }
    }

    public enum RestartPolicy {
        NEVER("never"), ON_FAILURE("on-failure"), ALWAYS("always");
        public final String wireName;
        RestartPolicy(String wireName) { this.wireName = wireName; }
        public static RestartPolicy parse(String value) throws WorkspaceException {
            for (RestartPolicy policy : values()) if (policy.wireName.equals(value)) return policy;
            throw new WorkspaceException("INVALID_REQUEST");
        }
    }

    /**
     * An approved long-running development service. Data only: nothing runs until a client starts it, and nothing is ever
     * started automatically after a restart. The default restart policy is never.
     */
    public static final class ServiceDefinition {
        public final String id, executable;
        public final List<String> args;
        public final List<Integer> ports;
        public final RestartPolicy restartPolicy;
        public final JobResourcePolicy resources;
        public ServiceDefinition(String id, String executable, List<String> args, RestartPolicy restartPolicy,
                                 JobResourcePolicy resources, List<Integer> ports) throws WorkspaceException {
            if (!validPresetId(id) || !validExecutable(executable) || args == null || args.size() > MAX_ARGS || restartPolicy == null ||
                ports == null || ports.size() > MAX_PORTS) throw new WorkspaceException("INVALID_REQUEST");
            for (String arg : args) if (!validText(arg, 4096, true)) throw new WorkspaceException("INVALID_REQUEST");
            for (Integer port : ports) if (port == null || port < 1 || port > 65535) throw new WorkspaceException("INVALID_REQUEST");
            if (new java.util.HashSet<>(ports).size() != ports.size()) throw new WorkspaceException("INVALID_REQUEST");
            this.id = id; this.executable = executable; this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.restartPolicy = restartPolicy; this.resources = resources; this.ports = Collections.unmodifiableList(new ArrayList<>(ports));
        }
    }

    public static final class Workspace {
        public final String id, name, rootId, path;
        public final long createdAt, updatedAt;
        public final List<TaskPreset> taskPresets;
        /** Optional default profile for this workspace; null means none, which keeps the historical behavior. */
        public final JobResourcePolicy.Profile resourceProfile;
        public final List<ServiceDefinition> services;
        /** False when the root or directory no longer resolves. Writes and selection refuse such a workspace. */
        public final boolean available;
        Workspace(String id, String name, String rootId, String path, long createdAt, long updatedAt, List<TaskPreset> presets, boolean available) {
            this(id, name, rootId, path, createdAt, updatedAt, presets, available, null, Collections.<ServiceDefinition>emptyList());
        }
        Workspace(String id, String name, String rootId, String path, long createdAt, long updatedAt, List<TaskPreset> presets, boolean available,
                  JobResourcePolicy.Profile resourceProfile, List<ServiceDefinition> services) {
            this.resourceProfile = resourceProfile; this.services = Collections.unmodifiableList(new ArrayList<>(services));
            this.id = id; this.name = name; this.rootId = rootId; this.path = path;
            this.createdAt = createdAt; this.updatedAt = updatedAt; this.taskPresets = Collections.unmodifiableList(new ArrayList<>(presets));
            this.available = available;
        }
    }

    private final File mFile;
    private final FileService mFiles;
    private final SecureRandom mRandom = new SecureRandom();
    private final List<Workspace> mWorkspaces = new ArrayList<>();
    private String mActive;
    private boolean mRecovered;

    public WorkspaceRepository(File file, FileService files) throws WorkspaceException {
        if (file == null || files == null) throw new IllegalArgumentException("Missing workspace storage");
        mFile = file; mFiles = files;
        load();
    }

    /** True when unreadable metadata was moved aside during construction. */
    public synchronized boolean recoveredFromCorruption() { return mRecovered; }

    public synchronized List<Workspace> list() {
        List<Workspace> result = new ArrayList<>();
        for (Workspace workspace : mWorkspaces) result.add(withAvailability(workspace));
        return Collections.unmodifiableList(result);
    }

    public synchronized String activeId() { return mActive; }

    public synchronized Workspace get(String id) throws WorkspaceException {
        return withAvailability(find(id));
    }

    public synchronized Workspace create(String name, String rootId, String path, List<TaskPreset> presets) throws WorkspaceException {
        return create(name, rootId, path, presets, null, Collections.<ServiceDefinition>emptyList());
    }

    public synchronized Workspace create(String name, String rootId, String path, List<TaskPreset> presets,
                                         JobResourcePolicy.Profile profile, List<ServiceDefinition> services) throws WorkspaceException {
        if (services == null || services.size() > MAX_SERVICES || hasDuplicateIds(services)) throw new WorkspaceException("INVALID_REQUEST");
        if (!validText(name, MAX_NAME, false) || presets == null || presets.size() > MAX_PRESETS) throw new WorkspaceException("INVALID_REQUEST");
        if (mWorkspaces.size() >= MAX_WORKSPACES) throw new WorkspaceException("LIMIT_REACHED");
        requireDirectory(rootId, path);
        long now = System.currentTimeMillis();
        Workspace created = new Workspace(newId(), name, rootId, path, now, now, presets, true, profile, services);
        mWorkspaces.add(created);
        persist();
        return created;
    }

    /** Null arguments keep the current value. A changed location is validated again before it is stored. */
    public synchronized Workspace update(String id, String name, String rootId, String path, List<TaskPreset> presets) throws WorkspaceException {
        return update(id, name, rootId, path, presets, false, null, null);
    }

    /** {@code setProfile} distinguishes "leave the profile" from "clear it" (profile null). Null services keep the current ones. */
    public synchronized Workspace update(String id, String name, String rootId, String path, List<TaskPreset> presets,
                                         boolean setProfile, JobResourcePolicy.Profile profile, List<ServiceDefinition> services) throws WorkspaceException {
        int index = indexOf(id);
        Workspace current = mWorkspaces.get(index);
        String newName = name == null ? current.name : name;
        String newRoot = rootId == null ? current.rootId : rootId;
        String newPath = path == null ? current.path : path;
        List<TaskPreset> newPresets = presets == null ? current.taskPresets : presets;
        if (!validText(newName, MAX_NAME, false) || newPresets.size() > MAX_PRESETS) throw new WorkspaceException("INVALID_REQUEST");
        if (!newRoot.equals(current.rootId) || !newPath.equals(current.path)) requireDirectory(newRoot, newPath);
        List<ServiceDefinition> newServices = services == null ? current.services : services;
        if (newServices.size() > MAX_SERVICES || hasDuplicateIds(newServices)) throw new WorkspaceException("INVALID_REQUEST");
        Workspace updated = new Workspace(current.id, newName, newRoot, newPath, current.createdAt, System.currentTimeMillis(), newPresets, true,
            setProfile ? profile : current.resourceProfile, newServices);
        mWorkspaces.set(index, updated);
        persist();
        return updated;
    }

    public synchronized void remove(String id) throws WorkspaceException {
        int index = indexOf(id);
        mWorkspaces.remove(index);
        if (id.equals(mActive)) mActive = null;
        persist();
    }

    /** Selection changes only this store's active pointer. It never changes any running session. */
    public synchronized Workspace select(String id) throws WorkspaceException {
        Workspace workspace = find(id);
        requireDirectory(workspace.rootId, workspace.path);
        mActive = id;
        persist();
        return workspace;
    }

    /** Canonical directory of a workspace whose root and location still resolve. Used by job path policy only. */
    public synchronized File directory(String id) throws WorkspaceException {
        Workspace workspace = find(id);
        try { return mFiles.directory(workspace.rootId, workspace.path); }
        catch (FileService.FileException e) { throw new WorkspaceException("UNAVAILABLE"); }
    }

    public synchronized List<TaskPreset> tasks(String id) throws WorkspaceException {
        return find(id).taskPresets;
    }

    private Workspace find(String id) throws WorkspaceException {
        return mWorkspaces.get(indexOf(id));
    }

    private int indexOf(String id) throws WorkspaceException {
        if (!validId(id)) throw new WorkspaceException("INVALID_REQUEST");
        for (int i = 0; i < mWorkspaces.size(); i++) if (mWorkspaces.get(i).id.equals(id)) return i;
        throw new WorkspaceException("NOT_FOUND");
    }

    private static boolean hasDuplicateIds(List<ServiceDefinition> services) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ServiceDefinition service : services) if (!ids.add(service.id)) return true;
        return false;
    }

    /** The portable definition of a workspace (name, tasks, services, profile). No path, root or timestamp: it moves between devices. */
    public synchronized byte[] exportConfig(String id) throws WorkspaceException {
        JsonObject item = toJson(find(id));
        item.remove("id"); item.remove("rootId"); item.remove("path"); item.remove("createdAt"); item.remove("updatedAt");
        return item.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Applies a definition produced by {@link #exportConfig}: tasks, services and profile are replaced; name, location and
     * identity are kept. Invalid content is refused as a whole and nothing changes.
     */
    public synchronized Workspace applyConfig(String id, byte[] config) throws WorkspaceException {
        if (config == null || config.length > 1024 * 1024) throw new WorkspaceException("INVALID_REQUEST");
        Workspace current = find(id);
        final Workspace parsed;
        try {
            JsonObject item = JsonParser.parseString(new String(config, StandardCharsets.UTF_8)).getAsJsonObject();
            item.addProperty("id", current.id); item.addProperty("rootId", current.rootId); item.addProperty("path", current.path);
            item.addProperty("createdAt", current.createdAt); item.addProperty("updatedAt", current.updatedAt);
            if (!item.has("name")) item.addProperty("name", current.name);
            parsed = parseWorkspace(item);
        } catch (WorkspaceException e) { throw e;
        } catch (RuntimeException e) { throw new WorkspaceException("INVALID_REQUEST"); }
        if (parsed.taskPresets.size() > MAX_PRESETS || parsed.services.size() > MAX_SERVICES || hasDuplicateIds(parsed.services)) throw new WorkspaceException("INVALID_REQUEST");
        return update(id, null, null, null, parsed.taskPresets, true, parsed.resourceProfile, parsed.services);
    }

    /** One approved service of a workspace. */
    public synchronized ServiceDefinition service(String workspaceId, String serviceId) throws WorkspaceException {
        for (ServiceDefinition service : find(workspaceId).services) if (service.id.equals(serviceId)) return service;
        throw new WorkspaceException("NOT_FOUND");
    }

    private Workspace withAvailability(Workspace workspace) {
        boolean available;
        try { requireDirectory(workspace.rootId, workspace.path); available = true; }
        catch (WorkspaceException e) { available = false; }
        return new Workspace(workspace.id, workspace.name, workspace.rootId, workspace.path, workspace.createdAt,
            workspace.updatedAt, workspace.taskPresets, available, workspace.resourceProfile, workspace.services);
    }

    /** Validates the location through the file service, so the same path rules apply everywhere. */
    private void requireDirectory(String rootId, String path) throws WorkspaceException {
        try {
            mFiles.list(rootId, path, 0, 1);
        } catch (FileService.FileException e) {
            throw new WorkspaceException(e.code.equals("NOT_FOUND") ? "UNAVAILABLE" : "INVALID_REQUEST");
        }
    }

    private String newId() {
        byte[] bytes = new byte[8];
        mRandom.nextBytes(bytes);
        StringBuilder id = new StringBuilder("ws_");
        for (byte b : bytes) id.append(String.format("%02x", b & 0xff));
        return id.toString();
    }

    private void load() throws WorkspaceException {
        if (!mFile.exists()) return;
        try {
            byte[] bytes = readAll(mFile);
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != VERSION) throw new IllegalStateException("Unsupported version");
            JsonArray items = root.getAsJsonArray("workspaces");
            if (items.size() > MAX_WORKSPACES) throw new IllegalStateException("Too many workspaces");
            for (JsonElement item : items) mWorkspaces.add(parseWorkspace(item.getAsJsonObject()));
            mActive = root.get("active").isJsonNull() ? null : root.get("active").getAsString();
            if (mActive != null) indexOf(mActive);
        } catch (Exception e) {
            // Unreadable metadata is preserved for inspection instead of being silently overwritten.
            mWorkspaces.clear();
            mActive = null;
            mRecovered = true;
            File moved = new File(mFile.getParentFile(), mFile.getName() + ".corrupt-" + System.currentTimeMillis());
            if (!mFile.renameTo(moved)) throw new WorkspaceException("UNAVAILABLE");
        }
    }

    private static byte[] readAll(File file) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            if (file.length() > 1024 * 1024) throw new IOException("Workspace metadata too large");
            byte[] bytes = new byte[(int) file.length()];
            int total = 0;
            while (total < bytes.length) {
                int read = in.read(bytes, total, bytes.length - total);
                if (read < 0) break;
                total += read;
            }
            return total == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, total);
        } finally {
            in.close();
        }
    }

    private Workspace parseWorkspace(JsonObject item) throws WorkspaceException {
        List<TaskPreset> presets = new ArrayList<>();
        for (JsonElement preset : item.getAsJsonArray("taskPresets")) {
            JsonObject p = preset.getAsJsonObject();
            List<String> args = new ArrayList<>();
            for (JsonElement arg : p.getAsJsonArray("args")) args.add(arg.getAsString());
            presets.add(new TaskPreset(p.get("id").getAsString(), p.get("label").getAsString(), p.get("executable").getAsString(),
                args, p.get("timeoutMs").getAsLong(), readResources(p)));
        }
        // Optional fields added after v1 shipped. Their absence is the historical behavior, so v1 files load unchanged.
        JobResourcePolicy.Profile profile = null;
        try {
            if (item.has("resourceProfile") && !item.get("resourceProfile").isJsonNull()) profile = JobResourcePolicy.Profile.parse(item.get("resourceProfile").getAsString());
        } catch (RuntimeException e) { profile = null; } // an unknown profile must not make the workspace unreadable
        List<ServiceDefinition> services = new ArrayList<>();
        if (item.has("services") && item.get("services").isJsonArray()) for (JsonElement element : item.getAsJsonArray("services")) {
            try {
                JsonObject sv = element.getAsJsonObject();
                List<String> sargs = new ArrayList<>();
                for (JsonElement arg : sv.getAsJsonArray("args")) sargs.add(arg.getAsString());
                List<Integer> ports = new ArrayList<>();
                if (sv.has("ports")) for (JsonElement port : sv.getAsJsonArray("ports")) ports.add(port.getAsInt());
                services.add(new ServiceDefinition(sv.get("id").getAsString(), sv.get("executable").getAsString(), sargs,
                    RestartPolicy.parse(sv.has("restartPolicy") ? sv.get("restartPolicy").getAsString() : "never"), readResources(sv), ports));
            } catch (WorkspaceException | RuntimeException e) { /* skip only the unreadable service; the workspace and its tasks survive */ }
            if (services.size() >= MAX_SERVICES) break;
        }
        String id = item.get("id").getAsString();
        if (!validId(id)) throw new WorkspaceException("INVALID_REQUEST");
        return new Workspace(id, item.get("name").getAsString(), item.get("rootId").getAsString(), item.get("path").getAsString(),
            item.get("createdAt").getAsLong(), item.get("updatedAt").getAsLong(), presets, true, profile, services);
    }

    private static JobResourcePolicy readResources(JsonObject owner) {
        try {
            return owner.has("resources") && owner.get("resources").isJsonObject() ? JobResourcePolicy.fromJson(owner.getAsJsonObject("resources")) : null;
        } catch (RuntimeException e) { return null; } // an invalid stored policy means "no policy", never a lost workspace
    }

    private void persist() throws WorkspaceException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("active", mActive);
        JsonArray items = new JsonArray();
        for (Workspace workspace : mWorkspaces) items.add(toJson(workspace));
        root.add("workspaces", items);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        File temp = new File(mFile.getParentFile(), mFile.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            throw new WorkspaceException("UNAVAILABLE");
        }
        if (!temp.renameTo(mFile)) throw new WorkspaceException("UNAVAILABLE");
    }

    private static JsonObject toJson(Workspace workspace) {
        JsonObject item = new JsonObject();
        item.addProperty("id", workspace.id);
        item.addProperty("name", workspace.name);
        item.addProperty("rootId", workspace.rootId);
        item.addProperty("path", workspace.path);
        item.addProperty("createdAt", workspace.createdAt);
        item.addProperty("updatedAt", workspace.updatedAt);
        JsonArray presets = new JsonArray();
        for (TaskPreset preset : workspace.taskPresets) {
            JsonObject p = new JsonObject();
            p.addProperty("id", preset.id);
            p.addProperty("label", preset.label);
            p.addProperty("executable", preset.executable);
            JsonArray args = new JsonArray();
            for (String arg : preset.args) args.add(arg);
            p.add("args", args);
            p.addProperty("timeoutMs", preset.timeoutMillis);
            if (preset.resources != null) p.add("resources", preset.resources.toJson());
            presets.add(p);
        }
        item.add("taskPresets", presets);
        if (workspace.resourceProfile != null) item.addProperty("resourceProfile", workspace.resourceProfile.wireName());
        if (!workspace.services.isEmpty()) {
            JsonArray services = new JsonArray();
            for (ServiceDefinition service : workspace.services) {
                JsonObject sv = new JsonObject();
                sv.addProperty("id", service.id); sv.addProperty("executable", service.executable);
                JsonArray sargs = new JsonArray(); for (String arg : service.args) sargs.add(arg);
                sv.add("args", sargs);
                sv.addProperty("restartPolicy", service.restartPolicy.wireName);
                JsonArray ports = new JsonArray(); for (Integer port : service.ports) ports.add(port);
                sv.add("ports", ports);
                if (service.resources != null) sv.add("resources", service.resources.toJson());
                services.add(sv);
            }
            item.add("services", services);
        }
        return item;
    }

    static boolean validId(String id) { return id != null && id.matches("ws_[0-9a-f]{16}"); }

    static boolean validPresetId(String id) { return id != null && id.matches("[a-z0-9_-]{1,64}"); }

    static boolean validText(String text, int maxChars, boolean emptyAllowed) {
        if (text == null || (!emptyAllowed && text.isEmpty()) || text.length() > maxChars) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
        }
        return true;
    }

    /** Absolute normalized paths, or a bare name resolved by the job policy later. Never a shell string. */
    static boolean validExecutable(String executable) {
        if (executable == null || executable.isEmpty() || executable.length() > 4096 || executable.indexOf('\0') >= 0) return false;
        if (executable.startsWith("/")) return !executable.endsWith("/") && !executable.contains("//") && !executable.matches(".*(^|/)\\.\\.?(/|$).*");
        return executable.matches("[A-Za-z0-9_+.-]+") && !executable.equals(".") && !executable.equals("..");
    }
}

package com.termux.app.control.workspace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.files.FileService;
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
    public static final int MAX_WORKSPACES = 64, MAX_PRESETS = 16, MAX_ARGS = 32, MAX_NAME = 80, MAX_LABEL = 80;
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
        public TaskPreset(String id, String label, String executable, List<String> args, long timeoutMillis) throws WorkspaceException {
            if (!validPresetId(id) || !validText(label, MAX_LABEL, false) || !validExecutable(executable) || args == null ||
                args.size() > MAX_ARGS || timeoutMillis < 0 || timeoutMillis > MAX_TIMEOUT_MILLIS) throw new WorkspaceException("INVALID_REQUEST");
            for (String arg : args) if (!validText(arg, 4096, true)) throw new WorkspaceException("INVALID_REQUEST");
            this.id = id; this.label = label; this.executable = executable;
            this.args = Collections.unmodifiableList(new ArrayList<>(args)); this.timeoutMillis = timeoutMillis;
        }
    }

    public static final class Workspace {
        public final String id, name, rootId, path;
        public final long createdAt, updatedAt;
        public final List<TaskPreset> taskPresets;
        /** False when the root or directory no longer resolves. Writes and selection refuse such a workspace. */
        public final boolean available;
        Workspace(String id, String name, String rootId, String path, long createdAt, long updatedAt, List<TaskPreset> presets, boolean available) {
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
        if (!validText(name, MAX_NAME, false) || presets == null || presets.size() > MAX_PRESETS) throw new WorkspaceException("INVALID_REQUEST");
        if (mWorkspaces.size() >= MAX_WORKSPACES) throw new WorkspaceException("LIMIT_REACHED");
        requireDirectory(rootId, path);
        long now = System.currentTimeMillis();
        Workspace created = new Workspace(newId(), name, rootId, path, now, now, presets, true);
        mWorkspaces.add(created);
        persist();
        return created;
    }

    /** Null arguments keep the current value. A changed location is validated again before it is stored. */
    public synchronized Workspace update(String id, String name, String rootId, String path, List<TaskPreset> presets) throws WorkspaceException {
        int index = indexOf(id);
        Workspace current = mWorkspaces.get(index);
        String newName = name == null ? current.name : name;
        String newRoot = rootId == null ? current.rootId : rootId;
        String newPath = path == null ? current.path : path;
        List<TaskPreset> newPresets = presets == null ? current.taskPresets : presets;
        if (!validText(newName, MAX_NAME, false) || newPresets.size() > MAX_PRESETS) throw new WorkspaceException("INVALID_REQUEST");
        if (!newRoot.equals(current.rootId) || !newPath.equals(current.path)) requireDirectory(newRoot, newPath);
        Workspace updated = new Workspace(current.id, newName, newRoot, newPath, current.createdAt, System.currentTimeMillis(), newPresets, true);
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

    private Workspace withAvailability(Workspace workspace) {
        boolean available;
        try { requireDirectory(workspace.rootId, workspace.path); available = true; }
        catch (WorkspaceException e) { available = false; }
        return new Workspace(workspace.id, workspace.name, workspace.rootId, workspace.path, workspace.createdAt,
            workspace.updatedAt, workspace.taskPresets, available);
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
                args, p.get("timeoutMs").getAsLong()));
        }
        String id = item.get("id").getAsString();
        if (!validId(id)) throw new WorkspaceException("INVALID_REQUEST");
        return new Workspace(id, item.get("name").getAsString(), item.get("rootId").getAsString(), item.get("path").getAsString(),
            item.get("createdAt").getAsLong(), item.get("updatedAt").getAsLong(), presets, true);
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
            presets.add(p);
        }
        item.add("taskPresets", presets);
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

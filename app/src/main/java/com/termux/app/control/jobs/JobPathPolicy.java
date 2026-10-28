package com.termux.app.control.jobs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Worker-only configuration and path checks for privileged managed execution. */
public final class JobPathPolicy implements JobCoordinator.Policy {
    private static final int MAX_ROOTS = 8, MAX_NAMES = 128, MAX_PATH = 4096;
    private final List<Root> mExecutableRoots, mWorkingRoots;
    private final Map<String, String> mNamedExecutables;
    private final String mDefaultCwd;

    /** Resolves and pins configured roots; construction performs filesystem I/O off the main thread. */
    public JobPathPolicy(List<String> executableRoots, List<String> workingRoots, String defaultCwd,
                         Map<String, String> namedExecutables) throws IOException {
        if (namedExecutables == null || namedExecutables.size() > MAX_NAMES || !validPath(defaultCwd))
            throw new IllegalArgumentException("Invalid job path policy");
        mExecutableRoots = roots(executableRoots);
        mWorkingRoots = roots(workingRoots);
        mDefaultCwd = defaultCwd;
        Map<String, String> names = new HashMap<>();
        for (Map.Entry<String, String> entry : namedExecutables.entrySet()) {
            String name = entry.getKey(), path = entry.getValue();
            if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,128}") || name.equals(".") || name.equals("..") || !validPath(path))
                throw new IllegalArgumentException("Invalid named executable");
            names.put(name, path);
        }
        mNamedExecutables = Collections.unmodifiableMap(names);
        if (!permittedDirectory(mDefaultCwd, mWorkingRoots)) throw new IllegalArgumentException("Invalid default working directory");
    }

    /** Returns null for unauthorized or unavailable paths. Workspace resolution is not implemented yet. */
    @Override public JobCoordinator.Resolution authorizeAndResolve(JobSpec spec, long authorizationEpoch) {
        if (spec == null || authorizationEpoch < 0 || spec.workspaceId() != null) return null;
        String executable = spec.executable().startsWith("/") ? spec.executable() : mNamedExecutables.get(spec.executable());
        String cwd = spec.workingDirectory() == null ? mDefaultCwd : spec.workingDirectory();
        if (!validPath(executable) || !validPath(cwd)) return null;
        try {
            if (!permittedExecutable(executable) || !permittedDirectory(cwd, mWorkingRoots)) return null;
            return new JobCoordinator.Resolution(executable, cwd);
        } catch (IOException e) { throw new IllegalStateException("Job path validation unavailable", e); }
    }

    private boolean permittedExecutable(String path) throws IOException {
        File file = new File(path);
        return withinRoots(file, mExecutableRoots) && file.isFile() && file.canExecute();
    }

    private static boolean permittedDirectory(String path, List<Root> roots) throws IOException {
        File directory = new File(path);
        return withinRoots(directory, roots) && directory.isDirectory();
    }

    private static boolean withinRoots(File candidate, List<Root> roots) throws IOException {
        String canonical = candidate.getCanonicalPath();
        for (Root root : roots) {
            // Refuse a root that disappeared or whose own symlink now resolves elsewhere.
            if (!root.path.isDirectory() || !root.path.getCanonicalPath().equals(root.canonical)) continue;
            if (canonical.equals(root.canonical) || canonical.startsWith(root.canonical + File.separator)) return true;
        }
        return false;
    }

    private static List<Root> roots(List<String> paths) throws IOException {
        if (paths == null || paths.isEmpty() || paths.size() > MAX_ROOTS) throw new IllegalArgumentException("Invalid configured roots");
        List<Root> roots = new ArrayList<>();
        for (String path : paths) {
            if (!validPath(path) || path.equals("/")) throw new IllegalArgumentException("Invalid configured root");
            File file = new File(path);
            if (!file.isDirectory()) throw new IOException("Configured root unavailable");
            String canonical = file.getCanonicalPath();
            if (canonical.equals("/")) throw new IllegalArgumentException("Invalid configured root");
            roots.add(new Root(file, canonical));
        }
        return Collections.unmodifiableList(roots);
    }

    private static boolean validPath(String path) {
        return path != null && path.length() <= MAX_PATH && AppShellJobAdapter.isNormalizedAbsolute(path);
    }

    private static final class Root {
        final File path; final String canonical;
        Root(File path, String canonical) { this.path = path; this.canonical = canonical; }
    }
}

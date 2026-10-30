package com.termux.app.control.workspace;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fixed argv arrays for Git. No shell is involved. Inspection takes no client text. Changes take only a
 * validated branch name, workspace-relative paths after {@code --}, or a commit message as the value of
 * {@code -m}, so no client value can become an option. Every argv runs as a managed job (JOBS_EXECUTE). Git
 * hooks, filters and configuration can run code, so no command here is treated as harmless. Push is not
 * offered: it sends data and credentials off the device.
 */
public final class GitCommandBuilder {
    private static final List<String> STATUS = Collections.unmodifiableList(Arrays.asList("git", "status", "--porcelain=v1", "--branch"));
    private static final List<String> BRANCH = Collections.unmodifiableList(Arrays.asList("git", "branch", "--show-current"));
    private static final List<String> DIFF = Collections.unmodifiableList(Arrays.asList("git", "diff", "--stat"));
    private static final List<String> LOG = Collections.unmodifiableList(Arrays.asList("git", "log", "--oneline", "-n", "20"));

    private GitCommandBuilder() { }

    /** Returns an unmodifiable argv. Only the four inspection subcommands are accepted. */
    public static List<String> readOnly(String subcommand) throws WorkspaceRepository.WorkspaceException {
        if ("status".equals(subcommand)) return STATUS;
        if ("branch".equals(subcommand)) return BRANCH;
        if ("diff".equals(subcommand)) return DIFF;
        if ("log".equals(subcommand)) return LOG;
        throw new WorkspaceRepository.WorkspaceException("INVALID_REQUEST");
    }

    public static final int MAX_PATHS = 100, MAX_MESSAGE_CHARS = 4096;

    /**
     * fetch and pull take nothing; switch takes a branch; stage takes paths; commit takes a message. A value
     * an action does not use must be absent. Returns an unmodifiable argv.
     */
    public static List<String> change(String action, String branch, List<String> paths, String message)
            throws WorkspaceRepository.WorkspaceException {
        List<String> argv = new java.util.ArrayList<>();
        argv.add("git");
        boolean usesBranch = "switch".equals(action), usesPaths = "stage".equals(action), usesMessage = "commit".equals(action);
        if ((branch != null) != usesBranch || (paths != null) != usesPaths || (message != null) != usesMessage) invalid();
        if ("fetch".equals(action)) argv.addAll(Arrays.asList("fetch", "--prune"));
        else if ("pull".equals(action)) argv.addAll(Arrays.asList("pull", "--ff-only", "--no-rebase"));
        else if (usesBranch) {
            if (!validBranch(branch)) invalid();
            argv.add("switch"); argv.add(branch);
        } else if (usesPaths) {
            if (paths.isEmpty() || paths.size() > MAX_PATHS) invalid();
            argv.add("add"); argv.add("--");
            for (String path : paths) {
                try { if (com.termux.app.control.files.FileService.isValidRelativePath(path)) { argv.add(path); continue; } }
                catch (RuntimeException ignored) { }
                invalid();
            }
        } else if (usesMessage) {
            if (message.trim().isEmpty() || message.length() > MAX_MESSAGE_CHARS || message.indexOf('\0') >= 0) invalid();
            argv.add("commit"); argv.add("-m"); argv.add(message);
        } else invalid();
        return Collections.unmodifiableList(argv);
    }

    /** A conservative subset of git-check-ref-format, which also refuses option-like names. */
    static boolean validBranch(String name) {
        if (name == null || name.isEmpty() || name.length() > 200 || !name.matches("[A-Za-z0-9._/-]+")) return false;
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock")) return false;
        if (name.contains("..") || name.contains("//") || name.contains("/.") || name.startsWith(".") || name.equals("HEAD")) return false;
        return true;
    }

    private static void invalid() throws WorkspaceRepository.WorkspaceException {
        throw new WorkspaceRepository.WorkspaceException("INVALID_REQUEST");
    }
}

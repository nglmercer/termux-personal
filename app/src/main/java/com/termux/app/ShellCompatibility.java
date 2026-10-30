package com.termux.app;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shell setup that makes common Linux tool installers work without manual steps:
 * <ul>
 *   <li>an empty ~/.bashrc when none exists, so installers that append PATH entries there have a target
 *       (Termux's profile already sources it for login shells);</li>
 *   <li>a profile script that puts the app's glibc execve shim before termux-exec, so glibc programs run
 *       through glibc-runner's loader automatically.</li>
 * </ul>
 * Existing user files are never modified. The profile script is owned by the app and rewritten on start.
 */
public final class ShellCompatibility {
    private static final String LOG_TAG = "ShellCompatibility";
    static final String PROFILE_SCRIPT = "termux-app-glibc.sh";

    static final String BASHRC =
        "# ~/.bashrc: your bash settings. Termux sources this file for every interactive shell.\n" +
        "# Tool installers may add lines here, for example to extend PATH.\n";

    static final String PROFILE =
        "# Written by the Termux app on start; local edits are replaced.\n" +
        "# Automatic glibc compatibility: put the app's execve shim before termux-exec, so glibc programs run\n" +
        "# through glibc-runner's loader when it is installed. Skipped if the shim is missing or fails to load.\n" +
        "if [ -n \"${TERMUX_GLIBC_EXEC:-}\" ] && [ -r \"$TERMUX_GLIBC_EXEC\" ] && [ -n \"${LD_PRELOAD:-}\" ]; then\n" +
        "    case \":$LD_PRELOAD:\" in\n" +
        "        *\":$TERMUX_GLIBC_EXEC:\"*) ;;\n" +
        "        *)\n" +
        "            if LD_PRELOAD=\"$TERMUX_GLIBC_EXEC:$LD_PRELOAD\" \"$PREFIX/bin/true\" 2>/dev/null; then\n" +
        "                export LD_PRELOAD=\"$TERMUX_GLIBC_EXEC:$LD_PRELOAD\"\n" +
        "                # This shell started before the shim was added; restart an interactive login shell once so\n" +
        "                # its own commands use it. Non-interactive shells keep running and only their children do.\n" +
        "                case $- in *i*)\n" +
        "                    if [ -n \"${BASH:-}\" ] && [ -z \"${TERMUX_GLIBC_RESTARTED:-}\" ] && shopt -q login_shell 2>/dev/null; then\n" +
        "                        export TERMUX_GLIBC_RESTARTED=1\n" +
        "                        exec \"$BASH\" -l\n" +
        "                    fi;;\n" +
        "                esac\n" +
        "            fi;;\n" +
        "    esac\n" +
        "fi\n";

    private ShellCompatibility() { }

    /** Small file writes only; safe to call on the main thread right before the first session starts. */
    public static void ensure() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File bashrc = new File(home, ".bashrc");
        try {
            // A link or any existing entry is left alone; only a missing file is created.
            if (home.isDirectory() && !exists(bashrc)) writeNew(bashrc, BASHRC);
        } catch (IOException | RuntimeException e) {
            Logger.logWarn(LOG_TAG, "Could not create ~/.bashrc");
        }
        File profileDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/profile.d");
        if (!profileDir.isDirectory()) return;
        File script = new File(profileDir, PROFILE_SCRIPT);
        try {
            if (script.isFile() && PROFILE.equals(read(script))) return;
            File staged = new File(profileDir, PROFILE_SCRIPT + ".tmp");
            try (FileOutputStream out = new FileOutputStream(staged)) {
                out.write(PROFILE.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!staged.renameTo(script)) throw new IOException("rename failed");
        } catch (IOException | RuntimeException e) {
            Logger.logWarn(LOG_TAG, "Could not write the glibc compatibility profile");
        }
    }

    /** lstat, so a dangling link also counts as existing and is never replaced. */
    private static boolean exists(File file) {
        try { android.system.Os.lstat(file.getPath()); return true; }
        catch (android.system.ErrnoException e) { return e.errno != android.system.OsConstants.ENOENT; }
    }

    private static String read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeNew(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}

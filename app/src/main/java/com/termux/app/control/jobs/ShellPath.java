package com.termux.app.control.jobs;

import com.termux.shared.termux.TermuxConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PATH as an interactive login bash sets it, so managed jobs find tools whose installers added themselves to
 * ~/.bashrc. Jobs do not start a shell, so without this they would only see the app's base PATH. The result is
 * cached and probed again only when a startup file or the base PATH changes. Worker thread only.
 */
final class ShellPath {
    private static final long TIMEOUT_MILLIS = 5000;
    private static final int MAX_OUTPUT = 64 * 1024, MAX_ENTRIES = 64;
    private static final String MARK = "\u0001";
    private static final String[] STARTUP_FILES = {".bashrc", ".bash_profile", ".profile"};

    private static String sKey, sPath;

    private ShellPath() { }

    /** The shell's PATH, or null to keep the base PATH (no bash, timeout, or unusable output). */
    static synchronized String forJobs(Map<String, String> baseEnvironment) {
        String key = stamp(baseEnvironment.get("PATH"));
        if (key.equals(sKey)) return sPath;
        sPath = probe(baseEnvironment);
        sKey = key;
        return sPath;
    }

    private static String stamp(String basePath) {
        StringBuilder key = new StringBuilder(String.valueOf(basePath));
        for (String name : STARTUP_FILES) {
            File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, name);
            key.append('|').append(file.lastModified()).append(':').append(file.length());
        }
        File profile = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/profile");
        return key.append('|').append(profile.lastModified()).toString();
    }

    private static String probe(Map<String, String> baseEnvironment) {
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        if (!bash.canExecute()) return null;
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(bash.getAbsolutePath(), "-lic", "printf '\\001%s\\001' \"$PATH\"");
            builder.environment().clear();
            builder.environment().putAll(baseEnvironment);
            // The glibc profile restarts interactive login shells once; a probe must not, or -c would be lost.
            builder.environment().put("TERMUX_GLIBC_RESTARTED", "1");
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            process = builder.start();
            process.getOutputStream().close();
            String output = readBounded(process.getInputStream());
            int start = output.lastIndexOf(MARK, output.length() - 2), end = output.lastIndexOf(MARK);
            if (start < 0 || end <= start) return null;
            return sanitize(output.substring(start + 1, end));
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** Reads stdout until EOF or the size bound; a shell that keeps the pipe open past the timeout is abandoned. */
    private static String readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        byte[] buffer = new byte[4096];
        while (System.nanoTime() < deadline && out.size() < MAX_OUTPUT) {
            if (input.available() == 0) {
                // Poll so a stuck startup file cannot hold the job launch beyond the timeout.
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (marks(out.toByteArray()) >= 2) break;
                continue;
            }
            int n = input.read(buffer);
            if (n < 0) break;
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int marks(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) if (b == 1) count++;
        return count;
    }

    /** Absolute entries only, no duplicates, $PREFIX/bin first so a startup file cannot drop the Termux tools. */
    static String sanitize(String path) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        for (String entry : path.split(":")) {
            if (entries.size() >= MAX_ENTRIES) break;
            if (entry.startsWith("/") && entry.indexOf('\n') < 0 && entry.indexOf('\u0000') < 0) entries.add(entry);
        }
        StringBuilder joined = new StringBuilder();
        for (String entry : entries) joined.append(joined.length() == 0 ? "" : ":").append(entry);
        return joined.toString();
    }
}

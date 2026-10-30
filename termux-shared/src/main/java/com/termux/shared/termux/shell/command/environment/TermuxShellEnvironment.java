package com.termux.shared.termux.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.AndroidShellEnvironment;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.ShellCommandShellEnvironment;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.TermuxShellUtils;

import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * Environment for Termux.
 */
public class TermuxShellEnvironment extends AndroidShellEnvironment {

    private static final String LOG_TAG = "TermuxShellEnvironment";

    /** Environment variable for the termux {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH}. */
    public static final String ENV_PREFIX = "PREFIX";

    public TermuxShellEnvironment() {
        super();
        shellCommandShellEnvironment = new TermuxShellCommandShellEnvironment();
    }


    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void init(@NonNull Context currentPackageContext) {
        TermuxAppShellEnvironment.setTermuxAppEnvironment(currentPackageContext);
    }

    /** Init {@link TermuxShellEnvironment} constants and caches. */
    public synchronized static void writeEnvironmentToFile(@NonNull Context currentPackageContext) {
        HashMap<String, String> environmentMap = new TermuxShellEnvironment().getEnvironment(currentPackageContext, false);
        String environmentString = ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environmentMap);

        // Write environment string to temp file and then move to final location since otherwise
        // writing may happen while file is being sourced/read
        Error error = FileUtils.writeTextToFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH,
            Charset.defaultCharset(), environmentString, false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
            return;
        }

        error = FileUtils.moveRegularFile("termux.env.tmp", TermuxConstants.TERMUX_ENV_TEMP_FILE_PATH, TermuxConstants.TERMUX_ENV_FILE_PATH, true);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    /** Get shell environment for Termux. */
    @NonNull
    @Override
    public HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext, boolean isFailSafe) {

        // Termux environment builds upon the Android environment
        HashMap<String, String> environment = super.getEnvironment(currentPackageContext, isFailSafe);

        HashMap<String, String> termuxAppEnvironment = TermuxAppShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxAppEnvironment != null)
            environment.putAll(termuxAppEnvironment);

        HashMap<String, String> termuxApiAppEnvironment = TermuxAPIShellEnvironment.getEnvironment(currentPackageContext);
        if (termuxApiAppEnvironment != null)
            environment.putAll(termuxApiAppEnvironment);

        environment.put(ENV_HOME, TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put(ENV_PREFIX, TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        // If failsafe is not enabled, then we keep default PATH and TMPDIR so that system binaries can be used
        if (!isFailSafe) {
            environment.put(ENV_TMPDIR, TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            if (TermuxBootstrap.isAppPackageVariantAPTAndroid5()) {
                // Termux in android 5/6 era shipped busybox binaries in applets directory
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/applets");
                environment.put(ENV_LD_LIBRARY_PATH, TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            } else {
                // Termux binaries on Android 7+ rely on DT_RUNPATH, so LD_LIBRARY_PATH should be unset by default
                environment.put(ENV_PATH, TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + userBinPath());
                environment.remove(ENV_LD_LIBRARY_PATH);
            }
            putGlibcCompatibility(currentPackageContext, environment);
        }

        return environment;
    }


    /** The fixed part: the conventional per-user directories on Linux. Everything else comes from path-append. */
    static final String[] USER_BIN_DIRS = {".local/bin", "bin"};
    private static final int MAX_APPENDED = 32;

    /**
     * ":"-prefixed directories that follow $PREFIX/bin. Listing a directory that does not exist yet is harmless
     * and lets a tool installed later work in new sessions without editing PATH.
     */
    static String userBinPath() {
        String configured = null;
        try {
            com.termux.shared.termux.settings.properties.TermuxAppSharedProperties properties =
                com.termux.shared.termux.settings.properties.TermuxAppSharedProperties.getProperties();
            if (properties != null) configured = properties.getPathAppend();
        } catch (RuntimeException ignored) { }
        return userBinPath(configured == null ? TermuxPropertyConstants.DEFAULT_IVALUE_PATH_APPEND : configured);
    }

    /** Expands "~/" and "$HOME/", keeps absolute entries only, drops duplicates and anything that could split PATH. */
    static String userBinPath(String pathAppend) {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        for (String dir : USER_BIN_DIRS) dirs.add(home + "/" + dir);
        if (pathAppend != null) {
            for (String raw : pathAppend.split(":")) {
                String dir = raw.trim();
                if (dir.equals("~")) dir = home;
                else if (dir.startsWith("~/")) dir = home + dir.substring(1);
                else if (dir.startsWith("$HOME/")) dir = home + dir.substring(5);
                while (dir.length() > 1 && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
                if (!dir.startsWith("/") || dir.indexOf('\0') >= 0 || dir.indexOf('\n') >= 0) continue;
                if (dirs.size() >= MAX_APPENDED) break;
                dirs.add(dir);
            }
        }
        dirs.remove(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        StringBuilder path = new StringBuilder();
        for (String dir : dirs) path.append(':').append(dir);
        return path.toString();
    }

    /**
     * Paths of the app's glibc compatibility libraries, which the shell profile adds after termux-exec. They live
     * in the app's native library directory, which changes when the app is updated, so they are set per session.
     */
    private static void putGlibcCompatibility(@NonNull Context context, @NonNull HashMap<String, String> environment) {
        if (!TermuxConstants.TERMUX_PACKAGE_NAME.equals(context.getPackageName())) return;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null) return;
        environment.put("TERMUX_GLIBC_EXEC", nativeDir + "/libtermux-glibc-exec.so");
        environment.put("TERMUX_GLIBC_PRELOAD", nativeDir + "/libtermux-glibc-preload.so");
    }

    @NonNull
    @Override
    public String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @NonNull
    @Override
    public String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, String[] arguments) {
        return TermuxShellUtils.setupShellCommandArguments(executable, arguments);
    }

}

package com.termux.app.compat.cli;

import android.content.Context;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** Runs Android's real shell and linker, without requiring a musl installation. */
@RunWith(AndroidJUnit4.class)
public class RuntimeAdapterInstrumentedTest {
    @Test(timeout = 30000) public void filtersKnownPreloadsAndKeepsAnotherLibrary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File base = new File(context.getCacheDir(), "runtime-adapter-" + UUID.randomUUID());
        File lib = new File(base, "lib");
        assertTrue(lib.mkdirs());
        File alias = new File(lib, "libtermux-exec-ld-preload.so");
        File adapterDir = new File(base, "compat-v1");
        File adapter = new File(adapterDir, "termux-run-clean");
        Process process = null;
        try {
            ByteArrayOutputStream asset = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open("compat-v1/termux-run-clean")) {
                byte[] buffer = new byte[1024];
                for (int n; (n = in.read(buffer)) != -1; ) asset.write(buffer, 0, n);
            }
            assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, "termux-run-clean", asset.toByteArray()));
            File shim = new File(context.getApplicationInfo().nativeLibraryDir, "libtermux-glibc-exec.so");
            assertTrue(shim.isFile());
            Os.symlink(shim.getAbsolutePath(), alias.getAbsolutePath());
            boolean is64 = System.getProperty("os.arch", "").contains("64");
            File userLibrary = new File(is64 ? "/system/lib64/libm.so" : "/system/lib/libm.so");
            assertTrue(userLibrary.isFile());
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", adapter.getAbsolutePath(), "/system/bin/sh", "-c",
                "printf '%s|%s' \"${LD_PRELOAD-unset}\" \"$1\"", "test", "argument with spaces");
            Map<String, String> env = builder.environment();
            env.put("PREFIX", base.getAbsolutePath());
            env.put("TERMUX_GLIBC_EXEC", shim.getAbsolutePath());
            env.put("LD_PRELOAD", shim.getAbsolutePath() + ":" + alias.getAbsolutePath() + ":" + userLibrary.getAbsolutePath());
            env.remove("TERMUX_GLIBC_EXE");
            builder.redirectErrorStream(true);
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                for (int n; (n = in.read(buffer)) != -1; ) output.write(buffer, 0, n);
            }
            assertEquals(0, process.waitFor());
            assertEquals(userLibrary.getAbsolutePath() + "|argument with spaces", output.toString("UTF-8"));
        } finally {
            if (process != null) process.destroy();
            adapter.delete(); adapterDir.delete(); alias.delete(); lib.delete(); base.delete();
        }
    }

    /** Exercises the actual installed Termux preload when an initialized bootstrap is available. */
    @Test(timeout = 30000) public void installedTermuxExecPreservesOtherPreloadsThroughAdapter() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File termuxExec = new File(prefix, "lib/libtermux-exec-ld-preload.so");
        Assume.assumeTrue("Termux bootstrap and termux-exec are required", termuxExec.isFile());
        File base = new File(context.getCacheDir(), "runtime-real-exec-" + UUID.randomUUID());
        assertTrue(base.mkdir());
        File adapterDir = new File(base, "compat-v1");
        File adapter = new File(adapterDir, "termux-run-clean");
        Process process = null;
        try {
            ByteArrayOutputStream asset = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open("compat-v1/termux-run-clean")) {
                byte[] buffer = new byte[1024];
                for (int n; (n = in.read(buffer)) != -1; ) asset.write(buffer, 0, n);
            }
            assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, "termux-run-clean", asset.toByteArray()));
            File shim = new File(context.getApplicationInfo().nativeLibraryDir, "libtermux-glibc-exec.so");
            assertTrue(shim.isFile());
            boolean is64 = System.getProperty("os.arch", "").contains("64");
            File userLibrary = new File(is64 ? "/system/lib64/libm.so" : "/system/lib/libm.so");
            assertTrue(userLibrary.isFile());
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", "exec \"$@\"", "test",
                adapter.getAbsolutePath(), "/system/bin/sh", "-c", "printf '%s' \"${LD_PRELOAD-unset}\"");
            Map<String, String> env = builder.environment();
            env.putAll(new TermuxShellEnvironment().getEnvironment(context, false));
            env.put("PREFIX", prefix.getAbsolutePath());
            env.put("TERMUX_GLIBC_EXEC", shim.getAbsolutePath());
            env.put("LD_PRELOAD", shim.getAbsolutePath() + ":" + termuxExec.getAbsolutePath() + ":" + userLibrary.getAbsolutePath());
            env.remove("TERMUX_GLIBC_EXE");
            builder.redirectErrorStream(true);
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                for (int n; (n = in.read(buffer)) != -1; ) output.write(buffer, 0, n);
            }
            assertEquals(0, process.waitFor());
            assertEquals(userLibrary.getAbsolutePath(), output.toString("UTF-8"));
        } finally {
            if (process != null) process.destroy();
            adapter.delete(); adapterDir.delete(); base.delete();
        }
    }
}

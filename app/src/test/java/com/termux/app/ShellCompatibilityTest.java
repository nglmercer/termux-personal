package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShellCompatibilityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private String runProfile(String preload, boolean probeSucceeds) throws Exception {
        File prefix = temp.newFolder();
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdir());
        File probe = new File(bin, "true");
        Files.write(probe.toPath(), ("#!/bin/sh\nexit " + (probeSucceeds ? "0" : "1") + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(probe.setExecutable(true));
        // Pattern characters in the name ensure the case expression compares a literal path.
        File shim = new File(prefix, "shim[1].so");
        assertTrue(shim.createNewFile());
        return runProfile(prefix, shim.getAbsolutePath(), preload);
    }

    private String runProfile(File prefix, String shim, String preload) throws Exception {
        String script = "TERMUX_GLIBC_EXEC=$1; LD_PRELOAD=$2; PREFIX=$3; export TERMUX_GLIBC_EXEC LD_PRELOAD PREFIX\n"
            + ShellCompatibility.PROFILE + "printf '%s' \"$LD_PRELOAD\"\n";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", script, "profile-test", shim, preload, prefix.getAbsolutePath());
        builder.environment().remove("LD_PRELOAD");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        for (int n; (n = process.getInputStream().read(buffer)) != -1; ) output.write(buffer, 0, n);
        assertEquals(0, process.waitFor());
        return output.toString("UTF-8");
    }

    @Test public void existingEntryIsRecognizedWithBothAndroidLinkerSeparators() throws Exception {
        File prefix = temp.newFolder();
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdir());
        File probe = new File(bin, "true");
        Files.write(probe.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(probe.setExecutable(true));
        File shim = new File(prefix, "shim[1].so");
        assertTrue(shim.createNewFile());
        String path = shim.getAbsolutePath();
        for (String preload : new String[]{path, path + " /other", "/other " + path,
                "/other:" + path + " /third", "/other " + path + ":/third",
                "/other:" + path + ":/third", "/other  " + path + "  /third"}) {
            assertEquals(preload, runProfile(prefix, path, preload));
        }
    }

    @Test public void differentOrPartialEntryDoesNotSuppressInsertion() throws Exception {
        String result = runProfile("/other/shim[1].so:/second", true);
        assertTrue(result.endsWith("/shim[1].so:/other/shim[1].so:/second"));
    }

    @Test public void failedProbeAndEmptyPreloadRemainUnchanged() throws Exception {
        assertEquals("/existing.so", runProfile("/existing.so", false));
        assertEquals("", runProfile("", true));
    }
}

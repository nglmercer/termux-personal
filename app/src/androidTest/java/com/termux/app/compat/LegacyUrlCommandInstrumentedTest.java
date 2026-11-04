package com.termux.app.compat;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.app.TermuxActivity;
import com.termux.app.compat.auth.BrowserCallbackProbe;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/** Explicit device test: a real shell command and external handler, with no OAuth data. */
@RunWith(AndroidJUnit4.class)
public class LegacyUrlCommandInstrumentedTest {
    @Test public void selectedCommandCanReachTemporaryLoopbackCallback() throws Exception {
        Assume.assumeTrue("Requires explicit external-handler test opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("compatLegacyUrlCommand")));
        String command = InstrumentationRegistry.getArguments().getString("compatCommand", "termux-open-url");
        if (!"termux-open-url".equals(command) && !"termux-open".equals(command) && !"xdg-open".equals(command))
            throw new IllegalArgumentException("Unsupported command selection");
        String address = InstrumentationRegistry.getArguments().getString("compatBrowserAddress", "ipv4");
        if (!"ipv4".equals(address) && !"ipv6".equals(address)) throw new IllegalArgumentException("Invalid address family");
        BrowserCallbackProbe.Family family = "ipv6".equals(address) ? BrowserCallbackProbe.Family.IPV6 : BrowserCallbackProbe.Family.IPV4;
        File prefix = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File bin = new File(prefix, "bin");
        File shell = new File(bin, "sh");

        try (ActivityScenario<TermuxActivity> scenario = ActivityScenario.launch(TermuxActivity.class)) {
            // Initialization and package installation must already have finished on the device.
            Assume.assumeTrue("Installed Termux shell and selected command are required",
                shell.canExecute() && new File(bin, command).canExecute());
            try (BrowserCallbackProbe probe = new BrowserCallbackProbe(family)) {
                ExecutorService worker = Executors.newFixedThreadPool(2);
                Process process = null;
                try {
                    Future<BrowserCallbackProbe.Status> result = worker.submit(probe::await);
                    ProcessBuilder builder = new ProcessBuilder(shell.getAbsolutePath(), "-c", "exec \"$1\" \"$2\"", "compat-test", command, probe.url());
                    Map<String, String> env = builder.environment();
                    env.put("PREFIX", prefix.getAbsolutePath());
                    env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
                    env.put("TMPDIR", new File(prefix, "tmp").getAbsolutePath());
                    env.put("PATH", bin.getAbsolutePath() + ":/system/bin");
                    env.put("TERMUX__USER_ID", Integer.toString(android.os.Process.myUid() / 100000));
                    // Do not retain command output, which could contain a request URI or other data.
                    builder.redirectOutput(new File("/dev/null"));
                    builder.redirectError(new File("/dev/null"));
                    process = builder.start();
                    Process child = process;
                    Future<Integer> exit = worker.submit(() -> { return child.waitFor(); });
                    assertEquals("Command must exit successfully", 0, (int) exit.get(15, TimeUnit.SECONDS));
                    assertEquals("External handler must reach the temporary listener",
                        BrowserCallbackProbe.Status.REQUEST_RECEIVED, result.get(35, TimeUnit.SECONDS));
                } finally {
                    if (process != null) process.destroy();
                    probe.close();
                    worker.shutdownNow();
                }
            }
        }
    }
}

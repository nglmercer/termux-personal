package com.termux.app.control.jobs;

import android.content.Context;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AppShellManagedCaptureTest {
    private static final IShellEnvironment ENV = new IShellEnvironment() {
        public String getDefaultWorkingDirectoryPath() { return "/tmp"; }
        public String getDefaultBinPath() { return "/bin"; }
        public String[] setupShellCommandArguments(String executable, String[] arguments) {
            String[] result = new String[arguments.length + 1]; result[0] = executable;
            System.arraycopy(arguments, 0, result, 1, arguments.length); return result;
        }
        public HashMap<String, String> setupShellCommandEnvironment(Context context, ExecutionCommand command) { return new HashMap<>(); }
    };

    @Test public void managedCaptureReceivesOwnedProcessAndTrueExitWithoutLegacyBuffers() throws Exception {
        ExecutionCommand command = new ExecutionCommand(1, "/bin/sh", new String[]{"-c", "printf abc; printf err >&2; exit 7"}, null, "/tmp", "app-shell", false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> stdout = new AtomicReference<>(), stderr = new AtomicReference<>();
        AtomicReference<AppShell> shell = new AtomicReference<>();
        Thread launcher = new Thread(() -> shell.set(AppShell.executeManaged(RuntimeEnvironment.getApplication(), command,
            appShell -> { calls.incrementAndGet(); done.countDown(); }, ENV, null, process -> {
                process.getOutputStream().close();
                stdout.set(readAll(process.getInputStream())); stderr.set(readAll(process.getErrorStream()));
                return process.waitFor();
            })));
        launcher.start(); launcher.join(5000);
        assertFalse(launcher.isAlive()); assertNotNull(shell.get());
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get()); assertEquals(Integer.valueOf(7), command.resultData.exitCode);
        assertArrayEquals(new byte[]{'a', 'b', 'c'}, stdout.get());
        assertArrayEquals(new byte[]{'e', 'r', 'r'}, stderr.get());
        assertEquals(0, command.resultData.stdout.length()); assertEquals(0, command.resultData.stderr.length());
    }

    @Test public void captureFailureReportsSingleFailureAndDestroysOwnedProcess() throws Exception {
        ExecutionCommand command = new ExecutionCommand(2, "/bin/sh", new String[]{"-c", "sleep 30"}, null, "/tmp", "app-shell", false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AppShell> shell = new AtomicReference<>();
        Thread launcher = new Thread(() -> shell.set(AppShell.executeManaged(RuntimeEnvironment.getApplication(), command,
            appShell -> { calls.incrementAndGet(); done.countDown(); }, ENV, null, process -> { throw new IOException("secret must not be copied"); })));
        launcher.start(); launcher.join(5000);
        assertFalse(launcher.isAlive()); assertNotNull(shell.get()); assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(command.isStateFailed()); assertEquals(1, calls.get());
        assertTrue(shell.get().getProcess().waitFor(5, TimeUnit.SECONDS));
        assertFalse(command.resultData.errorsList.toString().contains("secret must not be copied"));
    }

    @Test public void legacyAppShellStillPopulatesItsResultBuffers() throws Exception {
        ExecutionCommand command = new ExecutionCommand(3, "/bin/sh", new String[]{"-c", "printf abc; printf err >&2; exit 7"}, null, "/tmp", "app-shell", false);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AppShell> shell = new AtomicReference<>();
        Thread launcher = new Thread(() -> shell.set(AppShell.execute(RuntimeEnvironment.getApplication(), command,
            appShell -> done.countDown(), ENV, null, false)));
        launcher.start(); launcher.join(5000);
        assertFalse(launcher.isAlive()); assertNotNull(shell.get()); assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(7), command.resultData.exitCode);
        assertEquals("abc\n", command.resultData.stdout.toString());
        assertEquals("err\n", command.resultData.stderr.toString());
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32]; int n;
            while ((n = source.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}

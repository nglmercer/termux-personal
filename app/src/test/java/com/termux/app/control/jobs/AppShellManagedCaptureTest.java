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

    @Test public void captureFailureWaitsForChildBeforeReportingFailure() throws Exception {
        ExecutionCommand command = new ExecutionCommand(2, "/bin/sh", new String[]{"-c", "IFS= read -r line"}, null, "/tmp", "app-shell", false);
        CountDownLatch done = new CountDownLatch(1), captureFailed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AppShell> shell = new AtomicReference<>();
        Thread launcher = new Thread(() -> shell.set(AppShell.executeManaged(RuntimeEnvironment.getApplication(), command,
            appShell -> { calls.incrementAndGet(); done.countDown(); }, ENV, null, process -> {
                captureFailed.countDown(); throw new IOException("secret must not be copied");
            })));
        launcher.start(); launcher.join(5000);
        assertFalse(launcher.isAlive()); assertNotNull(shell.get());
        try {
            assertTrue(captureFailed.await(5, TimeUnit.SECONDS));
            assertFalse(done.await(100, TimeUnit.MILLISECONDS));
            shell.get().getProcess().getOutputStream().close();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(command.isStateFailed()); assertEquals(1, calls.get());
            assertTrue(shell.get().getProcess().waitFor(5, TimeUnit.SECONDS));
            assertFalse(command.resultData.errorsList.toString().contains("secret must not be copied"));
        } finally { shell.get().getProcess().destroyForcibly(); }
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

    @Test public void boundedCaptureHandlesExactStdinAndLargeSeparateStreams() throws Exception {
        String script = "import os,sys,threading; data=sys.stdin.buffer.read(); " +
            "a=threading.Thread(target=lambda:os.write(1,b'\\x00\\xff'*131072)); a.start(); " +
            "os.write(2,b'\\xfe\\x01'*131072); a.join(); raise SystemExit(7 if data==b'\\x00\\xff\\x0a' else 19)";
        ExecutionCommand command = new ExecutionCommand(4, "/usr/bin/python3", new String[]{"-c", script}, null, "/tmp", "app-shell", false);
        JobIoPool io = new JobIoPool(1);
        JobOutputStore store = new JobOutputStore(1024, 1, 2048);
        JobOutputStore.Output output = store.reserve("job");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AppShell> shell = new AtomicReference<>();
        try {
            AppShellJobCapture capture = new AppShellJobCapture(io.tryReserve(), output, new byte[]{0, (byte)255, 10}, 5000);
            Thread launcher = new Thread(() -> shell.set(AppShell.executeManaged(RuntimeEnvironment.getApplication(), command,
                appShell -> done.countDown(), ENV, null, capture)));
            launcher.start(); launcher.join(5000);
            assertFalse(launcher.isAlive()); assertNotNull(shell.get()); assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(7), command.resultData.exitCode);
            assertTrue(output.isFinished());
            com.termux.terminal.OutputReplayBuffer.ReadResult stdout = output.read(JobOutputStore.Stream.STDOUT, 0, 1024);
            com.termux.terminal.OutputReplayBuffer.ReadResult stderr = output.read(JobOutputStore.Stream.STDERR, 0, 1024);
            assertTrue(stdout.gap); assertTrue(stderr.gap);
            assertEquals(262144, stdout.endOffset); assertEquals(262144, stderr.endOffset);
            assertEquals((byte) 0, stdout.data[0]); assertEquals((byte) 255, stdout.data[1]);
            assertEquals((byte) 254, stderr.data[0]); assertEquals((byte) 1, stderr.data[1]);
            assertEquals(0, command.resultData.stdout.length()); assertEquals(0, command.resultData.stderr.length());
        } finally { io.close(); store.close(); }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32]; int n;
            while ((n = source.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}

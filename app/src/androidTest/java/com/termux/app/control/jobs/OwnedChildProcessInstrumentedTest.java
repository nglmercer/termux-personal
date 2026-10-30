package com.termux.app.control.jobs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Runs inside the app process, so the zygote seccomp policy applies to fork, setpgid, execve, waitid and kill. */
@RunWith(AndroidJUnit4.class)
public class OwnedChildProcessInstrumentedTest {
    private static final String[] ENV = {"PATH=/system/bin"};

    @Test public void launchesWithSeparateStreamsAndExitCode() throws Exception {
        OwnedChildProcess child = onWorker(() -> OwnedChildProcess.launch(
            new String[]{"/system/bin/sh", "-c", "read line; echo out:$line; echo err >&2; exit 7"}, ENV, "/"));
        assertNotNull("Managed native library missing", child);
        child.getOutputStream().write("in\n".getBytes()); child.getOutputStream().close();
        assertEquals("out:in\n", drain(child.getInputStream()));
        assertEquals("err\n", drain(child.getErrorStream()));
        assertEquals(7, (int) onWorker(child::waitFor));
        assertNull(child.terminationSignal());
        assertFalse("an exited child is never signalled", child.signal(OwnedChildProcess.SIGKILL));
    }

    @Test public void groupSignalStopsTheChildAndItsDescendant() throws Exception {
        OwnedChildProcess child = onWorker(() -> OwnedChildProcess.launch(
            new String[]{"/system/bin/sh", "-c", "sleep 30 & sleep 30"}, ENV, "/"));
        assertNotNull(child);
        child.getOutputStream().close();
        Thread.sleep(200);
        assertTrue(child.signal(OwnedChildProcess.SIGTERM));
        long started = System.nanoTime();
        assertEquals(128 + OwnedChildProcess.SIGTERM, (int) onWorker(child::waitFor));
        assertEquals(Integer.valueOf(OwnedChildProcess.SIGTERM), child.terminationSignal());
        // The background sleep holds stdout open; EOF proves the group signal reached it too.
        assertEquals("", drain(child.getInputStream()));
        assertTrue("descendant stopped promptly", System.nanoTime() - started < 5_000_000_000L);
        drain(child.getErrorStream());
    }

    @Test public void execFailureIsReportedWithoutAChild() throws Exception {
        try {
            onWorker(() -> OwnedChildProcess.launch(new String[]{"/nonexistent/binary"}, ENV, "/"));
            fail("missing executable must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("errno 2"));
        }
    }

    private interface Call<T> { T call() throws Exception; }

    /** The native primitives refuse the main thread; instrumentation tests do not run on it, but stay explicit. */
    private static <T> T onWorker(Call<T> call) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { result.set(call.call()); } catch (Exception e) { failure.set(e); }
        });
        worker.start(); worker.join(20000);
        assertFalse("worker timed out", worker.isAlive());
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static String drain(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = input.read(buffer)) > 0; ) out.write(buffer, 0, n);
        input.close();
        return out.toString("UTF-8");
    }
}

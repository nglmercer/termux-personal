package com.termux.app.compat.runtime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs the app's Bionic-side shim in a system shell; no package files are modified. */
@RunWith(AndroidJUnit4.class)
public class GlibcShimFifoInstrumentedTest {
    private int attempt(File fifo, File shim) throws Exception {
        String command = "found=no; while IFS= read -r line; do case $line in *libtermux-glibc-exec.so*) found=yes;; esac; done < /proc/self/maps; " +
            "[ \"$found\" = yes ] || exit 99; exec \"$1\"";
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command, "compat-fifo-test", fifo.getAbsolutePath());
        builder.environment().put("LD_PRELOAD", shim.getAbsolutePath());
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.redirectOutput(new File("/dev/null"));
        builder.redirectError(new File("/dev/null"));
        Process process = builder.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> exit = worker.submit(() -> { return process.waitFor(); });
            int status = exit.get(5, TimeUnit.SECONDS);
            assertNotEquals("The Bionic shim must be loaded", 99, status);
            return status;
        } finally { process.destroy(); worker.shutdownNow(); }
    }

    @Test(timeout = 20000) public void fifoDoesNotBlockOrLoseData() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File shim = new File(context.getApplicationInfo().nativeLibraryDir, "libtermux-glibc-exec.so");
        assertTrue(shim.isFile());
        File fifo = new File(context.getCacheDir(), "compat-fifo-" + UUID.randomUUID());
        Os.mkfifo(fifo.getAbsolutePath(), 0700);
        try {
            assertNotEquals(0, attempt(fifo, shim));
            FileDescriptor fd = Os.open(fifo.getAbsolutePath(), OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            try {
                byte[] expected = "fifo-payload".getBytes(StandardCharsets.US_ASCII);
                assertEquals(expected.length, Os.write(fd, expected, 0, expected.length));
                assertNotEquals(0, attempt(fifo, shim));
                byte[] actual = new byte[expected.length];
                assertEquals(expected.length, Os.read(fd, actual, 0, actual.length));
                assertArrayEquals(expected, actual);
            } finally { Os.close(fd); }
        } finally { assertTrue(fifo.delete()); }
    }
}

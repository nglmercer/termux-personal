package com.termux.app.compat.cli;

import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WebCommandShimInstrumentedTest {
    private File base;
    @Before public void setUp() {
        base = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "shim-test-" + UUID.randomUUID());
        assertTrue(base.mkdir());
    }
    @After public void tearDown() {
        File directory = new File(base, "compat-v1");
        new File(directory, "termux-open-url").delete();
        directory.delete();
        new File(base, "sentinel").delete();
        base.delete();
    }
    @Test public void installIsIdempotentAndNeverOverwritesExistingFile() throws Exception {
        byte[] expected = "first script".getBytes(StandardCharsets.UTF_8);
        assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, expected));
        assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, expected));
        assertEquals(WebCommandShim.Status.CONFLICT, WebCommandShim.install(base, "different script".getBytes(StandardCharsets.UTF_8)));
        try (FileInputStream in = new FileInputStream(new File(base, "compat-v1/termux-open-url"))) {
            byte[] actual = new byte[expected.length];
            assertEquals(actual.length, in.read(actual));
            assertArrayEquals(expected, actual);
        }
    }
    @Test public void refusesSymlinkForShimFile() throws Exception {
        File directory = new File(base, "compat-v1"); assertTrue(directory.mkdir());
        File sentinel = new File(base, "sentinel");
        try (FileOutputStream output = new FileOutputStream(sentinel)) { output.write(7); }
        Os.symlink(sentinel.getAbsolutePath(), new File(directory, "termux-open-url").getAbsolutePath());
        assertEquals(WebCommandShim.Status.CONFLICT, WebCommandShim.install(base, new byte[]{1, 2, 3}));
        try (FileInputStream in = new FileInputStream(sentinel)) { assertEquals(7, in.read()); assertEquals(-1, in.read()); }
    }
    @Test public void refusesSymlinkForShimDirectory() throws Exception {
        File sentinel = new File(base, "sentinel"); assertTrue(sentinel.mkdir());
        Os.symlink(sentinel.getAbsolutePath(), new File(base, "compat-v1").getAbsolutePath());
        assertEquals(WebCommandShim.Status.CONFLICT, WebCommandShim.install(base, new byte[]{1}));
        assertFalse(new File(sentinel, "termux-open-url").exists());
    }
}

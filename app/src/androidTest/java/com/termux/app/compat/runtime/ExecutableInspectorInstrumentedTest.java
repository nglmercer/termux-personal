package com.termux.app.compat.runtime;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Reads a system executable; it never starts it. */
@RunWith(AndroidJUnit4.class)
public class ExecutableInspectorInstrumentedTest {
    @Test public void inspectsSystemShellDependencyMetadata() {
        File shell = new File("/system/bin/sh");
        Assume.assumeTrue("System shell must be readable", shell.isFile() && shell.canRead());
        ExecutableInspector.Report report = ExecutableInspector.inspect(shell, Build.SUPPORTED_ABIS, null);
        assertNotEquals(ExecutableInspector.Status.INVALID_FORMAT, report.status);
        assertNotEquals(ExecutableInspector.Status.UNAVAILABLE, report.status);
        assertTrue(report.detail, report.detail.contains("Direct ELF dependencies:"));
        assertFalse(report.detail, report.detail.contains("unavailable or beyond inspection limits"));
        assertTrue(report.describe().contains("library resolution"));
    }
}

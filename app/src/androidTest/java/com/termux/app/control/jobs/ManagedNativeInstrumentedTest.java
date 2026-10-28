package com.termux.app.control.jobs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;

/** Packaging and JNI marker only; kernel support requires separate device tests. */
@RunWith(AndroidJUnit4.class)
public class ManagedNativeInstrumentedTest {
    @Test public void packagedLibraryLoadsWithExpectedAbi() {
        assertTrue("Managed native library missing or incompatible", ManagedNative.isLibraryAvailable());
    }
}

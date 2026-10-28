package com.termux.app.control.jobs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.atomic.AtomicInteger;

/** Packaging and JNI marker only; kernel support requires separate device tests. */
@RunWith(AndroidJUnit4.class)
public class ManagedNativeInstrumentedTest {
    @Test public void packagedLibraryLoadsWithExpectedAbi() {
        assertTrue("Managed native library missing or incompatible", ManagedNative.isLibraryAvailable());
    }

    @Test public void pidfdProbeReturnsStructuredResultOffMainThread() throws Exception {
        AtomicInteger result = new AtomicInteger(1);
        Thread worker = new Thread(() -> result.set(ManagedNative.probePidfd()));
        worker.start(); worker.join(5000);
        assertTrue("Kernel probe did not finish", !worker.isAlive());
        assertTrue("Probe must return success or negative errno", result.get() <= 0);
    }
}

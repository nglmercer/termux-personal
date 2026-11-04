package com.termux.app.compat.network;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicitly contacts the diagnostic endpoint; does not change network settings. */
@RunWith(AndroidJUnit4.class)
public class LegacyNetworkInstrumentedTest {
    @Test public void legacyProbeCompletesOrReportsItsDeadline() throws Exception {
        Assume.assumeTrue("Requires API 21 or 22", Build.VERSION.SDK_INT == 21 || Build.VERSION.SDK_INT == 22);
        Assume.assumeTrue("Requires explicit network test opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("compatLegacyNetwork")));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<NetworkCompatibilityReport> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> NetworkDiagnostics.probe(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), report -> { result.set(report); done.countDown(); }));
        assertTrue("Expected a bounded result", done.await(16, TimeUnit.SECONDS));
        assertNotNull(result.get());
        assertNotEquals(NetworkCompatibilityReport.Status.UNAVAILABLE, result.get().status);
    }
}

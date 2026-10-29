package com.termux.app.control.jobs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WorkerJobPolicyInstrumentedTest {
    @Test public void rejectsMainThreadBeforeCallingDelegate() {
        AtomicBoolean rejected = new AtomicBoolean(), called = new AtomicBoolean();
        WorkerJobPolicy policy = new WorkerJobPolicy((spec, epoch) -> { called.set(true); return null; });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { policy.authorizeAndResolve(null, 0); }
            catch (IllegalStateException expected) { rejected.set(true); }
        });
        assertTrue(rejected.get());
        assertTrue(!called.get());
    }
}

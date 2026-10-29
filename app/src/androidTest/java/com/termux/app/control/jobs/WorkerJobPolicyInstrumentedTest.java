package com.termux.app.control.jobs;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.EnumSet;
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
        rejected.set(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { policy.authorizeReplay(null, 0); }
            catch (IllegalStateException expected) { rejected.set(true); }
        });
        assertTrue(rejected.get());
    }

    @Test public void productionCoordinatorDoesNotResolveOnMainThread() {
        AtomicBoolean called = new AtomicBoolean();
        JobAuthority authority = new JobAuthority();
        JobAuthority.Lease lease = authority.grant(EnumSet.of(JobAuthority.Permission.EXECUTE));
        JobRegistry registry = new JobRegistry(new JobIoPool(1), new JobOutputStore(16, 1, 32), 1);
        AppShellJobAdapter adapter = new AppShellJobAdapter(InstrumentationRegistry.getInstrumentation().getTargetContext());
        JobCoordinator coordinator = new JobCoordinator(registry, adapter, (spec, epoch) -> {
            called.set(true); return null;
        }, authority);
        JobSpec spec = new JobSpec("echo", Collections.emptyList(), null, null, Collections.emptyMap(), null,
            0, JobSpec.Presentation.BACKGROUND, null);
        AtomicReference<JobCoordinator.StartResult> result = new AtomicReference<>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(coordinator.start(spec, lease)));
            assertTrue(!called.get());
            assertTrue(result.get().status == JobCoordinator.StartStatus.UNAVAILABLE);
        } finally { coordinator.stopAccepting(); assertTrue(coordinator.closeIfIdle()); }
    }
}

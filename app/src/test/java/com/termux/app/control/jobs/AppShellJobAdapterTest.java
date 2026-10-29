package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

public class AppShellJobAdapterTest {
    private static JobSpec spec() {
        return new JobSpec("echo", Collections.singletonList("a b"), null, null, Collections.emptyMap(),
            null, 0, JobSpec.Presentation.BACKGROUND, "id");
    }
    private static JobRegistry registry() { return new JobRegistry(new JobIoPool(1), new JobOutputStore(16, 1, 32), 1); }

    @Test public void immediateExitBeforeLaunchReturnsWinsOverLateStartedTransition() {
        try (JobRegistry registry = registry()) {
            JobRegistry.Entry entry = registry.admit(spec(), 1, 100).entry;
            Process process = new FakeProcess();
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, callback) -> {
                assertEquals("/bin/echo", exe); assertEquals("/home", cwd);
                assertEquals(Collections.singletonList("a b"), job.arguments());
                capture.discardBeforeLaunch(); callback.finished(7, false);
                return process;
            });
            assertSame(process, adapter.launch(entry, spec(), "/bin/echo", "/home"));
            assertEquals(JobRecord.State.EXITED, entry.record().snapshot().state);
            assertEquals(Integer.valueOf(7), entry.record().snapshot().exitCode);
            assertNull(entry.record().snapshot().startedAt);
        }
    }

    @Test public void callbackAfterLaunchAndDuplicateCallbackHaveOneOutcome() {
        try (JobRegistry registry = registry()) {
            JobRegistry.Entry entry = registry.admit(spec(), 1, 100).entry;
            AtomicReference<AppShellJobAdapter.Completion> callback = new AtomicReference<>();
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, done) -> {
                capture.discardBeforeLaunch(); callback.set(done); return new FakeProcess();
            });
            assertNotNull(adapter.launch(entry, spec(), "/bin/echo", "/home"));
            assertEquals(JobRecord.State.RUNNING, entry.record().snapshot().state);
            callback.get().finished(12, false); callback.get().finished(0, false);
            assertEquals(Integer.valueOf(12), entry.record().snapshot().exitCode);
        }
    }

    @Test public void startupFailureReleasesResourcesAndIsReplayable() {
        try (JobRegistry registry = registry()) {
            JobRegistry.Entry entry = registry.admit(spec(), 1, 100).entry;
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, done) -> null);
            assertNull(adapter.launch(entry, spec(), "/bin/echo", "/home"));
            assertEquals(JobRecord.Failure.START_FAILED, entry.record().snapshot().failure);
            assertTrue(entry.output().isFinished());
            assertEquals(JobRegistry.Status.REPLAY, registry.admit(spec(), 1, 100).status);
        }
    }

    @Test public void captureFailurePreservesObservedExitCode() {
        try (JobRegistry registry = registry()) {
            JobRegistry.Entry entry = registry.admit(spec(), 1, 100).entry;
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, callback) -> {
                capture.discardBeforeLaunch(); callback.finished(7, true); return new FakeProcess();
            });
            adapter.launch(entry, spec(), "/bin/echo", "/home");
            assertEquals(JobRecord.State.FAILED, entry.record().snapshot().state);
            assertEquals(JobRecord.Failure.CAPTURE_FAILED, entry.record().snapshot().failure);
            assertEquals(Integer.valueOf(7), entry.record().snapshot().exitCode);
        }
    }

    @Test public void invalidResolvedPathsDoNotStartJob() {
        try (JobRegistry registry = registry()) {
            JobRegistry.Entry entry = registry.admit(spec(), 1, 100).entry;
            AppShellJobAdapter adapter = new AppShellJobAdapter((job, exe, cwd, capture, done) -> { fail(); return null; });
            assertThrows(IllegalArgumentException.class, () -> adapter.launch(entry, spec(), "/bin/../echo", "/home"));
            JobSpec different = new JobSpec("echo", Collections.singletonList("different"), null, null,
                Collections.emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, "id");
            assertThrows(IllegalArgumentException.class, () -> adapter.launch(entry, different, "/bin/echo", "/home"));
            assertEquals(JobRecord.State.QUEUED, entry.record().snapshot().state);
        }
    }

    private static final class FakeProcess extends Process {
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}

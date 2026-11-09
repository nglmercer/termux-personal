package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import com.termux.app.control.workspace.BackupOperations.State;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class BackupOperationsTest {
    private final BackupOperations operations = new BackupOperations();
    private static void await(CountDownLatch latch) throws IOException {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private static void nap() throws IOException {
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }

    @After public void tearDown() { operations.close(); }

    private static boolean until(java.util.function.BooleanSupplier c) throws Exception {
        for (int i = 0; i < 300; i++) { if (c.getAsBoolean()) return true; Thread.sleep(10); }
        return c.getAsBoolean();
    }

    @Test public void aSuccessfulOperationReportsItsResultOnlyWhenFinished() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        BackupOperations.View started = operations.submit("export", "ws_0123456789abcdef", control -> {
            await(go);
            return Collections.<String, Object>singletonMap("backup", "done");
        });
        assertEquals(State.RUNNING, started.state); assertNull(started.result); assertNull(started.endedAt);
        assertEquals(State.RUNNING, operations.get(started.id).state);
        go.countDown();
        assertTrue(until(() -> operations.get(started.id).state == State.COMPLETED));
        BackupOperations.View done = operations.get(started.id);
        assertEquals("done", done.result.get("backup")); assertNotNull(done.endedAt); assertNull(done.error);
    }

    @Test public void cancellationIsCooperativeAndEndsCancelledWithNoResult() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        BackupOperations.View v = operations.submit("import", "ws_0123456789abcdef", control -> {
            running.countDown();
            while (!control.cancelled()) nap();
            throw new WorkspaceBackupService.Cancelled();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        assertTrue(operations.cancel(v.id));
        assertTrue(until(() -> operations.get(v.id).state == State.CANCELLED));
        assertNull(operations.get(v.id).result);
        assertFalse("a finished operation cannot be cancelled again", operations.cancel(v.id));
        assertFalse(operations.cancel("bk_999")); assertFalse(operations.cancel(null));
    }

    @Test public void onlyOneOperationRunsAtATimeAndTheNextStartsAfterwards() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BackupOperations.View first = operations.submit("export", "ws_0123456789abcdef", control -> { await(release); return Collections.<String, Object>emptyMap(); });
        try { operations.submit("export", "ws_0123456789abcdef", control -> Collections.<String, Object>emptyMap()); fail("busy"); }
        catch (BackupOperations.BusyException expected) { }
        release.countDown();
        assertTrue(until(() -> operations.get(first.id).state == State.COMPLETED));
        BackupOperations.View second = operations.submit("export", "ws_0123456789abcdef", control -> Collections.<String, Object>emptyMap());
        assertNotEquals(first.id, second.id);
    }

    @Test public void failuresAreReportedWithAStableCodeAndNeverALeakedMessage() throws Exception {
        BackupOperations.View io = operations.submit("export", "ws_0123456789abcdef", control -> { throw new IOException("/data/data/secret/path failed"); });
        assertTrue(until(() -> operations.get(io.id).state == State.FAILED));
        assertEquals("INVALID_REQUEST", operations.get(io.id).error);
        assertNull(operations.get(io.id).result);
        BackupOperations.View crash = operations.submit("export", "ws_0123456789abcdef", control -> { throw new IllegalStateException("boom"); });
        assertTrue(until(() -> operations.get(crash.id).state == State.FAILED));
        assertEquals("UNAVAILABLE", operations.get(crash.id).error);
    }

    @Test public void progressIsVisibleWhileRunning() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("progress").toFile();
        try {
            for (int i = 0; i < 5; i++) java.nio.file.Files.write(new java.io.File(dir, "f" + i).toPath(), new byte[1000]);
            WorkspaceBackupService service = new WorkspaceBackupService(new java.io.File(dir, "b"), WorkspaceBackupService.Limits.defaults());
            java.io.File ws = new java.io.File(dir, "w"); assertTrue(ws.mkdirs());
            for (int i = 0; i < 5; i++) java.nio.file.Files.write(new java.io.File(ws, "f" + i).toPath(), new byte[1000]);
            BackupOperations.View v = operations.submit("export", "ws_0123456789abcdef", control -> {
                service.export("ws_0123456789abcdef", ws, null, WorkspaceBackupService.Format.ZIP, false, control);
                return Collections.<String, Object>emptyMap();
            });
            assertTrue(until(() -> operations.get(v.id).state == State.COMPLETED));
            assertEquals(5, operations.get(v.id).files); assertEquals(5000, operations.get(v.id).bytes);
        } finally { delete(dir); }
    }
    private static void delete(java.io.File f) { java.io.File[] k = f.listFiles(); if (k != null) for (java.io.File c : k) delete(c); f.delete(); }

    @Test public void onlyTheLastOperationsAreRemembered() throws Exception {
        String firstId = null;
        for (int i = 0; i < BackupOperations.MAX_RETAINED + 4; i++) {
            BackupOperations.View v = operations.submit("export", "ws_0123456789abcdef", control -> Collections.<String, Object>emptyMap());
            if (firstId == null) firstId = v.id;
            final String id = v.id;
            assertTrue(until(() -> operations.get(id).state == State.COMPLETED));
        }
        assertTrue(operations.list().size() <= BackupOperations.MAX_RETAINED + 1);
        assertNull("the oldest was forgotten", operations.get(firstId));
    }

    @Test public void closingCancelsARunningOperationAndRefusesNewOnes() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        BackupOperations.View v = operations.submit("export", "ws_0123456789abcdef", control -> {
            running.countDown();
            while (!control.cancelled()) nap();
            throw new WorkspaceBackupService.Cancelled();
        });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        operations.close();
        assertTrue(until(() -> operations.get(v.id).state == State.CANCELLED));
        try { operations.submit("export", "ws_0123456789abcdef", control -> Collections.<String, Object>emptyMap()); fail(); }
        catch (BackupOperations.BusyException expected) { }
    }
}

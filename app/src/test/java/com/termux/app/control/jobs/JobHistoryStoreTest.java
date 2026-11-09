package com.termux.app.control.jobs;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JobHistoryStoreTest {
    private File root;
    private int counter;

    @Before public void setUp() throws Exception { root = Files.createTempDirectory("job-history").toFile(); }
    @After public void tearDown() { delete(root); }
    private static void delete(File f) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) delete(k); f.delete(); }

    private String newId() { return String.format("job_%032x", ++counter); }

    private JobRecord.Snapshot snapshot(String id, JobRecord.State terminal) {
        JobRecord record = new JobRecord(id, "ws1", 1);
        record.begin(); record.started(null, 1);
        if (terminal != null) record.complete(terminal, terminal == JobRecord.State.EXITED ? 0 : null, terminal == JobRecord.State.FAILED ? JobRecord.Failure.START_FAILED : null, 9);
        return record.snapshot();
    }
    private JobRecord.Snapshot snapshot(String id) { return snapshot(id, null); }

    private JobTerminationEvidence evidence(String id) {
        return new JobTerminationEvidence(id, JobRecord.State.EXITED, 0, null, JobTerminationEvidence.Reason.NORMAL_EXIT, JobTerminationEvidence.Confidence.CONFIRMED,
            123L, null, null, false, false, Arrays.asList("exit code 0"), Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    @Test public void finishedRecordsRoundTripWithoutAnyCommandData() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        assertTrue(store.open().available);
        String id = newId();
        store.finished(snapshot(id, JobRecord.State.EXITED), "balanced", evidence(id), 5000L,
            Arrays.asList(new JobHistoryStore.EventRecord(7, "MEMORY_SOFT_EXCEEDED", "rss>1")), 100L, 200L);
        JobHistoryStore.Record r = store.get(id);
        assertEquals("EXITED", r.state); assertEquals("balanced", r.resourceProfile); assertEquals(Integer.valueOf(0), r.exitCode);
        assertEquals(Long.valueOf(200), r.endedAtWall); assertEquals(Long.valueOf(5000), r.peakRssBytes);
        assertEquals(JobTerminationEvidence.Reason.NORMAL_EXIT, r.evidence.reason);
        assertEquals("MEMORY_SOFT_EXCEEDED", r.events.get(0).type);
        String raw = new String(Files.readAllBytes(new File(root, "v1/history/" + id + ".json").toPath()), StandardCharsets.UTF_8);
        for (String forbidden : new String[] {"executable", "arguments", "env", "stdin", "stdout", "stderr", "token", "password"})
            assertFalse(forbidden, raw.toLowerCase().contains("\"" + forbidden));
    }

    @Test public void anUnfinishedRecordIsRecoveredAsLostAfterARestart() throws Exception {
        JobHistoryStore before = new JobHistoryStore(root);
        before.open();
        String running = newId(), done = newId();
        before.started(snapshot(running), "protected", 1000L);
        before.finished(snapshot(done, JobRecord.State.EXITED), "balanced", evidence(done), 1L, null, 1L, 2L);
        // The service dies here. A new instance reads the same directory.
        JobHistoryStore after = new JobHistoryStore(root);
        JobHistoryStore.OpenResult result = after.open();
        assertEquals(1, result.recoveredAsLost);
        JobHistoryStore.Record lost = after.get(running);
        assertEquals("LOST", lost.state); assertTrue(lost.recoveredAsLost);
        assertNull("no outcome is invented", lost.exitCode); assertNull(lost.signal);
        assertEquals(JobTerminationEvidence.Reason.SERVICE_LOST, lost.evidence.reason);
        assertTrue(lost.evidence.unavailable.toString().contains("what happened to the process"));
        assertEquals("a finished record is untouched", "EXITED", after.get(done).state);
        assertEquals("recovery is idempotent", 0, new JobHistoryStore(root).open().recoveredAsLost);
    }

    @Test public void nothingResemblingAPidIsEverPersisted() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        String id = newId();
        JobRecord record = new JobRecord(id, null, 1);
        record.begin(); record.started(4242, 1);
        store.started(record.snapshot(), null, 1L);
        String raw = new String(Files.readAllBytes(new File(root, "v1/history/" + id + ".json").toPath()), StandardCharsets.UTF_8);
        assertFalse(raw.contains("4242")); assertFalse(raw.toLowerCase().contains("pid"));
    }

    @Test public void interruptedWritesAndCorruptRecordsAreHandled() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        String good = newId(), bad = newId(), partial = newId();
        store.finished(snapshot(good, JobRecord.State.EXITED), null, evidence(good), 1L, null, 1L, 2L);
        Files.write(new File(root, "v1/history/" + bad + ".json").toPath(), "{\"jobId\": \"truncat".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "v1/history/" + partial + ".json.tmp").toPath(), "half".getBytes(StandardCharsets.UTF_8));
        JobHistoryStore.OpenResult result = new JobHistoryStore(root).open();
        assertTrue(result.available);
        assertEquals(1, result.corruptMoved);
        assertEquals(1, result.removed);
        JobHistoryStore reopened = new JobHistoryStore(root); reopened.open();
        assertEquals(1, reopened.list(10).size());
        assertNotNull(reopened.get(good));
        assertNull(reopened.get(bad));
        boolean moved = false;
        for (File f : new File(root, "v1/history").listFiles()) moved |= f.getName().contains(".corrupt-");
        assertTrue("the corrupt record is kept aside for inspection", moved);
    }

    @Test public void aRecordWhoseNameAndIdentityDisagreeIsRejected() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        String a = newId(), b = newId();
        store.finished(snapshot(a, JobRecord.State.EXITED), null, evidence(a), 1L, null, 1L, 2L);
        Files.copy(new File(root, "v1/history/" + a + ".json").toPath(), new File(root, "v1/history/" + b + ".json").toPath());
        JobHistoryStore reopened = new JobHistoryStore(root);
        reopened.open();
        assertNull(reopened.get(b));
    }

    @Test public void historyIsBoundedByCountAndTheOldestFinishedGoFirst() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        String first = newId();
        for (int i = 0; i < JobHistoryStore.MAX_RECORDS + 30; i++) {
            String id = i == 0 ? first : newId();
            store.finished(snapshot(id, JobRecord.State.EXITED), null, evidence(id), 1L, null, 1L, (long) i);
            new File(root, "v1/history/" + id + ".json").setLastModified(1_000_000L + i * 1000L);
        }
        assertTrue(store.list(1000).size() <= JobHistoryStore.MAX_RECORDS);
        assertNull("the oldest record was dropped", store.get(first));
    }

    @Test public void anUnfinishedRecordIsNotTrimmedAwayWhileTheServiceMayStillOwnIt() throws Exception {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        String running = newId();
        store.started(snapshot(running), null, 1L);
        new File(root, "v1/history/" + running + ".json").setLastModified(1000L); // oldest of all
        for (int i = 0; i < JobHistoryStore.MAX_RECORDS + 10; i++) {
            String id = newId();
            store.finished(snapshot(id, JobRecord.State.EXITED), null, evidence(id), 1L, null, 1L, 2L);
        }
        assertNotNull(store.get(running));
    }

    @Test public void aNewerLayoutDisablesTheStoreAndIsNeverOverwritten() throws Exception {
        new File(root, "v1/history").mkdirs(); new File(root, "v1/diagnostics").mkdirs();
        File metadata = new File(root, "v1/metadata.json");
        Files.write(metadata.toPath(), "{\"version\":2}".getBytes(StandardCharsets.UTF_8));
        JobHistoryStore store = new JobHistoryStore(root);
        assertFalse(store.open().available);
        String id = newId();
        store.finished(snapshot(id, JobRecord.State.EXITED), null, evidence(id), 1L, null, 1L, 2L);
        assertFalse(new File(root, "v1/history/" + id + ".json").exists());
        assertEquals("{\"version\":2}", new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void idsThatCouldEscapeTheDirectoryAreIgnored() {
        JobHistoryStore store = new JobHistoryStore(root);
        store.open();
        assertNull(store.get("../../etc/passwd"));
        assertNull(store.get("job_../x"));
        JobRecord record = new JobRecord("../escape", null, 1);
        record.begin(); record.started(null, 1);
        store.started(record.snapshot(), null, 1L);
        assertFalse(new File(root, "v1/escape.json").exists());
        assertTrue(store.list(10).isEmpty());
    }

    @Test public void unavailableStoreNeverThrows() {
        File blocker = new File(root, "blocked");
        try { Files.write(blocker.toPath(), new byte[0]); } catch (Exception e) { fail(); }
        JobHistoryStore store = new JobHistoryStore(blocker); // a file where a directory is needed
        assertFalse(store.open().available);
        String id = newId();
        store.started(snapshot(id), null, 1L);
        store.finished(snapshot(id, JobRecord.State.EXITED), null, null, null, null, 1L, 2L);
        assertTrue(store.list(5).isEmpty());
        assertNull(store.get(id));
    }
}

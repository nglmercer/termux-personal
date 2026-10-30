package com.termux.app.control.process;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ProcessServiceTest {
    private File root;
    private final Set<Integer> sessions = new HashSet<>();
    private final Set<Integer> tasks = new HashSet<>();

    @Before public void createProcTree() throws Exception {
        root = Files.createTempDirectory("proc-service").toFile();
        add(10, 1, 1000); add(11, 10, 1000); add(13, 1, 1000); add(20, 1, 2000);
    }

    @After public void deleteProcTree() {
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) { File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete(); dir.delete(); }
        root.delete();
    }

    private void add(int pid, int ppid, int uid) throws Exception {
        File dir = new File(root, String.valueOf(pid));
        assertTrue(dir.mkdir());
        Files.write(new File(dir, "status").toPath(), ("Name:\ttest\nUid:\t" + uid + "\t" + uid + "\n").getBytes(StandardCharsets.US_ASCII));
        String stat = pid + " (test) S " + ppid + " 0 0 0 0 0 0 0 0 0 10 20 0 0 0 0 0 0 55 0 7";
        Files.write(new File(dir, "stat").toPath(), stat.getBytes(StandardCharsets.US_ASCII));
    }

    private ProcessService service() {
        return new ProcessService(root, 1000, 100, 4096, new ProcessService.Roots() {
            @Override public Set<Integer> sessionRoots() { return sessions; }
            @Override public Set<Integer> taskRoots() { return tasks; }
        });
    }

    private static java.util.List<Integer> pids(ProcessService.Page page) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        for (com.termux.app.monitor.ProcessTable.Row row : page.rows) result.add(row.entry.pid);
        return result;
    }

    @Test public void ownedListsOnlySameUidDescendantsOfSessionRoots() {
        sessions.add(10);
        ProcessService.Page page = service().list("owned", 0, 50);
        assertEquals(Arrays.asList(10, 11), pids(page));
        assertFalse(page.incomplete);
        assertEquals(0, page.missingRoots);
        assertNull(page.nextCursor);
    }

    @Test public void allVisibleListsEverySameUidProcessButNotOtherUids() {
        ProcessService.Page page = service().list("all-visible", 0, 50);
        assertEquals(Arrays.asList(10, 11, 13), pids(page));
    }

    @Test public void pagesOverOneSampleAreOrderedAndCursorAdvances() {
        ProcessService.Page first = service().list("all-visible", 0, 2);
        assertEquals(Arrays.asList(10, 11), pids(first));
        assertEquals(Integer.valueOf(2), first.nextCursor);
        ProcessService.Page second = service().list("all-visible", first.nextCursor, 2);
        assertEquals(Arrays.asList(13), pids(second));
        assertNull(second.nextCursor);
    }

    @Test public void missingSessionRootIsReportedWithoutRows() {
        sessions.add(99);
        ProcessService.Page page = service().list("owned", 0, 50);
        assertTrue(pids(page).isEmpty());
        assertEquals(1, page.missingRoots);
    }

    @Test public void rejectsUnknownScopeAndOutOfRangeBounds() {
        assertInvalid("signal", 0, 50);
        assertInvalid("owned", -1, 50);
        assertInvalid("owned", 0, 0);
        assertInvalid("owned", 0, ProcessService.MAX_PAGE + 1);
        assertInvalid("all-visible", 4, 50);
    }

    private void assertInvalid(String scope, int cursor, int limit) {
        try { service().list(scope, cursor, limit); fail("accepted " + scope + " " + cursor + " " + limit); }
        catch (IllegalArgumentException expected) { /* rejected */ }
    }
}

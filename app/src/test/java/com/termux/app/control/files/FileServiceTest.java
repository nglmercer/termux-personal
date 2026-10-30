package com.termux.app.control.files;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FileServiceTest {
    private File base, root, outside;
    private FileService service;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("file-service").toFile();
        root = new File(base, "rootdir");
        outside = new File(base, "outside");
        assertTrue(root.mkdir()); assertTrue(outside.mkdir());
        write(new File(root, "notes.txt"), "hello world");
        File dir = new File(root, "dir");
        assertTrue(dir.mkdir());
        write(new File(dir, "inner.txt"), "inner");
        write(new File(outside, "secret.txt"), "secret");
        Files.createSymbolicLink(new File(root, "link").toPath(), new File(outside, "secret.txt").toPath());
        Files.createSymbolicLink(new File(root, "dirlink").toPath(), dir.toPath());
        Files.createSymbolicLink(new File(root, "dangling").toPath(), new File(outside, "missing").toPath());
        Map<String, File> roots = new HashMap<>();
        roots.put("home", root);
        service = new FileService(roots);
    }

    @After public void tearDown() {
        deleteTree(base);
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }



    private interface Action { void run() throws Exception; }

    private void expectDenied(String code, Action action) {
        try { action.run(); fail("accepted; expected " + code); }
        catch (FileService.FileException e) { assertEquals(code, e.code); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void listingShowsLinksAsEntriesWithoutFollowingThem() throws Exception {
        FileService.Page page = service.list("home", "", 0, 50);
        Map<String, String> types = new HashMap<>();
        for (FileService.Entry entry : page.entries) types.put(entry.name, entry.type);
        assertEquals("file", types.get("notes.txt"));
        assertEquals("directory", types.get("dir"));
        assertEquals("symlink", types.get("link"));
        assertEquals("symlink", types.get("dirlink"));
        assertFalse(types.containsKey("dangling"));
        assertNull(page.nextCursor);
    }

    @Test public void pagingUsesSortedNamesAndAdvancesCursor() throws Exception {
        FileService.Page first = service.list("home", "", 0, 2);
        assertEquals(2, first.entries.size());
        assertEquals(Integer.valueOf(2), first.nextCursor);
        FileService.Page second = service.list("home", "", first.nextCursor, 10);
        assertNull(second.nextCursor);
        assertEquals(2, second.entries.size());
    }

    @Test public void hostileRelativePathsAreRejected() {
        String[] hostile = {"../outside/secret.txt", "/etc/passwd", "dir//inner.txt", "dir/./inner.txt", "dir/../dir/inner.txt",
            "dir\\inner.txt", "bad\u0000name", "dir/", new String(new char[256]).replace("\0", "a")};
        for (String path : hostile) expectDenied("INVALID_REQUEST", () -> service.stat("home", path));
    }

    @Test public void symbolicLinksAreNeverTraversedOrRead() throws Exception {
        expectDenied("DENIED", () -> service.read("home", "link", 0, 16));
        expectDenied("DENIED", () -> service.read("home", "dirlink/inner.txt", 0, 16));
        expectDenied("DENIED", () -> service.list("home", "dirlink", 0, 10));
        expectDenied("DENIED", () -> service.stat("home", "dirlink/inner.txt"));
        FileService.Entry link = service.stat("home", "link");
        assertEquals("symlink", link.type);
        assertNull(link.size);
    }

    @Test public void readReturnsBoundedChunksAndEof() throws Exception {
        FileService.ReadResult first = service.read("home", "notes.txt", 0, 5);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), first.data);
        assertEquals(5, first.nextOffset);
        assertFalse(first.eof);
        FileService.ReadResult tail = service.read("home", "notes.txt", 5, 64);
        assertEquals(" world", new String(tail.data, StandardCharsets.UTF_8));
        assertTrue(tail.eof);
        FileService.ReadResult beyond = service.read("home", "notes.txt", 100, 4);
        assertEquals(0, beyond.data.length);
        assertTrue(beyond.eof);
    }

    @Test public void directoriesAndMissingEntriesAreReportedAsDomainErrors() {
        expectDenied("UNSUPPORTED", () -> service.read("home", "dir", 0, 4));
        expectDenied("UNSUPPORTED", () -> service.read("home", "", 0, 4));
        expectDenied("NOT_FOUND", () -> service.stat("home", "absent.txt"));
        expectDenied("NOT_FOUND", () -> service.stat("home", "dangling"));
        expectDenied("NOT_FOUND", () -> service.stat("missing-root", "notes.txt"));
        expectDenied("NOT_FOUND", () -> service.stat("home", "notes.txt/child"));
    }

    @Test public void boundsAreEnforced() {
        expectDenied("INVALID_REQUEST", () -> service.read("home", "notes.txt", 0, FileService.MAX_READ + 1));
        expectDenied("INVALID_REQUEST", () -> service.read("home", "notes.txt", -1, 4));
        expectDenied("INVALID_REQUEST", () -> service.list("home", "", 0, FileService.MAX_PAGE + 1));
        expectDenied("INVALID_REQUEST", () -> service.list("home", "", 99, 5));
    }

    @Test public void rootsMustBeDirectoriesWithSafeIds() throws Exception {
        try { new FileService(Collections.singletonMap("home", new File(base, "missing"))); fail(); }
        catch (IllegalArgumentException expected) { /* rejected */ }
        try { new FileService(Collections.singletonMap("Bad Id", root)); fail(); }
        catch (IllegalArgumentException expected) { /* rejected */ }
        assertEquals(Collections.singletonList("home"), service.rootIds());
    }
}

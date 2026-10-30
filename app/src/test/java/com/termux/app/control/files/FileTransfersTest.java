package com.termux.app.control.files;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTransfersTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File home, prefix;
    private FileService files;

    @Before public void setUp() throws Exception {
        home = temp.newFolder("home"); prefix = temp.newFolder("prefix");
        Map<String, File> roots = new LinkedHashMap<>();
        roots.put("home", home); roots.put("prefix", prefix);
        files = new FileService(roots, Collections.singleton("home"), new CanonicalAccess());
    }

    private static String code(ThrowingRunnable action) {
        try { action.run(); fail("expected a file error"); return null; }
        catch (FileService.FileException e) { return e.code; }
    }
    private interface ThrowingRunnable { void run() throws FileService.FileException; }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) text.append(String.format("%02x", b));
        return text.toString();
    }

    private String[] tempNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String name : home.list()) if (name.startsWith(FileTransfers.TEMP_PREFIX)) names.add(name);
        return names.toArray(new String[0]);
    }

    @Test public void chunkedUploadIsVerifiedAndPublishedOnlyAtCommit() throws Exception {
        byte[] data = new byte[FileTransfers.CHUNK_BYTES * 2 + 5];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        FileTransfers.Begin begin = files.transfers().begin("home", "upload.bin", data.length, sha256(data), null, false);
        assertTrue(begin.transferId.matches("tr_[0-9a-f]{32}"));
        long next = 0;
        for (int offset = 0; offset < data.length; offset += FileTransfers.CHUNK_BYTES) {
            byte[] chunk = java.util.Arrays.copyOfRange(data, offset, Math.min(data.length, offset + FileTransfers.CHUNK_BYTES));
            next = files.transfers().write(begin.transferId, offset, chunk);
            assertFalse("nothing is published before commit", new File(home, "upload.bin").exists());
        }
        assertEquals(data.length, next);
        // A retried chunk with identical bytes is accepted without changing the offset; different bytes are not.
        byte[] first = java.util.Arrays.copyOf(data, FileTransfers.CHUNK_BYTES);
        assertEquals(data.length, files.transfers().write(begin.transferId, 0, first));
        byte[] altered = first.clone(); altered[3] ^= 1;
        assertEquals("CONFLICT", code(() -> files.transfers().write(begin.transferId, 0, altered)));
        FileTransfers.Commit commit = files.transfers().commit(begin.transferId);
        assertEquals(sha256(data), commit.sha256);
        assertArrayEquals(data, Files.readAllBytes(new File(home, "upload.bin").toPath()));
        assertEquals(0, tempNames().length);
        assertEquals("NOT_FOUND", code(() -> files.transfers().commit(begin.transferId)));
    }

    @Test public void outOfOrderShortAndMismatchedUploadsNeverPublish() throws Exception {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        FileTransfers.Begin gap = files.transfers().begin("home", "a.txt", data.length, null, null, false);
        assertEquals("CONFLICT", code(() -> files.transfers().write(gap.transferId, 1, data)));
        assertEquals("INVALID_REQUEST", code(() -> files.transfers().write(gap.transferId, 0, "too long!".getBytes(StandardCharsets.UTF_8))));
        files.transfers().write(gap.transferId, 0, java.util.Arrays.copyOf(data, 2));
        assertEquals("a short upload cannot commit", "CONFLICT", code(() -> files.transfers().commit(gap.transferId)));
        files.transfers().abort(gap.transferId);
        assertEquals(0, tempNames().length);

        FileTransfers.Begin wrong = files.transfers().begin("home", "a.txt", data.length, sha256("other".getBytes(StandardCharsets.UTF_8)), null, false);
        files.transfers().write(wrong.transferId, 0, data);
        assertEquals("CONFLICT", code(() -> files.transfers().commit(wrong.transferId)));
        assertFalse(new File(home, "a.txt").exists());
        assertEquals("a failed commit discards the temporary file", 0, tempNames().length);
    }

    @Test public void commitNeverReplacesWithoutAMatchingRevision() throws Exception {
        byte[] data = "new".getBytes(StandardCharsets.UTF_8);
        FileTransfers.Begin begin = files.transfers().begin("home", "race.txt", data.length, null, null, false);
        files.transfers().write(begin.transferId, 0, data);
        Files.write(new File(home, "race.txt").toPath(), "theirs".getBytes(StandardCharsets.UTF_8));
        assertEquals("a destination created meanwhile is not overwritten", "CONFLICT", code(() -> files.transfers().commit(begin.transferId)));
        assertEquals("theirs", new String(Files.readAllBytes(new File(home, "race.txt").toPath()), StandardCharsets.UTF_8));

        assertEquals("CONFLICT", code(() -> files.transfers().begin("home", "race.txt", 3, null, null, false)));
        assertEquals("INVALID_REQUEST", code(() -> files.transfers().begin("home", "race.txt", 3, null, null, true)));
        String revision = files.stat("home", "race.txt").revision;
        FileTransfers.Begin replace = files.transfers().begin("home", "race.txt", data.length, null, revision, true);
        files.transfers().write(replace.transferId, 0, data);
        assertTrue(new File(home, "race.txt").setLastModified(new File(home, "race.txt").lastModified() + 5000));
        assertEquals("STALE_REVISION", code(() -> files.transfers().commit(replace.transferId)));
        assertEquals("theirs", new String(Files.readAllBytes(new File(home, "race.txt").toPath()), StandardCharsets.UTF_8));

        String current = files.stat("home", "race.txt").revision;
        FileTransfers.Begin ok = files.transfers().begin("home", "race.txt", data.length, null, current, true);
        files.transfers().write(ok.transferId, 0, data);
        files.transfers().commit(ok.transferId);
        assertEquals("new", new String(Files.readAllBytes(new File(home, "race.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void readOnlyRootsLimitsAndExpiry() throws Exception {
        assertEquals("DENIED", code(() -> files.transfers().begin("prefix", "x", 1, null, null, false)));
        assertEquals("DENIED", code(() -> files.mkdir("prefix", "x")));
        assertEquals("INVALID_REQUEST", code(() -> files.transfers().begin("home", "../x", 1, null, null, false)));
        assertEquals("NOT_FOUND", code(() -> files.transfers().begin("home", "missing/x", 1, null, null, false)));
        long[] now = {1000};
        files.transfers().setClock(() -> now[0]);
        for (int i = 0; i < FileTransfers.MAX_OPEN; i++) files.transfers().begin("home", "f" + i, 1, null, null, false);
        assertEquals("LIMIT_REACHED", code(() -> files.transfers().begin("home", "extra", 1, null, null, false)));
        now[0] += FileTransfers.IDLE_EXPIRY_MILLIS;
        assertEquals("idle transfers expire and release their files", 0, files.transfers().openCount());
        assertEquals(0, tempNames().length);
    }

    @Test public void mkdirRenameAndDeleteStayInsideTheRoot() throws Exception {
        assertEquals("directory", files.mkdir("home", "dir").type);
        assertEquals("CONFLICT", code(() -> files.mkdir("home", "dir")));
        Files.write(new File(home, "dir/a.txt").toPath(), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(home, "b.txt").toPath(), "b".getBytes(StandardCharsets.UTF_8));
        assertEquals("rename never overwrites", "CONFLICT", code(() -> files.rename("home", "b.txt", "dir/a.txt", null)));
        assertEquals("STALE_REVISION", code(() -> files.rename("home", "b.txt", "dir/b.txt", "r0.0")));
        assertEquals("dir/b.txt", files.rename("home", "b.txt", "dir/b.txt", files.stat("home", "b.txt").revision).path);
        assertEquals("a non-empty directory is not deleted", "CONFLICT", code(() -> files.delete("home", "dir", null)));
        Files.createSymbolicLink(new File(home, "link").toPath(), prefix.toPath());
        assertEquals("a link is not deleted through", "DENIED", code(() -> files.mkdir("home", "link/x")));
        assertEquals("UNSUPPORTED", code(() -> files.delete("home", "link", null)));
        files.delete("home", "dir/a.txt", null);
        files.delete("home", "dir/b.txt", null);
        files.delete("home", "dir", null);
        assertFalse(new File(home, "dir").exists());
        assertEquals(0, prefix.list().length);
    }
}

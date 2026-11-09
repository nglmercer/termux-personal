package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import com.termux.app.control.workspace.WorkspaceBackupService.Format;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkspaceBackupServiceTest {
    private static final String WS = "ws_00000000000000aa";
    private File base, workspace, backups, outside;
    private WorkspaceBackupService service;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("backup-test").toFile();
        workspace = new File(base, "ws"); backups = new File(base, "backups"); outside = new File(base, "outside");
        assertTrue(workspace.mkdirs()); assertTrue(outside.mkdirs());
        service = new WorkspaceBackupService(backups, WorkspaceBackupService.Limits.defaults());
    }
    @After public void tearDown() { delete(base); }
    private static void delete(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) delete(c); f.delete(); }
    private static void write(File f, String text) throws IOException { f.getParentFile().mkdirs(); Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    private void populate() throws Exception {
        write(new File(workspace, "src/main.rs"), "fn main() {}");
        write(new File(workspace, "README.md"), "hello");
        write(new File(workspace, "node_modules/dep/index.js"), "x");
        write(new File(workspace, "target/debug/app"), "bin");
        write(new File(workspace, ".git/config"), "git");
        write(new File(workspace, "cache/blob"), "c");
        write(new File(workspace, "build.tmp"), "t");
        write(new File(workspace, ".env"), "SECRET=1");
        write(new File(workspace, "keys/id_ed25519"), "key");
        write(new File(workspace, "server.pem"), "pem");
    }

    private WorkspaceBackupService.Summary export(Format format, boolean sensitive) throws Exception {
        return service.export(WS, workspace, "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8), format, sensitive, new WorkspaceBackupService.Control());
    }

    private File restoreTarget() { File t = new File(base, "restore"); t.mkdirs(); return t; }

    private WorkspaceBackupService.Summary restore(WorkspaceBackupService.Summary s, File target, List<String> selection, boolean overwrite, byte[][] config) throws Exception {
        return service.restore(s.backupId, service.resolve(WS, s.backupId), target, selection, overwrite, new WorkspaceBackupService.Control(), c -> { if (config != null) config[0] = c; });
    }

    // ---- export ----

    @Test public void exportSkipsBuildCacheTemporaryAndSensitiveFilesByDefaultForBothFormats() throws Exception {
        populate();
        for (Format format : Format.values()) {
            WorkspaceBackupService.Summary s = export(format, false);
            assertEquals(format.toString(), 2, s.files); // src/main.rs and README.md
            assertTrue(s.skippedExcluded >= 5); assertEquals(3, s.skippedSensitive);
            File target = restoreTarget();
            byte[][] config = new byte[1][];
            restore(s, target, null, false, config);
            assertEquals("fn main() {}", read(new File(target, "src/main.rs")));
            assertFalse(new File(target, "node_modules").exists()); assertFalse(new File(target, ".env").exists());
            assertFalse(new File(target, "keys/id_ed25519").exists()); assertFalse(new File(target, "server.pem").exists());
            assertEquals("config travels as metadata", "{\"name\":\"x\"}", new String(config[0], StandardCharsets.UTF_8));
            delete(target);
        }
    }

    @Test public void sensitiveFilesNeedAnExplicitChoice() throws Exception {
        populate();
        WorkspaceBackupService.Summary s = export(Format.ZIP, true);
        assertEquals(0, s.skippedSensitive); assertEquals(5, s.files);
        File target = restoreTarget();
        restore(s, target, null, false, null);
        assertEquals("SECRET=1", read(new File(target, ".env")));
    }

    @Test public void exportNeverFollowsSymbolicLinks() throws Exception {
        write(new File(outside, "secret.txt"), "outside data");
        write(new File(workspace, "real.txt"), "ok");
        Files.createSymbolicLink(new File(workspace, "link.txt").toPath(), new File(outside, "secret.txt").toPath());
        Files.createSymbolicLink(new File(workspace, "dirlink").toPath(), outside.toPath());
        for (Format format : Format.values()) {
            WorkspaceBackupService.Summary s = export(format, false);
            assertEquals(1, s.files); assertTrue(s.skippedLinks >= 1);
            File target = restoreTarget();
            restore(s, target, null, false, null);
            assertFalse(new File(target, "link.txt").exists()); assertFalse(new File(target, "dirlink").exists());
            delete(target);
        }
    }

    @Test public void limitsBoundTheArchiveAndSayWhenTheyCut() throws Exception {
        for (int i = 0; i < 10; i++) write(new File(workspace, "f" + i), "data");
        service = new WorkspaceBackupService(backups, new WorkspaceBackupService.Limits(4, 1 << 20, 1 << 20, 200, 240, 24, 8));
        WorkspaceBackupService.Summary s = export(Format.ZIP, false);
        assertEquals(4, s.files); assertTrue(s.truncated);
    }

    @Test public void cancellingStopsExportAndLeavesNoPartialBackup() throws Exception {
        for (int i = 0; i < 50; i++) write(new File(workspace, "f" + i), "data");
        WorkspaceBackupService.Control control = new WorkspaceBackupService.Control();
        control.cancel();
        try { service.export(WS, workspace, null, Format.ZIP, false, control); fail("should be cancelled"); }
        catch (WorkspaceBackupService.Cancelled expected) { }
        String[] left = backups.list();
        assertTrue("no archive or .part file remains", left == null || left.length == 0);
    }

    @Test public void onlyTheNewestBackupsPerWorkspaceAreKept() throws Exception {
        write(new File(workspace, "a"), "1");
        service = new WorkspaceBackupService(backups, new WorkspaceBackupService.Limits(100, 1 << 20, 1 << 20, 200, 240, 24, 2));
        for (String stamp : new String[] {"20240101-000001", "20240101-000002", "20240101-000003"})
            write(new File(backups, WS + "-" + stamp + ".zip"), "old");
        export(Format.ZIP, false);
        List<File> kept = service.list(WS);
        assertEquals(2, kept.size());
    }

    // ---- restore: hostile archives ----

    private File zip(String name, Object... entries) throws Exception {
        File f = new File(backups, WS + "-20250101-000000" + ".zip");
        backups.mkdirs();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(f.toPath()))) {
            for (int i = 0; i < entries.length; i += 2) { out.putNextEntry(new ZipEntry((String) entries[i])); out.write(((String) entries[i + 1]).getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        return f;
    }

    private WorkspaceBackupService.Summary restoreFile(File archive, File target, boolean overwrite) throws Exception {
        return service.restore(archive.getName(), archive, target, null, overwrite, new WorkspaceBackupService.Control(), null);
    }

    @Test public void zipSlipAndOtherUnsafeNamesAreRefusedAndCounted() throws Exception {
        File target = restoreTarget();
        File archive = zip("evil", "../escaped.txt", "x", "a/../../escaped2.txt", "x", "/etc/absolute.txt", "x", "dir\\win.txt", "x",
            "C:/drive.txt", "x", "ok/safe.txt", "fine", "./dot.txt", "x", "a//b.txt", "x");
        WorkspaceBackupService.Summary s = restoreFile(archive, target, false);
        assertEquals(1, s.files);
        assertEquals(7, s.skippedUnsafe);
        assertEquals("fine", read(new File(target, "ok/safe.txt")));
        assertFalse(new File(base, "escaped.txt").exists()); assertFalse(new File(base, "escaped2.txt").exists());
        assertFalse(new File("/etc/absolute.txt").exists() && read(new File("/etc/absolute.txt")).equals("x"));
    }

    @Test public void nothingIsWrittenThroughASymbolicLinkInsideTheTarget() throws Exception {
        File target = restoreTarget();
        Files.createSymbolicLink(new File(target, "evil").toPath(), outside.toPath());
        File archive = zip("through-link", "evil/pwned.txt", "x");
        WorkspaceBackupService.Summary s = restoreFile(archive, target, true);
        assertEquals(0, s.files); assertEquals(1, s.skippedUnsafe);
        assertFalse(new File(outside, "pwned.txt").exists());
    }

    @Test public void anExistingSymlinkIsNeverReplacedEvenWithOverwrite() throws Exception {
        File target = restoreTarget();
        write(new File(outside, "victim.txt"), "original");
        Files.createSymbolicLink(new File(target, "file.txt").toPath(), new File(outside, "victim.txt").toPath());
        File archive = zip("swap", "file.txt", "attacker");
        WorkspaceBackupService.Summary s = restoreFile(archive, target, true);
        assertEquals(0, s.files); assertEquals("a link in the way is counted as unsafe", 1, s.skippedUnsafe);
        assertEquals("original", read(new File(outside, "victim.txt")));
        assertTrue(Files.isSymbolicLink(new File(target, "file.txt").toPath()));
    }

    @Test public void existingFilesAreKeptUnlessOverwriteIsExplicitAndTheSwapIsAtomic() throws Exception {
        File target = restoreTarget();
        write(new File(target, "a.txt"), "mine");
        File archive = zip("existing", "a.txt", "theirs", "b.txt", "new");
        WorkspaceBackupService.Summary kept = restoreFile(archive, target, false);
        assertEquals(1, kept.conflicts); assertEquals(1, kept.files);
        assertEquals("mine", read(new File(target, "a.txt")));
        WorkspaceBackupService.Summary replaced = restoreFile(archive, target, true);
        assertEquals(0, replaced.conflicts);
        assertEquals("theirs", read(new File(target, "a.txt")));
        for (String name : target.list()) assertFalse("no temporary files left", name.endsWith(".restore-part"));
    }

    @Test public void aFileCannotReplaceADirectoryAndADirectoryCannotBeUsedAsAFile() throws Exception {
        File target = restoreTarget();
        assertTrue(new File(target, "dir").mkdirs());
        write(new File(target, "plain"), "file");
        File archive = zip("shape", "dir", "x", "plain/child.txt", "x");
        WorkspaceBackupService.Summary s = restoreFile(archive, target, true);
        assertEquals(0, s.files);
        assertTrue(new File(target, "dir").isDirectory()); assertEquals("file", read(new File(target, "plain")));
    }

    @Test public void aZipBombStopsEarlyOnSizeAndRatio() throws Exception {
        File target = restoreTarget();
        File archive = new File(backups, WS + "-20250101-000001.zip");
        backups.mkdirs();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            out.putNextEntry(new ZipEntry("zeros.bin"));
            byte[] chunk = new byte[1 << 20];
            for (int i = 0; i < 64; i++) out.write(chunk); // 64 MiB of zeros compresses to ~64 KiB
            out.closeEntry();
        }
        try { restoreFile(archive, target, false); fail("a bomb must be stopped"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("ratio") || expected.getMessage().contains("size")); }
        String[] left = target.list();
        assertTrue("the partial file was removed", left == null || left.length == 0);
        // A hard size cap also applies regardless of ratio.
        service = new WorkspaceBackupService(backups, new WorkspaceBackupService.Limits(100, 1 << 20, 1 << 20, 100_000, 240, 24, 8));
        try { restoreFile(archive, target, false); fail("size limit"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("size")); }
    }

    @Test public void tooManyEntriesAndOverlongOrDeepNamesAreBounded() throws Exception {
        File target = restoreTarget();
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) { entries.add("f" + i); entries.add("x"); }
        service = new WorkspaceBackupService(backups, new WorkspaceBackupService.Limits(3, 1 << 20, 1 << 20, 200, 240, 24, 8));
        WorkspaceBackupService.Summary s = restoreFile(zip("many", entries.toArray()), target, false);
        assertEquals(3, s.files); assertTrue(s.truncated);
        StringBuilder deep = new StringBuilder(); for (int i = 0; i < 30; i++) deep.append("d/");
        StringBuilder longName = new StringBuilder(); for (int i = 0; i < 300; i++) longName.append('n');
        service = new WorkspaceBackupService(backups, WorkspaceBackupService.Limits.defaults());
        File target2 = new File(base, "restore2"); target2.mkdirs();
        WorkspaceBackupService.Summary t = restoreFile(zip("deep", deep + "x.txt", "x", longName.toString(), "x"), target2, false);
        assertEquals(0, t.files); assertEquals(2, t.skippedUnsafe);
    }

    @Test public void selectedPathsRestoreOnlyThoseFiles() throws Exception {
        populate();
        WorkspaceBackupService.Summary s = export(Format.TAR_GZ, false);
        File target = restoreTarget();
        WorkspaceBackupService.Summary r = restore(s, target, Arrays.asList("src"), false, null);
        assertEquals(1, r.files);
        assertTrue(new File(target, "src/main.rs").exists()); assertFalse(new File(target, "README.md").exists());
        assertFalse("a prefix is a path boundary, not a substring", service.restore(s.backupId, service.resolve(WS, s.backupId), restoreTarget(), Arrays.asList("sr"), false,
            new WorkspaceBackupService.Control(), null).files > 0);
    }

    @Test public void cancellingARestoreStopsItAndRemovesTheTemporaryFile() throws Exception {
        File target = restoreTarget();
        WorkspaceBackupService.Control control = new WorkspaceBackupService.Control();
        control.cancel();
        try { service.restore("x", zip("c", "a.txt", "data"), target, null, false, control, null); fail(); }
        catch (WorkspaceBackupService.Cancelled expected) { }
        assertEquals(0, target.list().length);
    }

    // ---- tar reader hardening ----

    private File tar(byte[]... blocks) throws Exception {
        File f = new File(backups, WS + "-20250101-000002.tar.gz");
        backups.mkdirs();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(f.toPath()))) { for (byte[] b : blocks) out.write(b); out.write(new byte[1024]); }
        return f;
    }

    private static byte[] tarEntry(String name, char type, String content, String linkName) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(100, n.length));
        System.arraycopy("0000644".getBytes(), 0, h, 100, 7);
        byte[] c = content.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(String.format("%011o", c.length).getBytes(), 0, h, 124, 11);
        h[156] = (byte) type;
        if (linkName != null) System.arraycopy(linkName.getBytes(), 0, h, 157, linkName.length());
        System.arraycopy("ustar".getBytes(), 0, h, 257, 5);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        long sum = 0; for (byte b : h) sum += b & 0xff;
        System.arraycopy(String.format("%06o", sum).getBytes(), 0, h, 148, 6); h[154] = 0; h[155] = ' ';
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(h, 0, 512);
        out.write(c, 0, c.length);
        out.write(new byte[(512 - c.length % 512) % 512], 0, (512 - c.length % 512) % 512);
        return out.toByteArray();
    }

    @Test public void tarLinksDevicesAndTraversalAreRefusedAndTheStreamStaysInSync() throws Exception {
        File target = restoreTarget();
        File archive = tar(tarEntry("link", '2', "", "/etc/passwd"), tarEntry("hard", '1', "", "x"), tarEntry("dev", '3', "", null),
            tarEntry("../escape.txt", '0', "x", null), tarEntry("good.txt", '0', "kept", null));
        WorkspaceBackupService.Summary s = service.restore(archive.getName(), archive, target, null, false, new WorkspaceBackupService.Control(), null);
        assertEquals(1, s.files);
        assertEquals(4, s.skippedUnsafe);
        assertEquals("kept", read(new File(target, "good.txt")));
        assertFalse(new File(base, "escape.txt").exists());
    }

    @Test public void aTruncatedTarFailsInsteadOfWritingGarbageSilently() throws Exception {
        File target = restoreTarget();
        File archive = new File(backups, WS + "-20250101-000003.tar.gz");
        backups.mkdirs();
        byte[] entry = tarEntry("big.txt", '0', new String(new char[2000]).replace('\0', 'a'), null);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive.toPath()))) { out.write(entry, 0, 700); } // cut mid-file
        try { service.restore("t", archive, target, null, false, new WorkspaceBackupService.Control(), null); fail("truncated archive"); }
        catch (IOException expected) { }
        assertFalse(new File(target, "big.txt").exists());
    }

    @Test public void backupIdsCannotEscapeTheBackupDirectory() throws Exception {
        write(new File(backups, WS + "-20250101-000000.zip"), "z");
        assertNotNull(service.resolve(WS, WS + "-20250101-000000.zip"));
        for (String bad : new String[] {"../" + WS + "-20250101-000000.zip", WS + "-20250101-000000.zip/../x", "/etc/passwd", WS + "-x.zip", "ws_ffffffffffffffff-20250101-000000.zip", null})
            assertNull(String.valueOf(bad), service.resolve(WS, bad));
    }
}

package com.termux.app.control.workspace;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports a workspace directory to a ZIP or tar.gz archive in app-private storage and restores selected files from one.
 * Everything streams through a small buffer: no archive and no file is ever held whole in memory.
 *
 * <p>Requires {@code java.nio.file} (Android 8.0, API 26): callers must not create it on older versions, and
 * the feature is then simply not advertised.
 *
 * <p>Export never follows symbolic links (they are skipped and counted), leaves out build and cache directories by default
 * ({@code node_modules}, {@code .gradle}, {@code target}, {@code .git}, {@code cache}) and temporary files, and leaves out files that
 * look sensitive unless the caller explicitly includes them.
 *
 * <p>Restore treats the archive as hostile: absolute paths, {@code ..}, backslashes, NUL, drive letters, over-long or too deep
 * names, links, devices and anything that would be written through a symbolic link are refused and counted; the file count, the
 * total unpacked size, a single file's size and the compression ratio are bounded, so a zip bomb stops early. Existing files are
 * never overwritten unless the caller says so, and an overwrite replaces the file atomically and never touches a symlink.
 */
public final class WorkspaceBackupService {
    public enum Format {
        ZIP("zip", ".zip"), TAR_GZ("tar.gz", ".tar.gz");
        public final String wireName, extension;
        Format(String wireName, String extension) { this.wireName = wireName; this.extension = extension; }
        public static Format parse(String value) {
            for (Format f : values()) if (f.wireName.equals(value)) return f;
            throw new IllegalArgumentException("Unknown format");
        }
    }

    public static final class Limits {
        public final int maxFiles, maxPathBytes, maxDepth, maxBackupsPerWorkspace;
        public final long maxTotalBytes, maxFileBytes;
        public final int maxCompressionRatio;
        public Limits(int maxFiles, long maxTotalBytes, long maxFileBytes, int maxCompressionRatio, int maxPathBytes, int maxDepth, int maxBackupsPerWorkspace) {
            if (maxFiles < 1 || maxTotalBytes < 1 || maxFileBytes < 1 || maxCompressionRatio < 1 || maxPathBytes < 8 || maxDepth < 1 || maxBackupsPerWorkspace < 1)
                throw new IllegalArgumentException("Invalid limits");
            this.maxFiles = maxFiles; this.maxTotalBytes = maxTotalBytes; this.maxFileBytes = maxFileBytes; this.maxCompressionRatio = maxCompressionRatio;
            this.maxPathBytes = maxPathBytes; this.maxDepth = maxDepth; this.maxBackupsPerWorkspace = maxBackupsPerWorkspace;
        }
        public static Limits defaults() { return new Limits(20_000, 512L << 20, 256L << 20, 200, 240, 24, 8); }
    }

    /** Progress and cancellation for one operation. Callbacks run on the worker thread. */
    public static final class Control {
        private final AtomicBoolean mCancelled = new AtomicBoolean();
        private volatile long mFiles, mBytes;
        public void cancel() { mCancelled.set(true); }
        public boolean cancelled() { return mCancelled.get(); }
        public long files() { return mFiles; }
        public long bytes() { return mBytes; }
    }

    public static final class Cancelled extends IOException { Cancelled() { super("Cancelled"); } }

    public static final class Summary {
        public final String backupId; public final Format format;
        public final int files, skippedExcluded, skippedSensitive, skippedLinks, skippedUnsafe, conflicts;
        public final long bytes, archiveBytes;
        public final boolean truncated;
        Summary(String id, Format format, int files, long bytes, long archiveBytes, int excluded, int sensitive, int links, int unsafe, int conflicts, boolean truncated) {
            this.backupId = id; this.format = format; this.files = files; this.bytes = bytes; this.archiveBytes = archiveBytes;
            this.skippedExcluded = excluded; this.skippedSensitive = sensitive; this.skippedLinks = links; this.skippedUnsafe = unsafe;
            this.conflicts = conflicts; this.truncated = truncated;
        }
    }

    static final String CONFIG_ENTRY = ".termux-workspace/workspace.json";
    private static final Set<String> EXCLUDED_DIRECTORIES = new HashSet<>(Arrays.asList("node_modules", ".gradle", "target", ".git", "cache", ".cache"));
    private static final Pattern EXCLUDED_FILES = Pattern.compile("(?i).*(\\.tmp|\\.temp|\\.swp|~)$");
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?i)(\\.env(\\..*)?|id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?|.*\\.(pem|key|p12|pfx|jks|keystore)|\\.netrc|\\.npmrc|\\.pypirc|credentials(\\..*)?|secrets?(\\..*)?|\\.git-credentials)");
    private static final Pattern ID = Pattern.compile("(ws_[0-9a-f]{16})-(\\d{8}-\\d{6})(\\.zip|\\.tar\\.gz)");

    private final File mBackupDirectory;
    private final Limits mLimits;
    private BackupOperations mOperations;

    /** Background operations with progress and cancellation; created on first use. */
    public synchronized BackupOperations operations() {
        if (mOperations == null) mOperations = new BackupOperations();
        return mOperations;
    }

    /** Cancels a running background operation and stops accepting new ones. */
    public synchronized void close() { if (mOperations != null) mOperations.close(); }

    public WorkspaceBackupService(File backupDirectory, Limits limits) {
        if (backupDirectory == null || limits == null) throw new IllegalArgumentException("Missing backup configuration");
        mBackupDirectory = backupDirectory; mLimits = limits;
    }

    // ---- export ----

    /**
     * @param workspaceDir canonical workspace directory (the caller resolved it through the file service)
     * @param configJson   workspace definition to store as metadata, without any absolute path; may be null
     */
    public Summary export(String workspaceId, File workspaceDir, byte[] configJson, Format format, boolean includeSensitive, Control control) throws IOException {
        if (!WorkspaceRepository.validId(workspaceId) || workspaceDir == null || !workspaceDir.isDirectory() || format == null || control == null)
            throw new IllegalArgumentException("Invalid export request");
        if (!mBackupDirectory.isDirectory() && !mBackupDirectory.mkdirs()) throw new IOException("Backup storage unavailable");
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String id = workspaceId + "-" + stamp.format(new Date()) + format.extension;
        File target = new File(mBackupDirectory, id), temp = new File(mBackupDirectory, id + ".part");
        Counters c = new Counters();
        boolean ok = false;
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            ArchiveWriter writer = format == Format.ZIP ? new ZipWriter(raw) : new TarGzWriter(raw);
            if (configJson != null) writer.file(CONFIG_ENTRY, new java.io.ByteArrayInputStream(configJson), configJson.length, control, c, false);
            walk(workspaceDir.toPath(), writer, includeSensitive, control, c);
            writer.close();
            ok = true;
        } finally { if (!ok) temp.delete(); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Cannot finalize backup"); }
        enforceBackupCount(workspaceId);
        return new Summary(id, format, c.files, c.bytes, target.length(), c.excluded, c.sensitive, c.links, c.unsafe, 0, c.truncated);
    }

    private void walk(Path root, ArchiveWriter writer, boolean includeSensitive, Control control, Counters c) throws IOException {
        Files.walkFileTree(root, EnumSetNoFollow.NONE, mLimits.maxDepth, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (EXCLUDED_DIRECTORIES.contains(dir.getFileName().toString())) { c.excluded++; return FileVisitResult.SKIP_SUBTREE; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (control.cancelled()) throw new Cancelled();
                String name = file.getFileName().toString();
                if (attrs.isSymbolicLink()) { c.links++; return FileVisitResult.CONTINUE; }
                if (!attrs.isRegularFile()) { c.unsafe++; return FileVisitResult.CONTINUE; }
                if (EXCLUDED_FILES.matcher(name).matches()) { c.excluded++; return FileVisitResult.CONTINUE; }
                if (!includeSensitive && SENSITIVE.matcher(name).matches()) { c.sensitive++; return FileVisitResult.CONTINUE; }
                String entry = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (entry.getBytes(StandardCharsets.UTF_8).length > mLimits.maxPathBytes) { c.unsafe++; return FileVisitResult.CONTINUE; }
                if (c.files >= mLimits.maxFiles || c.bytes + attrs.size() > mLimits.maxTotalBytes || attrs.size() > mLimits.maxFileBytes) { c.truncated = true; return FileVisitResult.CONTINUE; }
                // NOFOLLOW so a file swapped for a link after the walk is still not followed.
                try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { writer.file(entry, in, attrs.size(), control, c, true); }
                catch (java.nio.file.FileSystemException e) { c.unsafe++; }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) { c.unsafe++; return FileVisitResult.CONTINUE; }
        });
    }

    /** Oldest backups of a workspace beyond the bound are removed. */
    private void enforceBackupCount(String workspaceId) {
        File[] files = mBackupDirectory.listFiles((dir, name) -> name.startsWith(workspaceId + "-") && ID.matcher(name).matches());
        if (files == null || files.length <= mLimits.maxBackupsPerWorkspace) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName())); // the timestamp sorts chronologically
        for (int i = 0; i < files.length - mLimits.maxBackupsPerWorkspace; i++) files[i].delete();
    }

    /** Backups of one workspace, oldest first. Only names this class would create are listed. */
    public List<File> list(String workspaceId) {
        List<File> result = new ArrayList<>();
        File[] files = mBackupDirectory.listFiles((dir, name) -> name.startsWith(workspaceId + "-") && ID.matcher(name).matches());
        if (files != null) { Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName())); result.addAll(Arrays.asList(files)); }
        return result;
    }

    /** Resolves an ID to a file inside the backup directory, or null. The pattern forbids any path component. */
    public File resolve(String workspaceId, String backupId) {
        if (backupId == null || !ID.matcher(backupId).matches() || !backupId.startsWith(workspaceId + "-")) return null;
        File file = new File(mBackupDirectory, backupId);
        return file.isFile() ? file : null;
    }

    // ---- restore ----

    /**
     * @param selection   archive path prefixes to restore, or null for everything
     * @param overwrite   replace existing regular files (the caller has already obtained confirmation); otherwise they are kept and counted
     * @param configSink  receives the stored workspace definition if the archive has one; may be null
     */
    public Summary restore(String backupId, File archive, File workspaceDir, List<String> selection, boolean overwrite, Control control,
                           java.util.function.Consumer<byte[]> configSink) throws IOException {
        if (archive == null || workspaceDir == null || !workspaceDir.isDirectory() || control == null) throw new IllegalArgumentException("Invalid restore request");
        Format format = archive.getName().endsWith(".zip") ? Format.ZIP : Format.TAR_GZ;
        Path root = workspaceDir.toPath().toRealPath();
        Counters c = new Counters();
        long archiveBytes = archive.length();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(archive.toPath()))) {
            ArchiveReader reader = format == Format.ZIP ? new ZipReader(in) : new TarGzReader(in);
            ArchiveReader.Entry entry;
            while ((entry = reader.next()) != null) {
                if (control.cancelled()) throw new Cancelled();
                if (!entry.regular) { c.unsafe++; reader.skip(); continue; }
                if (entry.name.equals(CONFIG_ENTRY)) {
                    byte[] config = reader.readBounded(1 << 20);
                    if (configSink != null && config != null) configSink.accept(config);
                    continue;
                }
                String safe = safeName(entry.name);
                if (safe == null || !selected(safe, selection)) { if (safe == null) c.unsafe++; reader.skip(); continue; }
                if (c.files >= mLimits.maxFiles) { c.truncated = true; break; }
                Path target = confined(root, safe);
                if (target == null) { c.unsafe++; reader.skip(); continue; }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!overwrite || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { c.conflicts++; reader.skip(); continue; }
                }
                Files.createDirectories(target.getParent());
                if (!insideRealRoot(root, target.getParent())) { c.unsafe++; reader.skip(); continue; }
                Path temp = target.resolveSibling(target.getFileName() + ".restore-part");
                long written = 0;
                boolean done = false;
                try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {
                        written += n;
                        c.bytes += n; control.mBytes = c.bytes;
                        if (written > mLimits.maxFileBytes || c.bytes > mLimits.maxTotalBytes) throw new IOException("Archive exceeds the size limit");
                        // Compression ratio: a tiny archive that inflates enormously is a bomb, whatever the entry header claims.
                        if (c.bytes > (1 << 20) && c.bytes / Math.max(1, archiveBytes) > mLimits.maxCompressionRatio) throw new IOException("Archive compression ratio too high");
                        if (control.cancelled()) throw new Cancelled();
                        out.write(buffer, 0, n);
                    }
                    done = true;
                } finally { if (!done) Files.deleteIfExists(temp); }
                // A final check right before the swap: the destination must still not be a link.
                if (Files.isSymbolicLink(target)) { Files.deleteIfExists(temp); c.unsafe++; continue; }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                c.files++; control.mFiles = c.files;
            }
        }
        return new Summary(backupId, format, c.files, c.bytes, archiveBytes, 0, 0, 0, c.unsafe, c.conflicts, c.truncated);
    }

    /** Normalized relative path with forward slashes, or null if it is anything but a plain relative path. */
    String safeName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.startsWith("/") || name.endsWith("/")) return null;
        if (name.getBytes(StandardCharsets.UTF_8).length > mLimits.maxPathBytes) return null;
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') return null; // drive letter
        String[] parts = name.split("/", -1);
        if (parts.length > mLimits.maxDepth) return null;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 0x20) return null;
        return name;
    }

    private static boolean selected(String name, List<String> selection) {
        if (selection == null || selection.isEmpty()) return true;
        for (String prefix : selection) if (name.equals(prefix) || name.startsWith(prefix.endsWith("/") ? prefix : prefix + "/")) return true;
        return false;
    }

    /** The target path, only if it stays under {@code root} lexically and no existing ancestor is a symbolic link. */
    private static Path confined(Path root, String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) return null;
        Path walk = root;
        for (Path part : root.relativize(target)) {
            walk = walk.resolve(part);
            if (Files.isSymbolicLink(walk)) return null;
            if (Files.exists(walk, LinkOption.NOFOLLOW_LINKS) && !walk.equals(target) && !Files.isDirectory(walk, LinkOption.NOFOLLOW_LINKS)) return null;
        }
        return target;
    }

    private static boolean insideRealRoot(Path realRoot, Path directory) {
        try { return directory.toRealPath().startsWith(realRoot); } catch (IOException e) { return false; }
    }

    // ---- archive formats ----

    private static final class Counters { int files, excluded, sensitive, links, unsafe, conflicts; long bytes; boolean truncated; }
    private static final class EnumSetNoFollow { static final Set<java.nio.file.FileVisitOption> NONE = java.util.Collections.emptySet(); }

    private interface ArchiveWriter extends AutoCloseable {
        void file(String name, InputStream in, long size, Control control, Counters counters, boolean counted) throws IOException;
        @Override void close() throws IOException;
    }

    private static final class ZipWriter implements ArchiveWriter {
        private final ZipOutputStream zip;
        ZipWriter(OutputStream out) { zip = new ZipOutputStream(out); zip.setLevel(6); }
        @Override public void file(String name, InputStream in, long size, Control control, Counters c, boolean counted) throws IOException {
            ZipEntry entry = new ZipEntry(name); entry.setTime(0);
            zip.putNextEntry(entry);
            copy(in, zip, control, c, counted);
            zip.closeEntry();
            if (counted) { c.files++; control.mFiles = c.files; }
        }
        @Override public void close() throws IOException { zip.finish(); zip.flush(); }
    }

    /** Minimal ustar writer: regular files only, names up to 255 bytes split into prefix and name. */
    private static final class TarGzWriter implements ArchiveWriter {
        private final GZIPOutputStream gz;
        TarGzWriter(OutputStream out) throws IOException { gz = new GZIPOutputStream(out); }
        @Override public void file(String name, InputStream in, long size, Control control, Counters c, boolean counted) throws IOException {
            byte[] header = new byte[512];
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            String prefix = "", base = name;
            if (nameBytes.length > 100) {
                int cut = name.lastIndexOf('/', 155);
                while (cut > 0 && (name.substring(cut + 1).getBytes(StandardCharsets.UTF_8).length > 100)) cut = name.lastIndexOf('/', cut - 1);
                if (cut <= 0 || name.substring(0, cut).getBytes(StandardCharsets.UTF_8).length > 155) throw new IOException("Path too long for tar");
                prefix = name.substring(0, cut); base = name.substring(cut + 1);
            }
            put(header, 0, 100, base); put(header, 100, 8, "0000644"); put(header, 108, 8, "0000000"); put(header, 116, 8, "0000000");
            put(header, 124, 12, String.format(Locale.ROOT, "%011o", size)); put(header, 136, 12, "00000000000");
            for (int i = 148; i < 156; i++) header[i] = ' ';
            header[156] = '0'; put(header, 257, 6, "ustar"); put(header, 263, 2, "00"); put(header, 345, 155, prefix);
            long sum = 0; for (byte b : header) sum += b & 0xff;
            put(header, 148, 8, String.format(Locale.ROOT, "%06o", sum)); header[154] = 0; header[155] = ' ';
            gz.write(header);
            long copied = copy(in, gz, control, c, counted);
            if (copied != size) throw new IOException("File changed while being archived");
            int pad = (int) ((512 - (size % 512)) % 512);
            gz.write(new byte[pad]);
            if (counted) { c.files++; control.mFiles = c.files; }
        }
        private static void put(byte[] block, int offset, int length, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, block, offset, Math.min(bytes.length, length));
        }
        @Override public void close() throws IOException { gz.write(new byte[1024]); gz.finish(); gz.flush(); }
    }

    private static long copy(InputStream in, OutputStream out, Control control, Counters c, boolean counted) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            if (control.cancelled()) throw new Cancelled();
            out.write(buffer, 0, n); total += n;
            if (counted) { c.bytes += n; control.mBytes = c.bytes; }
        }
        return total;
    }

    private interface ArchiveReader {
        final class Entry { final String name; final boolean regular; Entry(String name, boolean regular) { this.name = name; this.regular = regular; } }
        /** Next entry, or null at the end. Directory entries are skipped here; they carry nothing to restore. */
        Entry next() throws IOException;
        int read(byte[] buffer) throws IOException;
        void skip() throws IOException;
        /** The whole current entry if it is at most {@code max} bytes, else null (and the entry is consumed). */
        byte[] readBounded(int max) throws IOException;
    }

    private static final class ZipReader implements ArchiveReader {
        private final ZipInputStream zip;
        ZipReader(InputStream in) { zip = new ZipInputStream(in); }
        @Override public Entry next() throws IOException {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) { if (!e.isDirectory()) return new Entry(e.getName(), true); }
            return null;
        }
        @Override public int read(byte[] b) throws IOException { return zip.read(b); }
        @Override public void skip() { /* getNextEntry skips the rest of this entry */ }
        @Override public byte[] readBounded(int max) throws IOException { return boundedRead(zip, max); }
    }

    /** Reads ustar tar.gz: regular files and directories; GNU long names (L) and pax path records are honoured; links and devices are refused. */
    private static final class TarGzReader implements ArchiveReader {
        private final InputStream in;
        private long remaining, padding;
        TarGzReader(InputStream raw) throws IOException { in = new GZIPInputStream(raw); }
        @Override public Entry next() throws IOException {
            skip();
            String longName = null;
            for (;;) {
                byte[] header = new byte[512];
                if (!readFully(header)) return null;
                boolean empty = true; for (byte b : header) if (b != 0) { empty = false; break; }
                if (empty) return null;
                long size = octal(header, 124, 12);
                char type = header[156] == 0 ? '0' : (char) header[156];
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty() && new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) name = prefix + "/" + name;
                if (type == 'L' || type == 'x') { // long name or pax header: small metadata block, then the real header
                    if (size > 16 * 1024) throw new IOException("Oversized tar metadata");
                    byte[] data = new byte[(int) size];
                    if (!readFully(data)) throw new IOException("Truncated tar");
                    discard((512 - size % 512) % 512);
                    if (type == 'L') longName = cString(data, 0, data.length);
                    else { String text = new String(data, StandardCharsets.UTF_8); int at = text.indexOf(" path="); if (at >= 0) { int end = text.indexOf('\n', at); longName = text.substring(at + 6, end < 0 ? text.length() : end); } }
                    continue;
                }
                if (longName != null) { name = longName; longName = null; }
                remaining = (type == '0' || type == '7') ? size : 0;
                padding = (type == '0' || type == '7') ? (512 - size % 512) % 512 : 0;
                if (type == '0' || type == '7') return new Entry(name, true);
                if (type == '5') { skipBody(size); continue; }          // directory: nothing to restore
                skipBody(size);
                return new Entry(name, false);                           // link, device, fifo...: refused by the caller
            }
        }
        @Override public int read(byte[] buffer) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) throw new IOException("Truncated tar");
            remaining -= n; return n;
        }
        @Override public void skip() throws IOException { discard(remaining + padding); remaining = 0; padding = 0; }
        @Override public byte[] readBounded(int max) throws IOException {
            if (remaining > max) { skip(); return null; }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = read(buffer)) != -1) out.write(buffer, 0, n);
            discard(padding); padding = 0;
            return out.toByteArray();
        }
        private void skipBody(long size) throws IOException { discard(size + (512 - size % 512) % 512); }
        private void discard(long count) throws IOException {
            byte[] sink = new byte[4096];
            while (count > 0) { int n = in.read(sink, 0, (int) Math.min(sink.length, count)); if (n < 0) throw new IOException("Truncated tar"); count -= n; }
        }
        private boolean readFully(byte[] b) throws IOException {
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) { if (off == 0) return false; throw new IOException("Truncated tar"); } off += n; }
            return true;
        }
        private static long octal(byte[] b, int offset, int length) throws IOException {
            String s = cString(b, offset, length).trim();
            if (s.isEmpty()) return 0;
            try { long v = Long.parseLong(s, 8); if (v < 0) throw new IOException("Invalid tar size"); return v; }
            catch (NumberFormatException e) { throw new IOException("Invalid tar header"); }
        }
        private static String cString(byte[] b, int offset, int length) {
            int end = offset; while (end < offset + length && b[end] != 0) end++;
            return new String(b, offset, end - offset, StandardCharsets.UTF_8);
        }
    }

    private static byte[] boundedRead(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > max) { while (in.read(buffer) != -1) { /* drain */ } return null; } }
        return out.toByteArray();
    }
}

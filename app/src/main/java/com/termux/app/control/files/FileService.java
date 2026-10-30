package com.termux.app.control.files;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only file access under explicitly configured roots. Paths are root-relative UTF-8 names. Symbolic
 * links are listed as entries and never followed.
 *
 * <p>Traversal is delegated to an {@link Access}. {@link CanonicalAccess} checks components by canonical
 * path, so a same-UID process can replace a directory between the check and the open; only an access whose
 * {@link Access#raceSafe()} is true (descriptor-relative, no-follow opens) may back a client-facing API.
 * It has no mutation methods.
 *
 * <p>This class performs file I/O and must run on a worker thread, never the service thread.
 */
public final class FileService {
    public static final int MAX_PAGE = 100;
    public static final int MAX_READ = 64 * 1024;
    public static final int MAX_DIRECTORY_ENTRIES = 10000;
    private static final int MAX_PATH_BYTES = 4096, MAX_NAME_BYTES = 255;

    /** Error codes use the reserved v1 domain names; the dispatcher maps them into the -32001 family. */
    public static final class FileException extends Exception {
        public final String code;
        FileException(String code) { super(code); this.code = code; }
    }

    public static final class Entry {
        public final String name, path, type, revision;
        public final Long size;
        public final long mtimeMillis;
        Entry(String name, String path, String type, Long size, long mtimeMillis, String revision) {
            this.name = name; this.path = path; this.type = type; this.size = size; this.mtimeMillis = mtimeMillis; this.revision = revision;
        }
    }

    public static final class Page {
        public final List<Entry> entries;
        public final Integer nextCursor;
        Page(List<Entry> entries, Integer nextCursor) { this.entries = entries; this.nextCursor = nextCursor; }
    }

    public static final class ReadResult {
        public final long offset, nextOffset;
        public final boolean eof;
        public final String revision;
        public final byte[] data;
        ReadResult(long offset, long nextOffset, boolean eof, String revision, byte[] data) {
            this.offset = offset; this.nextOffset = nextOffset; this.eof = eof; this.revision = revision; this.data = data;
        }
    }

    /** Metadata from lstat; a link describes itself, never its target. */
    public static final class Meta {
        public final String type;
        public final long size, mtimeMillis;
        public Meta(String type, long size, long mtimeMillis) { this.type = type; this.size = size; this.mtimeMillis = mtimeMillis; }
    }

    /** An open directory. Names and metadata come from the opened directory, not from a path lookup. */
    public interface Directory extends Closeable {
        String[] names() throws FileException;
        /** NOT_FOUND when the name is absent. */
        Meta lstat(String name) throws FileException;
    }

    /** An open regular file. */
    public interface Regular extends Closeable {
        Meta meta() throws FileException;
        /** Reads at a file position into buffer[at..at+length); returns the byte count, 0 at end of file. */
        int read(long position, byte[] buffer, int at, int length) throws FileException;
    }

    /** A new file being written; reads let a transfer verify a replayed chunk. */
    public interface Writable extends Closeable {
        void write(long position, byte[] buffer, int at, int length) throws FileException;
        int read(long position, byte[] buffer, int at, int length) throws FileException;
        void sync() throws FileException;
    }

    /**
     * Root-relative traversal. Every component is a validated name. Intermediate components and opened
     * directories must be directories that are not links (DENIED for a link, NOT_FOUND otherwise).
     */
    public interface Access {
        boolean raceSafe();
        Directory openDirectory(File root, String[] parts) throws FileException;
        /** UNSUPPORTED when the final component exists but is not a regular file; DENIED for a link. */
        Regular openRegular(File root, String[] parts) throws FileException;
        /** CONFLICT when the name exists; the parent must be an unlinked directory. */
        void mkdir(File root, String[] parts) throws FileException;
        /** A regular file or an empty directory. A link or other type is UNSUPPORTED; a non-empty directory CONFLICT. */
        void delete(File root, String[] parts) throws FileException;
        /** Without replace, CONFLICT when the target exists and it is never overwritten. */
        void rename(File root, String[] from, String[] to, boolean replace) throws FileException;
        /** A new owner-only file; CONFLICT when the name exists. */
        Writable createNew(File root, String[] parts) throws FileException;
    }

    private final Map<String, File> mRoots;
    private final java.util.Set<String> mWritable;
    private final Access mAccess;
    private final FileTransfers mTransfers;

    /** Canonical-path access; not race-safe, so only for trusted in-process callers and tests. */
    public FileService(Map<String, File> roots) throws IOException {
        this(roots, new CanonicalAccess());
    }

    /** Read-only roots. */
    public FileService(Map<String, File> roots, Access access) throws IOException {
        this(roots, Collections.<String>emptySet(), access);
    }

    /** Roots are canonicalized once. A root that is not a directory is rejected. Writes need a listed root. */
    public FileService(Map<String, File> roots, java.util.Set<String> writableRoots, Access access) throws IOException {
        if (roots == null || writableRoots == null || access == null) throw new IllegalArgumentException("roots and access required");
        if (!roots.keySet().containsAll(writableRoots)) throw new IllegalArgumentException("Unknown writable root");
        mAccess = access;
        mWritable = Collections.unmodifiableSet(new java.util.HashSet<>(writableRoots));
        mTransfers = new FileTransfers(this);
        Map<String, File> copy = new LinkedHashMap<>();
        for (Map.Entry<String, File> entry : roots.entrySet()) {
            String id = entry.getKey();
            if (id == null || !id.matches("[a-z][a-z0-9_-]{0,31}") || entry.getValue() == null || !entry.getValue().isDirectory())
                throw new IllegalArgumentException("Invalid file root");
            copy.put(id, entry.getValue().getCanonicalFile());
        }
        mRoots = Collections.unmodifiableMap(copy);
    }

    public List<String> rootIds() { return Collections.unmodifiableList(new ArrayList<>(mRoots.keySet())); }

    /** True when traversal cannot be redirected by a concurrent same-UID directory or link swap. */
    public boolean raceSafe() { return mAccess.raceSafe(); }

    public boolean writable(String rootId) { return mWritable.contains(rootId); }

    public FileTransfers transfers() { return mTransfers; }

    Access access() { return mAccess; }

    /** The root for a mutation; DENIED for a read-only root. */
    File writableRoot(String rootId) throws FileException {
        File root = root(rootId);
        if (!mWritable.contains(rootId)) throw new FileException("DENIED");
        return root;
    }

    public Entry mkdir(String rootId, String path) throws FileException {
        String[] parts = components(path);
        if (parts.length == 0) throw new FileException("INVALID_REQUEST");
        mAccess.mkdir(writableRoot(rootId), parts);
        return stat(rootId, path);
    }

    /** Never overwrites. With ifRevision, the source must still have that revision. */
    public Entry rename(String rootId, String path, String newPath, String ifRevision) throws FileException {
        String[] from = components(path), to = components(newPath);
        if (from.length == 0 || to.length == 0) throw new FileException("INVALID_REQUEST");
        File root = writableRoot(rootId);
        requireRevision(rootId, path, ifRevision);
        mAccess.rename(root, from, to, false);
        return stat(rootId, newPath);
    }

    /** A regular file or an empty directory. Recursive deletion is not offered. */
    public void delete(String rootId, String path, String ifRevision) throws FileException {
        String[] parts = components(path);
        if (parts.length == 0) throw new FileException("INVALID_REQUEST");
        File root = writableRoot(rootId);
        requireRevision(rootId, path, ifRevision);
        mAccess.delete(root, parts);
    }

    /** STALE_REVISION when the entry changed; a null revision skips the check. */
    void requireRevision(String rootId, String path, String ifRevision) throws FileException {
        if (ifRevision != null && !ifRevision.equals(stat(rootId, path).revision)) throw new FileException("STALE_REVISION");
    }

    /** Splits a root-relative path. The empty string names the root itself. */
    static String[] components(String path) throws FileException {
        if (path == null || path.isEmpty()) return new String[0];
        if (utf8Length(path) > MAX_PATH_BYTES || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0 ||
            path.startsWith("/") || path.endsWith("/")) throw new FileException("INVALID_REQUEST");
        String[] parts = path.split("/", -1);
        for (String part : parts) if (!validName(part)) throw new FileException("INVALID_REQUEST");
        return parts;
    }

    /** A non-empty root-relative path with valid components, as accepted by every file method. */
    public static boolean isValidRelativePath(String path) {
        try { return components(path).length > 0; }
        catch (FileException e) { return false; }
    }

    static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || utf8Length(name) > MAX_NAME_BYTES) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\') return false;
        }
        return true;
    }

    private static int utf8Length(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    File root(String rootId) throws FileException {
        File root = mRoots.get(rootId);
        if (root == null) throw new FileException("NOT_FOUND");
        return root;
    }

    public Page list(String rootId, String path, int cursor, int limit) throws FileException {
        if (cursor < 0 || limit < 1 || limit > MAX_PAGE) throw new FileException("INVALID_REQUEST");
        String[] parts = components(path);
        try (Directory directory = mAccess.openDirectory(root(rootId), parts)) {
            String[] names = directory.names();
            if (names.length > MAX_DIRECTORY_ENTRIES) throw new FileException("LIMIT_REACHED");
            // Names that cannot be addressed or vanished since the listing are skipped. Paging runs over
            // the visible names, so a skipped entry never produces a short page.
            List<String> sorted = new ArrayList<>();
            for (String name : names) if (validName(name)) sorted.add(name);
            Collections.sort(sorted);
            List<Entry> visible = new ArrayList<>();
            for (String name : sorted) {
                try { visible.add(entry(name, join(path, name), directory.lstat(name))); }
                catch (FileException e) { if (!"NOT_FOUND".equals(e.code)) throw e; }
            }
            if (cursor > visible.size()) throw new FileException("INVALID_REQUEST");
            int end = Math.min(visible.size(), cursor + limit);
            return new Page(Collections.unmodifiableList(new ArrayList<>(visible.subList(cursor, end))), end < visible.size() ? end : null);
        } catch (IOException e) { throw new FileException("UNAVAILABLE"); }
    }

    /**
     * Canonical directory for a validated, unlinked path. Callers that hand a real path to a launcher use this
     * instead of joining strings, so the path they receive is the one that was checked.
     */
    public File directory(String rootId, String path) throws FileException {
        String[] parts = components(path);
        try (Directory ignored = mAccess.openDirectory(root(rootId), parts)) {
            File directory = root(rootId);
            for (String part : parts) directory = new File(directory, part);
            return directory.getCanonicalFile();
        } catch (IOException e) { throw new FileException("UNAVAILABLE"); }
    }

    public Entry stat(String rootId, String path) throws FileException {
        String[] parts = components(path);
        if (parts.length == 0) throw new FileException("INVALID_REQUEST");
        String name = parts[parts.length - 1];
        try (Directory parent = mAccess.openDirectory(root(rootId), Arrays.copyOf(parts, parts.length - 1))) {
            return entry(name, path, parent.lstat(name));
        } catch (IOException e) { throw new FileException("UNAVAILABLE"); }
    }

    private static Entry entry(String name, String path, Meta meta) {
        Long size = "file".equals(meta.type) ? meta.size : null;
        return new Entry(name, path, meta.type, size, meta.mtimeMillis, revision(meta.mtimeMillis, size));
    }

    public ReadResult read(String rootId, String path, long offset, int limit) throws FileException {
        if (offset < 0 || limit < 1 || limit > MAX_READ) throw new FileException("INVALID_REQUEST");
        String[] parts = components(path);
        if (parts.length == 0) throw new FileException("UNSUPPORTED");
        try (Regular file = mAccess.openRegular(root(rootId), parts)) {
            Meta meta = file.meta();
            byte[] data = new byte[0];
            if (offset < meta.size) {
                int wanted = (int) Math.min(limit, meta.size - offset);
                byte[] buffer = new byte[wanted];
                int total = 0;
                while (total < wanted) {
                    int read = file.read(offset + total, buffer, total, wanted - total);
                    if (read <= 0) break;
                    total += read;
                }
                data = total == wanted ? buffer : Arrays.copyOf(buffer, total);
            }
            long next = offset + data.length;
            return new ReadResult(offset, next, next >= meta.size, revision(meta.mtimeMillis, meta.size), data);
        } catch (IOException e) { throw new FileException("UNAVAILABLE"); }
    }

    private static String revision(long mtimeMillis, Long size) {
        return "r" + Long.toHexString(mtimeMillis) + "." + (size == null ? "-" : Long.toHexString(size));
    }

    private static String join(String parent, String name) { return parent.isEmpty() ? name : parent + "/" + name; }
}

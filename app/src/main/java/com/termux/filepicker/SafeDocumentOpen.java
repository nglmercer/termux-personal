package com.termux.filepicker;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Descriptor-relative, no-follow open for existing regular documents. */
final class SafeDocumentOpen {
    private static final boolean AVAILABLE;
    static {
        boolean available;
        try { System.loadLibrary("termux-document-path"); available = true; }
        catch (LinkageError e) { available = false; }
        AVAILABLE = available;
    }

    static ParcelFileDescriptor open(File root, String id, String mode) throws FileNotFoundException {
        String prefix = root.getAbsolutePath() + File.separator;
        if (!AVAILABLE || id == null || mode == null || !id.startsWith(prefix)) throw new FileNotFoundException("Invalid document");
        int access; boolean truncate = false, append = false;
        switch (mode) {
            case "r": access = 0; break;
            case "w": case "wt": access = 1; truncate = true; break;
            case "wa": access = 1; append = true; break;
            case "rw": access = 2; break;
            case "rwt": access = 2; truncate = true; break;
            default: throw new FileNotFoundException("Invalid mode");
        }
        int fd = openNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
            id.substring(prefix.length()).getBytes(StandardCharsets.UTF_8), access, truncate, append);
        if (fd < 0) throw new FileNotFoundException("Cannot open document");
        return ParcelFileDescriptor.adoptFd(fd);
    }
    private static native int openNative(byte[] root, byte[] path, int access, boolean truncate, boolean append);

    static int create(File root, String id, boolean directory) throws FileNotFoundException {
        String prefix = root.getAbsolutePath() + File.separator;
        if (!AVAILABLE || id == null || !id.startsWith(prefix)) throw new FileNotFoundException("Invalid document");
        int result = createNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
            id.substring(prefix.length()).getBytes(StandardCharsets.UTF_8), directory);
        if (result < 0) throw new FileNotFoundException("Cannot create document");
        return result;
    }
    private static native int createNative(byte[] root, byte[] path, boolean directory);

    static void delete(File root, String id) throws FileNotFoundException {
        String prefix = root.getAbsolutePath() + File.separator;
        if (!AVAILABLE || id == null || !id.startsWith(prefix) ||
            deleteNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8),
                id.substring(prefix.length()).getBytes(StandardCharsets.UTF_8)) != 0)
            throw new FileNotFoundException("Cannot delete document");
    }
    private static native int deleteNative(byte[] root, byte[] path);

    static File[] list(File root, String id) throws FileNotFoundException {
        String base = root.getAbsolutePath(), prefix = base + File.separator;
        if (!AVAILABLE || id == null || (!id.equals(base) && !id.startsWith(prefix)))
            throw new FileNotFoundException("Invalid directory");
        String relative = id.equals(base) ? "" : id.substring(prefix.length());
        int raw = directoryNative(base.getBytes(StandardCharsets.UTF_8), relative.getBytes(StandardCharsets.UTF_8));
        if (raw < 0) throw new FileNotFoundException("Cannot open directory");
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.adoptFd(raw)) {
            String[] names = new File("/proc/self/fd/" + fd.getFd()).list();
            if (names == null) throw new FileNotFoundException("Cannot list directory");
            File[] files = new File[names.length];
            for (int i = 0; i < names.length; i++) files[i] = new File(id, names[i]);
            return files;
        } catch (IOException e) { throw new FileNotFoundException("Cannot close directory"); }
    }
    private static native int directoryNative(byte[] root, byte[] path);

    static boolean isAvailable() { return AVAILABLE; }

    /** New owner-only regular file opened read/write; returns a descriptor or a negative errno. */
    static int createFile(File root, String relative) {
        if (!AVAILABLE || relative == null || relative.isEmpty()) return -22;
        return createFileNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8), relative.getBytes(StandardCharsets.UTF_8));
    }
    private static native int createFileNative(byte[] root, byte[] path);

    /** Returns 0 or a negative errno. Neither final component is followed. */
    static int rename(File root, String from, String to, boolean replace) {
        if (!AVAILABLE || from == null || to == null || from.isEmpty() || to.isEmpty()) return -22;
        return renameNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8), from.getBytes(StandardCharsets.UTF_8),
            to.getBytes(StandardCharsets.UTF_8), replace);
    }
    private static native int renameNative(byte[] root, byte[] from, byte[] to, boolean replace);

    /** Root-relative form of {@link #create(File, String, boolean)}: 0 created, 1 exists, -1 other failure. */
    static int createRelative(File root, String relative, boolean directory) throws FileNotFoundException {
        return create(root, root.getAbsolutePath() + File.separator + relative, directory);
    }

    /** Root-relative form of {@link #delete(File, String)}; regular files and empty directories only. */
    static void deleteRelative(File root, String relative) throws FileNotFoundException {
        delete(root, root.getAbsolutePath() + File.separator + relative);
    }

    /** Opens a directory below root without following any link; the empty path names root itself. */
    static ParcelFileDescriptor openDirectory(File root, String relative) throws FileNotFoundException {
        if (!AVAILABLE || relative == null) throw new FileNotFoundException("Invalid directory");
        int raw = directoryNative(root.getAbsolutePath().getBytes(StandardCharsets.UTF_8), relative.getBytes(StandardCharsets.UTF_8));
        if (raw < 0) throw new FileNotFoundException("Cannot open directory");
        return ParcelFileDescriptor.adoptFd(raw);
    }

    static final class Entry {
        final boolean directory, writable;
        final long size, modified;
        Entry(boolean directory, long size, long modified, boolean writable) {
            this.directory = directory; this.size = size; this.modified = modified; this.writable = writable;
        }
    }

    static Entry stat(File root, String id) throws FileNotFoundException {
        String base = root.getAbsolutePath(), prefix = base + File.separator;
        if (!AVAILABLE || id == null || (!id.equals(base) && !id.startsWith(prefix)))
            throw new FileNotFoundException("Invalid document");
        String relative = id.equals(base) ? "" : id.substring(prefix.length());
        int raw = directoryNative(base.getBytes(StandardCharsets.UTF_8), relative.getBytes(StandardCharsets.UTF_8));
        ParcelFileDescriptor fd = raw >= 0 ? ParcelFileDescriptor.adoptFd(raw) : open(root, id, "r");
        try (ParcelFileDescriptor descriptor = fd) {
            StructStat stat = Os.fstat(descriptor.getFileDescriptor());
            int writeBit = stat.st_uid == Os.geteuid() ? 0200 : stat.st_gid == Os.getegid() ? 0020 : 0002;
            return new Entry(OsConstants.S_ISDIR(stat.st_mode), stat.st_size,
                stat.st_mtime * 1000L, (stat.st_mode & writeBit) != 0);
        } catch (ErrnoException | IOException e) { throw new FileNotFoundException("Cannot stat document"); }
    }
}

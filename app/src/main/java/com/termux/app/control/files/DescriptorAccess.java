package com.termux.app.control.files;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.termux.filepicker.DescriptorPaths;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Race-safe traversal: each component is opened relative to its parent descriptor with O_NOFOLLOW, and
 * children are inspected through /proc/self/fd, which names the opened directory itself rather than a
 * path that could be swapped. A link is never followed for traversal or reads.
 */
public final class DescriptorAccess implements FileService.Access {
    /** Null when the descriptor library is not available; callers must not fall back to path checks. */
    public static DescriptorAccess createIfAvailable() { return DescriptorPaths.isAvailable() ? new DescriptorAccess() : null; }

    private DescriptorAccess() { }

    @Override public boolean raceSafe() { return true; }

    @Override public FileService.Directory openDirectory(File root, String[] parts) throws FileService.FileException {
        final ParcelFileDescriptor descriptor;
        try { descriptor = DescriptorPaths.openDirectory(root, android.text.TextUtils.join("/", parts)); }
        catch (FileNotFoundException e) { throw diagnose(root, parts, true); }
        String self = "/proc/self/fd/" + descriptor.getFd();
        return new FileService.Directory() {
            @Override public String[] names() throws FileService.FileException {
                String[] names = new File(self).list();
                if (names == null) throw new FileService.FileException("UNAVAILABLE");
                return names;
            }
            @Override public FileService.Meta lstat(String name) throws FileService.FileException {
                try { return meta(Os.lstat(self + "/" + name)); }
                catch (ErrnoException e) {
                    throw new FileService.FileException(e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR ? "NOT_FOUND" : "UNAVAILABLE");
                }
            }
            @Override public void close() throws IOException { descriptor.close(); }
        };
    }

    @Override public FileService.Regular openRegular(File root, String[] parts) throws FileService.FileException {
        final ParcelFileDescriptor descriptor;
        try { descriptor = DescriptorPaths.openRegularForRead(root, android.text.TextUtils.join("/", parts)); }
        catch (FileNotFoundException e) { throw diagnose(root, parts, false); }
        final FileService.Meta meta;
        try { meta = meta(Os.fstat(descriptor.getFileDescriptor())); }
        catch (ErrnoException e) { closeQuietly(descriptor); throw new FileService.FileException("UNAVAILABLE"); }
        return new FileService.Regular() {
            @Override public FileService.Meta meta() { return meta; }
            @Override public int read(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try { return Math.max(Os.pread(descriptor.getFileDescriptor(), buffer, at, length, position), 0); }
                catch (ErrnoException | java.io.InterruptedIOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void close() throws IOException { descriptor.close(); }
        };
    }

    @Override public void mkdir(File root, String[] parts) throws FileService.FileException {
        int result;
        try { result = DescriptorPaths.createDirectory(root, join(parts)); }
        catch (FileNotFoundException e) { result = -1; }
        if (result == 1) throw new FileService.FileException("CONFLICT");
        if (result != 0) throw parentFailure(root, parts);
    }

    @Override public void delete(File root, String[] parts) throws FileService.FileException {
        try (FileService.Directory parent = openDirectory(root, java.util.Arrays.copyOf(parts, parts.length - 1))) {
            FileService.Meta meta = parent.lstat(parts[parts.length - 1]);
            if (!"file".equals(meta.type) && !"directory".equals(meta.type)) throw new FileService.FileException("UNSUPPORTED");
            try { DescriptorPaths.delete(root, join(parts)); }
            catch (FileNotFoundException e) {
                // Delete refuses a non-empty directory; any other failure is reported as unavailable.
                throw new FileService.FileException("directory".equals(meta.type) ? "CONFLICT" : "UNAVAILABLE");
            }
        } catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
    }

    @Override public void rename(File root, String[] from, String[] to, boolean replace) throws FileService.FileException {
        int result = DescriptorPaths.rename(root, join(from), join(to), replace);
        if (result == 0) return;
        if (result == -OsConstants.EEXIST || result == -OsConstants.ENOTEMPTY) throw new FileService.FileException("CONFLICT");
        if (result == -OsConstants.ENOENT) throw parentFailure(root, from);
        throw new FileService.FileException("UNAVAILABLE");
    }

    @Override public FileService.Writable createNew(File root, String[] parts) throws FileService.FileException {
        int raw = DescriptorPaths.createFile(root, join(parts));
        if (raw == -OsConstants.EEXIST) throw new FileService.FileException("CONFLICT");
        if (raw < 0) throw parentFailure(root, parts);
        ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(raw);
        return new FileService.Writable() {
            @Override public void write(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try {
                    for (int done = 0; done < length; ) {
                        int n = Os.pwrite(descriptor.getFileDescriptor(), buffer, at + done, length - done, position + done);
                        if (n <= 0) throw new FileService.FileException("UNAVAILABLE");
                        done += n;
                    }
                } catch (ErrnoException | java.io.InterruptedIOException e) {
                    throw new FileService.FileException(e instanceof ErrnoException && ((ErrnoException) e).errno == OsConstants.ENOSPC ?
                        "RESOURCE_LIMITED" : "UNAVAILABLE");
                }
            }
            @Override public int read(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try { return Math.max(Os.pread(descriptor.getFileDescriptor(), buffer, at, length, position), 0); }
                catch (ErrnoException | java.io.InterruptedIOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void sync() throws FileService.FileException {
                try { Os.fsync(descriptor.getFileDescriptor()); }
                catch (ErrnoException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void close() throws IOException { descriptor.close(); }
        };
    }

    private static String join(String[] parts) { return android.text.TextUtils.join("/", parts); }

    /** The code for a failure whose likely cause is the parent path. */
    private static FileService.FileException parentFailure(File root, String[] parts) {
        FileService.FileException parent = diagnose(root, java.util.Arrays.copyOf(parts, parts.length - 1), true);
        return "UNAVAILABLE".equals(parent.code) ? new FileService.FileException("NOT_FOUND") : parent;
    }

    private static FileService.Meta meta(StructStat stat) {
        String type = OsConstants.S_ISLNK(stat.st_mode) ? "symlink" : OsConstants.S_ISDIR(stat.st_mode) ? "directory" :
            OsConstants.S_ISREG(stat.st_mode) ? "file" : "other";
        return new FileService.Meta(type, stat.st_size, stat.st_mtime * 1000L);
    }

    /**
     * Error code for a failed open, found by walking again with lstat. Only the code depends on this walk;
     * no data is returned from it, so a concurrent swap can change the code but never expose an entry.
     */
    private static FileService.FileException diagnose(File root, String[] parts, boolean wantDirectory) {
        try {
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) path.append('/');
                path.append(parts[i]);
                StructStat stat;
                try { stat = Os.lstat(new File(root, path.toString()).getPath()); }
                catch (ErrnoException e) { return new FileService.FileException("NOT_FOUND"); }
                if (OsConstants.S_ISLNK(stat.st_mode)) return new FileService.FileException("DENIED");
                boolean last = i == parts.length - 1;
                if (!OsConstants.S_ISDIR(stat.st_mode) && (!last || wantDirectory)) return new FileService.FileException("NOT_FOUND");
                if (last && !wantDirectory && !OsConstants.S_ISREG(stat.st_mode)) return new FileService.FileException("UNSUPPORTED");
            }
        } catch (RuntimeException ignored) { }
        return new FileService.FileException("UNAVAILABLE");
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try { descriptor.close(); } catch (IOException ignored) { }
    }
}

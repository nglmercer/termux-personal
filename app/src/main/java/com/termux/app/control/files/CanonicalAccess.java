package com.termux.app.control.files;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Path-based traversal that checks each component by canonical path. A same-UID process can swap a
 * directory between the check and the use, so this is not race-safe. Dangling links are reported as
 * missing: the JDK canonicalizes them literally, so they cannot be classified as links without lstat.
 */
final class CanonicalAccess implements FileService.Access {
    @Override public boolean raceSafe() { return false; }

    @Override public FileService.Directory openDirectory(File root, String[] parts) throws FileService.FileException {
        File directory = resolve(root, parts);
        if (!directory.isDirectory()) throw new FileService.FileException("NOT_FOUND");
        return new FileService.Directory() {
            @Override public String[] names() throws FileService.FileException {
                String[] names = directory.list();
                if (names == null) throw new FileService.FileException("UNAVAILABLE");
                return names;
            }
            @Override public FileService.Meta lstat(String name) throws FileService.FileException {
                File file = new File(directory, name);
                if (!file.exists()) throw new FileService.FileException("NOT_FOUND");
                String type = isSymbolicLink(file) ? "symlink" : file.isDirectory() ? "directory" : file.isFile() ? "file" : "other";
                return new FileService.Meta(type, "file".equals(type) ? file.length() : 0, file.lastModified());
            }
            @Override public void close() { }
        };
    }

    @Override public FileService.Regular openRegular(File root, String[] parts) throws FileService.FileException {
        File file = resolve(root, parts);
        if (!file.isFile()) throw new FileService.FileException("UNSUPPORTED");
        FileService.Meta meta = new FileService.Meta("file", file.length(), file.lastModified());
        final RandomAccessFile raf;
        try { raf = new RandomAccessFile(file, "r"); }
        catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
        return new FileService.Regular() {
            @Override public FileService.Meta meta() { return meta; }
            @Override public int read(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try {
                    raf.seek(position);
                    int read = raf.read(buffer, at, length);
                    return Math.max(read, 0);
                } catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void close() throws IOException { raf.close(); }
        };
    }

    @Override public void mkdir(File root, String[] parts) throws FileService.FileException {
        File directory = new File(parent(root, parts), parts[parts.length - 1]);
        if (exists(directory)) throw new FileService.FileException("CONFLICT");
        if (!directory.mkdir()) throw new FileService.FileException("UNAVAILABLE");
    }

    @Override public void delete(File root, String[] parts) throws FileService.FileException {
        File file = new File(parent(root, parts), parts[parts.length - 1]);
        if (!exists(file)) throw new FileService.FileException("NOT_FOUND");
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !(file.isFile() || file.isDirectory()))
            throw new FileService.FileException("UNSUPPORTED");
        String[] children = file.isDirectory() ? file.list() : null;
        if (children != null && children.length > 0) throw new FileService.FileException("CONFLICT");
        if (!file.delete()) throw new FileService.FileException("UNAVAILABLE");
    }

    @Override public void rename(File root, String[] from, String[] to, boolean replace) throws FileService.FileException {
        File source = new File(parent(root, from), from[from.length - 1]);
        File target = new File(parent(root, to), to[to.length - 1]);
        if (!exists(source)) throw new FileService.FileException("NOT_FOUND");
        try {
            if (replace) java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            else java.nio.file.Files.move(source.toPath(), target.toPath());
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new FileService.FileException("CONFLICT");
        } catch (IOException e) {
            throw new FileService.FileException("UNAVAILABLE");
        }
    }

    @Override public FileService.Writable createNew(File root, String[] parts) throws FileService.FileException {
        File file = new File(parent(root, parts), parts[parts.length - 1]);
        final RandomAccessFile raf;
        try {
            if (exists(file) || !file.createNewFile()) throw new FileService.FileException("CONFLICT");
            raf = new RandomAccessFile(file, "rw");
        } catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
        return new FileService.Writable() {
            @Override public void write(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try { raf.seek(position); raf.write(buffer, at, length); }
                catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public int read(long position, byte[] buffer, int at, int length) throws FileService.FileException {
                try { raf.seek(position); return Math.max(raf.read(buffer, at, length), 0); }
                catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void sync() throws FileService.FileException {
                try { raf.getFD().sync(); } catch (IOException e) { throw new FileService.FileException("UNAVAILABLE"); }
            }
            @Override public void close() throws IOException { raf.close(); }
        };
    }

    /** The resolved, unlinked parent directory of the last component. */
    private static File parent(File root, String[] parts) throws FileService.FileException {
        if (parts.length == 0) throw new FileService.FileException("INVALID_REQUEST");
        File parent = resolve(root, java.util.Arrays.copyOf(parts, parts.length - 1));
        if (!parent.isDirectory()) throw new FileService.FileException("NOT_FOUND");
        return parent;
    }

    /** Also true for a dangling link, which File.exists reports as absent. */
    private static boolean exists(File file) {
        return file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath());
    }

    /** Every component must exist and must not be a link; all but the last must be directories. */
    private static File resolve(File root, String[] parts) throws FileService.FileException {
        File current = root;
        for (int i = 0; i < parts.length; i++) {
            File next = new File(current, parts[i]);
            if (!next.exists()) throw new FileService.FileException("NOT_FOUND");
            if (isSymbolicLink(next)) throw new FileService.FileException("DENIED");
            if (i < parts.length - 1 && !next.isDirectory()) throw new FileService.FileException("NOT_FOUND");
            current = next;
        }
        return current;
    }

    /** Only meaningful for existing entries; the canonical path of an existing link is its resolved target. */
    private static boolean isSymbolicLink(File file) {
        try {
            File parent = file.getParentFile();
            if (parent == null) return false;
            return !file.getCanonicalPath().equals(parent.getCanonicalPath() + File.separator + file.getName());
        } catch (IOException e) {
            return false;
        }
    }
}

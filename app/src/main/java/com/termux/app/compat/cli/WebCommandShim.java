package com.termux.app.compat.cli;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Installs the opt-in, app-owned shim without replacing any existing filesystem entry. */
public final class WebCommandShim {
    public enum Status { READY, CONFLICT, UNAVAILABLE }
    private static final String DIRECTORY = "compat-v1";
    private static final String NAME = "termux-open-url";
    private static final int MAX_BYTES = 8192;
    private WebCommandShim() { }

    public static synchronized Status ensure(Context context) { return ensureAsset(context, NAME); }
    public static synchronized Status ensureDoctor(Context context) { return ensureAsset(context, "termux-compat"); }
    private static Status ensureAsset(Context context, String name) {
        try (InputStream source = context.getAssets().open(DIRECTORY + "/" + name)) {
            return install(context.getFilesDir(), name, readBounded(source));
        } catch (IOException | RuntimeException e) { return Status.UNAVAILABLE; }
    }
    static Status install(File filesDir, byte[] expected) { return install(filesDir, NAME, expected); }
    static Status install(File filesDir, String name, byte[] expected) {
        if (filesDir == null || (!NAME.equals(name) && !"termux-compat".equals(name)) || expected == null || expected.length == 0 || expected.length > MAX_BYTES) return Status.UNAVAILABLE;
        File directory = new File(filesDir, DIRECTORY);
        try {
            try { Os.mkdir(directory.getPath(), 0700); }
            catch (ErrnoException e) { if (e.errno != OsConstants.EEXIST) return Status.UNAVAILABLE; }
            // Pin the directory itself so a concurrent rename or symlink swap cannot redirect creation.
            FileDescriptor raw;
            try { raw = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0); }
            catch (ErrnoException e) { return Status.CONFLICT; }
            ParcelFileDescriptor pinned;
            try { pinned = ParcelFileDescriptor.dup(raw); }
            finally { Os.close(raw); }
            try (ParcelFileDescriptor handle = pinned) {
                StructStat directoryInfo = Os.fstat(handle.getFileDescriptor());
                if (!OsConstants.S_ISDIR(directoryInfo.st_mode) || directoryInfo.st_uid != android.os.Process.myUid()) return Status.CONFLICT;
                File file = new File("/proc/self/fd/" + handle.getFd(), name);
                // O_EXCL closes the race between checking the name and creating it.
                try {
                    Os.lstat(file.getPath());
                    return verifyExisting(file, expected);
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.ENOENT) return Status.UNAVAILABLE;
                }
                FileDescriptor fd;
                try {
                    fd = Os.open(file.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0700);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EEXIST) return verifyExisting(file, expected);
                    return Status.UNAVAILABLE;
                }
                try (FileOutputStream output = new FileOutputStream(fd)) {
                    output.write(expected);
                    output.flush();
                    Os.fchmod(fd, 0700);
                    output.getFD().sync();
                }
                return Status.READY;
            }
        } catch (ErrnoException | IOException | SecurityException e) { return Status.UNAVAILABLE; }
    }
    private static Status verifyExisting(File file, byte[] expected) {
        try {
            StructStat entry = Os.lstat(file.getPath());
            if (!OsConstants.S_ISREG(entry.st_mode) || entry.st_uid != android.os.Process.myUid()) return Status.CONFLICT;
            FileDescriptor fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
            try (FileInputStream input = new FileInputStream(fd)) {
                StructStat info = Os.fstat(fd);
                if (!OsConstants.S_ISREG(info.st_mode) || info.st_uid != android.os.Process.myUid() || (info.st_mode & 0100) == 0)
                    return Status.CONFLICT;
                return Arrays.equals(expected, readBounded(input)) ? Status.READY : Status.CONFLICT;
            }
        } catch (ErrnoException | IOException | SecurityException e) { return Status.CONFLICT; }
    }
    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            if (bytes.size() + count > MAX_BYTES) throw new IOException("File exceeds limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}

package com.termux.filepicker;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
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
}

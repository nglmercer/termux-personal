package com.termux.filepicker;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Root-relative, descriptor-based opens shared with other app components. Every component is opened
 * relative to its parent descriptor with O_NOFOLLOW, so a swapped directory or link cannot redirect it.
 */
public final class DescriptorPaths {
    private DescriptorPaths() { }

    public static boolean isAvailable() { return SafeDocumentOpen.isAvailable(); }

    /** An existing directory; the empty path names root. Throws for links, missing names and non-directories. */
    public static ParcelFileDescriptor openDirectory(File root, String relative) throws FileNotFoundException {
        return SafeDocumentOpen.openDirectory(root, relative);
    }

    /** An existing regular file opened read-only; relative must name an entry below root. */
    public static ParcelFileDescriptor openRegularForRead(File root, String relative) throws FileNotFoundException {
        if (relative == null || relative.isEmpty()) throw new FileNotFoundException("Invalid document");
        return SafeDocumentOpen.open(root, root.getAbsolutePath() + File.separator + relative, "r");
    }

    /** A new owner-only regular file opened read/write; fails if the name exists. Returns an fd or a negative errno. */
    public static int createFile(File root, String relative) { return SafeDocumentOpen.createFile(root, relative); }

    /** 0 created, 1 the name already exists, -1 another failure. */
    public static int createDirectory(File root, String relative) throws FileNotFoundException {
        if (relative == null || relative.isEmpty()) throw new FileNotFoundException("Invalid directory");
        return SafeDocumentOpen.createRelative(root, relative, true);
    }

    /** Removes a regular file or an empty directory; links and other types are refused. */
    public static void delete(File root, String relative) throws FileNotFoundException {
        if (relative == null || relative.isEmpty()) throw new FileNotFoundException("Invalid document");
        SafeDocumentOpen.deleteRelative(root, relative);
    }

    /** Renames within root; without replace an existing target is never overwritten. 0 or a negative errno. */
    public static int rename(File root, String from, String to, boolean replace) {
        return SafeDocumentOpen.rename(root, from, to, replace);
    }
}

package com.termux.filepicker;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Validates legacy absolute document IDs without changing their stable representation. */
final class DocumentPathPolicy {
    static File existing(File root, String id) throws FileNotFoundException {
        if (id == null) throw new FileNotFoundException("Missing document ID");
        File file = new File(id);
        try {
            String rootAbsolute = root.getAbsolutePath();
            String absolute = file.getAbsolutePath();
            if (!file.isAbsolute() || (!absolute.equals(rootAbsolute) &&
                !absolute.startsWith(rootAbsolute + File.separator))) throw new IOException();
            String canonicalRoot = root.getCanonicalPath();
            String canonical = file.getCanonicalPath();
            // Reject normalization and symlinks, including links pointing back inside the root.
            if (!canonical.equals(canonicalRoot + absolute.substring(rootAbsolute.length())) || !file.exists())
                throw new IOException();
            return file;
        } catch (IOException | RuntimeException e) {
            throw new FileNotFoundException("Invalid document ID");
        }
    }

    static boolean validName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") &&
            name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }
}

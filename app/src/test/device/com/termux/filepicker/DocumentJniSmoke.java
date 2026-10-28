package com.termux.filepicker;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/** Standalone Android runtime smoke test; uses only an isolated temporary directory. */
public final class DocumentJniSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File root = File.createTempFile("jni-doc-", "", new File(System.getenv("TMPDIR")));
        check(root.delete() && root.mkdir());
        File dir = new File(root, "dir"), file = new File(dir, "ñ.txt"), link = new File(root, "link");
        try {
            check(dir.mkdir());
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
            Os.symlink("dir", link.getAbsolutePath());
            check(SafeDocumentOpen.list(root, root.getAbsolutePath()).length == 2);
            check(SafeDocumentOpen.list(root, dir.getAbsolutePath()).length == 1);
            try { SafeDocumentOpen.list(root, link.getAbsolutePath()); throw new AssertionError(); }
            catch (FileNotFoundException expected) { }
            try (ParcelFileDescriptor fd = SafeDocumentOpen.open(root, file.getAbsolutePath(), "r")) {
                check(fd.getStatSize() == 3);
            }
            try { SafeDocumentOpen.open(root, new File(link, "ñ.txt").getAbsolutePath(), "w"); throw new AssertionError(); }
            catch (FileNotFoundException expected) { }
            try { SafeDocumentOpen.open(root, new File(root, "../escape").getAbsolutePath(), "r"); throw new AssertionError(); }
            catch (FileNotFoundException expected) { }
            try { SafeDocumentOpen.create(root, new File(link, "escape").getAbsolutePath(), false); throw new AssertionError(); }
            catch (FileNotFoundException expected) { }
            check(file.length() == 3);
            File created = new File(dir, "created");
            check(SafeDocumentOpen.create(root, created.getAbsolutePath(), false) == 0);
            check(SafeDocumentOpen.create(root, created.getAbsolutePath(), false) == 1);
            SafeDocumentOpen.delete(root, created.getAbsolutePath());
            check(!created.exists());
            try { SafeDocumentOpen.delete(root, new File(link, "ñ.txt").getAbsolutePath()); throw new AssertionError(); }
            catch (FileNotFoundException expected) { }
            try (ParcelFileDescriptor fd = SafeDocumentOpen.open(root, file.getAbsolutePath(), "rwt")) {
                check(fd.getStatSize() == 0);
            }
            System.out.println("Android document JNI and descriptor smoke passed");
        } finally {
            new File(dir, "created").delete(); link.delete(); file.delete(); dir.delete(); root.delete();
        }
    }
}

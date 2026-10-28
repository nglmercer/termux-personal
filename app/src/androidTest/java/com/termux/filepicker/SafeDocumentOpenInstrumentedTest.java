package com.termux.filepicker;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SafeDocumentOpenInstrumentedTest {
    @Test public void opensRegularFilesAndRejectsTraversalAndLinks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = File.createTempFile("docs", "", context.getCacheDir());
        assertTrue(root.delete()); assertTrue(root.mkdir());
        File dir = new File(root, "dir"); assertTrue(dir.mkdir());
        File file = new File(dir, "ñ.txt");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("abc".getBytes(StandardCharsets.UTF_8)); }
        File link = new File(root, "link"); Os.symlink(dir.getPath(), link.getPath());
        try {
            assertEquals(2, SafeDocumentOpen.list(root, root.getAbsolutePath()).length);
            assertEquals(1, SafeDocumentOpen.list(root, dir.getAbsolutePath()).length);
            try { SafeDocumentOpen.list(root, link.getAbsolutePath()); fail(); }
            catch (FileNotFoundException expected) { }
            try (ParcelFileDescriptor fd = SafeDocumentOpen.open(root, file.getPath(), "r")) {
                assertEquals(3, fd.getStatSize());
            }
            reject(root, new File(link, "ñ.txt").getPath(), "w");
            assertEquals(0, SafeDocumentOpen.create(root, new File(dir, "new").getPath(), false));
            assertEquals(1, SafeDocumentOpen.create(root, new File(dir, "new").getPath(), false));
            SafeDocumentOpen.delete(root, new File(dir, "new").getPath());
            assertFalse(new File(dir, "new").exists());
            try { SafeDocumentOpen.create(root, new File(link, "escape").getPath(), false); fail(); }
            catch (FileNotFoundException expected) { }
            try { SafeDocumentOpen.delete(root, new File(link, "ñ.txt").getPath()); fail(); }
            catch (FileNotFoundException expected) { }
            assertFalse(new File(dir, "escape").exists());
            reject(root, new File(root, "../outside").getPath(), "r");
            reject(root, dir.getPath(), "r");
            assertEquals(3, file.length());
            try (ParcelFileDescriptor fd = SafeDocumentOpen.open(root, file.getPath(), "rwt")) {
                assertEquals(0, fd.getStatSize());
            }
        } finally {
            link.delete(); new File(dir, "new").delete(); file.delete(); dir.delete(); root.delete();
        }
    }

    private static void reject(File root, String id, String mode) throws Exception {
        try (ParcelFileDescriptor ignored = SafeDocumentOpen.open(root, id, mode)) { fail("unsafe open accepted"); }
        catch (FileNotFoundException expected) { }
    }
}

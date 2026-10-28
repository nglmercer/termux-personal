package com.termux.filepicker;

import android.database.Cursor;
import android.provider.DocumentsContract.Document;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TermuxDocumentsProviderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root;
    private TermuxDocumentsProvider provider;

    @Before public void setUp() throws Exception {
        root = temporary.newFolder("home");
        provider = new TermuxDocumentsProvider(root);
    }

    @Test public void normalDocumentsKeepAbsoluteIdsAndEnforceAncestry() throws Exception {
        String folder = provider.createDocument(root.getPath(), Document.MIME_TYPE_DIR, "project");
        String file = provider.createDocument(folder, "text/plain", "ReadMe.txt");
        String second = provider.createDocument(folder, "text/plain", "ReadMe.txt");
        assertEquals(new File(root, "project/ReadMe.txt").getAbsolutePath(), file);
        assertEquals(new File(root, "project/ReadMe.txt (2)").getAbsolutePath(), second);
        assertTrue(provider.isChildDocument(root.getPath(), file));
        assertFalse(provider.isChildDocument(folder, folder));
        try (Cursor children = provider.queryChildDocuments(folder, null, (String) null)) {
            assertEquals(2, children.getCount());
        }
        try (Cursor search = provider.querySearchDocuments(root.getPath(), "readme", null)) {
            assertEquals(2, search.getCount());
        }
        provider.deleteDocument(second);
        provider.deleteDocument(file);
        provider.deleteDocument(folder);
        assertTrue(root.exists());
    }

    @Test public void rejectsHostileIdsNamesAndRootRemoval() throws Exception {
        File sibling = temporary.newFolder("home-other");
        File outside = new File(sibling, "secret"); assertTrue(outside.createNewFile());
        File linked = new File(root, "link"); Files.createSymbolicLink(linked.toPath(), sibling.toPath());
        File prefix = new File(root, "project"); assertTrue(prefix.mkdir());
        File prefixSibling = new File(root, "project-other"); assertTrue(prefixSibling.mkdir());
        assertFalse(provider.isChildDocument(prefix.getPath(), prefixSibling.getPath()));
        assertFalse(provider.isChildDocument(root.getPath(), outside.getPath()));
        assertFalse(provider.isChildDocument(root.getPath(), new File(linked, "secret").getPath()));
        expectMissing(() -> provider.queryDocument(outside.getPath(), null));
        expectMissing(() -> provider.queryDocument(new File(linked, "secret").getPath(), null));
        expectMissing(() -> provider.createDocument(root.getPath(), "text/plain", "../escape"));
        expectMissing(() -> provider.createDocument(root.getPath(), "text/plain", null));
        expectMissing(() -> provider.createDocument(outside.getPath(), "text/plain", "x"));
        expectMissing(() -> provider.deleteDocument(root.getPath()));
        expectMissing(() -> provider.querySearchDocuments(sibling.getPath(), "secret", null));
        expectMissing(() -> provider.querySearchDocuments(root.getPath(), (String) null, null));
        expectMissing(() -> provider.deleteDocument(outside.getPath()));
        expectMissing(() -> provider.queryChildDocuments(outside.getPath(), null, (String) null));
        assertTrue(outside.exists());
    }

    private interface Operation { void run() throws Exception; }
    private static void expectMissing(Operation operation) throws Exception {
        try { operation.run(); fail("operation accepted invalid input"); }
        catch (FileNotFoundException expected) { }
    }
}

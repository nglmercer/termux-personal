package com.termux.filepicker;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class DocumentPathPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rejectsTraversalSiblingAndLinks() throws Exception {
        File root = temporary.newFolder("home");
        File child = new File(root, "child");
        assertTrue(child.createNewFile());
        File sibling = temporary.newFolder("home-other");
        File outside = new File(sibling, "outside");
        assertTrue(outside.createNewFile());
        assertEquals(child, DocumentPathPolicy.existing(root, child.getAbsolutePath()));
        reject(root, outside.getAbsolutePath());
        reject(root, new File(root, "../home-other/outside").getAbsolutePath());
        reject(root, "relative");
        reject(root, null);
        Files.createSymbolicLink(new File(root, "outside-link").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(root, "inside-link").toPath(), child.toPath());
        reject(root, new File(root, "outside-link").getAbsolutePath());
        reject(root, new File(root, "inside-link").getAbsolutePath());
    }

    @Test public void validatesSingleNames() {
        for (String name : new String[]{null, "", ".", "..", "../x", "/x", "a\0b"})
            assertFalse(DocumentPathPolicy.validName(name));
        assertTrue(DocumentPathPolicy.validName("notes.txt"));
    }

    private static void reject(File root, String path) throws Exception {
        try { DocumentPathPolicy.existing(root, path); fail("accepted " + path); }
        catch (FileNotFoundException expected) { }
    }
}

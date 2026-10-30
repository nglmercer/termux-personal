package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import com.termux.app.control.files.FileService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkspaceRepositoryTest {
    private File base, home, store;
    private FileService files;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("workspaces").toFile();
        home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        store = new File(base, "store");
        assertTrue(store.mkdir());
        Map<String, File> roots = new HashMap<>();
        roots.put("home", home);
        files = new FileService(roots);
    }

    @After public void tearDown() { delete(base); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private WorkspaceRepository open() throws Exception { return new WorkspaceRepository(new File(store, "v1.json"), files); }

    private static WorkspaceRepository.TaskPreset preset(String id) throws Exception {
        return new WorkspaceRepository.TaskPreset(id, "Run tests", "cargo", Collections.singletonList("test"), 120000);
    }

    private static void assertCode(String code, Action action) {
        try { action.run(); fail("accepted; expected " + code); }
        catch (WorkspaceRepository.WorkspaceException e) { assertEquals(code, e.code); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private interface Action { void run() throws Exception; }

    @Test public void createPersistsAndSurvivesReopen() throws Exception {
        WorkspaceRepository repository = open();
        List<WorkspaceRepository.TaskPreset> presets = new ArrayList<>();
        presets.add(preset("test"));
        WorkspaceRepository.Workspace created = repository.create("my-app", "home", "projects/app", presets);
        assertTrue(created.id.matches("ws_[0-9a-f]{16}"));
        assertTrue(created.available);
        assertEquals(1, repository.list().size());

        WorkspaceRepository reopened = open();
        WorkspaceRepository.Workspace loaded = reopened.get(created.id);
        assertEquals("my-app", loaded.name);
        assertEquals("cargo", loaded.taskPresets.get(0).executable);
        assertEquals(java.util.Arrays.asList("test"), loaded.taskPresets.get(0).args);
        assertFalse(reopened.recoveredFromCorruption());
    }

    @Test public void locationsAreValidatedThroughTheFileService() throws Exception {
        WorkspaceRepository repository = open();
        assertCode("UNAVAILABLE", () -> repository.create("ghost", "home", "projects/missing", Collections.<WorkspaceRepository.TaskPreset>emptyList()));
        assertCode("INVALID_REQUEST", () -> repository.create("escape", "home", "../etc", Collections.<WorkspaceRepository.TaskPreset>emptyList()));
        assertCode("INVALID_REQUEST", () -> repository.create("line\nbreak", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList()));
        assertCode("NOT_FOUND", () -> repository.get("ws_0000000000000000"));
        assertCode("INVALID_REQUEST", () -> repository.get("ws_zz"));
        assertEquals(0, repository.list().size());
    }

    @Test public void presetsAreValidatedAsData() throws Exception {
        try { new WorkspaceRepository.TaskPreset("Bad Id", "x", "cargo", Collections.<String>emptyList(), 0); fail(); }
        catch (WorkspaceRepository.WorkspaceException expected) { assertEquals("INVALID_REQUEST", expected.code); }
        try { new WorkspaceRepository.TaskPreset("ok", "x", "sh -c rm", Collections.<String>emptyList(), 0); fail(); }
        catch (WorkspaceRepository.WorkspaceException expected) { assertEquals("INVALID_REQUEST", expected.code); }
        try { new WorkspaceRepository.TaskPreset("ok", "x", "/a/../b", Collections.<String>emptyList(), 0); fail(); }
        catch (WorkspaceRepository.WorkspaceException expected) { assertEquals("INVALID_REQUEST", expected.code); }
    }

    @Test public void updateKeepsUnchangedFieldsAndRejectsMissingLocationWithoutWriting() throws Exception {
        WorkspaceRepository repository = open();
        WorkspaceRepository.Workspace created = repository.create("app", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList());
        WorkspaceRepository.Workspace renamed = repository.update(created.id, "app-2", null, null, null);
        assertEquals("app-2", renamed.name);
        assertEquals("projects/app", renamed.path);
        assertCode("UNAVAILABLE", () -> repository.update(created.id, null, null, "projects/nowhere", null));
        assertEquals("app-2", repository.get(created.id).name);
        assertEquals("projects/app", repository.get(created.id).path);
    }

    @Test public void selectionIsStoredAndRemovalClearsIt() throws Exception {
        WorkspaceRepository repository = open();
        WorkspaceRepository.Workspace created = repository.create("app", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList());
        repository.select(created.id);
        assertEquals(created.id, open().activeId());
        repository.remove(created.id);
        assertNull(repository.activeId());
        assertCode("NOT_FOUND", () -> repository.get(created.id));
    }

    @Test public void vanishedRootIsReportedUnavailableNotHidden() throws Exception {
        WorkspaceRepository repository = open();
        WorkspaceRepository.Workspace created = repository.create("app", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList());
        assertTrue(new File(home, "projects/app").delete());
        assertFalse(repository.get(created.id).available);
        assertFalse(repository.list().get(0).available);
        assertCode("UNAVAILABLE", () -> repository.select(created.id));
    }

    @Test public void unreadableMetadataIsPreservedAndStoreStartsEmpty() throws Exception {
        File file = new File(store, "v1.json");
        Files.write(file.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        WorkspaceRepository repository = open();
        assertTrue(repository.recoveredFromCorruption());
        assertEquals(0, repository.list().size());
        assertFalse(file.exists());
        File[] leftovers = store.listFiles();
        boolean preserved = false;
        for (File candidate : leftovers) if (candidate.getName().startsWith("v1.json.corrupt-")) preserved = true;
        assertTrue(preserved);
    }

    @Test public void countIsBounded() throws Exception {
        WorkspaceRepository repository = open();
        for (int i = 0; i < WorkspaceRepository.MAX_WORKSPACES; i++)
            repository.create("app" + i, "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList());
        assertCode("LIMIT_REACHED", () -> repository.create("one-more", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList()));
    }
}

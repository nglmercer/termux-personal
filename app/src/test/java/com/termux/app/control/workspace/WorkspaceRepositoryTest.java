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

    private static final String V1_FILE = "{\"version\":1,\"active\":null,\"workspaces\":[{\"id\":\"ws_00000000000000aa\",\"name\":\"old\","
        + "\"rootId\":\"home\",\"path\":\"projects/app\",\"createdAt\":1,\"updatedAt\":2,\"taskPresets\":[{\"id\":\"build\","
        + "\"label\":\"Build\",\"executable\":\"cargo\",\"args\":[\"build\"],\"timeoutMs\":0}]}]}";

    @Test public void aV1FileWrittenBeforeResourceFieldsLoadsUnchangedWithHistoricalBehavior() throws Exception {
        Files.write(new File(store, "v1.json").toPath(), V1_FILE.getBytes(StandardCharsets.UTF_8));
        WorkspaceRepository repository = open();
        assertFalse(repository.recoveredFromCorruption());
        WorkspaceRepository.Workspace w = repository.get("ws_00000000000000aa");
        assertNull("no profile means the historical behavior", w.resourceProfile);
        assertTrue(w.services.isEmpty());
        assertNull(w.taskPresets.get(0).resources);
        // Saving does not add the new members to data that never had them.
        repository.update(w.id, "renamed", null, null, null);
        String raw = new String(Files.readAllBytes(new File(store, "v1.json").toPath()), StandardCharsets.UTF_8);
        assertFalse(raw.contains("resourceProfile")); assertFalse(raw.contains("services")); assertFalse(raw.contains("\"resources\""));
        assertEquals("renamed", open().get(w.id).name);
    }

    @Test public void profilePresetResourcesAndServicesPersistAndRoundTrip() throws Exception {
        WorkspaceRepository repository = open();
        com.termux.app.control.jobs.JobResourcePolicy protectedPolicy = com.termux.app.control.jobs.JobResourcePolicy.builder()
            .profile(com.termux.app.control.jobs.JobResourcePolicy.Profile.PROTECTED).processConcurrencyHint(2L).memorySoftBytes(1L << 30).build();
        WorkspaceRepository.TaskPreset build = new WorkspaceRepository.TaskPreset("build", "Build", "cargo", Collections.singletonList("build"), 0, protectedPolicy);
        WorkspaceRepository.ServiceDefinition service = new WorkspaceRepository.ServiceDefinition("dev-server", "node", java.util.Arrays.asList("server.js"),
            WorkspaceRepository.RestartPolicy.ON_FAILURE, com.termux.app.control.jobs.JobResourcePolicy.ofProfile(com.termux.app.control.jobs.JobResourcePolicy.Profile.BALANCED),
            java.util.Arrays.asList(3000, 3001));
        WorkspaceRepository.Workspace created = repository.create("rust", "home", "projects/app", Collections.singletonList(build),
            com.termux.app.control.jobs.JobResourcePolicy.Profile.BALANCED, Collections.singletonList(service));
        WorkspaceRepository.Workspace loaded = open().get(created.id);
        assertEquals(com.termux.app.control.jobs.JobResourcePolicy.Profile.BALANCED, loaded.resourceProfile);
        assertEquals(protectedPolicy, loaded.taskPresets.get(0).resources);
        WorkspaceRepository.ServiceDefinition back = loaded.services.get(0);
        assertEquals("dev-server", back.id); assertEquals(WorkspaceRepository.RestartPolicy.ON_FAILURE, back.restartPolicy);
        assertEquals(java.util.Arrays.asList(3000, 3001), back.ports);
        // Clearing the profile is distinct from leaving it.
        repository.update(created.id, null, null, null, null);
        assertNotNull(open().get(created.id).resourceProfile);
        repository.update(created.id, null, null, null, null, true, null, null);
        assertNull(open().get(created.id).resourceProfile);
        assertEquals("services untouched when not given", 1, open().get(created.id).services.size());
    }

    @Test public void theEndpointCredentialOptInPersistsOnlyWhenChosen() throws Exception {
        WorkspaceRepository repository = open();
        WorkspaceRepository.ServiceDefinition api = new WorkspaceRepository.ServiceDefinition("api", "node", java.util.Arrays.asList("s.js"),
            WorkspaceRepository.RestartPolicy.NEVER, null, java.util.Arrays.asList(8080), true);
        WorkspaceRepository.ServiceDefinition plain = new WorkspaceRepository.ServiceDefinition("plain", "node", java.util.Arrays.asList("s.js"),
            WorkspaceRepository.RestartPolicy.NEVER, null, java.util.Arrays.asList(8081));
        WorkspaceRepository.Workspace created = repository.create("web", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList(),
            null, java.util.Arrays.asList(api, plain));
        WorkspaceRepository.Workspace loaded = open().get(created.id);
        assertTrue(loaded.services.get(0).credential);
        assertFalse(loaded.services.get(1).credential);
        String raw = new String(java.nio.file.Files.readAllBytes(new File(store, "v1.json").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("written once, for the service that chose it", raw.indexOf("\"credential\""), raw.lastIndexOf("\"credential\""));
    }

    @Test public void invalidOptionalFieldsNeverCostTheUserTheirWorkspaces() throws Exception {
        String damaged = V1_FILE.replace("\"taskPresets\":[{", "\"resourceProfile\":\"turbo\",\"services\":[{\"id\":\"BAD ID\"},{\"id\":\"ok\",\"executable\":\"node\",\"args\":[],\"restartPolicy\":\"sometimes\"}],\"taskPresets\":[{\"resources\":{\"memorySoftBytes\":1},")
            ;
        Files.write(new File(store, "v1.json").toPath(), damaged.getBytes(StandardCharsets.UTF_8));
        WorkspaceRepository repository = open();
        assertFalse("not treated as corruption", repository.recoveredFromCorruption());
        WorkspaceRepository.Workspace w = repository.get("ws_00000000000000aa");
        assertEquals("old", w.name);
        assertEquals("the task survives", "build", w.taskPresets.get(0).id);
        assertNull(w.resourceProfile); assertNull(w.taskPresets.get(0).resources); assertTrue(w.services.isEmpty());
    }

    @Test public void serviceDefinitionsAreValidated() throws Exception {
        assertCode("INVALID_REQUEST", () -> new WorkspaceRepository.ServiceDefinition("Bad Id", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, Collections.<Integer>emptyList()));
        assertCode("INVALID_REQUEST", () -> new WorkspaceRepository.ServiceDefinition("s", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, java.util.Arrays.asList(0)));
        assertCode("INVALID_REQUEST", () -> new WorkspaceRepository.ServiceDefinition("s", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, java.util.Arrays.asList(70000)));
        assertCode("INVALID_REQUEST", () -> new WorkspaceRepository.ServiceDefinition("s", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, java.util.Arrays.asList(80, 80)));
        assertCode("INVALID_REQUEST", () -> new WorkspaceRepository.ServiceDefinition("s", "../../bin/sh", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, Collections.<Integer>emptyList()));
        assertCode("INVALID_REQUEST", () -> WorkspaceRepository.RestartPolicy.parse("maybe"));
        WorkspaceRepository repository = open();
        WorkspaceRepository.ServiceDefinition a = new WorkspaceRepository.ServiceDefinition("s", "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, Collections.<Integer>emptyList());
        assertCode("INVALID_REQUEST", () -> repository.create("dup", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList(), null, java.util.Arrays.asList(a, a)));
        List<WorkspaceRepository.ServiceDefinition> tooMany = new ArrayList<>();
        for (int i = 0; i <= WorkspaceRepository.MAX_SERVICES; i++)
            tooMany.add(new WorkspaceRepository.ServiceDefinition("s" + i, "node", Collections.<String>emptyList(), WorkspaceRepository.RestartPolicy.NEVER, null, Collections.<Integer>emptyList()));
        assertCode("INVALID_REQUEST", () -> repository.create("many", "home", "projects/app", Collections.<WorkspaceRepository.TaskPreset>emptyList(), null, tooMany));
    }
}

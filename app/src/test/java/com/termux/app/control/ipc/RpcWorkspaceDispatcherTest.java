package com.termux.app.control.ipc;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
import com.termux.app.control.workspace.WorkspaceRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RpcWorkspaceDispatcherTest {
    private static final Set<ControlCore.Scope> READ = EnumSet.of(ControlCore.Scope.WORKSPACES_READ);
    private static final Set<ControlCore.Scope> WRITE = EnumSet.of(ControlCore.Scope.WORKSPACES_WRITE);
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";
    private static final String CREATE = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"workspace.create\",\"params\":"
        + "{\"name\":\"my-app\",\"rootId\":\"home\",\"path\":\"projects/app\",\"taskPresets\":[{\"id\":\"test\",\"label\":\"Run tests\","
        + "\"executable\":\"cargo\",\"args\":[\"test\"],\"timeoutMs\":120000}]}}";

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String name, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] data) { }
        @Override public void rename(String id, String name) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private File base, home, store;
    private RpcRequestParser parser;
    private RpcDispatcher dispatcher;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("rpc-workspaces").toFile();
        home = new File(base, "home");
        assertTrue(new File(home, "projects/app").mkdirs());
        store = new File(base, "store");
        assertTrue(store.mkdir());
        Map<String, File> roots = new HashMap<>();
        roots.put("home", home);
        WorkspaceRepository repository = new WorkspaceRepository(new File(store, "v1.json"), new FileService(roots));
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), null, new DomainServices(null, null, null, null, repository));
        parser = new RpcRequestParser();
        dispatcher.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() { delete(base); }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private JsonObject rpc(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(bytes(json)), scopes), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private String createdId() throws Exception {
        return rpc(CREATE, WRITE).getAsJsonObject("result").getAsJsonObject("workspace").get("id").getAsString();
    }

    @Test public void discoveryShowsWriteMethodsOnlyWithWriteScope() throws Exception {
        String readOnly = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"system.capabilities\"}", READ).toString();
        assertTrue(readOnly.contains("workspace.list"));
        assertTrue(readOnly.contains("workspace.tasks"));
        assertFalse(readOnly.contains("workspace.create"));
        assertFalse(readOnly.contains("workspace.select"));
    }

    @Test public void writeMethodsRequireWriteScopeAndReadMethodsRequireReadScope() throws Exception {
        assertEquals("DENIED", rpc(CREATE, READ).getAsJsonObject("error").get("message").getAsString());
        assertEquals("DENIED", rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"workspace.list\"}", WRITE)
            .getAsJsonObject("error").get("message").getAsString());
    }

    @Test public void createListGetAndTasksRoundTrip() throws Exception {
        String id = createdId();
        JsonObject listed = rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"workspace.list\"}", READ).getAsJsonObject("result");
        assertEquals(1, listed.getAsJsonArray("workspaces").size());
        assertTrue(listed.get("activeId").isJsonNull());
        JsonObject got = rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"workspace.get\",\"params\":{\"id\":\"" + id + "\"}}", READ)
            .getAsJsonObject("result").getAsJsonObject("workspace");
        assertEquals("my-app", got.get("name").getAsString());
        assertTrue(got.get("available").getAsBoolean());
        JsonObject tasks = rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"workspace.tasks\",\"params\":{\"id\":\"" + id + "\"}}", READ)
            .getAsJsonObject("result");
        assertEquals("cargo", tasks.getAsJsonArray("tasks").get(0).getAsJsonObject().get("executable").getAsString());
    }

    @Test public void selectThenRemoveUpdatesActivePointerAndHidesWorkspace() throws Exception {
        String id = createdId();
        JsonObject selected = rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"workspace.select\",\"params\":{\"id\":\"" + id + "\"}}", WRITE)
            .getAsJsonObject("result");
        assertEquals(id, selected.get("activeId").getAsString());
        assertTrue(rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"workspace.remove\",\"params\":{\"id\":\"" + id + "\"}}", WRITE)
            .getAsJsonObject("result").get("accepted").getAsBoolean());
        assertEquals("NOT_FOUND", rpc("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"workspace.get\",\"params\":{\"id\":\"" + id + "\"}}", READ)
            .getAsJsonObject("error").get("message").getAsString());
    }

    @Test public void invalidLocationsAndParametersAreRejected() throws Exception {
        assertEquals("INVALID_REQUEST", rpc("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"workspace.create\",\"params\":"
            + "{\"name\":\"x\",\"rootId\":\"home\",\"path\":\"../etc\"}}", WRITE).getAsJsonObject("error").get("message").getAsString());
        assertEquals("UNAVAILABLE", rpc("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"workspace.create\",\"params\":"
            + "{\"name\":\"x\",\"rootId\":\"home\",\"path\":\"projects/none\"}}", WRITE).getAsJsonObject("error").get("message").getAsString());
        assertEquals(-32602, rpc("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"workspace.create\",\"params\":"
            + "{\"name\":\"x\",\"rootId\":\"home\",\"path\":\"projects/app\",\"command\":\"rm -rf\"}}", WRITE)
            .getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void workspaceMethodsAreAbsentWithoutService() throws Exception {
        RpcDispatcher bare = new RpcDispatcher(new ControlCore(new NoSessions()));
        bare.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
        String caps = new String(bare.dispatch(parser.parse(bytes(
            "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"system.capabilities\"}")), EnumSet.allOf(ControlCore.Scope.class)), StandardCharsets.UTF_8);
        assertFalse(caps.contains("workspace."));
    }
}

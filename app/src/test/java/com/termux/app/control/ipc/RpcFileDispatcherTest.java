package com.termux.app.control.ipc;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.files.FileService;
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

public class RpcFileDispatcherTest {
    private static final Set<ControlCore.Scope> READ = EnumSet.of(ControlCore.Scope.FILES_READ);
    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}";

    private static final class NoSessions implements ControlCore.Backend {
        @Override public boolean isCloseEnabled() { return false; }
        @Override public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
        @Override public ControlCore.SessionInfo create(String name, int c, int r, int w, int h) throws ControlCore.ControlException { throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE); }
        @Override public void write(String id, byte[] data) { }
        @Override public void rename(String id, String name) { }
        @Override public void close(String id) { }
        @Override public void resize(String id, int c, int r, int w, int h) { }
    }

    private File base, root;
    private RpcRequestParser parser;
    private RpcDispatcher dispatcher;

    @Before public void setUp() throws Exception {
        base = Files.createTempDirectory("rpc-files").toFile();
        root = new File(base, "home");
        assertTrue(root.mkdir());
        Files.write(new File(root, "notes.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        Map<String, File> roots = new HashMap<>();
        roots.put("home", root);
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), null, new DomainServices(null, null, null, new FileService(roots), null));
        parser = new RpcRequestParser();
        dispatcher.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() {
        File[] files = root.listFiles();
        if (files != null) for (File f : files) f.delete();
        root.delete(); base.delete();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private JsonObject rpc(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(bytes(json)), scopes), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private RpcDispatcher.Result raw(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return dispatcher.dispatchResult(parser.parse(bytes(json)), scopes, null);
    }

    @Test public void rootsAreNamedAndNeverReturnedAsDevicePaths() throws Exception {
        String roots = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"file.roots\",\"params\":{}}", READ).toString();
        assertTrue(roots.contains("\"id\":\"home\""));
        assertFalse(roots.contains(root.getAbsolutePath()));
        assertFalse(roots.contains("/"));
    }

    @Test public void everyFileMethodRequiresFilesReadScope() throws Exception {
        String list = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"file.list\",\"params\":{\"rootId\":\"home\"}}";
        assertEquals("DENIED", rpc(list, Collections.<ControlCore.Scope>emptySet()).getAsJsonObject("error").get("message").getAsString());
        assertEquals("DENIED", rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"file.roots\"}", Collections.<ControlCore.Scope>emptySet())
            .getAsJsonObject("error").get("message").getAsString());
    }

    @Test public void listAndStatReturnRootRelativeEntries() throws Exception {
        JsonObject result = rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"file.list\",\"params\":{\"rootId\":\"home\",\"limit\":10}}", READ)
            .getAsJsonObject("result");
        assertEquals("notes.txt", result.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("notes.txt", result.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString());
        JsonObject stat = rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"file.stat\",\"params\":{\"rootId\":\"home\",\"path\":\"notes.txt\"}}", READ)
            .getAsJsonObject("result").getAsJsonObject("entry");
        assertEquals(5, stat.get("size").getAsLong());
        assertEquals("file", stat.get("type").getAsString());
    }

    @Test public void readReturnsMetadataAndBinaryFrame() throws Exception {
        RpcDispatcher.Result result = raw("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"file.read\","
            + "\"params\":{\"rootId\":\"home\",\"path\":\"notes.txt\",\"offset\":1,\"limit\":3}}", READ);
        JsonObject metadata = JsonParser.parseString(new String(result.json, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("result");
        assertEquals(3, metadata.get("length").getAsInt());
        assertEquals(4, metadata.get("nextOffset").getAsInt());
        assertFalse(metadata.get("eof").getAsBoolean());
        assertEquals("ell", new String(result.binary, StandardCharsets.UTF_8));
    }

    @Test public void pathViolationsAndMissingFilesMapToReservedCodes() throws Exception {
        assertEquals("INVALID_REQUEST", rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"file.stat\",\"params\":{\"rootId\":\"home\",\"path\":\"../secret\"}}", READ)
            .getAsJsonObject("error").get("message").getAsString());
        assertEquals("NOT_FOUND", rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"file.stat\",\"params\":{\"rootId\":\"other\",\"path\":\"notes.txt\"}}", READ)
            .getAsJsonObject("error").get("message").getAsString());
        assertEquals(-32602, rpc("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"file.read\",\"params\":{\"rootId\":\"home\",\"path\":\"notes.txt\",\"offset\":0}}", READ)
            .getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void fileMethodsAreAbsentWithoutService() throws Exception {
        RpcDispatcher bare = new RpcDispatcher(new ControlCore(new NoSessions()));
        bare.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
        JsonObject caps = JsonParser.parseString(new String(bare.dispatch(parser.parse(bytes(
            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"system.capabilities\"}")), READ), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(caps.toString().contains("file."));
    }
}

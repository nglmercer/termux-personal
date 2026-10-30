package com.termux.app.control.ipc;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import com.termux.app.control.process.ProcessService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RpcProcessDispatcherTest {
    private static final Set<ControlCore.Scope> READ = EnumSet.of(ControlCore.Scope.PROCESSES_READ);
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

    private File root;
    private final Set<Integer> sessions = new HashSet<>();
    private RpcRequestParser parser;
    private RpcDispatcher dispatcher;

    @Before public void setUp() throws Exception {
        root = Files.createTempDirectory("rpc-proc").toFile();
        add(10, 1); add(11, 10);
        ProcessService service = new ProcessService(root, 1000, 100, 4096, new ProcessService.Roots() {
            @Override public Set<Integer> sessionRoots() { return sessions; }
            @Override public Set<Integer> taskRoots() { return Collections.emptySet(); }
        });
        sessions.add(10);
        parser = new RpcRequestParser();
        dispatcher = new RpcDispatcher(new ControlCore(new NoSessions()), null, new DomainServices(null, null, service, null, null));
        dispatcher.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
    }

    @After public void tearDown() {
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) { File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete(); dir.delete(); }
        root.delete();
    }

    private void add(int pid, int ppid) throws Exception {
        File dir = new File(root, String.valueOf(pid));
        assertTrue(dir.mkdir());
        Files.write(new File(dir, "status").toPath(), "Name:\ttest\nUid:\t1000\t1000\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(dir, "stat").toPath(), (pid + " (zsh) S " + ppid + " 0 0 0 0 0 0 0 0 0 10 20 0 0 0 0 0 0 55 0 7").getBytes(StandardCharsets.US_ASCII));
    }

    private JsonObject rpc(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return JsonParser.parseString(new String(dispatcher.dispatch(parser.parse(bytes(json)), scopes), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String list(String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"process.list\",\"params\":" + params + "}";
    }

    @Test public void processListIsAbsentWithoutService() throws Exception {
        RpcDispatcher bare = new RpcDispatcher(new ControlCore(new NoSessions()));
        bare.dispatch(parser.parse(bytes(HELLO)), Collections.<ControlCore.Scope>emptySet());
        JsonObject caps = JsonParser.parseString(new String(bare.dispatch(parser.parse(bytes(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"system.capabilities\"}")), READ), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(caps.toString().contains("process."));
        JsonObject missing = JsonParser.parseString(new String(bare.dispatch(parser.parse(bytes(list("{}"))), READ), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(-32601, missing.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test public void discoveryAdvertisesProcessListOnlyWithReadScope() throws Exception {
        String caps = rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"system.capabilities\"}", READ).toString();
        assertTrue(caps.contains("process.list"));
        assertFalse(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"system.capabilities\"}", Collections.<ControlCore.Scope>emptySet())
            .toString().contains("process.list"));
    }

    @Test public void listRequiresProcessReadScope() throws Exception {
        assertEquals("DENIED", rpc(list("{}"), Collections.<ControlCore.Scope>emptySet())
            .getAsJsonObject("error").get("message").getAsString());
    }

    @Test public void listReturnsBoundedReadOnlyRowsWithoutManagedHandles() throws Exception {
        JsonObject result = rpc(list("{\"scope\":\"owned\",\"limit\":5}"), READ).getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("processes").size());
        JsonObject first = result.getAsJsonArray("processes").get(0).getAsJsonObject();
        assertEquals(10, first.get("pid").getAsInt());
        assertEquals("S", first.get("state").getAsString());
        assertTrue(first.get("cpuPercent").isJsonNull());
        assertTrue(first.get("managedJobId").isJsonNull());
        assertEquals("partial", first.get("visibility").getAsString());
        assertFalse(result.get("incomplete").getAsBoolean());
        assertTrue(result.get("nextCursor").isJsonNull());
    }

    @Test public void invalidParametersAreRejectedBeforeSampling() throws Exception {
        assertEquals(-32602, rpc(list("{\"scope\":\"signal\"}"), READ).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, rpc(list("{\"limit\":101}"), READ).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, rpc(list("{\"pid\":10}"), READ).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, rpc(list("{\"cursor\":9}"), READ).getAsJsonObject("error").get("code").getAsInt());
    }
}

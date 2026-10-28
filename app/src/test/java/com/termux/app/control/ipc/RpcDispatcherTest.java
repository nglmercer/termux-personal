package com.termux.app.control.ipc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import java.io.InputStreamReader;
import com.termux.app.control.ControlCore;
import junit.framework.TestCase;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class RpcDispatcherTest extends TestCase {
    private static final class Backend implements ControlCore.Backend {
        int calls;
        boolean closeEnabled;
        String lastId;
        @Override public boolean isCloseEnabled() { return closeEnabled; }
        @Override public List<ControlCore.SessionInfo> list() {
            calls++;
            return Collections.singletonList(new ControlCore.SessionInfo("s1", ControlCore.State.RUNNING, 123, "Work"));
        }
        @Override public ControlCore.SessionInfo create(String name, int columns, int rows, int width, int height) {
            calls++; return new ControlCore.SessionInfo("new", ControlCore.State.RUNNING, 42, name);
        }
        @Override public void write(String id, byte[] data) { calls++; }
        @Override public void rename(String id, String name) { calls++; lastId = id; }
        @Override public void close(String id) { calls++; lastId = id; }
        @Override public void resize(String id, int columns, int rows, int width, int height) { calls++; lastId = id; }
    }

    private final RpcRequestParser parser = new RpcRequestParser();
    private final Backend backend = new Backend();
    private final RpcDispatcher dispatcher = new RpcDispatcher(new ControlCore(backend));

    private byte[] send(String json, Set<ControlCore.Scope> scopes) throws Exception {
        return dispatcher.dispatch(parser.parse(json.getBytes(StandardCharsets.UTF_8)), scopes);
    }

    private JsonObject object(byte[] value) {
        assertNotNull(value);
        return JsonParser.parseString(new String(value, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void hello() throws Exception {
        JsonObject response = object(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}", Collections.emptySet()));
        assertEquals(1, response.getAsJsonObject("result").get("version").getAsInt());
    }

    public void testReadOnlyV1GoldenConversation() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(
                getClass().getResourceAsStream("/control/v1-read-only.json"), StandardCharsets.UTF_8)) {
            JsonArray cases = JsonParser.parseReader(reader).getAsJsonArray();
            for (com.google.gson.JsonElement element : cases) {
                JsonObject entry = element.getAsJsonObject();
                JsonObject response = object(send(entry.get("request").toString(), EnumSet.of(ControlCore.Scope.SESSIONS_READ)));
                assertEquals(entry.get("response"), response);
            }
        }
    }

    public void testHandshakeAuthorizationAndList() throws Exception {
        JsonObject before = object(send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.list\"}", Collections.emptySet()));
        assertEquals(-32600, before.getAsJsonObject("error").get("code").getAsInt());
        assertFalse(dispatcher.isNegotiated());
        hello();
        JsonObject denied = object(send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"session.list\"}", Collections.emptySet()));
        assertEquals("DENIED", denied.getAsJsonObject("error").get("message").getAsString());
        assertEquals(0, backend.calls);
        JsonObject success = object(send("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"session.list\"}", EnumSet.of(ControlCore.Scope.SESSIONS_READ)));
        assertEquals("a", success.get("id").getAsString());
        assertEquals("s1", success.getAsJsonObject("result").getAsJsonArray("sessions").get(0).getAsJsonObject().get("id").getAsString());
    }

    public void testScopesCannotBeSuppliedInParameters() throws Exception {
        hello();
        JsonObject response = object(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.close\",\"params\":{\"id\":\"s1\",\"scopes\":[\"sessions.write\"]}}", Collections.emptySet()));
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(0, backend.calls);
    }

    public void testNotificationsAndBinaryMethodRequiresAFrame() throws Exception {
        hello();
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"session.rename\",\"params\":{\"id\":\"s1\",\"name\":\"A\"}}", EnumSet.of(ControlCore.Scope.SESSIONS_WRITE)));
        assertEquals(1, backend.calls);
        JsonObject response = object(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"session.write\",\"params\":{}}", EnumSet.of(ControlCore.Scope.SESSIONS_WRITE)));
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        JsonObject caps = object(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"system.capabilities\"}", Collections.emptySet()));
        assertFalse(caps.getAsJsonObject("result").toString().contains("session.write"));
        assertFalse(caps.getAsJsonObject("result").toString().contains("session.list"));
        JsonObject readCaps = object(send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"system.capabilities\"}", EnumSet.of(ControlCore.Scope.SESSIONS_READ)));
        String readMethods = readCaps.getAsJsonObject("result").toString();
        assertTrue(readMethods.contains("session.list"));
        for (String method : new String[]{"session.create", "session.write", "session.rename", "session.resize", "session.focus", "session.close", "session.snapshot"})
            assertFalse(readMethods.contains(method));
        assertFalse(caps.getAsJsonObject("result").toString().contains("session.close"));
    }

    public void testCloseCapabilityAndRequestRequireExplicitScope() throws Exception {
        hello(); backend.closeEnabled = true;
        EnumSet<ControlCore.Scope> write = EnumSet.of(ControlCore.Scope.SESSIONS_WRITE);
        EnumSet<ControlCore.Scope> both = EnumSet.of(ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_CLOSE);
        assertFalse(object(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"system.capabilities\"}", write)).getAsJsonObject("result").toString().contains("session.close"));
        assertTrue(object(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"system.capabilities\"}", both)).getAsJsonObject("result").toString().contains("session.close"));
        String close = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.close\",\"params\":{\"id\":\"s1\"}}";
        assertEquals("DENIED", object(send(close, write)).getAsJsonObject("error").get("message").getAsString());
        assertEquals(0, backend.calls);
        assertTrue(object(send(close, both)).getAsJsonObject("result").get("accepted").getAsBoolean());
        assertEquals(1, backend.calls);
        backend.closeEnabled = false;
        assertEquals("UNAVAILABLE", object(send(close, both)).getAsJsonObject("error").get("message").getAsString());
        assertEquals(1, backend.calls);
    }

    public void testTransportTimeoutPreservesUnknownOutcomeAndSuppressesNotifications() throws Exception {
        RpcRequestParser.Request request = parser.parse(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"session.close\"}".getBytes(StandardCharsets.UTF_8));
        JsonObject response = object(RpcDispatcher.transportFailure(request, ServiceThreadDispatch.Failure.OUTCOME_UNKNOWN));
        assertEquals(9, response.get("id").getAsInt());
        assertEquals("OUTCOME_UNKNOWN", response.getAsJsonObject("error").get("message").getAsString());
        RpcRequestParser.Request notification = parser.parse(
            "{\"jsonrpc\":\"2.0\",\"method\":\"session.close\"}".getBytes(StandardCharsets.UTF_8));
        assertNull(RpcDispatcher.transportFailure(notification, ServiceThreadDispatch.Failure.TIMEOUT_NOT_STARTED));
        assertEquals(-32700, object(RpcDispatcher.invalidRequest(true)).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32600, object(RpcDispatcher.invalidRequest(false)).getAsJsonObject("error").get("code").getAsInt());
    }

    public void testBadGeometryDoesNotReachBackend() throws Exception {
        hello();
        JsonObject response = object(send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"session.resize\",\"params\":{\"id\":\"s1\",\"columns\":80.5,\"rows\":24}}", EnumSet.of(ControlCore.Scope.SESSIONS_WRITE)));
        assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(0, backend.calls);
    }
}

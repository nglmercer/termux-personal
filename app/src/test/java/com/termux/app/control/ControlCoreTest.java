package com.termux.app.control;

import junit.framework.TestCase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public class ControlCoreTest extends TestCase {
    private static final class FakeBackend implements ControlCore.Backend {
        int calls;
        boolean closeEnabled;
        String target;
        byte[] received;
        String name;
        int columns, rows;
        final List<ControlCore.SessionInfo> sessions = new ArrayList<>();
        @Override public boolean isCloseEnabled() { return closeEnabled; }
        @Override public List<ControlCore.SessionInfo> list() { calls++; return sessions; }
        @Override public ControlCore.SessionInfo create(String name, int columns, int rows, int cellWidth, int cellHeight) {
            calls++; this.name = name; this.columns = columns; this.rows = rows;
            return new ControlCore.SessionInfo("new-session", ControlCore.State.RUNNING, 42, name);
        }
        @Override public void write(String id, byte[] data) { calls++; target = id; received = data; }
        @Override public void close(String id) { calls++; target = id; }
        @Override public void rename(String id, String name) { calls++; target = id; this.name = name; }
        @Override public void focus(String id) { calls++; target = id; }
        @Override public String snapshot(String id) { calls++; target = id; return "screen"; }
        @Override public void resize(String id, int columns, int rows, int cellWidth, int cellHeight) {
            calls++; target = id; this.columns = columns; this.rows = rows;
        }
    }

    public void testFocusAndSnapshotRequireTheirOwnScopes() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        try { core.focusSession(EnumSet.of(ControlCore.Scope.SESSIONS_READ), "s"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.snapshotSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        assertEquals(0, backend.calls);
        core.focusSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s");
        assertEquals("s", backend.target);
        assertEquals("screen", core.snapshotSession(EnumSet.of(ControlCore.Scope.SESSIONS_OUTPUT), "s"));
    }

    public void testAuthorizationPrecedesBackendAccess() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        try { core.listSessions(Collections.emptySet()); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.writeSession(EnumSet.of(ControlCore.Scope.SESSIONS_READ), "id", new byte[]{1}); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        assertEquals(0, backend.calls);
    }

    public void testTargetedBinaryWriteIsCopiedAndBounded() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        byte[] data = {0, (byte) 255, 3};
        core.writeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "session-1", data);
        data[0] = 9;
        assertEquals("session-1", backend.target);
        assertTrue(Arrays.equals(new byte[]{0, (byte) 255, 3}, backend.received));
        try { core.writeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "session-1", new byte[ControlCore.MAX_WRITE_BYTES + 1]); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        assertEquals(1, backend.calls);
    }

    public void testRenameRequiresScopeAndValidLabel() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        try { core.renameSession(Collections.emptySet(), "s1", "Project"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.renameSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1", "bad\nname"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        assertEquals(0, backend.calls);
        core.renameSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1", "项目");
        assertEquals("s1", backend.target);
        assertEquals("项目", backend.name);
    }

    public void testCreateRequiresScopeAndValidOptions() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        try { core.createSession(Collections.emptySet(), "name", 80, 24, 8, 16); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.createSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "bad\nname", 80, 24, 8, 16); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        try { core.createSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), null, 80, 1, 8, 16); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        assertEquals(0, backend.calls);
        ControlCore.SessionInfo result = core.createSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "Work", 80, 24, 8, 16);
        assertEquals("new-session", result.id);
        assertEquals("Work", backend.name);
        assertEquals(1, backend.calls);
    }

    public void testCloseRequiresIndependentConsentAndEnabledBackend() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        EnumSet<ControlCore.Scope> both = EnumSet.of(ControlCore.Scope.SESSIONS_WRITE, ControlCore.Scope.SESSIONS_CLOSE);
        try { core.closeSession(Collections.emptySet(), "s1"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.closeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.closeSession(EnumSet.of(ControlCore.Scope.SESSIONS_CLOSE), "s1"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.closeSession(both, ""); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        try { core.closeSession(both, "s1"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
        assertEquals(0, backend.calls);
        backend.closeEnabled = true;
        core.closeSession(both, "s1");
        assertEquals("s1", backend.target); assertEquals(1, backend.calls);
        backend.closeEnabled = false;
        try { core.closeSession(both, "s1"); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.UNAVAILABLE, e.code); }
        assertEquals(1, backend.calls);
    }

    public void testResizeRequiresScopeAndBoundedGeometry() throws Exception {
        FakeBackend backend = new FakeBackend();
        ControlCore core = new ControlCore(backend);
        try { core.resizeSession(Collections.emptySet(), "s1", 80, 24, 0, 0); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.DENIED, e.code); }
        try { core.resizeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1", 1, 24, 0, 0); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        try { core.resizeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1", 501, 24, 0, 0); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        assertEquals(0, backend.calls);
        core.resizeSession(EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), "s1", 80, 24, 8, 16);
        assertEquals("s1", backend.target);
        assertEquals(80, backend.columns);
        assertEquals(24, backend.rows);
    }

    public void testSnapshotsAndVersionNegotiation() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.sessions.add(new ControlCore.SessionInfo("id", ControlCore.State.RUNNING, 123, "Work"));
        ControlCore core = new ControlCore(backend);
        List<ControlCore.SessionInfo> snapshot = core.listSessions(EnumSet.of(ControlCore.Scope.SESSIONS_READ));
        backend.sessions.clear();
        assertEquals(1, snapshot.size());
        assertEquals("Work", snapshot.get(0).name);
        try { snapshot.clear(); fail(); } catch (UnsupportedOperationException expected) { }
        assertEquals(1, core.hello(1));
        try { core.hello(2); fail(); }
        catch (ControlCore.ControlException e) { assertEquals(ControlCore.Error.INVALID_REQUEST, e.code); }
        assertFalse(core.capabilities().contains("command.execute"));
        assertFalse(core.capabilities().contains("session.close"));
    }
}

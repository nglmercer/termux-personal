package com.termux.app.control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** In-process control operations. This class does not expose a transport or grant permissions. */
public final class ControlCore {
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_WRITE_BYTES = 64 * 1024;

    public enum Scope {
        SESSIONS_READ, SESSIONS_WRITE, SESSIONS_OUTPUT, SESSIONS_CLOSE, SESSIONS_OUTPUT_RESUME,
        JOBS_READ, JOBS_OUTPUT, JOBS_EXECUTE, JOBS_CANCEL,
        FILES_READ, FILES_WRITE, PROCESSES_READ, PROCESSES_SIGNAL, WORKSPACES_READ, WORKSPACES_WRITE
    }
    public enum State { STARTING, RUNNING, EXITED }
    public enum Error { DENIED, INVALID_REQUEST, NOT_FOUND, NOT_RUNNING, BACKPRESSURE, CONFLICT, LIMIT_REACHED, UNAVAILABLE }

    public static final class ControlException extends Exception {
        public final Error code;
        public ControlException(Error code) { super(code.name()); this.code = code; }
    }

    public static final class SessionInfo {
        public final String id;
        public final State state;
        public final int pid;
        public final String name;
        public SessionInfo(String id, State state, int pid, String name) {
            this.id = id;
            this.state = state;
            this.pid = pid;
            this.name = name;
        }
    }

    /** The backend must execute on the existing service/emulator thread. */
    public interface Backend {
        boolean isCloseEnabled();
        default boolean isFocusAvailable() { return false; }
        default boolean isSnapshotAvailable() { return false; }
        List<SessionInfo> list() throws ControlException;
        SessionInfo create(String name, int columns, int rows, int cellWidth, int cellHeight) throws ControlException;
        void write(String id, byte[] data) throws ControlException;
        void rename(String id, String name) throws ControlException;
        void close(String id) throws ControlException;
        void resize(String id, int columns, int rows, int cellWidth, int cellHeight) throws ControlException;
        default void focus(String id) throws ControlException { throw new ControlException(Error.UNAVAILABLE); }
        default String snapshot(String id) throws ControlException { throw new ControlException(Error.UNAVAILABLE); }
    }

    private final Backend mBackend;

    public ControlCore(Backend backend) {
        if (backend == null) throw new IllegalArgumentException("backend required");
        mBackend = backend;
    }

    /** A single supported protocol version; transports must negotiate before dispatch. */
    public int hello(int requestedVersion) throws ControlException {
        if (requestedVersion != PROTOCOL_VERSION) throw new ControlException(Error.INVALID_REQUEST);
        return PROTOCOL_VERSION;
    }

    /** Only implemented capabilities are listed; this does not start a listener. */
    public List<String> capabilities() {
        List<String> methods = new ArrayList<>();
        methods.add("system.hello");
        methods.add("system.capabilities");
        methods.add("session.list");
        methods.add("session.create");
        methods.add("session.write");
        methods.add("session.rename");
        methods.add("session.resize");
        if (mBackend.isFocusAvailable()) methods.add("session.focus");
        if (mBackend.isSnapshotAvailable()) methods.add("session.snapshot");
        if (mBackend.isCloseEnabled()) methods.add("session.close");
        return Collections.unmodifiableList(methods);
    }

    public List<SessionInfo> listSessions(Set<Scope> scopes) throws ControlException {
        require(scopes, Scope.SESSIONS_READ);
        return Collections.unmodifiableList(new ArrayList<>(mBackend.list()));
    }

    /** Start a default shell in a new service-managed session. */
    public SessionInfo createSession(Set<Scope> scopes, String name, int columns, int rows,
                                     int cellWidth, int cellHeight) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        if ((name != null && !validName(name)) || !validGeometry(columns, rows, cellWidth, cellHeight))
            throw new ControlException(Error.INVALID_REQUEST);
        return mBackend.create(name, columns, rows, cellWidth, cellHeight);
    }

    /** Accepts one complete byte frame for the specified session, never the focused session. */
    public void writeSession(Set<Scope> scopes, String id, byte[] data) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        if (!validId(id) || data == null || data.length == 0 || data.length > MAX_WRITE_BYTES)
            throw new ControlException(Error.INVALID_REQUEST);
        mBackend.write(id, data.clone());
    }

    /** Request termination; process exit and session removal occur asynchronously. */
    public void closeSession(Set<Scope> scopes, String id) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        require(scopes, Scope.SESSIONS_CLOSE);
        if (!validId(id)) throw new ControlException(Error.INVALID_REQUEST);
        if (!mBackend.isCloseEnabled()) throw new ControlException(Error.UNAVAILABLE);
        mBackend.close(id);
    }

    /** Resize an existing live session. A visible local terminal takes precedence. */
    public void resizeSession(Set<Scope> scopes, String id, int columns, int rows,
                              int cellWidth, int cellHeight) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        if (!validId(id) || !validGeometry(columns, rows, cellWidth, cellHeight))
            throw new ControlException(Error.INVALID_REQUEST);
        mBackend.resize(id, columns, rows, cellWidth, cellHeight);
    }

    /** Rename a specific session; empty name clears its custom label. */
    public void renameSession(Set<Scope> scopes, String id, String name) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        if (!validId(id) || !validName(name)) throw new ControlException(Error.INVALID_REQUEST);
        mBackend.rename(id, name);
    }

    public void focusSession(Set<Scope> scopes, String id) throws ControlException {
        require(scopes, Scope.SESSIONS_WRITE);
        if (!validId(id)) throw new ControlException(Error.INVALID_REQUEST);
        mBackend.focus(id);
    }

    public String snapshotSession(Set<Scope> scopes, String id) throws ControlException {
        require(scopes, Scope.SESSIONS_OUTPUT);
        if (!validId(id)) throw new ControlException(Error.INVALID_REQUEST);
        return mBackend.snapshot(id);
    }

    private static boolean validGeometry(int columns, int rows, int cellWidth, int cellHeight) {
        return columns >= 2 && columns <= 500 && rows >= 2 && rows <= 250
            && cellWidth >= 0 && cellWidth <= 4096 && cellHeight >= 0 && cellHeight <= 4096;
    }

    private static boolean validName(String name) {
        if (name == null || name.length() > 80) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) return false;
        }
        return true;
    }

    private static boolean validId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 64;
    }

    private static void require(Set<Scope> scopes, Scope required) throws ControlException {
        if (scopes == null || !scopes.contains(required)) throw new ControlException(Error.DENIED);
    }
}

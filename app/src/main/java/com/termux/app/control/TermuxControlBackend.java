package com.termux.app.control;

import android.os.Looper;
import android.system.OsConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import java.util.ArrayList;
import java.util.List;

/** Main-thread adapter for the existing service; no session ownership is duplicated. */
public final class TermuxControlBackend implements ControlCore.Backend {
    private final TermuxService mService;
    public TermuxControlBackend(TermuxService service) {
        if (service == null) throw new IllegalArgumentException("service required");
        mService = service;
    }

    private void checkAvailable() throws ControlCore.ControlException {
        if (Looper.myLooper() != Looper.getMainLooper() || !mService.isControlAvailable())
            throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
    }

    @Override
    public boolean isCloseEnabled() {
        return Looper.myLooper() == Looper.getMainLooper() && mService.isControlAvailable()
            && mService.isSafeControlCloseEnabled();
    }

    @Override
    public List<ControlCore.SessionInfo> list() throws ControlCore.ControlException {
        checkAvailable();
        List<ControlCore.SessionInfo> result = new ArrayList<>();
        synchronized (mService) {
            for (TermuxSession item : mService.getTermuxSessions()) {
                TerminalSession session = item.getTerminalSession();
                int pid = session.getPid();
                ControlCore.State state = pid > 0 ? ControlCore.State.RUNNING :
                    (pid == 0 ? ControlCore.State.STARTING : ControlCore.State.EXITED);
                result.add(new ControlCore.SessionInfo(session.mHandle, state, pid > 0 ? pid : 0, session.mSessionName));
            }
        }
        return result;
    }

    @Override
    public ControlCore.SessionInfo create(String name, int columns, int rows, int cellWidth, int cellHeight)
        throws ControlCore.ControlException {
        checkAvailable();
        TermuxSession created;
        synchronized (mService) {
            if (mService.getTermuxSessionsSize() >= 8)
                throw new ControlCore.ControlException(ControlCore.Error.LIMIT_REACHED);
            created = mService.createTermuxSession(null, null, null,
                mService.getControlDefaultWorkingDirectory(), false, name);
        }
        if (created == null) throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        TerminalSession session = created.getTerminalSession();
        try {
            session.updateSize(columns, rows, cellWidth, cellHeight);
        } catch (RuntimeException | LinkageError e) {
            // If a child has already started, keep it registered for the existing
            // waiter and service lifecycle. Never silently discard a live session.
            if (session.getPid() == 0) mService.discardUnstartedControlSession(created);
            throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        }
        int pid = session.getPid();
        if (pid <= 0) throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        return new ControlCore.SessionInfo(session.mHandle, ControlCore.State.RUNNING, pid, session.mSessionName);
    }

    @Override
    public void focus(String id) throws ControlCore.ControlException {
        checkAvailable();
        mService.focusSessionForControl(findSession(id));
    }

    @Override
    public String snapshot(String id) throws ControlCore.ControlException {
        checkAvailable();
        TerminalSession session = findSession(id);
        if (session.getEmulator() == null) throw new ControlCore.ControlException(ControlCore.Error.NOT_RUNNING);
        String text = session.getEmulator().getScreen().getTranscriptText();
        int start = Math.max(0, text.length() - 8000);
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start++;
        return text.substring(start);
    }

    @Override
    public void write(String id, byte[] data) throws ControlCore.ControlException {
        checkAvailable();
        TerminalSession target = findSession(id);
        if (target.getPid() <= 0) throw new ControlCore.ControlException(ControlCore.Error.NOT_RUNNING);
        if (!target.tryWrite(data, 0, data.length))
            throw new ControlCore.ControlException(ControlCore.Error.BACKPRESSURE);
    }

    @Override
    public void rename(String id, String name) throws ControlCore.ControlException {
        checkAvailable();
        if (!mService.renameSessionForControl(id, name))
            throw new ControlCore.ControlException(ControlCore.Error.NOT_FOUND);
    }

    @Override
    public void close(String id) throws ControlCore.ControlException {
        checkAvailable();
        if (!mService.isSafeControlCloseEnabled())
            throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
        TerminalSession target = findSession(id);
        if (target.getPid() <= 0) throw new ControlCore.ControlException(ControlCore.Error.NOT_RUNNING);
        int result = target.killUsingProcessHandle();
        if (result == -OsConstants.ESRCH) throw new ControlCore.ControlException(ControlCore.Error.NOT_RUNNING);
        if (result != 0) throw new ControlCore.ControlException(ControlCore.Error.UNAVAILABLE);
    }

    @Override
    public void resize(String id, int columns, int rows, int cellWidth, int cellHeight)
        throws ControlCore.ControlException {
        checkAvailable();
        TerminalSession target = findSession(id);
        if (target.getPid() <= 0 || target.getEmulator() == null)
            throw new ControlCore.ControlException(ControlCore.Error.NOT_RUNNING);
        if (mService.isSessionVisibleForControl(target))
            throw new ControlCore.ControlException(ControlCore.Error.CONFLICT);
        target.updateSize(columns, rows, cellWidth, cellHeight);
    }

    private TerminalSession findSession(String id) throws ControlCore.ControlException {
        synchronized (mService) {
            for (TermuxSession item : mService.getTermuxSessions()) {
                TerminalSession session = item.getTerminalSession();
                if (id.equals(session.mHandle)) return session;
            }
        }
        throw new ControlCore.ControlException(ControlCore.Error.NOT_FOUND);
    }

}

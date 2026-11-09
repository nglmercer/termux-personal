package com.termux.app.control.workspace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Runs backup exports and restores in the background so a client can watch progress and cancel. At most one operation runs at a
 * time (a second request is refused as busy, never queued without bound), and only the last {@link #MAX_RETAINED} are remembered.
 * Cancellation is cooperative: the running operation checks it between chunks, removes its temporary files and ends
 * {@code cancelled}. State is in memory; after a restart no operation is remembered and nothing resumes.
 */
public final class BackupOperations implements AutoCloseable {
    public enum State { RUNNING, COMPLETED, FAILED, CANCELLED }
    public static final int MAX_RETAINED = 8;

    /** The work itself; returns the result to report on success. */
    public interface Task { Map<String, Object> run(WorkspaceBackupService.Control control) throws IOException; }

    public static final class BusyException extends Exception { BusyException() { super("A backup operation is already running"); } }

    /** A consistent view of one operation. Result and error are set only in a terminal state. */
    public static final class View {
        public final String id, kind, workspaceId, error;
        public final State state;
        public final long files, bytes, startedAt;
        public final Long endedAt;
        public final Map<String, Object> result;
        View(String id, String kind, String workspaceId, State state, long files, long bytes, long startedAt, Long endedAt, Map<String, Object> result, String error) {
            this.id = id; this.kind = kind; this.workspaceId = workspaceId; this.state = state; this.files = files; this.bytes = bytes;
            this.startedAt = startedAt; this.endedAt = endedAt; this.result = result; this.error = error;
        }
    }

    private static final class Operation {
        final String id, kind, workspaceId; final long startedAt;
        final WorkspaceBackupService.Control control = new WorkspaceBackupService.Control();
        State state = State.RUNNING; Long endedAt; Map<String, Object> result; String error;
        Operation(String id, String kind, String workspaceId, long startedAt) { this.id = id; this.kind = kind; this.workspaceId = workspaceId; this.startedAt = startedAt; }
    }

    private final ThreadPoolExecutor mWorker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1), r -> {
        Thread t = new Thread(r, "TermuxBackup"); t.setDaemon(true); return t;
    });
    private final Map<String, Operation> mOperations = new LinkedHashMap<>();
    private int mCounter;
    private boolean mClosed;

    public BackupOperations() { mWorker.allowCoreThreadTimeOut(true); }

    /** Starts {@code task} in the background. Throws {@link BusyException} while another operation is running. */
    public synchronized View submit(String kind, String workspaceId, Task task) throws BusyException {
        if (mClosed) throw new BusyException();
        for (Operation op : mOperations.values()) if (op.state == State.RUNNING) throw new BusyException();
        final Operation op = new Operation("bk_" + (++mCounter), kind, workspaceId, System.currentTimeMillis());
        mOperations.put(op.id, op);
        trim();
        try {
            mWorker.execute(() -> run(op, task));
        } catch (RejectedExecutionException e) {
            mOperations.remove(op.id);
            throw new BusyException();
        }
        return view(op);
    }

    private void run(Operation op, Task task) {
        Map<String, Object> result = null; String error = null; State state;
        try {
            result = task.run(op.control);
            state = op.control.cancelled() && result == null ? State.CANCELLED : State.COMPLETED;
        } catch (WorkspaceBackupService.Cancelled e) { state = State.CANCELLED;
        } catch (IOException e) { state = op.control.cancelled() ? State.CANCELLED : State.FAILED; error = state == State.FAILED ? "INVALID_REQUEST" : null;
        } catch (RuntimeException e) { state = State.FAILED; error = "UNAVAILABLE"; }
        synchronized (this) { op.state = state; op.endedAt = System.currentTimeMillis(); op.result = state == State.COMPLETED ? result : null; op.error = error; }
    }

    public synchronized View get(String id) {
        Operation op = id == null ? null : mOperations.get(id);
        return op == null ? null : view(op);
    }

    /** Asks a running operation to stop; false when it is unknown or already finished. The caller polls {@link #get} for the outcome. */
    public synchronized boolean cancel(String id) {
        Operation op = id == null ? null : mOperations.get(id);
        if (op == null || op.state != State.RUNNING) return false;
        op.control.cancel();
        return true;
    }

    public synchronized List<View> list() {
        List<View> views = new ArrayList<>();
        for (Operation op : mOperations.values()) views.add(view(op));
        return Collections.unmodifiableList(views);
    }

    @Override public synchronized void close() {
        mClosed = true;
        for (Operation op : mOperations.values()) if (op.state == State.RUNNING) op.control.cancel();
        mWorker.shutdown();
    }

    private View view(Operation op) {
        return new View(op.id, op.kind, op.workspaceId, op.state, op.control.files(), op.control.bytes(), op.startedAt, op.endedAt, op.result, op.error);
    }

    /** Monitor held. Forgets the oldest finished operations beyond the bound; a running one is never forgotten. */
    private void trim() {
        for (java.util.Iterator<Operation> it = mOperations.values().iterator(); mOperations.size() > MAX_RETAINED && it.hasNext();) {
            if (it.next().state != State.RUNNING) it.remove();
        }
    }
}

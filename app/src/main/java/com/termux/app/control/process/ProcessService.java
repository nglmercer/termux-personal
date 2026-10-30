package com.termux.app.control.process;

import com.termux.app.monitor.ProcessSnapshot;
import com.termux.app.monitor.ProcessTable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, read-only process listing for same-UID processes that Android exposes. Each call takes a
 * fresh /proc sample, so a cursor indexes that sample and pages are not a stable snapshot. This class
 * performs file I/O and must run on a worker thread, never the service thread. It never signals.
 */
public final class ProcessService {
    public static final int MAX_PAGE = 100;

    /** Root PIDs come from trusted service state. Clients cannot supply them. */
    public interface Roots {
        Set<Integer> sessionRoots();
        Set<Integer> taskRoots();
    }

    public static final class Page {
        public final List<ProcessTable.Row> rows;
        public final boolean incomplete;
        public final int missingRoots;
        public final Integer nextCursor;

        Page(List<ProcessTable.Row> rows, boolean incomplete, int missingRoots, Integer nextCursor) {
            this.rows = rows; this.incomplete = incomplete; this.missingRoots = missingRoots; this.nextCursor = nextCursor;
        }
    }

    private final File mProc;
    private final int mUid;
    private final long mTicksPerSecond, mPageSize;
    private final Roots mRoots;

    public ProcessService(File proc, int uid, long ticksPerSecond, long pageSize, Roots roots) {
        if (proc == null || roots == null || ticksPerSecond <= 0 || pageSize <= 0) throw new IllegalArgumentException("Invalid process service");
        mProc = proc; mUid = uid; mTicksPerSecond = ticksPerSecond; mPageSize = pageSize; mRoots = roots;
    }

    /** Maps the wire scope names to the table's selections. */
    public static ProcessTable.Scope scope(String value) {
        if ("owned".equals(value)) return ProcessTable.Scope.OWNED;
        if ("session".equals(value)) return ProcessTable.Scope.SESSION;
        if ("task".equals(value)) return ProcessTable.Scope.TASK;
        if ("all-visible".equals(value)) return ProcessTable.Scope.ALL_VISIBLE;
        throw new IllegalArgumentException("Unknown process scope");
    }

    /** Sorted by PID so that consecutive pages of one sample never repeat or skip a row. */
    public Page list(String scope, int cursor, int limit) {
        ProcessTable.Scope selection = scope(scope);
        if (cursor < 0 || limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("Invalid page");
        Set<Integer> sessions = new HashSet<>(mRoots.sessionRoots());
        Set<Integer> tasks = new HashSet<>(mRoots.taskRoots());
        Set<Integer> roots = new HashSet<>(sessions);
        roots.addAll(tasks);
        ProcessSnapshot snapshot = ProcessSnapshot.collect(mProc, mUid, roots);
        ProcessTable table = new ProcessTable(snapshot, null, 0, mTicksPerSecond, mPageSize, sessions, tasks);
        List<ProcessTable.Row> all = table.select(null, ProcessTable.Sort.PID, selection);
        if (cursor > all.size()) throw new IllegalArgumentException("Cursor past end");
        int end = Math.min(all.size(), cursor + limit);
        Integer next = end < all.size() ? end : null;
        return new Page(Collections.unmodifiableList(new ArrayList<>(all.subList(cursor, end))),
            snapshot.incomplete, snapshot.missingRoots, next);
    }
}

package com.termux.app.terminal;

import androidx.annotation.NonNull;

import com.termux.terminal.SessionExitStatus;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Opt-in, in-memory record of how the root process of recent terminal sessions ended, owned by the service so that both
 * session clients (activity bound or not) feed the same list. It keeps at most {@link #CAPACITY} results and nothing
 * else: no session title or name, command, arguments, environment, directory, input or transcript, and no reference to
 * a session or an Activity. Nothing is persisted, so results are lost when the service dies.
 */
public final class SessionExitRegistry {
    public static final int CAPACITY = 32;

    public static final class Entry {
        /** Increasing number within this service lifetime. */
        public final long sequence;
        public final long wallMillis;
        @NonNull public final SessionExitStatus status;
        /** The session's random handle, used only to ignore a second report of the same session; never shown. */
        private final String mKey;
        private Entry(long sequence, long wallMillis, @NonNull SessionExitStatus status, String key) {
            this.sequence = sequence; this.wallMillis = wallMillis; this.status = status; mKey = key;
        }
    }

    private final ArrayDeque<Entry> mEntries = new ArrayDeque<>();
    private long mNextSequence = 1;

    /**
     * Records one finished session. {@code key} identifies the session (its random handle) for deduplication only.
     * Returns false for a duplicate.
     */
    public synchronized boolean record(@NonNull String key, int rawExitStatus, long wallMillis) {
        for (Entry entry : mEntries) if (entry.mKey.equals(key)) return false;
        mEntries.addLast(new Entry(mNextSequence++, wallMillis, SessionExitStatus.decode(rawExitStatus), key));
        while (mEntries.size() > CAPACITY) mEntries.removeFirst();
        return true;
    }

    /** Newest first. */
    @NonNull public synchronized List<Entry> recent() {
        List<Entry> result = new ArrayList<>(mEntries);
        Collections.reverse(result);
        return result;
    }

    public synchronized void clear() { mEntries.clear(); }

    /** Single-line summary for a list: local time and what was observed. */
    @NonNull public static String summary(@NonNull Entry entry) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        return format.format(new Date(entry.wallMillis)) + "  " + entry.status.describe();
    }

    /**
     * The minimal text a user may copy: when (UTC), what the root process reported and the Android API level. It
     * deliberately carries no session or command information, and states that the cause is unknown.
     */
    @NonNull public static String report(@NonNull List<Entry> entries, int sdkInt) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder text = new StringBuilder();
        text.append("Termux session exit results\n");
        text.append("source: root process of each terminal session (normally the shell), via waitpid\n");
        text.append("not observed: commands that ended while their shell kept running\n");
        text.append("cause: unknown; an exit code is not a signal and a signal does not say who sent it\n");
        text.append("android api: ").append(sdkInt).append('\n');
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            text.append(format.format(new Date(entry.wallMillis))).append("  ").append(entry.status.describe()).append('\n');
        }
        return text.toString();
    }
}

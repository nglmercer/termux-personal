package com.termux.app.control.jobs;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Admission and replay table. Call admission from a worker, not the service thread.
 *
 * <p>Capacity is split so that finished work cannot exhaust it:
 * <ul>
 * <li><b>Active and recent records</b> ({@code maxRecords}): when full, the oldest <em>finished</em> record whose pipe
 * workers have stopped is evicted together with its retained output. A running or queued job is never evicted; if
 * nothing finished can be evicted the registry still answers LIMIT_REACHED.</li>
 * <li><b>Replay keys</b>: a client request ID keeps its idempotency guarantee after its record is evicted. A
 * tombstone holds the key, request fingerprint, epoch and final metadata (never output) for
 * {@link #REPLAY_RETENTION_MILLIS}, at most {@link #MAX_TOMBSTONES} of them. Within that window a retry returns the
 * original job's final state instead of running the command again, and a different request under the same key is
 * still a conflict. After the window (or beyond the cap, oldest first) the key is forgotten and a retry runs as a new
 * request: this is the documented, finite replay retention policy.</li>
 * </ul>
 */
public final class JobRegistry implements AutoCloseable {
    public enum Status { ACCEPTED, REPLAY, CONFLICT, LIMIT_REACHED }

    private final JobIoPool mIoPool;
    private final JobOutputStore mOutputStore;
    private final SecureRandom mRandom = new SecureRandom();
    private final Map<String, Entry> mEntries = new LinkedHashMap<>();
    private final Map<String, Replay> mRequests = new LinkedHashMap<>();
    private final int mMaxRecords;
    private final LongSupplier mClockMillis;
    private boolean mClosed;

    /** How long a request ID keeps its idempotency guarantee after its record was evicted. */
    public static final long REPLAY_RETENTION_MILLIS = 10L * 60 * 1000;
    public static final int MAX_TOMBSTONES = 256;

    public JobRegistry(JobIoPool ioPool, JobOutputStore outputStore, int maxRecords) {
        this(ioPool, outputStore, maxRecords, () -> System.nanoTime() / 1_000_000L);
    }

    JobRegistry(JobIoPool ioPool, JobOutputStore outputStore, int maxRecords, LongSupplier monotonicMillis) {
        if (monotonicMillis == null) throw new IllegalArgumentException("Missing clock");
        mClockMillis = monotonicMillis;
        if (ioPool == null || outputStore == null || maxRecords < 1 || maxRecords > JobOutputStore.DEFAULT_RECORD_LIMIT)
            throw new IllegalArgumentException("Invalid job registry configuration");
        mIoPool = ioPool; mOutputStore = outputStore; mMaxRecords = maxRecords;
    }

    /**
     * Reserves a record, output rings and all three pipe workers before publication. A request key keeps its
     * replay guarantee for the documented retention window even after the record has been evicted.
     */
    public synchronized Admission admit(JobSpec spec, long authorizationEpoch, long drainTimeoutMillis) {
        if (mClosed) throw new IllegalStateException("Job registry closed");
        if (spec == null || authorizationEpoch < 0 || drainTimeoutMillis < 1 || drainTimeoutMillis > 30000)
            throw new IllegalArgumentException("Invalid admission request");
        purgeExpiredReplays();
        byte[] fingerprint = spec.fingerprintCopy();
        String key = spec.clientRequestId();
        try {
            Replay old = key == null ? null : mRequests.get(key);
            if (old != null) {
                boolean same = old.epoch == authorizationEpoch && MessageDigest.isEqual(old.fingerprint, fingerprint);
                return same ? Admission.replay(old) : new Admission(Status.CONFLICT, null, null, null);
            }
            if (mEntries.size() >= mMaxRecords && !evictOldestFinished()) return new Admission(Status.LIMIT_REACHED, null, null, null);
            JobIoPool.Lease lease = mIoPool.tryReserve();
            if (lease == null) return new Admission(Status.LIMIT_REACHED, null, null, null);
            JobOutputStore.Output output = null;
            AppShellJobCapture capture = null;
            String id = null;
            try {
                do { id = newId(); } while (mEntries.containsKey(id));
                // A job asks for less retention than the store default, never more; no request means the store default.
                JobResourcePolicy resources = spec.resources();
                int streamBytes = resources == null || resources.maxOutputBytes() == 0 ? 0 : resources.maxOutputBytes();
                output = streamBytes == 0 ? mOutputStore.reserve(id) : mOutputStore.reserve(id, streamBytes);
                if (output == null && evictOldestFinished()) // quota, not record count, was the limit
                    output = streamBytes == 0 ? mOutputStore.reserve(id) : mOutputStore.reserve(id, streamBytes);
                if (output == null) { lease.close(); return new Admission(Status.LIMIT_REACHED, null, null, null); }
                byte[] stdin = spec.stdin();
                try { capture = new AppShellJobCapture(lease, output, stdin, drainTimeoutMillis); }
                finally { Arrays.fill(stdin, (byte) 0); }
                Entry entry = new Entry(new JobRecord(id, spec.workspaceId(), authorizationEpoch), output, capture, fingerprint.clone(), key);
                mEntries.put(id, entry);
                if (key != null) mRequests.put(key, new Replay(key, fingerprint.clone(), authorizationEpoch, entry));
                return new Admission(Status.ACCEPTED, entry, null, null);
            } catch (RuntimeException | Error e) {
                if (id != null) mEntries.remove(id);
                if (key != null) mRequests.remove(key);
                if (capture != null) capture.discardBeforeLaunch();
                if (output != null) mOutputStore.remove(id);
                lease.close();
                throw e;
            }
        } finally { Arrays.fill(fingerprint, (byte) 0); }
    }

    /**
     * Evicts the oldest record that is finished and whose pipe workers have stopped, together with its output.
     * Its replay key, if any, becomes a tombstone. Running or queued records are never evicted.
     */
    private boolean evictOldestFinished() {
        for (Iterator<Entry> it = mEntries.values().iterator(); it.hasNext();) {
            Entry entry = it.next();
            JobRecord.Snapshot snapshot = entry.mRecord.snapshot();
            if (!JobRecord.isTerminal(snapshot.state) || !entry.mCapture.isReleased()) continue;
            JobOutputStore.Offsets offsets = entry.mOutput.offsets();
            it.remove();
            mOutputStore.remove(snapshot.id);
            Arrays.fill(entry.mFingerprint, (byte) 0);
            Replay replay = entry.mKey == null ? null : mRequests.get(entry.mKey);
            if (replay != null) { replay.entry = null; replay.finalSnapshot = snapshot; replay.finalOffsets = offsets; replay.evictedAt = mClockMillis.getAsLong(); }
            trimTombstones();
            return true;
        }
        return false;
    }

    private void purgeExpiredReplays() {
        long now = mClockMillis.getAsLong();
        for (Iterator<Replay> it = mRequests.values().iterator(); it.hasNext();) {
            Replay replay = it.next();
            if (replay.entry == null && now - replay.evictedAt >= REPLAY_RETENTION_MILLIS) { Arrays.fill(replay.fingerprint, (byte) 0); it.remove(); }
        }
    }

    /** Keeps the tombstone count finite, dropping the oldest eviction first. */
    private void trimTombstones() {
        int tombstones = 0;
        for (Replay replay : mRequests.values()) if (replay.entry == null) tombstones++;
        for (Iterator<Replay> it = mRequests.values().iterator(); tombstones > MAX_TOMBSTONES && it.hasNext();) {
            Replay replay = it.next();
            if (replay.entry != null) continue;
            Arrays.fill(replay.fingerprint, (byte) 0); it.remove(); tombstones--;
        }
    }

    public synchronized Entry get(String id, long authorizationEpoch) {
        Entry entry = mEntries.get(id);
        return entry != null && entry.mRecord.snapshot().authorizationEpoch == authorizationEpoch ? entry : null;
    }

    /** Lookup without reserving resources or repeating a mutation. */
    synchronized Admission replay(JobSpec spec, long epoch) {
        if (mClosed) throw new IllegalStateException("Job registry closed");
        purgeExpiredReplays();
        Replay old = spec.clientRequestId() == null ? null : mRequests.get(spec.clientRequestId());
        if (old == null) return null;
        byte[] fingerprint = spec.fingerprintCopy();
        try {
            boolean same = old.epoch == epoch && MessageDigest.isEqual(old.fingerprint, fingerprint);
            return same ? Admission.replay(old) : new Admission(Status.CONFLICT, null, null, null);
        } finally { Arrays.fill(fingerprint, (byte) 0); }
    }

    /** Internal views still require the current coordinator's permission check. */
    synchronized Entry getRetained(String id) { return mEntries.get(id); }

    synchronized List<Entry> retainedEntries() { return new ArrayList<>(mEntries.values()); }

    public synchronized List<JobRecord.Snapshot> list(long authorizationEpoch) {
        List<JobRecord.Snapshot> records = new ArrayList<>();
        for (Entry entry : mEntries.values()) {
            JobRecord.Snapshot snapshot = entry.mRecord.snapshot();
            if (snapshot.authorizationEpoch == authorizationEpoch) records.add(snapshot);
        }
        return Collections.unmodifiableList(records);
    }

    /** The owner must first stop owned live processes; this closes storage and worker reservations. */
    @Override public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        for (Entry entry : mEntries.values()) {
            Arrays.fill(entry.mFingerprint, (byte) 0);
            entry.mCapture.discardIfUnstarted();
        }
        for (Replay replay : mRequests.values()) Arrays.fill(replay.fingerprint, (byte) 0);
        mRequests.clear(); mEntries.clear();
        mIoPool.close(); mOutputStore.close();
    }

    private String newId() {
        byte[] random = new byte[16]; mRandom.nextBytes(random);
        StringBuilder id = new StringBuilder("job_");
        for (byte b : random) id.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        return id.toString();
    }

    /** Idempotency record: the entry while it is retained, then its final metadata alone. */
    private static final class Replay {
        final String key; final byte[] fingerprint; final long epoch;
        Entry entry; JobRecord.Snapshot finalSnapshot; JobOutputStore.Offsets finalOffsets; long evictedAt;
        Replay(String key, byte[] fingerprint, long epoch, Entry entry) { this.key = key; this.fingerprint = fingerprint; this.epoch = epoch; this.entry = entry; }
    }

    public static final class Admission {
        public final Status status;
        /** Null for a replay whose record was evicted; use {@link #snapshot()} and {@link #offsets()} for both cases. */
        public final Entry entry;
        private final JobRecord.Snapshot mSnapshot; private final JobOutputStore.Offsets mOffsets;
        private Admission(Status status, Entry entry, JobRecord.Snapshot snapshot, JobOutputStore.Offsets offsets) {
            this.status = status; this.entry = entry; mSnapshot = snapshot; mOffsets = offsets;
        }
        static Admission replay(Replay replay) {
            return replay.entry != null ? new Admission(Status.REPLAY, replay.entry, null, null) :
                new Admission(Status.REPLAY, null, replay.finalSnapshot, replay.finalOffsets);
        }
        /** Metadata of the admitted or replayed job; null when no job is associated. */
        public JobRecord.Snapshot snapshot() { return entry != null ? entry.record().snapshot() : mSnapshot; }
        public JobOutputStore.Offsets offsets() { return entry != null ? entry.output().offsets() : mOffsets; }
    }

    /** The capture is consumed exactly once by the launcher or discarded on failed startup. */
    public static final class Entry {
        private final JobRecord mRecord;
        private final JobOutputStore.Output mOutput;
        private final AppShellJobCapture mCapture;
        private final byte[] mFingerprint;
        private final String mKey;
        private Entry(JobRecord record, JobOutputStore.Output output, AppShellJobCapture capture, byte[] fingerprint, String key) {
            mRecord = record; mOutput = output; mCapture = capture; mFingerprint = fingerprint; mKey = key;
        }
        public JobRecord record() { return mRecord; }
        public JobOutputStore.Output output() { return mOutput; }
        public AppShellJobCapture capture() { return mCapture; }
        boolean matches(JobSpec spec) {
            if (spec == null) return false;
            byte[] fingerprint = spec.fingerprintCopy();
            try { return MessageDigest.isEqual(mFingerprint, fingerprint); }
            finally { Arrays.fill(fingerprint, (byte) 0); }
        }
    }
}

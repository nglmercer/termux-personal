package com.termux.app.control.jobs;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Service-lifetime admission and replay table. Call admission from a worker, not the service thread. */
public final class JobRegistry implements AutoCloseable {
    public enum Status { ACCEPTED, REPLAY, CONFLICT, LIMIT_REACHED }

    private final JobIoPool mIoPool;
    private final JobOutputStore mOutputStore;
    private final SecureRandom mRandom = new SecureRandom();
    private final Map<String, Entry> mEntries = new LinkedHashMap<>();
    private final Map<String, Entry> mRequests = new HashMap<>();
    private final int mMaxRecords;
    private boolean mClosed;

    public JobRegistry(JobIoPool ioPool, JobOutputStore outputStore, int maxRecords) {
        if (ioPool == null || outputStore == null || maxRecords < 1 || maxRecords > JobOutputStore.DEFAULT_RECORD_LIMIT)
            throw new IllegalArgumentException("Invalid job registry configuration");
        mIoPool = ioPool; mOutputStore = outputStore; mMaxRecords = maxRecords;
    }

    /**
     * Reserves a record, output rings and all three pipe workers before publication. A key is
     * retained for the service lifetime: a full registry refuses new work instead of silently
     * forgetting an idempotency key and possibly executing its mutation twice.
     */
    public synchronized Admission admit(JobSpec spec, long authorizationEpoch, long drainTimeoutMillis) {
        if (mClosed) throw new IllegalStateException("Job registry closed");
        if (spec == null || authorizationEpoch < 0 || drainTimeoutMillis < 1 || drainTimeoutMillis > 30000)
            throw new IllegalArgumentException("Invalid admission request");
        byte[] fingerprint = spec.fingerprintCopy();
        String key = spec.clientRequestId();
        try {
            Entry old = key == null ? null : mRequests.get(key);
            if (old != null) {
                boolean same = old.mRecord.snapshot().authorizationEpoch == authorizationEpoch &&
                    MessageDigest.isEqual(old.mFingerprint, fingerprint);
                return new Admission(same ? Status.REPLAY : Status.CONFLICT, same ? old : null);
            }
            if (mEntries.size() >= mMaxRecords) return new Admission(Status.LIMIT_REACHED, null);
            JobIoPool.Lease lease = mIoPool.tryReserve();
            if (lease == null) return new Admission(Status.LIMIT_REACHED, null);
            JobOutputStore.Output output = null;
            AppShellJobCapture capture = null;
            String id = null;
            try {
                do { id = newId(); } while (mEntries.containsKey(id));
                output = mOutputStore.reserve(id);
                if (output == null) { lease.close(); return new Admission(Status.LIMIT_REACHED, null); }
                byte[] stdin = spec.stdin();
                try { capture = new AppShellJobCapture(lease, output, stdin, drainTimeoutMillis); }
                finally { Arrays.fill(stdin, (byte) 0); }
                Entry entry = new Entry(new JobRecord(id, spec.workspaceId(), authorizationEpoch), output, capture, fingerprint.clone());
                mEntries.put(id, entry);
                if (key != null) mRequests.put(key, entry);
                return new Admission(Status.ACCEPTED, entry);
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

    public synchronized Entry get(String id, long authorizationEpoch) {
        Entry entry = mEntries.get(id);
        return entry != null && entry.mRecord.snapshot().authorizationEpoch == authorizationEpoch ? entry : null;
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
        mRequests.clear(); mEntries.clear();
        mIoPool.close(); mOutputStore.close();
    }

    private String newId() {
        byte[] random = new byte[16]; mRandom.nextBytes(random);
        StringBuilder id = new StringBuilder("job_");
        for (byte b : random) id.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        return id.toString();
    }

    public static final class Admission {
        public final Status status; public final Entry entry;
        private Admission(Status status, Entry entry) { this.status = status; this.entry = entry; }
    }

    /** The capture is consumed exactly once by the launcher or discarded on failed startup. */
    public static final class Entry {
        private final JobRecord mRecord;
        private final JobOutputStore.Output mOutput;
        private final AppShellJobCapture mCapture;
        private final byte[] mFingerprint;
        private Entry(JobRecord record, JobOutputStore.Output output, AppShellJobCapture capture, byte[] fingerprint) {
            mRecord = record; mOutput = output; mCapture = capture; mFingerprint = fingerprint;
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

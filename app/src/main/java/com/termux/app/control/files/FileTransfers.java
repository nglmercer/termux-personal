package com.termux.app.control.files;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Chunked uploads into a writable root. Bytes go to an owner-only temporary file beside the destination,
 * written strictly in order; commit checks the length and optional SHA-256, syncs, and then renames. Without
 * overwrite the rename never replaces an existing entry; with overwrite the destination must still have the
 * revision given at begin. An interrupted transfer never leaves a partial destination file, only a temporary
 * file that abort, expiry or {@link #abortAll()} removes. Must run on a worker thread.
 */
public final class FileTransfers {
    public static final int CHUNK_BYTES = 64 * 1024;
    public static final int MAX_OPEN = 4;
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long IDLE_EXPIRY_MILLIS = 15 * 60 * 1000;
    static final String TEMP_PREFIX = ".termux-upload-";

    public static final class Begin {
        public final String transferId;
        public final long offset;
        Begin(String transferId, long offset) { this.transferId = transferId; this.offset = offset; }
    }

    public static final class Commit {
        public final FileService.Entry entry;
        public final String sha256;
        Commit(FileService.Entry entry, String sha256) { this.entry = entry; this.sha256 = sha256; }
    }

    interface Clock { long millis(); }

    private static final class Transfer {
        final String rootId, path, tempPath, expectedSha256, ifRevision;
        final String[] tempParts;
        final long total;
        final boolean overwrite;
        final FileService.Writable file;
        final MessageDigest digest;
        long next, touched;
        Transfer(String rootId, String path, String tempPath, String[] tempParts, long total, String sha256, String ifRevision,
                 boolean overwrite, FileService.Writable file, MessageDigest digest, long now) {
            this.rootId = rootId; this.path = path; this.tempPath = tempPath; this.tempParts = tempParts; this.total = total;
            this.expectedSha256 = sha256; this.ifRevision = ifRevision; this.overwrite = overwrite; this.file = file;
            this.digest = digest; this.touched = now;
        }
    }

    private final FileService mService;
    private final Map<String, Transfer> mTransfers = new HashMap<>();
    private final SecureRandom mRandom = new SecureRandom();
    private Clock mClock = System::currentTimeMillis;

    FileTransfers(FileService service) { mService = service; }

    void setClock(Clock clock) { mClock = clock; }

    /**
     * Reserves the destination name for this transfer. The parent must exist. Without overwrite an existing
     * destination is CONFLICT; with overwrite, ifRevision is required and checked again at commit.
     */
    public synchronized Begin begin(String rootId, String path, long totalBytes, String sha256, String ifRevision,
                                    boolean overwrite) throws FileService.FileException {
        expire();
        String[] parts = FileService.components(path);
        if (parts.length == 0 || totalBytes < 0 || totalBytes > MAX_BYTES || (sha256 != null && !sha256.matches("[0-9a-f]{64}")) ||
            (overwrite && ifRevision == null) || (!overwrite && ifRevision != null))
            throw new FileService.FileException("INVALID_REQUEST");
        File root = mService.writableRoot(rootId);
        if (mTransfers.size() >= MAX_OPEN) throw new FileService.FileException("LIMIT_REACHED");
        boolean exists = exists(rootId, path);
        if (!overwrite && exists) throw new FileService.FileException("CONFLICT");
        if (overwrite) {
            if (!exists) throw new FileService.FileException("NOT_FOUND");
            mService.requireRevision(rootId, path, ifRevision);
        }
        String id = newId();
        String[] tempParts = parts.clone();
        tempParts[tempParts.length - 1] = TEMP_PREFIX + id;
        StringBuilder tempPath = new StringBuilder();
        for (String part : tempParts) tempPath.append(tempPath.length() == 0 ? "" : "/").append(part);
        FileService.Writable file = mService.access().createNew(root, tempParts);
        try {
            mTransfers.put(id, new Transfer(rootId, path, tempPath.toString(), tempParts, totalBytes, sha256, ifRevision, overwrite, file,
                MessageDigest.getInstance("SHA-256"), mClock.millis()));
        } catch (NoSuchAlgorithmException e) {
            discard(root, tempParts, file);
            throw new FileService.FileException("UNAVAILABLE");
        }
        return new Begin(id, 0);
    }

    /**
     * Appends the chunk at the expected offset and returns the next expected offset. A chunk that exactly
     * repeats bytes already written (a retry after a lost reply) is accepted without writing again.
     */
    public synchronized long write(String transferId, long offset, byte[] data) throws FileService.FileException {
        Transfer transfer = live(transferId);
        if (data == null || data.length == 0 || data.length > CHUNK_BYTES || offset < 0) throw new FileService.FileException("INVALID_REQUEST");
        transfer.touched = mClock.millis();
        if (offset < transfer.next) {
            if (offset + data.length > transfer.next) throw new FileService.FileException("CONFLICT");
            byte[] existing = new byte[data.length];
            int read = 0;
            while (read < existing.length) {
                int n = transfer.file.read(offset + read, existing, read, existing.length - read);
                if (n <= 0) break;
                read += n;
            }
            if (read != data.length || !Arrays.equals(existing, data)) throw new FileService.FileException("CONFLICT");
            return transfer.next;
        }
        if (offset != transfer.next) throw new FileService.FileException("CONFLICT");
        if (transfer.next + data.length > transfer.total) throw new FileService.FileException("INVALID_REQUEST");
        transfer.file.write(offset, data, 0, data.length);
        transfer.digest.update(data);
        transfer.next += data.length;
        return transfer.next;
    }

    /** Verifies and publishes the file. On a failed check the transfer is aborted and nothing is published. */
    public synchronized Commit commit(String transferId) throws FileService.FileException {
        Transfer transfer = live(transferId);
        File root = mService.root(transfer.rootId);
        if (transfer.next != transfer.total) throw new FileService.FileException("CONFLICT");
        String sha256 = hex(transfer.digest.digest());
        try {
            if (transfer.expectedSha256 != null && !transfer.expectedSha256.equals(sha256)) throw new FileService.FileException("CONFLICT");
            if (transfer.overwrite) mService.requireRevision(transfer.rootId, transfer.path, transfer.ifRevision);
            transfer.file.sync();
            closeQuietly(transfer.file);
            mService.access().rename(root, transfer.tempParts, FileService.components(transfer.path), transfer.overwrite);
        } catch (FileService.FileException e) {
            mTransfers.remove(transferId);
            discard(root, transfer.tempParts, transfer.file);
            throw e;
        }
        mTransfers.remove(transferId);
        return new Commit(mService.stat(transfer.rootId, transfer.path), sha256);
    }

    public synchronized void abort(String transferId) throws FileService.FileException {
        Transfer transfer = mTransfers.remove(transferId);
        if (transfer == null) throw new FileService.FileException("NOT_FOUND");
        discard(mService.root(transfer.rootId), transfer.tempParts, transfer.file);
    }

    /** Removes every open transfer's temporary file; used when the service stops. */
    public synchronized void abortAll() {
        for (Transfer transfer : mTransfers.values()) {
            try { discard(mService.root(transfer.rootId), transfer.tempParts, transfer.file); }
            catch (FileService.FileException ignored) { }
        }
        mTransfers.clear();
    }

    public synchronized int openCount() { expire(); return mTransfers.size(); }

    private Transfer live(String transferId) throws FileService.FileException {
        expire();
        Transfer transfer = transferId == null ? null : mTransfers.get(transferId);
        if (transfer == null) throw new FileService.FileException("NOT_FOUND");
        return transfer;
    }

    private void expire() {
        long now = mClock.millis();
        for (Iterator<Map.Entry<String, Transfer>> it = mTransfers.entrySet().iterator(); it.hasNext(); ) {
            Transfer transfer = it.next().getValue();
            if (now - transfer.touched < IDLE_EXPIRY_MILLIS) continue;
            it.remove();
            try { discard(mService.root(transfer.rootId), transfer.tempParts, transfer.file); }
            catch (FileService.FileException ignored) { }
        }
    }

    private boolean exists(String rootId, String path) throws FileService.FileException {
        try { mService.stat(rootId, path); return true; }
        catch (FileService.FileException e) {
            if ("NOT_FOUND".equals(e.code)) return false;
            throw e;
        }
    }

    private void discard(File root, String[] tempParts, FileService.Writable file) {
        closeQuietly(file);
        try { mService.access().delete(root, tempParts); } catch (FileService.FileException ignored) { }
    }

    private static void closeQuietly(FileService.Writable file) {
        try { file.close(); } catch (IOException ignored) { }
    }

    /** 128 random bits; transfer IDs are capabilities and must not be guessable. */
    private String newId() {
        byte[] bytes = new byte[16];
        mRandom.nextBytes(bytes);
        return "tr_" + hex(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return text.toString();
    }
}

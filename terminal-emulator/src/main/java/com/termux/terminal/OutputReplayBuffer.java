package com.termux.terminal;

import java.util.Arrays;

/** Opt-in bounded raw PTY output history. Does not perform I/O or retain callers. */
public final class OutputReplayBuffer {
    public static final int MAX_CAPACITY = 256 * 1024;
    public static final int MAX_READ = 64 * 1024;
    private final byte[] mBytes;
    private long mEnd;
    private boolean mOpen = true;

    public OutputReplayBuffer(int capacity) {
        if (capacity <= 0 || capacity > MAX_CAPACITY) throw new IllegalArgumentException("Invalid capacity");
        mBytes = new byte[capacity];
    }

    /** Append a PTY chunk without waiting for a consumer. Returns false after close. */
    public synchronized boolean append(byte[] data, int offset, int count) {
        if (data == null || offset < 0 || count < 0 || offset > data.length - count)
            throw new IllegalArgumentException("Invalid data");
        if (!mOpen) return false;
        if (count > Long.MAX_VALUE - mEnd) {
            close();
            return false;
        }
        int retained = Math.min(count, mBytes.length);
        int skipped = count - retained;
        int tail = (int) ((mEnd + skipped) % mBytes.length);
        int first = Math.min(retained, mBytes.length - tail);
        System.arraycopy(data, offset + skipped, mBytes, tail, first);
        System.arraycopy(data, offset + skipped + first, mBytes, 0, retained - first);
        mEnd += count;
        return true;
    }

    public static final class ReadResult {
        public final long offset, nextOffset, endOffset;
        public final boolean gap;
        public final byte[] data;
        ReadResult(long offset, long nextOffset, long endOffset, boolean gap, byte[] data) {
            this.offset = offset; this.nextOffset = nextOffset; this.endOffset = endOffset;
            this.gap = gap; this.data = data;
        }
    }

    /** Current absolute end offset without exposing any retained bytes. */
    public synchronized long endOffset() {
        if (!mOpen) throw new IllegalStateException("Buffer closed");
        return mEnd;
    }

    /** Return up to limit bytes from an absolute offset; gap means older data was overwritten. */
    public synchronized ReadResult read(long offset, int limit) {
        if (!mOpen) throw new IllegalStateException("Buffer closed");
        if (offset < 0 || offset > mEnd || limit <= 0 || limit > MAX_READ)
            throw new IllegalArgumentException("Invalid offset or limit");
        long oldest = Math.max(0, mEnd - mBytes.length);
        boolean gap = offset < oldest;
        long start = Math.max(offset, oldest);
        int count = (int) Math.min((long) limit, mEnd - start);
        byte[] result = new byte[count];
        int head = (int) (start % mBytes.length);
        int first = Math.min(count, mBytes.length - head);
        System.arraycopy(mBytes, head, result, 0, first);
        System.arraycopy(mBytes, 0, result, first, count - first);
        return new ReadResult(start, start + count, mEnd, gap, result);
    }

    /** Revoke future reads and clear the in-memory ring. */
    public synchronized void close() {
        mOpen = false;
        Arrays.fill(mBytes, (byte) 0);
    }
}

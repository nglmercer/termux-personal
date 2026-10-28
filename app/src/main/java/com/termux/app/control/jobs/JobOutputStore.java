package com.termux.app.control.jobs;

import com.termux.terminal.OutputReplayBuffer;
import java.util.HashMap;
import java.util.Map;

/** Service-lifetime, bounded job output storage. No I/O, callbacks or logging occur here. */
public final class JobOutputStore implements AutoCloseable {
    public static final int DEFAULT_STREAM_BYTES = 256 * 1024;
    public static final int DEFAULT_RECORD_LIMIT = 32;
    public static final int DEFAULT_TOTAL_BYTES = 16 * 1024 * 1024;
    public enum Stream { STDOUT, STDERR }

    private final int mStreamBytes, mRecordLimit, mTotalBytes;
    private final Map<String, Output> mOutputs = new HashMap<>();
    private boolean mClosed;

    public JobOutputStore() { this(DEFAULT_STREAM_BYTES, DEFAULT_RECORD_LIMIT, DEFAULT_TOTAL_BYTES); }

    public JobOutputStore(int streamBytes, int recordLimit, int totalBytes) {
        if (streamBytes < 1 || streamBytes > OutputReplayBuffer.MAX_CAPACITY || recordLimit < 1 ||
            recordLimit > DEFAULT_RECORD_LIMIT || totalBytes < 2 * streamBytes || totalBytes > DEFAULT_TOTAL_BYTES)
            throw new IllegalArgumentException("Invalid output quota");
        mStreamBytes = streamBytes; mRecordLimit = recordLimit; mTotalBytes = totalBytes;
    }

    /** Reserve both stream rings before publishing a job. Returns null when quota is exhausted. */
    public synchronized Output reserve(String jobId) {
        if (mClosed) throw new IllegalStateException("Output store closed");
        if (jobId == null || jobId.isEmpty() || jobId.length() > 128) throw new IllegalArgumentException("Invalid job ID");
        if (mOutputs.containsKey(jobId)) throw new IllegalArgumentException("Duplicate job ID");
        if (mOutputs.size() >= mRecordLimit || (long)(mOutputs.size() + 1) * 2 * mStreamBytes > mTotalBytes) return null;
        Output output = new Output(mStreamBytes);
        mOutputs.put(jobId, output);
        return output;
    }

    public synchronized Output get(String jobId) { return mOutputs.get(jobId); }

    /** Remove retained data and invalidate any previously handed-out handle. */
    public synchronized boolean remove(String jobId) {
        Output output = mOutputs.remove(jobId);
        if (output == null) return false;
        output.close();
        return true;
    }

    @Override public synchronized void close() {
        if (mClosed) return;
        mClosed = true;
        for (Output output : mOutputs.values()) output.close();
        mOutputs.clear();
    }

    /** Each stream has independent monotonic offsets; finished logs remain readable until removal. */
    public static final class Output implements AutoCloseable {
        private final OutputReplayBuffer mStdout, mStderr;
        private boolean mFinished, mClosed;

        private Output(int capacity) {
            mStdout = new OutputReplayBuffer(capacity);
            mStderr = new OutputReplayBuffer(capacity);
        }

        public synchronized boolean append(Stream stream, byte[] data, int offset, int length) {
            if (mFinished || mClosed) return false;
            return buffer(stream).append(data, offset, length);
        }

        public synchronized OutputReplayBuffer.ReadResult read(Stream stream, long offset, int limit) {
            if (mClosed) throw new IllegalStateException("Output removed");
            return buffer(stream).read(offset, limit);
        }

        /** Couple the finished flag and end offset so a caller cannot miss a final append. */
        public synchronized Read readSnapshot(Stream stream, long offset, int limit) {
            if (mClosed) throw new IllegalStateException("Output removed");
            return new Read(buffer(stream).read(offset, limit), mFinished);
        }

        /** Offset metadata is separate from permission to read retained output. */
        public synchronized Offsets offsets() {
            if (mClosed) throw new IllegalStateException("Output removed");
            return new Offsets(mStdout.endOffset(), mStderr.endOffset());
        }

        /** Freeze writes only after both stream readers have finished. */
        public synchronized void finish() { if (!mClosed) mFinished = true; }
        public synchronized boolean isFinished() { return mFinished; }

        private OutputReplayBuffer buffer(Stream stream) {
            if (stream == null) throw new IllegalArgumentException("Missing stream");
            return stream == Stream.STDOUT ? mStdout : mStderr;
        }

        @Override public synchronized void close() {
            if (mClosed) return;
            mClosed = true;
            mStdout.close(); mStderr.close();
        }
    }

    public static final class Offsets {
        public final long stdoutEndOffset, stderrEndOffset;
        private Offsets(long stdoutEndOffset, long stderrEndOffset) {
            this.stdoutEndOffset = stdoutEndOffset; this.stderrEndOffset = stderrEndOffset;
        }
    }

    public static final class Read {
        public final OutputReplayBuffer.ReadResult data; public final boolean finished;
        private Read(OutputReplayBuffer.ReadResult data, boolean finished) { this.data = data; this.finished = finished; }
    }
}

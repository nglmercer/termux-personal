/*
 * Copyright (C) 2012-2019 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.termux.shared.shell;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.termux.shared.logger.Logger;

/**
 * Thread utility class continuously reading from an InputStream
 *
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/Shell.java#L141
 * https://github.com/Chainfire/libsuperuser/blob/1.1.0.201907261845/libsuperuser/src/eu/chainfire/libsuperuser/StreamGobbler.java
 */
@SuppressWarnings({"WeakerAccess"})
public class StreamGobbler extends Thread {
    private static int threadCounter = 0;
    private static int incThreadCounter() {
        synchronized (StreamGobbler.class) {
            int ret = threadCounter;
            threadCounter++;
            return ret;
        }
    }

    /**
     * Line callback interface
     */
    public interface OnLineListener {
        /**
         * <p>Line callback</p>
         *
         * <p>This callback should process the line as quickly as possible.
         * Delays in this callback may pause the native process or even
         * result in a deadlock</p>
         *
         * @param line String that was gobbled
         */
        void onLine(String line);
    }

    /**
     * Stream closed callback interface
     */
    public interface OnStreamClosedListener {
        /**
         * <p>Stream closed callback</p>
         */
        void onStreamClosed();
    }

    @NonNull
    private final String shell;
    @NonNull
    private final InputStream inputStream;
    @NonNull
    private final BufferedReader reader;
    @Nullable
    private final List<String> listWriter;
    @Nullable
    private final StringBuilder stringWriter;
    @Nullable
    private final OnLineListener lineListener;
    @Nullable
    private final OnStreamClosedListener streamClosedListener;
    @Nullable
    private final Integer mLogLevel;
    private volatile boolean active = true;
    private volatile boolean calledOnClose = false;

    private static final String LOG_TAG = "StreamGobbler";

    /** Default retention bound for the {@link StringBuilder} constructor, per stream. */
    public static final long DEFAULT_MAX_RETAINED_BYTES = 4L * 1024 * 1024;
    /** Longest line passed to the logger when output logging is enabled; longer lines are cut for logging only. */
    private static final int MAX_LOG_LINE_BYTES = 4096;
    private static final int READ_BUFFER_BYTES = 8192;

    private long mMaxRetainedBytes;
    private volatile long mDroppedBytes;

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputList {@literal List<String>} to write to, or null
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream,
                         @Nullable List<String> outputList,
                         @Nullable Integer logLevel) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        streamClosedListener = null;

        listWriter = outputList;
        stringWriter = null;
        lineListener = null;

        mLogLevel = logLevel;
    }

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     * Do not use this for concurrent reading for STDOUT and STDERR for the same StringBuilder since
     * its not synchronized.
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param outputString {@literal List<String>} to write to, or null
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream,
                         @Nullable StringBuilder outputString,
                         @Nullable Integer logLevel) {
        this(shell, inputStream, outputString, logLevel, DEFAULT_MAX_RETAINED_BYTES);
    }

    /**
     * Like {@link #StreamGobbler(String, InputStream, StringBuilder, Integer)} but with an explicit
     * retention bound. Output is read as raw bytes, so memory use is bounded by {@code maxRetainedBytes}
     * plus one fixed read buffer no matter how much the process writes or how long a single line is.
     * The stream keeps being drained after the bound is reached so the child never blocks on a full
     * pipe; the bytes that were not retained are counted by {@link #getDroppedBytes()}.
     *
     * @param maxRetainedBytes Maximum number of bytes (line terminators included) appended to
     *                         {@code outputString}, or a value {@code <= 0} for no bound.
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream,
                         @Nullable StringBuilder outputString,
                         @Nullable Integer logLevel, long maxRetainedBytes) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        streamClosedListener = null;

        listWriter = null;
        stringWriter = outputString;
        lineListener = null;
        mMaxRetainedBytes = maxRetainedBytes;

        mLogLevel = logLevel;
    }

    /**
     * <p>StreamGobbler constructor</p>
     *
     * <p>We use this class because shell STDOUT and STDERR should be read as quickly as
     * possible to prevent a deadlock from occurring, or Process.waitFor() never
     * returning (as the buffer is full, pausing the native process)</p>
     *
     * @param shell Name of the shell
     * @param inputStream InputStream to read from
     * @param onLineListener OnLineListener callback
     * @param onStreamClosedListener OnStreamClosedListener callback
     * @param logLevel The custom log level to use for logging the command output. If set to
     *                 {@code null}, then {@link Logger#LOG_LEVEL_VERBOSE} will be used.
     */
    @AnyThread
    public StreamGobbler(@NonNull String shell, @NonNull InputStream inputStream,
                         @Nullable OnLineListener onLineListener,
                         @Nullable OnStreamClosedListener onStreamClosedListener,
                         @Nullable Integer logLevel) {
        super("Gobbler#" + incThreadCounter());
        this.shell = shell;
        this.inputStream = inputStream;
        reader = new BufferedReader(new InputStreamReader(inputStream));
        streamClosedListener = onStreamClosedListener;

        listWriter = null;
        stringWriter = null;
        lineListener = onLineListener;

        mLogLevel = logLevel;
    }

    @Override
    public void run() {
        String defaultLogTag = Logger.getDefaultLogTag();
        boolean loggingEnabled = Logger.shouldEnableLoggingForCustomLogLevel(mLogLevel);
        if (loggingEnabled)
            Logger.logVerbose(LOG_TAG, "Using custom log level: " + mLogLevel + ", current log level: " + Logger.getLogLevel());

        // keep reading the InputStream until it ends (or an error occurs)
        // optionally pausing when a command is executed that consumes the InputStream itself
        try {
            if (stringWriter != null) {
                // Raw-byte bounded path: never builds an unbounded line or string.
                gobbleBounded(loggingEnabled, defaultLogTag);
            } else {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (loggingEnabled)
                        Logger.logVerboseForce(defaultLogTag + "Command", String.format(Locale.ENGLISH, "[%s] %s", shell, line)); // This will get truncated by LOGGER_ENTRY_MAX_LEN, likely 4KB

                    if (listWriter != null) listWriter.add(line);
                    if (lineListener != null) lineListener.onLine(line);
                    while (!active) {
                        synchronized (this) {
                            try {
                                this.wait(128);
                            } catch (InterruptedException e) {
                                // no action
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            // reader probably closed, expected exit condition
            if (streamClosedListener != null) {
                calledOnClose = true;
                streamClosedListener.onStreamClosed();
            }
        }

        // make sure our stream is closed and resources will be freed
        try {
            reader.close();
        } catch (IOException e) {
            // read already closed
        }

        if (!calledOnClose) {
            if (streamClosedListener != null) {
                calledOnClose = true;
                streamClosedListener.onStreamClosed();
            }
        }
    }

    /** Bytes read from the stream that were not appended to the output builder; valid once the thread has ended. */
    @AnyThread
    public long getDroppedBytes() {
        return mDroppedBytes;
    }

    /**
     * Reads the stream as bytes, normalizes line terminators exactly as {@link BufferedReader#readLine()}
     * does (\n, \r and \r\n become one "\n", and an unterminated last line gets one), and appends the
     * decoded text until {@link #mMaxRetainedBytes} is reached. Everything after that is counted and discarded.
     */
    private void gobbleBounded(boolean loggingEnabled, String defaultLogTag) throws IOException {
        final StringBuilder out = stringWriter;
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        final byte[] read = new byte[READ_BUFFER_BYTES];
        // Normalized bytes of one read; a CRLF can only shrink, so the input size is an upper bound.
        final byte[] normalized = new byte[READ_BUFFER_BYTES];
        final ByteBuffer pending = ByteBuffer.allocate(READ_BUFFER_BYTES + 8);
        final CharBuffer chars = CharBuffer.allocate(READ_BUFFER_BYTES + 8);
        final ByteArrayOutputStream logLine = loggingEnabled ? new ByteArrayOutputStream() : null;
        long retained = 0;
        boolean skipLf = false, inLine = false;
        int count;
        while ((count = inputStream.read(read)) != -1) {
            int length = 0;
            for (int i = 0; i < count; i++) {
                byte b = read[i];
                if (skipLf) { skipLf = false; if (b == '\n') continue; }
                if (b == '\r' || b == '\n') {
                    skipLf = b == '\r';
                    inLine = false;
                    normalized[length++] = '\n';
                    if (logLine != null) { logLine(defaultLogTag, logLine); }
                } else {
                    inLine = true;
                    normalized[length++] = b;
                    if (logLine != null && logLine.size() < MAX_LOG_LINE_BYTES) logLine.write(b);
                }
            }
            retained = append(out, decoder, pending, chars, normalized, length, retained);
            while (!active) {
                synchronized (this) {
                    try { this.wait(128); } catch (InterruptedException e) { /* no action */ }
                }
            }
        }
        if (inLine) {
            normalized[0] = '\n';
            if (logLine != null) logLine(defaultLogTag, logLine);
            retained = append(out, decoder, pending, chars, normalized, 1, retained);
        }
        // Flush a multi-byte sequence cut off by the end of the stream (or by the bound) as a replacement char.
        pending.flip();
        chars.clear();
        decoder.decode(pending, chars, true);
        decoder.flush(chars);
        chars.flip();
        if (chars.hasRemaining()) out.append(chars);
    }

    private void logLine(String defaultLogTag, ByteArrayOutputStream line) {
        Logger.logVerboseForce(defaultLogTag + "Command", String.format(Locale.ENGLISH, "[%s] %s", shell,
            new String(line.toByteArray(), StandardCharsets.UTF_8)));
        line.reset();
    }

    /** Appends as much of {@code data} as the bound allows and counts the rest; returns the new retained total. */
    private long append(StringBuilder out, CharsetDecoder decoder, ByteBuffer pending, CharBuffer chars,
                        byte[] data, int length, long retained) {
        int keep = length;
        if (mMaxRetainedBytes > 0 && retained + length > mMaxRetainedBytes)
            keep = (int) Math.max(0, mMaxRetainedBytes - retained);
        if (keep < length) mDroppedBytes += length - keep;
        if (keep == 0) return retained;
        // Leftover bytes of a split multi-byte sequence stay in `pending` for the next call.
        pending.put(data, 0, keep);
        pending.flip();
        chars.clear();
        decoder.decode(pending, chars, false);
        chars.flip();
        if (chars.hasRemaining()) out.append(chars);
        pending.compact();
        return retained + keep;
    }

    /**
     * <p>Resume consuming the input from the stream</p>
     */
    @AnyThread
    public void resumeGobbling() {
        if (!active) {
            synchronized (this) {
                active = true;
                this.notifyAll();
            }
        }
    }

    /**
     * <p>Suspend gobbling, so other code may read from the InputStream instead</p>
     *
     * <p>This should <i>only</i> be called from the OnLineListener callback!</p>
     */
    @AnyThread
    public void suspendGobbling() {
        synchronized (this) {
            active = false;
            this.notifyAll();
        }
    }

    /**
     * <p>Wait for gobbling to be suspended</p>
     *
     * <p>Obviously this cannot be called from the same thread as {@link #suspendGobbling()}</p>
     */
    @WorkerThread
    public void waitForSuspend() {
        synchronized (this) {
            while (active) {
                try {
                    this.wait(32);
                } catch (InterruptedException e) {
                    // no action
                }
            }
        }
    }

    /**
     * <p>Is gobbling suspended ?</p>
     *
     * @return is gobbling suspended?
     */
    @AnyThread
    public boolean isSuspended() {
        synchronized (this) {
            return !active;
        }
    }

    /**
     * <p>Get current source InputStream</p>
     *
     * @return source InputStream
     */
    @NonNull
    @AnyThread
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * <p>Get current OnLineListener</p>
     *
     * @return OnLineListener
     */
    @Nullable
    @AnyThread
    public OnLineListener getOnLineListener() {
        return lineListener;
    }

    void conditionalJoin() throws InterruptedException {
        if (calledOnClose) return; // deadlock from callback, we're inside exit procedure
        if (Thread.currentThread() == this) return; // can't join self
        join();
    }
}

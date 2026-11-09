package com.termux.shared.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class StreamGobblerBoundedTest {
    private static final class Result { String text; long dropped; }

    private static Result gobble(InputStream in, long limit) throws InterruptedException {
        StringBuilder out = new StringBuilder();
        StreamGobbler gobbler = new StreamGobbler("test", in, out, null, limit);
        gobbler.start();
        gobbler.join();
        Result r = new Result(); r.text = out.toString(); r.dropped = gobbler.getDroppedBytes();
        return r;
    }

    private static Result gobble(String text, long limit) throws InterruptedException {
        return gobble(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), limit);
    }

    @Test public void smallOutputKeepsReadLineSemantics() throws Exception {
        assertEquals("", gobble("", 1024).text);
        assertEquals("a\n", gobble("a", 1024).text);
        assertEquals("a\nb\n", gobble("a\nb\n", 1024).text);
        assertEquals("a\nb\n", gobble("a\r\nb\r\n", 1024).text);
        assertEquals("a\n\nb\n", gobble("a\r\rb", 1024).text);
        assertEquals("\n", gobble("\n", 1024).text);
        Result r = gobble("héllo wörld ✓\n", 1024);
        assertEquals("héllo wörld ✓\n", r.text);
        assertEquals(0, r.dropped);
    }

    @Test public void crlfSplitAcrossReadsIsOneTerminator() throws Exception {
        InputStream in = new InputStream() {
            private final byte[][] chunks = { "a\r".getBytes(), "\nb".getBytes() };
            private int chunk, pos;
            @Override public int read() { throw new UnsupportedOperationException(); }
            @Override public int read(byte[] b, int off, int len) {
                if (chunk >= chunks.length) return -1;
                int n = Math.min(len, chunks[chunk].length - pos);
                System.arraycopy(chunks[chunk], pos, b, off, n);
                if ((pos += n) == chunks[chunk].length) { chunk++; pos = 0; }
                return n;
            }
        };
        assertEquals("a\nb\n", gobble(in, 1024).text);
    }

    @Test public void retainedOutputNeverExceedsBoundAndDropsAreCounted() throws Exception {
        byte[] line = new byte[3_000_000];
        Arrays.fill(line, (byte) 'x');
        Result r = gobble(new ByteArrayInputStream(line), 1000);
        assertEquals(1000, r.text.length());
        assertEquals(line.length + 1 - 1000, r.dropped); // plus the added final terminator
    }

    @Test public void singleHugeLineIsDrainedWithoutBuildingALine() throws Exception {
        final long total = 40L * 1024 * 1024;
        InputStream in = new InputStream() {
            private long sent;
            @Override public int read() { return sent++ < total ? 'y' : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (sent >= total) return -1;
                int n = (int) Math.min(len, total - sent);
                Arrays.fill(b, off, off + n, (byte) 'y');
                sent += n;
                return n;
            }
        };
        Result r = gobble(in, 64 * 1024);
        assertEquals(64 * 1024, r.text.length());
        assertEquals(total + 1 - 64 * 1024, r.dropped);
    }

    @Test public void binaryAndInvalidUtf8DoNotThrowAndAreReplaced() throws Exception {
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Result r = gobble(new ByteArrayInputStream(data), 1 << 20);
        assertTrue(r.text.length() > 0);
        assertTrue(r.text.indexOf('�') >= 0);
        assertEquals(0, r.dropped);
    }

    @Test public void multiByteCharacterCutByTheBoundBecomesReplacement() throws Exception {
        // "é" is two bytes; a 1-byte bound keeps half of it.
        Result r = gobble("é", 1);
        assertEquals("�", r.text);
        assertEquals(2, r.dropped);
    }

    @Test public void unboundedWhenLimitIsZero() throws Exception {
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 20000; i++) expected.append("line ").append(i).append('\n');
        Result r = gobble(expected.toString(), 0);
        assertEquals(expected.toString(), r.text);
        assertEquals(0, r.dropped);
    }

    @Test public void streamFailureEndsTheThreadWithoutHanging() throws Exception {
        InputStream in = new InputStream() {
            private boolean first = true;
            @Override public int read() throws IOException { throw new IOException("closed"); }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (first) { first = false; b[off] = 'k'; return 1; }
                throw new IOException("closed");
            }
        };
        assertEquals("k", gobble(in, 1024).text); // the unterminated partial line is lost on a read error
    }
}

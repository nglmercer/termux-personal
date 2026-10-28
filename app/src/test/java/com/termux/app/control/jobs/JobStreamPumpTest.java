package com.termux.app.control.jobs;

import com.termux.terminal.OutputReplayBuffer;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class JobStreamPumpTest {
    @Test public void drainsLargeConcurrentChildStreamsWithoutLineDecoding() throws Exception {
        Process process = new ProcessBuilder("python3", "-c",
            "import os,threading; a=threading.Thread(target=lambda:os.write(1,b'\\x00\\xff'*131072)); a.start(); os.write(2,b'\\xfe\\x01'*131072); a.join(); raise SystemExit(7)").start();
        JobOutputStore store = new JobOutputStore(1024, 1, 2048);
        JobOutputStore.Output output = store.reserve("job");
        JobStreamPump stdout = new JobStreamPump(process.getInputStream(), output, JobOutputStore.Stream.STDOUT);
        JobStreamPump stderr = new JobStreamPump(process.getErrorStream(), output, JobOutputStore.Stream.STDERR);
        Thread outThread = new Thread(stdout), errThread = new Thread(stderr);
        outThread.start(); errThread.start();
        try {
            assertTrue("child blocked on a pipe", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(7, process.exitValue());
            assertEquals(JobStreamPump.Result.COMPLETE, stdout.await(5, TimeUnit.SECONDS));
            assertEquals(JobStreamPump.Result.COMPLETE, stderr.await(5, TimeUnit.SECONDS));
            output.finish();
            OutputReplayBuffer.ReadResult out = output.read(JobOutputStore.Stream.STDOUT, 0, 1024);
            OutputReplayBuffer.ReadResult err = output.read(JobOutputStore.Stream.STDERR, 0, 1024);
            assertTrue(out.gap); assertTrue(err.gap);
            assertEquals(262144, out.endOffset); assertEquals(262144, err.endOffset);
            for (int i = 0; i < 1024; i++) {
                assertEquals((byte) ((i & 1) == 0 ? 0 : 255), out.data[i]);
                assertEquals((byte) ((i & 1) == 0 ? 254 : 1), err.data[i]);
            }
        } finally {
            process.destroyForcibly(); outThread.join(5000); errThread.join(5000); store.close();
        }
    }

    @Test public void drainsAndClosesInputAfterRetentionIsRevoked() throws Exception {
        JobOutputStore store = new JobOutputStore(4, 1, 8);
        JobOutputStore.Output output = store.reserve("job");
        byte[] data = new byte[20000];
        class CountingInput extends ByteArrayInputStream {
            boolean closed;
            CountingInput() { super(data); }
            @Override public void close() throws IOException { closed = true; super.close(); }
        }
        CountingInput input = new CountingInput();
        JobStreamPump pump = new JobStreamPump(input, output, JobOutputStore.Stream.STDOUT);
        store.remove("job");
        pump.run();
        assertEquals(JobStreamPump.Result.CAPTURE_UNAVAILABLE, pump.await(1, TimeUnit.SECONDS));
        assertEquals(0, input.available()); assertTrue(input.closed);
        try { pump.run(); fail(); } catch (IllegalStateException expected) { }
        store.close();
    }

    @Test public void reportsReadFailureAndClosesInput() throws Exception {
        JobOutputStore store = new JobOutputStore(4, 1, 8);
        class FailingInput extends InputStream {
            boolean closed;
            @Override public int read() throws IOException { throw new IOException("not logged"); }
            @Override public void close() { closed = true; }
        }
        FailingInput input = new FailingInput();
        JobStreamPump pump = new JobStreamPump(input, store.reserve("job"), JobOutputStore.Stream.STDERR);
        assertNull(pump.await(0, TimeUnit.MILLISECONDS));
        pump.run();
        assertEquals(JobStreamPump.Result.IO_ERROR, pump.await(1, TimeUnit.SECONDS));
        assertTrue(input.closed); store.close();
    }

    @Test public void uncheckedReadFailureIsNotReportedAsSuccess() throws Exception {
        JobOutputStore store = new JobOutputStore(4, 1, 8);
        InputStream input = new InputStream() {
            @Override public int read() { throw new IllegalStateException("not logged"); }
        };
        JobStreamPump pump = new JobStreamPump(input, store.reserve("job"), JobOutputStore.Stream.STDOUT);
        pump.run();
        assertEquals(JobStreamPump.Result.IO_ERROR, pump.await(1, TimeUnit.SECONDS));
        store.close();
    }
}

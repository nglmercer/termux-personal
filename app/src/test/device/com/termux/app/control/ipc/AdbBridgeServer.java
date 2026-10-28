package com.termux.app.control.ipc;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import com.termux.app.control.ControlCore;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated test server for the host ADB bridge, never the user's active listener. */
public final class AdbBridgeServer {
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        File root = File.createTempFile("adb-server-", "", new File(System.getenv("TMPDIR")));
        if (!root.delete() || !root.mkdir()) throw new IllegalStateException("Cannot create test root");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        };
        CountDownLatch written = new CountDownLatch(2);
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { throw new AssertionError(); }
            public void write(String id, byte[] data) {
                if (!"s1".equals(id) || !Arrays.equals(data, new byte[]{0, (byte) 255, 3})) throw new AssertionError();
                written.countDown();
            }
            public void rename(String id, String name) { throw new AssertionError(); }
            public void close(String id) { throw new AssertionError(); }
            public void resize(String id, int c, int r, int w, int h) { throw new AssertionError(); }
        };
        LocalControlServer server = new LocalControlServer(context, new ControlCore(backend),
            new ServiceThreadDispatch(Runnable::run, () -> false, 4), EnumSet.of(ControlCore.Scope.SESSIONS_WRITE), null, null);
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread start = new Thread(() -> { try { server.start(); } catch (Throwable t) { failure.set(t); } });
            start.start(); start.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
            System.out.println("READY " + new File(root, "control-ipc/control.sock").getAbsolutePath() + " " + args[0]);
            System.out.flush();
            if (!written.await(15, TimeUnit.SECONDS)) throw new AssertionError("No write received");
            Thread.sleep(500); // Allow the worker to return its response before teardown.
        } finally {
            server.close(); new File(root, "control-ipc").delete(); root.delete();
        }
    }
}

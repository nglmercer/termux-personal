package com.termux.app.control.ipc;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import com.termux.app.control.ControlCore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Uses an isolated socket directory, never the user's active listener. */
public final class LocalSocketSmoke {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        File root = File.createTempFile("socket-smoke-", "", new File(System.getenv("TMPDIR")));
        check(root.delete() && root.mkdir());
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        };
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { throw new AssertionError(); }
            public void write(String id, byte[] data) { throw new AssertionError(); }
            public void rename(String id, String name) { throw new AssertionError(); }
            public void close(String id) { throw new AssertionError(); }
            public void resize(String id, int c, int r, int w, int h) { throw new AssertionError(); }
        };
        LocalControlServer server = new LocalControlServer(context, new ControlCore(backend),
            new ServiceThreadDispatch(Runnable::run, () -> false, 4), EnumSet.of(ControlCore.Scope.SESSIONS_READ), null, null);
        LocalSocket client = new LocalSocket();
        try {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread start = new Thread(() -> { try { server.start(); } catch (Throwable t) { failure.set(t); } });
            start.start(); start.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
            File socket = new File(root, "control-ipc/control.sock");
            check((Os.lstat(socket.getAbsolutePath()).st_mode & 0777) == 0600);
            client.connect(new LocalSocketAddress(socket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            check(client.getPeerCredentials().getUid() == Process.myUid());
            client.setSoTimeout(5000);
            FrameCodec codec = new FrameCodec(65536);
            write(codec, client, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}");
            check(read(codec, client).contains("\"version\":1"));
            write(codec, client, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"system.capabilities\"}");
            String capabilities = read(codec, client);
            check(capabilities.contains("session.list") && !capabilities.contains("session.write"));
            write(codec, client, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.rename\",\"params\":{\"id\":\"s1\",\"name\":\"x\"}}");
            check(read(codec, client).contains("DENIED"));
            server.requestStop(); check(!server.isRunning());
            try { check(client.getInputStream().read() == -1); }
            catch (java.net.SocketTimeoutException e) { throw new AssertionError("revocation did not close client", e); }
            catch (java.io.IOException expected) { /* A reset is also a closed connection. */ }
            System.out.println("Android LocalSocket credentials, scopes and revocation passed");
        } finally {
            server.close(); client.close(); new File(root, "control-ipc").delete(); root.delete();
        }
    }
    private static void write(FrameCodec codec, LocalSocket socket, String json) throws Exception {
        codec.write(socket.getOutputStream(), json.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(FrameCodec codec, LocalSocket socket) throws Exception {
        return new String(codec.read(socket.getInputStream()), StandardCharsets.UTF_8);
    }
}

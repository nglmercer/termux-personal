package com.termux.app.control.ipc;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.app.control.ControlCore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalControlServerInstrumentedTest {
    @Test public void sameUidNegotiatesAndRevocationClosesConnection() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = File.createTempFile("ipc", "", target.getCacheDir());
        assertTrue(root.delete()); assertTrue(root.mkdir());
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
        };
        ControlCore.Backend backend = new ControlCore.Backend() {
            public boolean isCloseEnabled() { return false; }
            public List<ControlCore.SessionInfo> list() { return Collections.emptyList(); }
            public ControlCore.SessionInfo create(String n, int c, int r, int w, int h) { return null; }
            public void write(String id, byte[] data) { fail("write was not consented"); }
            public void rename(String id, String name) { fail("write was not consented"); }
            public void close(String id) { fail("close was not consented"); }
            public void resize(String id, int c, int r, int w, int h) { fail("write was not consented"); }
        };
        ServiceThreadDispatch dispatch = new ServiceThreadDispatch(Runnable::run, () -> false, 4);
        LocalControlServer server = new LocalControlServer(isolated, new ControlCore(backend), dispatch,
            EnumSet.of(ControlCore.Scope.SESSIONS_READ), null, null);
        LocalSocket client = new LocalSocket();
        try {
            server.start();
            File socket = new File(root, "control-ipc/control.sock");
            assertEquals(0600, Os.lstat(socket.getAbsolutePath()).st_mode & 0777);
            client.connect(new LocalSocketAddress(socket.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            assertEquals(Process.myUid(), client.getPeerCredentials().getUid());
            client.setSoTimeout(5000);
            FrameCodec codec = new FrameCodec(65536);
            codec.write(client.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"system.hello\",\"params\":{\"version\":1}}".getBytes(StandardCharsets.UTF_8));
            String hello = new String(codec.read(client.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(hello.contains("\"version\":1"));
            codec.write(client.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"system.capabilities\"}".getBytes(StandardCharsets.UTF_8));
            String capabilities = new String(codec.read(client.getInputStream()), StandardCharsets.UTF_8);
            assertTrue(capabilities.contains("session.list"));
            assertFalse(capabilities.contains("session.write"));
            codec.write(client.getOutputStream(), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"session.rename\",\"params\":{\"id\":\"s1\",\"name\":\"x\"}}".getBytes(StandardCharsets.UTF_8));
            assertTrue(new String(codec.read(client.getInputStream()), StandardCharsets.UTF_8).contains("DENIED"));
            server.requestStop();
            assertFalse(server.isRunning());
            try { assertEquals(-1, client.getInputStream().read()); }
            catch (java.io.IOException expected) { /* A reset also closes the lease. */ }
        } finally {
            server.close(); client.close();
            new File(root, "control-ipc").delete(); root.delete();
        }
    }
}

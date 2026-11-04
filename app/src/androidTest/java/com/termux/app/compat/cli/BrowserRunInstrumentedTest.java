package com.termux.app.compat.cli;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs the app-owned scripts with Android's shell; it does not open a browser. */
@RunWith(AndroidJUnit4.class)
public class BrowserRunInstrumentedTest {
    private static byte[] asset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    @Test(timeout = 15000) public void childReceivesOnlyTheExplicitBrowserMode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File base = new File(context.getCacheDir(), "browser-run-" + UUID.randomUUID());
        assertTrue(base.mkdir());
        File v1 = new File(base, "compat-v1"), v2 = new File(base, "compat-v2");
        File prefix = new File(base, "prefix"), bin = new File(prefix, "bin"), checksum = new File(bin, "sha256sum");
        try {
            assertTrue(bin.mkdirs());
            try (FileOutputStream out = new FileOutputStream(checksum)) {
                out.write("#!/system/bin/sh\nprintf '%s  file\\n' eef8f41780ea128de23320dfd591b5839267b1812ff33e2c16682ef276e21a28\n".getBytes(StandardCharsets.US_ASCII));
            }
            assertTrue(checksum.setExecutable(true));
            assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, "termux-open-url", asset(context, "compat-v1/termux-open-url")));
            assertEquals(WebCommandShim.Status.READY, WebCommandShim.install(base, "compat-v2", "termux-compat", asset(context, "compat-v2/termux-compat")));
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", new File(v2, "termux-compat").getAbsolutePath(),
                "browser-run", "--path", "--", "/system/bin/sh", "-c", "printf '%s\\n' \"$BROWSER\" \"$PATH\" \"$1\"", "child", "argument with spaces");
            builder.environment().put("TERMUX_APP__FILES_DIR", base.getAbsolutePath());
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("PATH", "/system/bin");
            builder.environment().remove("BROWSER");
            builder.redirectError(new File("/dev/null"));
            Process process = builder.start();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                assertEquals(0, (int) worker.submit(() -> { return process.waitFor(); }).get(5, TimeUnit.SECONDS));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[256];
                    for (int n; (n = in.read(buffer)) != -1; ) output.write(buffer, 0, n);
                }
                String expected = new File(v1, "termux-open-url").getAbsolutePath() + "\n" + v1.getAbsolutePath() + ":/system/bin\nargument with spaces\n";
                assertEquals(expected, new String(output.toByteArray(), StandardCharsets.UTF_8));
            } finally { process.destroy(); worker.shutdownNow(); }
        } finally {
            new File(v1, "termux-open-url").delete(); v1.delete();
            new File(v2, "termux-compat").delete(); v2.delete(); checksum.delete(); bin.delete(); prefix.delete(); base.delete();
        }
    }
}

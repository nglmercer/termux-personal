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

/** Runs the static-binary adapter with Android's shell and a fake proot; no network or traced process. */
@RunWith(AndroidJUnit4.class)
public class GlibcRunInstrumentedTest {
    private static byte[] asset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File file, String content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    @Test(timeout = 15000) public void childReceivesProotEnvironmentAndExitStatus() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File base = new File(context.getCacheDir(), "glibc-run-" + UUID.randomUUID());
        assertTrue(base.mkdir());
        try {
            File prefix = new File(base, "prefix");
            File bin = new File(prefix, "bin");
            File tls = new File(prefix, "etc/tls");
            File tmp = new File(prefix, "tmp");
            assertTrue(bin.mkdirs());
            assertTrue(tls.mkdirs());
            assertTrue(tmp.mkdirs());
            File resolver = new File(prefix, "etc/resolv.conf");
            File hosts = new File(prefix, "etc/hosts");
            File cert = new File(tls, "cert.pem");
            write(resolver, "nameserver 8.8.8.8\n");
            write(hosts, "127.0.0.1 localhost\n");
            write(cert, "fixture ca\n");
            File proot = new File(bin, "proot");
            write(proot, "#!/system/bin/sh\n" +
                "printf 'ssl=%s\\n' \"$SSL_CERT_FILE\"\n" +
                "while [ \"${1-}\" = -b ]; do printf 'bind=%s\\n' \"$2\"; shift 2; done\n" +
                "printf 'command=%s\\n' \"$1\"\n" +
                "shift\n" +
                "for argument do printf 'arg=%s\\n' \"$argument\"; done\n" +
                "exit 37\n");
            assertTrue(proot.setExecutable(true));
            assertEquals(WebCommandShim.Status.READY,
                WebCommandShim.install(base, "compat-v2", "termux-glibc-run", asset(context, "compat-v2/termux-glibc-run")));
            File runner = new File(new File(base, "compat-v2"), "termux-glibc-run");
            ProcessBuilder builder = new ProcessBuilder(runner.getAbsolutePath(), "--",
                "/system/bin/sh", "argument with spaces");
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("PATH", "/system/bin");
            builder.environment().remove("SSL_CERT_FILE");
            Process process = builder.start();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                assertEquals(37, (int) worker.submit(() -> { return process.waitFor(); }).get(5, TimeUnit.SECONDS));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[256];
                    for (int n; (n = in.read(buffer)) != -1; ) output.write(buffer, 0, n);
                }
                String expected = "ssl=" + cert.getAbsolutePath() + "\n" +
                    "bind=" + tmp.getAbsolutePath() + ":/tmp\n" +
                    "bind=" + resolver.getAbsolutePath() + ":/etc/resolv.conf\n" +
                    "bind=" + hosts.getAbsolutePath() + ":/etc/hosts\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/ssl/certs/ca-certificates.crt\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/pki/tls/certs/ca-bundle.crt\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/ssl/ca-bundle.pem\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/pki/tls/cacert.pem\n" +
                    "bind=" + cert.getAbsolutePath() + ":/etc/ssl/cert.pem\n" +
                    "command=/system/bin/sh\n" +
                    "arg=argument with spaces\n";
                assertEquals(expected, new String(output.toByteArray(), StandardCharsets.UTF_8));
            } finally { process.destroy(); worker.shutdownNow(); }
        } finally {
            deleteTree(base);
        }
    }
}

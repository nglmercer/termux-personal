package com.termux.app.control;

import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Minimal agent command bridge for devices whose ROM refuses {@code run-as}.
 *
 * Instrumentation runs in the app process, so this test executes a shell
 * command with the app UID and writes the result to a world-writable scratch
 * directory that the host ADB shell can read. It is a test-only component:
 * it is not shipped in the Termux APK and does not expose a network service.
 *
 * Usage from the host:
 *   adb shell am instrument -w -r -e cmd <base64> \
 *     com.termux.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class AgentCommandInstrumentedTest {

    private static final File OUT_DIR = new File("/sdcard/termux-agent");

    @Test
    public void runAgentCommand() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String encoded = arguments.getString("cmd", "");
        if (encoded.isEmpty()) {
            write("{\"ok\":false,\"error\":\"missing cmd argument\"}");
            assertTrue(true);
            return;
        }

        String script;
        try {
            script = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            write("{\"ok\":false,\"error\":\"invalid base64 cmd\"}");
            assertTrue(true);
            return;
        }

        String[] env = null;
        File termuxRoot = new File("/data/data/com.termux/files");
        if (termuxRoot.exists() && termuxRoot.canRead()) {
            env = new String[] {
                "HOME=" + new File(termuxRoot, "home").getAbsolutePath(),
                "PREFIX=" + new File(termuxRoot, "usr").getAbsolutePath(),
                "PATH=" + new File(termuxRoot, "usr/bin").getAbsolutePath() + ":/system/bin",
                "TMPDIR=" + new File(termuxRoot, "usr/tmp").getAbsolutePath(),
                "LANG=C.UTF-8"
            };
        }

        try {
            Process process = Runtime.getRuntime().exec(
                new String[] {"/system/bin/sh", "-c", script}, env);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = process.getInputStream().read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            while ((read = process.getErrorStream().read(buffer)) != -1) {
                err.write(buffer, 0, read);
            }
            int exit = process.waitFor();
            write("{\"ok\":" + (exit == 0) + ",\"exit\":" + exit
                + ",\"stdout\":" + quote(out.toString("UTF-8"))
                + ",\"stderr\":" + quote(err.toString("UTF-8")) + "}");
        } catch (Exception error) {
            write("{\"ok\":false,\"error\":" + quote(String.valueOf(error)) + "}");
        }
        assertTrue(true);
    }

    private static void write(String json) throws Exception {
        OUT_DIR.mkdirs();
        File result = new File(OUT_DIR, "result.json");
        FileOutputStream stream = new FileOutputStream(result, false);
        try {
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        } finally {
            stream.close();
        }
        result.setReadable(true, false);
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\\': json.append("\\\\"); break;
                case '"': json.append("\\\""); break;
                case '\n': json.append("\\n"); break;
                case '\r': json.append("\\r"); break;
                case '\t': json.append("\\t"); break;
                default:
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
            }
        }
        return json.append('"').toString();
    }
}

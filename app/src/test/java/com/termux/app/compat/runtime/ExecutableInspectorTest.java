package com.termux.app.compat.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ExecutableInspectorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File file(byte[] bytes) throws Exception {
        File f = temp.newFile();
        Files.write(f.toPath(), bytes);
        assertTrue(f.setExecutable(true));
        return f;
    }
    private byte[] elf64(int machine, String interpreter) {
        byte[] bytes = new byte[512];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes[0] = 0x7f; bytes[1] = 'E'; bytes[2] = 'L'; bytes[3] = 'F';
        bytes[4] = 2; bytes[5] = 1; bytes[6] = 1;
        b.putShort(16, (short) 2); b.putShort(18, (short) machine);
        if (interpreter != null) {
            b.putLong(32, 64); b.putShort(54, (short) 56); b.putShort(56, (short) 1);
            b.putInt(64, 3); b.putLong(72, 160);
            byte[] raw = interpreter.getBytes(StandardCharsets.US_ASCII);
            b.putLong(96, raw.length + 1); System.arraycopy(raw, 0, bytes, 160, raw.length);
        }
        return bytes;
    }
    @Test public void identifiesMissingAndAvailableGlibcLoader() throws Exception {
        File binary = file(elf64(183, "/does-not-exist/ld-linux-aarch64.so.1"));
        File loaders = temp.newFolder();
        ExecutableInspector.Report missing = ExecutableInspector.inspect(binary, new String[]{"arm64-v8a"}, loaders);
        assertEquals(ExecutableInspector.Status.MISSING_INTERPRETER, missing.status);
        assertTrue(missing.detail.contains("Linux glibc"));
        File loader = new File(loaders, "ld-linux-aarch64.so.1");
        assertTrue(loader.createNewFile()); assertTrue(loader.setExecutable(true));
        ExecutableInspector.Report available = ExecutableInspector.inspect(binary, new String[]{"arm64-v8a"}, loaders);
        assertEquals(ExecutableInspector.Status.COMPATIBILITY_ADAPTER_AVAILABLE, available.status);
        assertTrue(available.describe().contains("not tested"));
    }
    @Test public void distinguishesAbiAndDoesNotDeclareStaticElfCompatible() throws Exception {
        File binary = file(elf64(62, null));
        assertEquals(ExecutableInspector.Status.ABI_MISMATCH, ExecutableInspector.inspect(binary, new String[]{"arm64-v8a"}, null).status);
        ExecutableInspector.Report nativeAbi = ExecutableInspector.inspect(binary, new String[]{"x86_64"}, null);
        assertEquals(ExecutableInspector.Status.UNTESTED, nativeAbi.status);
        assertTrue(nativeAbi.detail.contains("possibly static"));
    }
    @Test public void rejectsMalformedOffsetsAndInterpreter() throws Exception {
        byte[] bad = elf64(183, "/system/bin/linker64");
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE);
        assertEquals(ExecutableInspector.Status.INVALID_FORMAT, ExecutableInspector.inspect(file(bad), new String[]{"arm64-v8a"}, null).status);
        byte[] relative = elf64(183, "relative-loader");
        assertEquals(ExecutableInspector.Status.INVALID_FORMAT, ExecutableInspector.inspect(file(relative), new String[]{"arm64-v8a"}, null).status);
        assertEquals(ExecutableInspector.Status.INVALID_FORMAT, ExecutableInspector.inspect(file(new byte[]{0x7f, 'E', 'L', 'F'}), new String[]{"arm64-v8a"}, null).status);
    }
    @Test public void reads32BitBigEndianHeadersAndRejectsClassMismatch() throws Exception {
        byte[] elf = new byte[256];
        ByteBuffer b = ByteBuffer.wrap(elf).order(ByteOrder.BIG_ENDIAN);
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 1; elf[5] = 2; elf[6] = 1;
        b.putShort(16, (short) 2); b.putShort(18, (short) 40);
        b.putInt(28, 52); b.putShort(42, (short) 32); b.putShort(44, (short) 1);
        b.putInt(52, 3); b.putInt(56, 120);
        byte[] interpreter = "/system/bin/linker".getBytes(StandardCharsets.US_ASCII);
        b.putInt(68, interpreter.length + 1); System.arraycopy(interpreter, 0, elf, 120, interpreter.length);
        ExecutableInspector.Report report = ExecutableInspector.inspect(file(elf), new String[]{"armeabi-v7a"}, null);
        assertEquals(ExecutableInspector.Status.ABI_MISMATCH, report.status);
        assertTrue(report.detail.contains("Android Bionic"));
        assertTrue(report.detail.contains("32-bit, big endian"));
        assertTrue(report.detail.contains("translation layers were not tested"));
        b.putShort(18, (short) 183);
        assertEquals(ExecutableInspector.Status.INVALID_FORMAT, ExecutableInspector.inspect(file(elf), new String[]{"arm64-v8a"}, null).status);
    }
    @Test public void inspectsScriptWithoutExposingArguments() throws Exception {
        File script = file("#! /usr/bin/env\tpython --secret\nprint(1)\n".getBytes(StandardCharsets.US_ASCII));
        ExecutableInspector.Report report = ExecutableInspector.inspect(script, new String[]{"arm64-v8a"}, null);
        assertEquals(ExecutableInspector.Status.UNTESTED, report.status);
        assertTrue(report.detail.contains("/usr/bin/env"));
        assertFalse(report.describe().contains("secret"));
    }
    @Test public void detectsMalformedAndNonExecutableFiles() throws Exception {
        File binary = file(elf64(183, null));
        assertTrue(binary.setExecutable(false, false));
        // Root may retain execute access through its capabilities; this assertion is conditional on actual access.
        if (!binary.canExecute()) assertEquals(ExecutableInspector.Status.EXECUTION_DENIED,
            ExecutableInspector.inspect(binary, new String[]{"arm64-v8a"}, null).status);
        assertEquals(ExecutableInspector.Status.UNAVAILABLE, ExecutableInspector.inspect(new File(temp.getRoot(), "missing"), new String[0], null).status);
    }
}

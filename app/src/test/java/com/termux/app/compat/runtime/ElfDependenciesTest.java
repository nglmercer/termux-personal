package com.termux.app.compat.runtime;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ElfDependenciesTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private byte[] fixture(boolean wide, ByteOrder order) {
        byte[] data = new byte[1024];
        ByteBuffer b = ByteBuffer.wrap(data).order(order);
        int phoff = wide ? 64 : 52, size = wide ? 56 : 32;
        data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = (byte) (wide ? 2 : 1); data[5] = (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2); data[6] = 1;
        b.putShort(16, (short) 2); b.putShort(18, (short) (wide ? 62 : 3));
        if (wide) { b.putLong(32, phoff); b.putShort(54, (short) size); b.putShort(56, (short) 2); }
        else { b.putInt(28, phoff); b.putShort(42, (short) size); b.putShort(44, (short) 2); }
        // A load segment maps the full fixture at virtual address 0x1000.
        b.putInt(phoff, 1);
        if (wide) { b.putLong(phoff + 8, 0); b.putLong(phoff + 16, 0x1000); b.putLong(phoff + 32, data.length); }
        else { b.putInt(phoff + 4, 0); b.putInt(phoff + 8, 0x1000); b.putInt(phoff + 16, data.length); }
        int dyn = phoff + size;
        b.putInt(dyn, 2);
        if (wide) { b.putLong(dyn + 8, 320); b.putLong(dyn + 16, 0x1140); b.putLong(dyn + 32, 80); }
        else { b.putInt(dyn + 4, 320); b.putInt(dyn + 8, 0x1140); b.putInt(dyn + 16, 40); }
        int step = wide ? 16 : 8;
        dynamic(b, 320, wide, 1, 1);
        dynamic(b, 320 + step, wide, 1, 13);
        dynamic(b, 320 + step * 2, wide, 5, 0x1200);
        dynamic(b, 320 + step * 3, wide, 10, 30);
        dynamic(b, 320 + step * 4, wide, 0, 0);
        byte[] names = "\0libfound.so\0libmissing.so\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(names, 0, data, 512, names.length);
        return data;
    }

    private void dynamic(ByteBuffer b, int at, boolean wide, long tag, long value) {
        if (wide) { b.putLong(at, tag); b.putLong(at + 8, value); }
        else { b.putInt(at, (int) tag); b.putInt(at + 4, (int) value); }
    }

    private String inspect(byte[] bytes, boolean wide, ByteOrder order, File[] dirs) throws Exception {
        File file = temp.newFile();
        Files.write(file.toPath(), bytes);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            return ElfDependencies.inspect(input, bytes.length, wide ? 64 : 52, wide ? 56 : 32, 2, wide, order, dirs);
        }
    }

    @Test public void reportsCandidatesWithoutClaimingResolution() throws Exception {
        File dir = temp.newFolder();
        assertTrue(new File(dir, "libfound.so").createNewFile());
        for (boolean wide : new boolean[]{false, true}) for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            String report = inspect(fixture(wide, order), wide, order, new File[]{dir});
            assertTrue(report, report.contains("libfound.so: candidate present"));
            assertTrue(report, report.contains("libmissing.so: not found in checked directories"));
            assertTrue(report, report.contains("actual loader resolution are not tested"));
        }
    }

    @Test public void malformedTablesAndUnsafeNamesAreNotReported() throws Exception {
        byte[] badOffset = fixture(true, ByteOrder.LITTLE_ENDIAN);
        ByteBuffer.wrap(badOffset).order(ByteOrder.LITTLE_ENDIAN).putLong(328, Long.MAX_VALUE);
        assertTrue(inspect(badOffset, true, ByteOrder.LITTLE_ENDIAN, new File[0]).contains("unavailable"));
        byte[] badAddress = fixture(true, ByteOrder.LITTLE_ENDIAN);
        ByteBuffer.wrap(badAddress).order(ByteOrder.LITTLE_ENDIAN).putLong(360, Long.MAX_VALUE);
        assertTrue(inspect(badAddress, true, ByteOrder.LITTLE_ENDIAN, new File[0]).contains("unavailable"));
        byte[] unsafe = fixture(true, ByteOrder.LITTLE_ENDIAN);
        unsafe[515] = '/';
        String report = inspect(unsafe, true, ByteOrder.LITTLE_ENDIAN, new File[0]);
        assertTrue(report.contains("unavailable"));
        assertFalse(report.contains("libfound"));
    }

    @Test public void executableInspectorIncludesTheReadOnlyDependencyObservation() throws Exception {
        File binary = temp.newFile();
        Files.write(binary.toPath(), fixture(true, ByteOrder.LITTLE_ENDIAN));
        assertTrue(binary.setExecutable(true));
        assertTrue(new File(binary.getParentFile(), "libfound.so").createNewFile());
        ExecutableInspector.Report report = ExecutableInspector.inspect(binary, new String[]{"x86_64"}, null);
        assertEquals(ExecutableInspector.Status.UNTESTED, report.status);
        assertTrue(report.detail, report.detail.contains("libfound.so: candidate present"));
        assertTrue(report.detail, report.detail.contains("libmissing.so: not found in checked directories"));
        assertTrue(report.describe().contains("library resolution"));
    }
}

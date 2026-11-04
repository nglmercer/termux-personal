package com.termux.app.compat.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Bounded ELF DT_NEEDED inspection; never executes the inspected file or its linker. */
final class ElfDependencies {
    private static final int MAX_DYNAMIC_BYTES = 65536;
    private static final int MAX_NEEDED = 32;
    private static final int MAX_NAME = 128;
    private static final String UNAVAILABLE = "\nDirect ELF dependencies: unavailable or beyond inspection limits.";
    private ElfDependencies() { }

    private static boolean range(long offset, long size, long total) {
        return offset >= 0 && size >= 0 && offset <= total && size <= total - offset;
    }
    private static long value(ByteBuffer b, int at, boolean wide) {
        return wide ? b.getLong(at) : b.getInt(at) & 0xffffffffL;
    }
    private static final class Segment {
        final long offset, address, size;
        Segment(long offset, long address, long size) { this.offset = offset; this.address = address; this.size = size; }
    }

    static String inspect(RandomAccessFile file, long length, long phoff, int phsize, int count,
                          boolean wide, ByteOrder order, File[] candidates) {
        try {
            List<Segment> loads = new ArrayList<>();
            Segment dynamic = null;
            int headerBytes = wide ? 56 : 32;
            byte[] header = new byte[headerBytes];
            for (int i = 0; i < count; i++) {
                file.seek(phoff + (long) i * phsize);
                file.readFully(header);
                ByteBuffer p = ByteBuffer.wrap(header).order(order);
                int type = p.getInt(0);
                if (type != 1 && type != 2) continue;
                long offset = value(p, wide ? 8 : 4, wide);
                long address = value(p, wide ? 16 : 8, wide);
                long size = value(p, wide ? 32 : 16, wide);
                if (address < 0 || !range(offset, size, length)) return UNAVAILABLE;
                Segment segment = new Segment(offset, address, size);
                if (type == 1) loads.add(segment);
                else if (dynamic == null) dynamic = segment;
                else return UNAVAILABLE;
            }
            if (dynamic == null) return "\nDirect ELF dependencies: no dynamic segment; library resolution not tested.";
            int entrySize = wide ? 16 : 8;
            if (dynamic.size > MAX_DYNAMIC_BYTES || dynamic.size < entrySize || dynamic.size % entrySize != 0) return UNAVAILABLE;
            List<Long> needed = new ArrayList<>();
            long stringAddress = -1, stringSize = -1;
            boolean terminated = false;
            byte[] entry = new byte[entrySize];
            file.seek(dynamic.offset);
            for (long i = 0; i < dynamic.size / entrySize; i++) {
                file.readFully(entry);
                ByteBuffer d = ByteBuffer.wrap(entry).order(order);
                long tag = value(d, 0, wide), data = value(d, wide ? 8 : 4, wide);
                if (tag == 0) { terminated = true; break; }
                if (tag == 1) { if (data < 0 || needed.size() == MAX_NEEDED) return UNAVAILABLE; needed.add(data); }
                else if (tag == 5) { if (stringAddress != -1 || data < 0) return UNAVAILABLE; stringAddress = data; }
                else if (tag == 10) { if (stringSize != -1 || data < 0) return UNAVAILABLE; stringSize = data; }
            }
            if (!terminated) return UNAVAILABLE;
            if (needed.isEmpty()) return "\nDirect ELF dependencies: none declared; dynamic loading and library resolution not tested.";
            if (stringAddress < 0 || stringSize <= 0) return UNAVAILABLE;
            long stringOffset = -1;
            for (Segment load : loads) {
                if (stringAddress < load.address) continue;
                long delta = stringAddress - load.address;
                if (!range(delta, stringSize, load.size)) continue;
                long candidate = load.offset + delta;
                if (!range(candidate, stringSize, length)) return UNAVAILABLE;
                if (stringOffset != -1 && stringOffset != candidate) return UNAVAILABLE;
                stringOffset = candidate;
            }
            if (stringOffset < 0) return UNAVAILABLE;
            StringBuilder out = new StringBuilder("\nDirect ELF dependencies (candidate files only):");
            for (long offset : needed) {
                if (offset >= stringSize) return UNAVAILABLE;
                file.seek(stringOffset + offset);
                StringBuilder name = new StringBuilder();
                boolean end = false;
                for (int i = 0; i <= MAX_NAME && i < stringSize - offset; i++) {
                    int c = file.read();
                    if (c == 0) { end = true; break; }
                    if (c < 0 || i == MAX_NAME || !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' ||
                        c >= '0' && c <= '9' || c == '.' || c == '_' || c == '+' || c == '-')) return UNAVAILABLE;
                    name.append((char) c);
                }
                if (!end || name.length() == 0 || name.toString().equals(".") || name.toString().equals("..")) return UNAVAILABLE;
                boolean found = false;
                for (File directory : candidates) {
                    if (directory != null && new File(directory, name.toString()).isFile()) { found = true; break; }
                }
                out.append("\n  ").append(name).append(found ? ": candidate present" : ": not found in checked directories");
            }
            return out.append("\nChecked: executable directory, Termux library and glibc loader directories when known, and standard Android system/runtime library directories. " +
                "Other paths, namespaces, library ABI, transitive dependencies and actual loader resolution are not tested.").toString();
        } catch (IOException | SecurityException | IllegalArgumentException e) { return UNAVAILABLE; }
    }
}

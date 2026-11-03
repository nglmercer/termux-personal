package com.termux.app.compat.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Read-only, bounded format inspection. It never executes a program or its loader. */
public final class ExecutableInspector {
    public enum Status { UNTESTED, UNAVAILABLE, INVALID_FORMAT, EXECUTION_DENIED, ABI_MISMATCH, MISSING_INTERPRETER, COMPATIBILITY_ADAPTER_AVAILABLE }
    public static final class Report {
        public final Status status;
        public final String detail;
        Report(Status status, String detail) { this.status = status; this.detail = detail; }
        public String describe() { return "Inspection: " + status + "\n" + detail +
            "\nExecution, shared libraries, kernel support and SELinux: not tested."; }
    }
    private ExecutableInspector() { }
    private static Report result(Status status, String detail) { return new Report(status, detail); }
    private static boolean inFile(long offset, long length, long total) {
        return offset >= 0 && length >= 0 && offset <= total && length <= total - offset;
    }
    private static long unsigned32(int value) { return value & 0xffffffffL; }
    private static long offset(ByteBuffer b, int at, boolean is64) {
        return is64 ? b.getLong(at) : unsigned32(b.getInt(at));
    }
    private static String machineName(int machine) {
        switch (machine) {
            case 3: return "x86";
            case 40: return "ARM";
            case 62: return "x86_64";
            case 183: return "arm64";
            default: return "ELF machine " + machine;
        }
    }
    private static boolean knownMachine(int machine) { return machine == 3 || machine == 40 || machine == 62 || machine == 183; }
    private static boolean matchesAbi(int machine, String[] abis) {
        for (String abi : abis) {
            if (machine == 3 && "x86".equals(abi)) return true;
            if (machine == 62 && "x86_64".equals(abi)) return true;
            if (machine == 40 && ("armeabi-v7a".equals(abi) || "armeabi".equals(abi))) return true;
            if (machine == 183 && "arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
    private static String runtime(String interp) {
        String name = new File(interp).getName();
        if (interp.equals("/system/bin/linker") || interp.equals("/system/bin/linker64") ||
            interp.equals("/apex/com.android.runtime/bin/linker") || interp.equals("/apex/com.android.runtime/bin/linker64")) return "Android Bionic";
        if (name.startsWith("ld-linux")) return "Linux glibc";
        if (name.startsWith("ld-musl-")) return "Linux musl";
        return "Unknown dynamic runtime";
    }
    private static String safeAscii(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            int c = bytes[i] & 255;
            if (c < 32 || c > 126) return null;
        }
        return new String(bytes, from, to - from, StandardCharsets.US_ASCII);
    }
    public static Report inspect(File file, String[] supportedAbis, File glibcLoaderDirectory) {
        try {
            if (file == null || !file.isFile() || !file.canRead()) return result(Status.UNAVAILABLE, "File is absent, not regular, or unreadable.");
            boolean executable = file.canExecute();
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long length = input.length();
                byte[] first = new byte[(int) Math.min(4096, length)];
                input.readFully(first);
                if (first.length >= 2 && first[0] == '#' && first[1] == '!') {
                    int end = 2;
                    while (end < first.length && first[end] != '\n' && first[end] != '\r' && end < 258) end++;
                    if (end == 258 || (end == first.length && length > first.length)) return result(Status.INVALID_FORMAT, "Script interpreter line is too long.");
                    for (int i = 2; i < end; i++) {
                        int c = first[i] & 255;
                        if ((c < 32 && c != '\t') || c > 126) return result(Status.INVALID_FORMAT, "Invalid script interpreter line.");
                    }
                    String line = new String(first, 2, end - 2, StandardCharsets.US_ASCII);
                    line = line.trim();
                    int separator = 0;
                    while (separator < line.length() && line.charAt(separator) != ' ' && line.charAt(separator) != '\t') separator++;
                    String interpreter = line.substring(0, separator);
                    if (!interpreter.startsWith("/")) return result(Status.INVALID_FORMAT, "Script does not name an absolute interpreter.");
                    String detail = "Format: script\nInterpreter: " + interpreter +
                        "\nInterpreter arguments and PATH: not inspected. Termux execution shims may rewrite shebangs.";
                    if (!executable) return result(Status.EXECUTION_DENIED, detail + "\nFile lacks execute access for this process.");
                    File executableInterpreter = new File(interpreter);
                    if (!executableInterpreter.isFile() || !executableInterpreter.canExecute())
                        return result(Status.MISSING_INTERPRETER, detail + "\nThe literal interpreter is absent or not executable in the app process; execution shims were not tested.");
                    return result(Status.UNTESTED, detail + "\nThe literal interpreter is present; compatibility is not established.");
                }
                if (first.length < 16 || first[0] != 0x7f || first[1] != 'E' || first[2] != 'L' || first[3] != 'F')
                    return result(Status.INVALID_FORMAT, "Neither a recognized ELF file nor a shebang script.");
                boolean is64 = first[4] == 2;
                if ((!is64 && first[4] != 1) || (first[5] != 1 && first[5] != 2) || first[6] != 1)
                    return result(Status.INVALID_FORMAT, "Unsupported ELF class, byte order or version.");
                int headerSize = is64 ? 64 : 52;
                if (first.length < headerSize) return result(Status.INVALID_FORMAT, "Truncated ELF header.");
                ByteOrder order = first[5] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                ByteBuffer header = ByteBuffer.wrap(first).order(order);
                int machine = header.getShort(18) & 0xffff;
                int type = header.getShort(16) & 0xffff;
                if (((machine == 3 || machine == 40) && is64) || ((machine == 62 || machine == 183) && !is64))
                    return result(Status.INVALID_FORMAT, "ELF class does not match its machine architecture.");
                long phoff = offset(header, is64 ? 32 : 28, is64);
                int phsize = header.getShort(is64 ? 54 : 42) & 0xffff;
                int count = header.getShort(is64 ? 56 : 44) & 0xffff;
                String detail = "Format: ELF " + (is64 ? "64" : "32") + "-bit, " + (order == ByteOrder.LITTLE_ENDIAN ? "little" : "big") + " endian\nArchitecture: " + machineName(machine) +
                    "\nELF type: " + type + "\nDevice ABI list: " + Arrays.toString(supportedAbis);
                if (count == 0xffff || count > 128 || (count > 0 && (phsize < (is64 ? 56 : 32) || !inFile(phoff, (long) phsize * count, length))))
                    return result(Status.INVALID_FORMAT, detail + "\nProgram header table is invalid or exceeds inspection limits.");
                String interpreter = null;
                byte[] ph = new byte[is64 ? 56 : 32];
                for (int i = 0; i < count; i++) {
                    input.seek(phoff + (long) i * phsize);
                    input.readFully(ph);
                    ByteBuffer p = ByteBuffer.wrap(ph).order(order);
                    if (p.getInt(0) != 3) continue;
                    if (interpreter != null) return result(Status.INVALID_FORMAT, detail + "\nMultiple ELF interpreters.");
                    long loc = offset(p, is64 ? 8 : 4, is64);
                    long size = offset(p, is64 ? 32 : 16, is64);
                    if (size < 2 || size > 256 || !inFile(loc, size, length)) return result(Status.INVALID_FORMAT, detail + "\nInvalid interpreter field.");
                    byte[] raw = new byte[(int) size];
                    input.seek(loc);
                    input.readFully(raw);
                    if (raw[raw.length - 1] != 0) return result(Status.INVALID_FORMAT, detail + "\nUnterminated interpreter field.");
                    interpreter = safeAscii(raw, 0, raw.length - 1);
                    if (interpreter == null || !interpreter.startsWith("/")) return result(Status.INVALID_FORMAT, detail + "\nInvalid interpreter path.");
                }
                if (interpreter != null) detail += "\nInterpreter: " + interpreter + "\nRuntime: " + runtime(interpreter);
                if (!executable) return result(Status.EXECUTION_DENIED, detail + "\nFile lacks execute access for this process.");
                if (order == ByteOrder.BIG_ENDIAN)
                    return result(Status.ABI_MISMATCH, detail + "\nAndroid's native ABIs are little-endian; translation layers were not tested.");
                if (supportedAbis.length > 0 && knownMachine(machine) && !matchesAbi(machine, supportedAbis))
                    return result(Status.ABI_MISMATCH, detail + "\nArchitecture is not in Android's reported ABI list; translation layers were not tested.");
                if (interpreter == null) return result(Status.UNTESTED, detail + "\nNo ELF interpreter: possibly static or a shared object. Runtime cannot be determined from this alone.");
                if (!new File(interpreter).canExecute()) {
                    File adapter = glibcLoaderDirectory == null ? null : new File(glibcLoaderDirectory, new File(interpreter).getName());
                    if ("Linux glibc".equals(runtime(interpreter)) && adapter != null && adapter.isFile() && adapter.canExecute())
                        return result(Status.COMPATIBILITY_ADAPTER_AVAILABLE, detail + "\nA matching glibc loader exists; whether this execution context enables the shim is not tested.");
                    return result(Status.MISSING_INTERPRETER, detail + "\nThe named interpreter is not executable at its literal path; execution shims were not tested.");
                }
                return result(Status.UNTESTED, detail + "\nThe interpreter is present; compatibility is not established.");
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return result(Status.UNAVAILABLE, "File could not be inspected.");
        }
    }
}

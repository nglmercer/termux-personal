package com.termux.app.control.jobs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable structural validation only; a trusted policy must authorize executable and cwd at launch. */
public final class JobSpec {
    public enum Presentation { FOREGROUND, BACKGROUND }
    public static final long MAX_TIMEOUT_MILLIS = 24L * 60 * 60 * 1000;
    private static final int MAX_FIELD_BYTES = 4096, MAX_ARGUMENTS = 128, MAX_ENVIRONMENT = 64, MAX_TOTAL_BYTES = 65536;
    private final String mExecutable, mWorkingDirectory, mWorkspaceId, mClientRequestId;
    private final List<String> mArguments;
    private final Map<String, String> mEnvironment;
    private final byte[] mStdin, mFingerprint;
    private final long mTimeoutMillis;
    private final Presentation mPresentation;

    public JobSpec(String executable, List<String> arguments, String workingDirectory, String workspaceId,
                   Map<String, String> environment, byte[] stdin, long timeoutMillis,
                   Presentation presentation, String clientRequestId) {
        requireText(executable, MAX_FIELD_BYTES, false);
        if (executable.startsWith("/")) requireAbsolutePath(executable);
        else if (!executable.matches("[A-Za-z0-9_+.-]+") || executable.equals(".") || executable.equals(".."))
            throw new IllegalArgumentException("Invalid executable");
        if (workingDirectory != null) { requireText(workingDirectory, MAX_FIELD_BYTES, false); requireAbsolutePath(workingDirectory); }
        if (workspaceId != null) requireIdentifier(workspaceId, 64);
        if (clientRequestId != null) requireIdentifier(clientRequestId, 128);
        if (arguments == null || arguments.size() > MAX_ARGUMENTS || environment == null || environment.size() > MAX_ENVIRONMENT ||
            (stdin != null && stdin.length > AppShellJobCapture.MAX_STDIN_BYTES) || timeoutMillis < 0 || timeoutMillis > MAX_TIMEOUT_MILLIS || presentation == null)
            throw new IllegalArgumentException("Invalid job specification");
        List<String> argsCopy = new ArrayList<>(arguments.size());
        int total = byteLength(executable) + (workingDirectory == null ? 0 : byteLength(workingDirectory));
        for (String arg : arguments) {
            requireText(arg, MAX_FIELD_BYTES, true); total += byteLength(arg); argsCopy.add(arg);
        }
        TreeMap<String, String> envCopy = new TreeMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("Invalid environment key");
            requireText(value, MAX_FIELD_BYTES, true); total += byteLength(key) + byteLength(value); envCopy.put(key, value);
        }
        if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Job metadata too large");
        mExecutable = executable; mArguments = Collections.unmodifiableList(argsCopy); mWorkingDirectory = workingDirectory;
        mWorkspaceId = workspaceId; mEnvironment = Collections.unmodifiableMap(envCopy);
        mStdin = stdin == null ? new byte[0] : stdin.clone(); mTimeoutMillis = timeoutMillis;
        mPresentation = presentation; mClientRequestId = clientRequestId;
        mFingerprint = fingerprint();
    }

    public String executable() { return mExecutable; }
    public List<String> arguments() { return mArguments; }
    public String workingDirectory() { return mWorkingDirectory; }
    public String workspaceId() { return mWorkspaceId; }
    public Map<String, String> environment() { return mEnvironment; }
    public byte[] stdin() { return mStdin.clone(); }
    public long timeoutMillis() { return mTimeoutMillis; }
    public Presentation presentation() { return mPresentation; }
    public String clientRequestId() { return mClientRequestId; }
    public boolean sameExecution(JobSpec other) { return other != null && MessageDigest.isEqual(mFingerprint, other.mFingerprint); }
    byte[] fingerprintCopy() { return mFingerprint.clone(); }

    private static void requireAbsolutePath(String path) {
        if (!path.startsWith("/") || (path.length() > 1 && path.endsWith("/"))) throw new IllegalArgumentException("Path must be absolute and normalized");
        if (path.length() > 1) for (String component : path.substring(1).split("/", -1))
            if (component.isEmpty() || component.equals(".") || component.equals("..")) throw new IllegalArgumentException("Path must be normalized");
    }
    private static void requireIdentifier(String text, int max) {
        requireText(text, max, false);
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) throw new IllegalArgumentException("Invalid identifier");
    }
    private static void requireText(String text, int maxBytes, boolean emptyAllowed) {
        if (text == null || (!emptyAllowed && text.isEmpty()) || text.length() > maxBytes) throw new IllegalArgumentException("Invalid text");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) throw new IllegalArgumentException("NUL not allowed");
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
        }
        if (byteLength(text) > maxBytes) throw new IllegalArgumentException("Text too long");
    }
    private static int byteLength(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static void field(DataOutputStream out, String value) throws IOException {
        if (value == null) { out.writeInt(-1); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { out.writeInt(bytes.length); out.write(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private byte[] fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream data = new DataOutputStream(new DigestOutputStream(new OutputStream() {
                @Override public void write(int value) { }
                @Override public void write(byte[] value, int offset, int length) { }
            }, digest));
            field(data, mExecutable); field(data, mWorkingDirectory); field(data, mWorkspaceId);
            data.writeInt(mArguments.size()); for (String arg : mArguments) field(data, arg);
            data.writeInt(mEnvironment.size()); for (Map.Entry<String, String> entry : mEnvironment.entrySet()) { field(data, entry.getKey()); field(data, entry.getValue()); }
            data.writeInt(mStdin.length); data.write(mStdin); data.writeLong(mTimeoutMillis); field(data, mPresentation.name()); data.flush();
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot fingerprint job specification", e); }
    }
}

package com.termux.app.compat.network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded, read-only file observation; never sends DNS queries or returns resolver addresses. */
final class ResolverFileDiagnostics {
    enum Status { ABSENT_OR_UNREADABLE, NOT_REGULAR, TOO_LARGE, NO_NAMESERVERS, UNRECOGNIZED,
        UNCOMPARED, MATCHES_NETWORK, DIFFERS_FROM_NETWORK }
    private static final int LIMIT = 8192;
    private ResolverFileDiagnostics() { }

    static Status inspect(File file, List<InetAddress> networkDns) {
        try {
            if (!file.exists() || !file.canRead()) return Status.ABSENT_OR_UNREADABLE;
            if (!file.isFile()) return Status.NOT_REGULAR;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[1024];
                for (int n; (n = input.read(buffer)) != -1; ) {
                    if (output.size() + n > LIMIT) return Status.TOO_LARGE;
                    output.write(buffer, 0, n);
                }
            }
            return parse(output.toByteArray(), networkDns);
        } catch (IOException | SecurityException e) { return Status.ABSENT_OR_UNREADABLE; }
    }

    static Status parse(byte[] bytes, List<InetAddress> networkDns) {
        if (bytes.length > LIMIT) return Status.TOO_LARGE;
        for (byte b : bytes) if ((b & 255) > 126 || ((b & 255) < 32 && b != '\n' && b != '\r' && b != '\t')) return Status.UNRECOGNIZED;
        List<byte[]> addresses = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\r?\\n")) {
            int comment = line.indexOf('#');
            int semicolon = line.indexOf(';');
            if (comment < 0 || (semicolon >= 0 && semicolon < comment)) comment = semicolon;
            if (comment >= 0) line = line.substring(0, comment);
            String[] parts = line.trim().split("\\s+");
            if (!"nameserver".equals(parts[0])) continue;
            if (parts.length != 2) return Status.UNRECOGNIZED;
            byte[] address = numericAddress(parts[1]);
            if (address == null) return Status.UNRECOGNIZED;
            addresses.add(address);
        }
        if (addresses.isEmpty()) return Status.NO_NAMESERVERS;
        if (networkDns == null || networkDns.isEmpty()) return Status.UNCOMPARED;
        for (byte[] address : addresses) {
            boolean found = false;
            for (InetAddress networkAddress : networkDns)
                if (networkAddress != null && Arrays.equals(address, networkAddress.getAddress())) { found = true; break; }
            if (!found) return Status.DIFFERS_FROM_NETWORK;
        }
        for (InetAddress networkAddress : networkDns) {
            if (networkAddress == null) return Status.UNCOMPARED;
            boolean found = false;
            for (byte[] address : addresses) if (Arrays.equals(address, networkAddress.getAddress())) { found = true; break; }
            if (!found) return Status.DIFFERS_FROM_NETWORK;
        }
        return Status.MATCHES_NETWORK;
    }

    private static byte[] numericAddress(String value) {
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) return null;
            try {
                // Validate IPv6 syntax before getByName: malformed colon-containing input must not
                // fall through to a hostname lookup on an implementation with a permissive parser.
                java.net.URI uri = new java.net.URI("http://[" + value + "]/");
                if (uri.getHost() == null) return null;
                return InetAddress.getByName(value).getAddress();
            } catch (IOException | java.net.URISyntaxException e) { return null; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] result = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) return null;
            if (parts[i].length() > 1 && parts[i].charAt(0) == '0') return null;
            int part = Integer.parseInt(parts[i]);
            if (part > 255) return null;
            result[i] = (byte) part;
        }
        return result;
    }
}

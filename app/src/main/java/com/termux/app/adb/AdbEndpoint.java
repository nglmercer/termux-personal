package com.termux.app.adb;

/** Numeric ADB wireless endpoint; never passed through a shell. */
public final class AdbEndpoint {
    private AdbEndpoint() { }
    public static boolean valid(String value) {
        if (value == null || value.length() > 80 || !value.equals(value.trim())) return false;
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return false;
        String host = value.substring(0, colon), port = value.substring(colon + 1);
        if (port.length() > 5 || !port.matches("[0-9]+")) return false;
        int number;
        try { number = Integer.parseInt(port); } catch (NumberFormatException e) { return false; }
        if (number < 1 || number > 65535) return false;
        if (host.startsWith("[") && host.endsWith("]")) {
            String address = host.substring(1, host.length() - 1);
            // No DNS, interface zones, or shell metacharacters. The ADB client performs full IPv6 validation.
            return address.length() >= 2 && address.length() <= 45 && address.contains(":") && address.matches("[0-9a-fA-F:.]+");
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.matches("[0-9]+")) return false;
            try { if (Integer.parseInt(part) > 255) return false; }
            catch (NumberFormatException e) { return false; }
        }
        return true;
    }
    public static boolean validPairingCode(String code) {
        return code != null && code.matches("[0-9]{6}");
    }
}

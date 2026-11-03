package com.termux.app.compat.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** A bounded snapshot of platform metadata and explicitly unevaluated capabilities. */
public final class CompatibilityProfile {
    private final int androidApi, targetSdk;
    private final Integer androidUserId;
    private final String manufacturer, model, androidRelease, kernelRelease;
    private final List<String> abis;

    public CompatibilityProfile(int androidApi, int targetSdk, Integer androidUserId, String manufacturer,
                                String model, String androidRelease, String kernelRelease, String[] abis) {
        this.androidApi = Math.max(0, androidApi);
        this.targetSdk = Math.max(0, targetSdk);
        this.androidUserId = androidUserId != null && androidUserId >= 0 ? androidUserId : null;
        this.manufacturer = safe(manufacturer);
        this.model = safe(model);
        this.androidRelease = safe(androidRelease);
        this.kernelRelease = safe(kernelRelease);
        LinkedHashSet<String> list = new LinkedHashSet<>();
        if (abis != null) for (String abi : abis) {
            if (list.size() >= 8) break;
            String value = safe(abi);
            if (!"unknown".equals(value)) list.add(value);
        }
        this.abis = Collections.unmodifiableList(new ArrayList<>(list));
    }
    private static String safe(String input) {
        if (input == null || input.isEmpty()) return "unknown";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length() && out.length() < 64; i++) {
            char c = input.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                c == '.' || c == '_' || c == '-' || c == ' ' ? c : '?');
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("androidApi", androidApi);
        result.put("targetSdk", targetSdk);
        result.put("androidUserId", androidUserId);
        result.put("manufacturer", manufacturer);
        result.put("model", model);
        result.put("androidRelease", androidRelease);
        result.put("kernelRelease", kernelRelease);
        result.put("abis", new ArrayList<>(abis));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        // These values describe this snapshot, not prior probes whose network/lifecycle may have changed.
        capabilities.put("webLaunch", "UNKNOWN");
        capabilities.put("backgroundReceiverRequests", "USER_ACTION_REQUIRED");
        capabilities.put("nativeDns", "UNKNOWN");
        capabilities.put("tls", "UNKNOWN");
        capabilities.put("localhostCallback", "UNKNOWN");
        capabilities.put("nativeExecutable", "UNKNOWN");
        capabilities.put("notifications", "UNKNOWN");
        result.put("capabilities", capabilities);
        return result;
    }
    public String describe() {
        StringBuilder abiList = new StringBuilder();
        for (String abi : abis) { if (abiList.length() > 0) abiList.append(", "); abiList.append(abi); }
        return "Android API: " + androidApi + "\nTarget SDK: " + targetSdk +
            "\nAndroid user ID: " + (androidUserId == null ? "unknown" : androidUserId) +
            "\nDevice: " + manufacturer + " " + model + "\nAndroid release: " + androidRelease +
            "\nKernel: " + kernelRelease + "\nSupported ABIs: " + (abis.isEmpty() ? "unknown" : abiList.toString()) +
            "\n\nBrowser, network, TLS, callbacks, executable compatibility and notifications: not evaluated in this snapshot." +
            "\nBackground web requests received by Termux require user action and may expire.";
    }
}

package com.termux.app.compat.profile;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class CompatibilityProfileTest {
    @Test public void reportsOnlyPolicyAndUnknownForUnprobedCapabilities() {
        CompatibilityProfile profile = new CompatibilityProfile(36, 28, 10, "Example", "Model", "16", "6.1", new String[]{"arm64-v8a", "arm64-v8a"});
        Map<String, Object> values = profile.toMap();
        assertEquals(10, values.get("androidUserId"));
        assertEquals(1, ((List<?>) values.get("abis")).size());
        Map<?, ?> capabilities = (Map<?, ?>) values.get("capabilities");
        assertEquals("USER_ACTION_REQUIRED", capabilities.get("backgroundReceiverRequests"));
        for (String key : new String[]{"webLaunch", "nativeDns", "tls", "localhostCallback", "nativeExecutable", "notifications"})
            assertEquals(key, "UNKNOWN", capabilities.get(key));
        assertTrue(profile.describe().contains("not evaluated"));
    }
    @Test public void boundsAndSanitizesDeviceMetadata() {
        CompatibilityProfile profile = new CompatibilityProfile(0, 0, -1, "maker\nsecret=1", null, null, "x".repeat(200),
            new String[]{"arm64-v8a", "x86_64", "one", "two", "three", "four", "five", "six", "seven"});
        Map<String, Object> values = profile.toMap();
        assertNull(values.get("androidUserId"));
        assertFalse(values.get("manufacturer").toString().contains("\n"));
        assertEquals(64, values.get("kernelRelease").toString().length());
        assertEquals(8, ((List<?>) values.get("abis")).size());
        ((List<?>) values.get("abis")).clear();
        assertEquals(8, ((List<?>) profile.toMap().get("abis")).size());
    }
}

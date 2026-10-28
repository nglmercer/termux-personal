package com.termux.app.control.jobs;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class JobSpecTest {
    private static JobSpec spec(String executable, String cwd, String requestId) {
        return new JobSpec(executable, Arrays.asList("one", "two words"), cwd, "workspace-1",
            Collections.singletonMap("MODE", "test"), new byte[]{0, 1, (byte)255}, 1000, JobSpec.Presentation.BACKGROUND, requestId);
    }

    @Test public void immutableCopiesAndFingerprintIgnoreRequestId() {
        ArrayList<String> args = new ArrayList<>(Arrays.asList("one", "two words"));
        Map<String, String> env = new HashMap<>(); env.put("MODE", "test");
        byte[] input = {0, 1, (byte)255};
        JobSpec first = new JobSpec("/usr/bin/tool", args, "/tmp/project", "workspace-1", env, input, 1000, JobSpec.Presentation.BACKGROUND, "request-a");
        args.set(0, "changed"); env.put("MODE", "changed"); input[0] = 9;
        byte[] returned = first.stdin(); returned[1] = 9;
        assertEquals("one", first.arguments().get(0)); assertEquals("test", first.environment().get("MODE"));
        assertArrayEquals(new byte[]{0, 1, (byte)255}, first.stdin());
        assertTrue(first.sameExecution(spec("/usr/bin/tool", "/tmp/project", "request-b")));
        assertFalse(first.sameExecution(spec("/usr/bin/other", "/tmp/project", "request-a")));
        try { first.arguments().add("bad"); fail(); } catch (UnsupportedOperationException expected) { }
        try { first.environment().put("BAD", "value"); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void environmentOrderingDoesNotChangeFingerprint() {
        Map<String, String> one = new java.util.LinkedHashMap<>(), two = new java.util.LinkedHashMap<>();
        one.put("A", "1"); one.put("B", "2"); two.put("B", "2"); two.put("A", "1");
        JobSpec a = new JobSpec("cargo", Collections.emptyList(), null, null, one, null, 0, JobSpec.Presentation.FOREGROUND, null);
        JobSpec b = new JobSpec("cargo", Collections.emptyList(), null, null, two, null, 0, JobSpec.Presentation.FOREGROUND, null);
        assertTrue(a.sameExecution(b));
    }

    @Test public void rejectsInvalidPathsUnicodeEnvironmentAndLimits() {
        reject(() -> spec("../tool", "/tmp", null));
        reject(() -> spec("/tmp/../tool", "/tmp", null));
        reject(() -> spec("/tmp/tool", "/tmp//project", null));
        reject(() -> spec("/tmp/tool", "relative", null));
        reject(() -> spec("/tmp/tool", "/tmp", "bad\nkey"));
        reject(() -> new JobSpec("cargo", Collections.singletonList("bad\u0000arg"), null, null, Collections.emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, null));
        reject(() -> new JobSpec("cargo", Collections.singletonList("\ud800"), null, null, Collections.emptyMap(), null, 0, JobSpec.Presentation.BACKGROUND, null));
        reject(() -> new JobSpec("cargo", Collections.emptyList(), null, null, Collections.singletonMap("BAD=KEY", "x"), null, 0, JobSpec.Presentation.BACKGROUND, null));
        reject(() -> new JobSpec("cargo", Collections.emptyList(), null, null, Collections.emptyMap(), new byte[65537], 0, JobSpec.Presentation.BACKGROUND, null));
        reject(() -> new JobSpec("cargo", Collections.emptyList(), null, null, Collections.emptyMap(), null, JobSpec.MAX_TIMEOUT_MILLIS + 1, JobSpec.Presentation.BACKGROUND, null));
    }

    private static void reject(Runnable action) {
        try { action.run(); fail("invalid job accepted"); } catch (IllegalArgumentException expected) { }
    }
}

package com.termux.app.control.jobs;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class JobPathPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static JobSpec spec(String executable, String cwd, String workspace) {
        return new JobSpec(executable, Collections.emptyList(), cwd, workspace, Collections.emptyMap(), null, 0,
            JobSpec.Presentation.BACKGROUND, null);
    }
    private static JobPathPolicy policy(File bin, File home, Map<String, String> names) throws Exception {
        return new JobPathPolicy(Collections.singletonList(bin.getAbsolutePath()), Collections.singletonList(home.getAbsolutePath()),
            home.getAbsolutePath(), names);
    }

    @Test public void namedAndExplicitExecutablesAreResolvedWithoutShellStrings() throws Exception {
        File bin = temporary.newFolder("bin"), home = temporary.newFolder("home");
        File executable = new File(bin, "cargo"); assertTrue(executable.createNewFile()); assertTrue(executable.setExecutable(true));
        Map<String, String> names = new HashMap<>(); names.put("cargo", executable.getAbsolutePath());
        JobPathPolicy policy = policy(bin, home, names); names.clear();
        JobCoordinator.Resolution named = policy.authorizeAndResolve(spec("cargo", null, null), 1);
        assertEquals(executable.getAbsolutePath(), named.executable); assertEquals(home.getAbsolutePath(), named.cwd);
        assertNotNull(policy.authorizeAndResolve(spec(executable.getAbsolutePath(), home.getAbsolutePath(), null), 1));
        assertNull(policy.authorizeAndResolve(spec("unlisted", null, null), 1));
        assertNull(policy.authorizeAndResolve(spec("cargo", null, "workspace"), 1));
        File nonExecutable = new File(bin, "not-executable"); assertTrue(nonExecutable.createNewFile());
        assertTrue(nonExecutable.setExecutable(false, false));
        assertFalse(nonExecutable.canExecute());
        assertNull(policy.authorizeAndResolve(spec(nonExecutable.getAbsolutePath(), null, null), 1));
        assertTrue(executable.delete());
        assertNull(policy.authorizeAndResolve(spec("cargo", null, null), 1));
    }

    @Test public void siblingPrefixesAndSymlinkEscapesAreRejected() throws Exception {
        File bin = temporary.newFolder("bin"), sibling = temporary.newFolder("bin-extra");
        File home = temporary.newFolder("home"), outside = temporary.newFolder("outside");
        File external = new File(sibling, "tool"); assertTrue(external.createNewFile()); assertTrue(external.setExecutable(true));
        File link = new File(bin, "escape"); Files.createSymbolicLink(link.toPath(), external.toPath());
        File cwdLink = new File(home, "escape"); Files.createSymbolicLink(cwdLink.toPath(), outside.toPath());
        File safe = new File(bin, "safe"); assertTrue(safe.createNewFile()); assertTrue(safe.setExecutable(true));
        JobPathPolicy policy = policy(bin, home, Collections.singletonMap("safe", safe.getAbsolutePath()));
        assertNull(policy.authorizeAndResolve(spec(external.getAbsolutePath(), null, null), 1));
        assertNull(policy.authorizeAndResolve(spec(link.getAbsolutePath(), null, null), 1));
        assertNull(policy.authorizeAndResolve(spec("safe", outside.getAbsolutePath(), null), 1));
        assertNull(policy.authorizeAndResolve(spec("safe", cwdLink.getAbsolutePath(), null), 1));
        File inner = new File(home, "project"); assertTrue(inner.mkdir());
        assertNotNull(policy.authorizeAndResolve(spec("safe", inner.getAbsolutePath(), null), 1));
    }

    @Test public void rootChangesAndUnavailableOrNonExecutableTargetsFailClosed() throws Exception {
        File real = temporary.newFolder("real"), alternate = temporary.newFolder("alternate"), home = temporary.newFolder("home");
        File root = new File(temporary.getRoot(), "bin-link"); Files.createSymbolicLink(root.toPath(), real.toPath());
        File tool = new File(real, "tool"); assertTrue(tool.createNewFile()); assertTrue(tool.setExecutable(true));
        JobPathPolicy policy = policy(root, home, Collections.singletonMap("tool", new File(root, "tool").getAbsolutePath()));
        assertNotNull(policy.authorizeAndResolve(spec("tool", null, null), 1));
        Files.delete(root.toPath()); Files.createSymbolicLink(root.toPath(), alternate.toPath());
        File replacement = new File(alternate, "tool"); assertTrue(replacement.createNewFile()); assertTrue(replacement.setExecutable(true));
        assertNull(policy.authorizeAndResolve(spec("tool", null, null), 1));
        assertThrows(IllegalArgumentException.class, () -> new JobPathPolicy(Collections.singletonList("/"),
            Collections.singletonList(home.getAbsolutePath()), home.getAbsolutePath(), Collections.emptyMap()));
    }
}

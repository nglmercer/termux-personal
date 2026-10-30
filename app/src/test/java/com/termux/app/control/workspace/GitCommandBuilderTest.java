package com.termux.app.control.workspace;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.Arrays;

public class GitCommandBuilderTest {
    @Test public void inspectionCommandsAreFixedArgvArrays() throws Exception {
        assertEquals(Arrays.asList("git", "status", "--porcelain=v1", "--branch"), GitCommandBuilder.readOnly("status"));
        assertEquals(Arrays.asList("git", "branch", "--show-current"), GitCommandBuilder.readOnly("branch"));
        assertEquals(Arrays.asList("git", "diff", "--stat"), GitCommandBuilder.readOnly("diff"));
        assertEquals(Arrays.asList("git", "log", "--oneline", "-n", "20"), GitCommandBuilder.readOnly("log"));
    }

    @Test public void mutatingAndShellLikeSubcommandsAreRefused() {
        String[] refused = {"commit", "checkout", "fetch", "pull", "push", "reset", "status; rm -rf ~", "status --exec=sh", ""};
        for (String subcommand : refused) {
            try { GitCommandBuilder.readOnly(subcommand); fail("accepted " + subcommand); }
            catch (WorkspaceRepository.WorkspaceException expected) { assertEquals("INVALID_REQUEST", expected.code); }
        }
    }

    @Test public void changesBuildFixedArgvWithClientValuesOnlyInSafePositions() throws Exception {
        assertEquals(Arrays.asList("git", "fetch", "--prune"), GitCommandBuilder.change("fetch", null, null, null));
        assertEquals(Arrays.asList("git", "pull", "--ff-only", "--no-rebase"), GitCommandBuilder.change("pull", null, null, null));
        assertEquals(Arrays.asList("git", "switch", "feature/x-1.2"), GitCommandBuilder.change("switch", "feature/x-1.2", null, null));
        assertEquals(Arrays.asList("git", "add", "--", "-rf", "src/a b.txt"),
            GitCommandBuilder.change("stage", null, Arrays.asList("-rf", "src/a b.txt"), null));
        assertEquals("a message that looks like an option stays the value of -m",
            Arrays.asList("git", "commit", "-m", "--amend"), GitCommandBuilder.change("commit", null, null, "--amend"));
    }

    @Test public void unsafeOrMismatchedChangesAreRefused() {
        Object[][] refused = {
            {"push", null, null, null}, {"switch", "-c", null, null}, {"switch", "--detach", null, null}, {"switch", "a..b", null, null},
            {"switch", "x.lock", null, null}, {"switch", "HEAD", null, null}, {"switch", "a b", null, null}, {"switch", null, null, null},
            {"stage", null, Arrays.asList("../escape"), null}, {"stage", null, Arrays.asList("/abs"), null},
            {"stage", null, java.util.Collections.emptyList(), null}, {"commit", null, null, " "}, {"commit", null, null, "a\0b"},
            {"fetch", "main", null, null}, {"commit", "main", null, "m"}, {"", null, null, null}};
        for (Object[] c : refused) {
            try {
                @SuppressWarnings("unchecked") java.util.List<String> paths = (java.util.List<String>) c[2];
                GitCommandBuilder.change((String) c[0], (String) c[1], paths, (String) c[3]);
                fail("accepted " + Arrays.toString(c));
            } catch (WorkspaceRepository.WorkspaceException expected) { assertEquals("INVALID_REQUEST", expected.code); }
        }
    }

    @Test(expected = UnsupportedOperationException.class) public void returnedArgvCannotBeMutated() throws Exception {
        GitCommandBuilder.readOnly("status").add("--exec");
    }
}

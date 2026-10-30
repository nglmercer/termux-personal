package com.termux.shared.termux.shell.command.environment;

import static org.junit.Assert.assertEquals;
import com.termux.shared.termux.TermuxConstants;
import org.junit.Test;

public class TermuxShellEnvironmentPathTest {
    private static final String HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;

    @Test public void fixedDirectoriesComeFirstAndConfiguredOnesFollow() {
        assertEquals(":" + HOME + "/.local/bin:" + HOME + "/bin:" + HOME + "/.cargo/bin:/opt/tool/bin",
            TermuxShellEnvironment.userBinPath("~/.cargo/bin:/opt/tool/bin/"));
    }

    @Test public void homeFormsExpandAndUnsafeEntriesAreDropped() {
        assertEquals(":" + HOME + "/.local/bin:" + HOME + "/bin:" + HOME + "/x:" + HOME,
            TermuxShellEnvironment.userBinPath(" $HOME/x : relative/bin :~:~/.local/bin::" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH));
    }

    @Test public void emptyValueAppendsNothing() {
        assertEquals(":" + HOME + "/.local/bin:" + HOME + "/bin", TermuxShellEnvironment.userBinPath(""));
    }

    @Test public void appendedDirectoriesAreBounded() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) many.append("/d").append(i).append(':');
        assertEquals(32, TermuxShellEnvironment.userBinPath(many.toString()).split(":").length - 1);
    }
}

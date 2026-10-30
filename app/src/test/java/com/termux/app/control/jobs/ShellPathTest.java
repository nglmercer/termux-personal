package com.termux.app.control.jobs;

import static org.junit.Assert.assertEquals;
import com.termux.shared.termux.TermuxConstants;
import org.junit.Test;

public class ShellPathTest {
    private static final String BIN = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;

    @Test public void prefixStaysFirstAndOnlyAbsoluteEntriesSurvive() {
        assertEquals(BIN + ":/home/u/.opencode/bin:/usr/local/bin",
            ShellPath.sanitize("/home/u/.opencode/bin:relative:" + BIN + "::/usr/local/bin:/home/u/.opencode/bin"));
    }

    @Test public void aStartupFileCannotRemoveTheTermuxTools() {
        assertEquals(BIN + ":/only/this", ShellPath.sanitize("/only/this"));
    }
}

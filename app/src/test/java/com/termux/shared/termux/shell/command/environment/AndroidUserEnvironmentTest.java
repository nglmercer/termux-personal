package com.termux.shared.termux.shell.command.environment;

import java.util.HashMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class AndroidUserEnvironmentTest {
    @Test public void derivesAndroidUserFromUidInsteadOfSerial() {
        HashMap<String, String> env = new HashMap<>();
        TermuxShellEnvironment.putAndroidUserId(env, 10123, null);
        assertEquals("0", env.get("TERMUX__USER_ID"));
        env.clear();
        TermuxShellEnvironment.putAndroidUserId(env, 1010123, null);
        assertEquals("10", env.get("TERMUX__USER_ID"));
        env.clear();
        TermuxShellEnvironment.putAndroidUserId(env, -1, null);
        assertFalse(env.containsKey("TERMUX__USER_ID"));
    }
    @Test public void preservesExplicitOverrides() {
        HashMap<String, String> env = new HashMap<>();
        TermuxShellEnvironment.putAndroidUserId(env, 1010123, "42");
        assertEquals("42", env.get("TERMUX__USER_ID"));
        TermuxShellEnvironment.putAndroidUserId(env, 1010123, "77");
        assertEquals("42", env.get("TERMUX__USER_ID"));
    }
}

package com.termux.shared.termux.shell.am;

import com.termux.shared.errors.Error;
import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxAmSocketServerPrivacyTest {
    @Test public void notificationSummaryOmitsErrorMessagesAndExceptionContents() {
        String secret = "https://example.com/?code=private-token";
        Error error = new Error("private-" + secret, 123, "request " + secret, new IllegalStateException(secret));
        String summary = TermuxAmSocketServer.safeErrorSummary(error);
        assertTrue(summary.contains("123"));
        assertFalse(summary.contains(secret));
        assertFalse(summary.contains("private"));
    }
}

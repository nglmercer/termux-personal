package com.termux.app.adb;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbEndpointTest {
    @Test public void validatesEndpointsWithoutDnsOrShellSyntax() {
        assertTrue(AdbEndpoint.valid("192.168.1.2:45678"));
        assertTrue(AdbEndpoint.valid("[::1]:1234"));
        assertFalse(AdbEndpoint.valid("example.com:4444"));
        assertFalse(AdbEndpoint.valid("192.168.1.256:123"));
        assertFalse(AdbEndpoint.valid("1.2.3.4:0"));
        assertFalse(AdbEndpoint.valid("1.2.3.4:65536"));
        assertFalse(AdbEndpoint.valid("1.2.3.4:123;id"));
        assertFalse(AdbEndpoint.valid(" 1.2.3.4:123"));
        assertFalse(AdbEndpoint.valid("[fe80::1%wlan0]:123"));
    }
    @Test public void acceptsOnlySixDigitPairingCode() {
        assertTrue(AdbEndpoint.validPairingCode("012345"));
        assertFalse(AdbEndpoint.validPairingCode("12345"));
        assertFalse(AdbEndpoint.validPairingCode("12345\n"));
        assertFalse(AdbEndpoint.validPairingCode(null));
    }
}

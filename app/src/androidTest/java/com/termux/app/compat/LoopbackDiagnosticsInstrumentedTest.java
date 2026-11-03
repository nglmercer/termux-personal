package com.termux.app.compat;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.termux.app.compat.network.LoopbackDiagnostics;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real on-device loopback test; it does not test an external browser or CLI callback. */
@RunWith(AndroidJUnit4.class)
public class LoopbackDiagnosticsInstrumentedTest {
    @Test public void testsLocalListeners() {
        LoopbackDiagnostics.Result result = LoopbackDiagnostics.probe();
        Log.i("CompatLoopbackTest", "IPv4=" + result.ipv4 + " IPv6=" + result.ipv6);
        assertEquals(LoopbackDiagnostics.Status.AVAILABLE, result.ipv4);
        assertNotNull(result.ipv6);
    }
}

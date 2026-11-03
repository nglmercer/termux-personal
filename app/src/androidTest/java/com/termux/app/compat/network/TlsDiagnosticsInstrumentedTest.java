package com.termux.app.compat.network;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

/** Exercises the API 21-compatible classification path on the installed device. */
@RunWith(AndroidJUnit4.class)
public class TlsDiagnosticsInstrumentedTest {
    @Test public void recognizesTypedPathAndValidityFailures() {
        SSLHandshakeException handshake = new SSLHandshakeException("not logged");
        handshake.initCause(new CertPathValidatorException("not logged"));
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, NetworkErrorClassifier.classify(handshake));
        assertEquals(TlsDiagnostics.Detail.CERTIFICATE_PATH_INVALID, TlsDiagnostics.classify(handshake, false));
        assertEquals(TlsDiagnostics.Detail.EXPIRED, TlsDiagnostics.classify(new CertificateExpiredException(), false));
    }    @Test public void api24ReasonIsUsedOnlyOnSupportedDevices() {
        if (android.os.Build.VERSION.SDK_INT >= 24) Api24.check();
    }
    private static final class Api24 {
        static void check() {
            CertPathValidatorException error = new CertPathValidatorException("not logged", null, null, -1,
                CertPathValidatorException.BasicReason.NOT_YET_VALID);
            assertEquals(TlsDiagnostics.Detail.NOT_YET_VALID, TlsDiagnostics.classify(error, true));
        }
    }

}

package com.termux.app.compat.network;

import org.junit.Test;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import static org.junit.Assert.*;

public class TlsDiagnosticsTest {
    private SSLHandshakeException wrap(Throwable cause) {
        SSLHandshakeException e = new SSLHandshakeException("sensitive authorization data");
        e.initCause(cause);
        return e;
    }
    @Test public void classifiesCertPathFailuresAndValidityWithoutMessages() {
        SSLHandshakeException untrusted = wrap(new CertPathValidatorException("secret trust anchor"));
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, NetworkErrorClassifier.classify(untrusted));
        assertEquals(TlsDiagnostics.Detail.CERTIFICATE_PATH_INVALID, TlsDiagnostics.classify(untrusted, false));
        assertEquals(TlsDiagnostics.Detail.EXPIRED, TlsDiagnostics.classify(wrap(new CertificateExpiredException("secret")), false));
        assertEquals(TlsDiagnostics.Detail.NOT_YET_VALID, TlsDiagnostics.classify(wrap(new CertificateNotYetValidException("secret")), false));
        assertEquals(TlsDiagnostics.Detail.PEER_UNVERIFIED, TlsDiagnostics.classify(new SSLPeerUnverifiedException("secret"), false));
        assertEquals(TlsDiagnostics.Detail.CERTIFICATE_ERROR, TlsDiagnostics.classify(new CertificateException("secret"), false));
    }
    @Test public void usesApi24ReasonOnlyWhenAvailable() {
        CertPathValidatorException expired = new CertPathValidatorException("secret", null, null, -1, CertPathValidatorException.BasicReason.EXPIRED);
        assertEquals(TlsDiagnostics.Detail.CERTIFICATE_PATH_INVALID, TlsDiagnostics.classify(expired, false));
        assertEquals(TlsDiagnostics.Detail.EXPIRED, TlsDiagnostics.classify(expired, true));
        CertPathValidatorException future = new CertPathValidatorException("secret", null, null, -1, CertPathValidatorException.BasicReason.NOT_YET_VALID);
        assertEquals(TlsDiagnostics.Detail.NOT_YET_VALID, TlsDiagnostics.classify(future, true));
    }
    @Test public void conflictingValidityEvidenceIsNotReducedToOneDiagnosis() {
        CertificateExpiredException expired = new CertificateExpiredException("secret");
        expired.initCause(new CertificateNotYetValidException("secret"));
        assertEquals(TlsDiagnostics.Detail.CONFLICTING_VALIDITY, TlsDiagnostics.classify(wrap(expired), false));
    }
    @Test public void reportsValidityAsPotentialClockIssueNotProvenClockFailure() {
        NetworkCompatibilityReport report = new NetworkCompatibilityReport("resolved", NetworkCompatibilityReport.DnsStatus.RESOLVED,
            NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, 0, NetworkCompatibilityReport.FamilyStatus.NOT_TESTED,
            NetworkCompatibilityReport.FamilyStatus.NOT_TESTED, TlsDiagnostics.Detail.EXPIRED);
        assertTrue(report.describe().contains("clock has not been independently verified"));
        assertFalse(report.describe().contains("secret"));
    }
}

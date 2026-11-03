package com.termux.app.compat.network;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateExpiredException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

public class NetworkErrorClassifierTest {
    @Test public void classifiesWithoutExposingMessagesOrGuessingConnectionCause() {
        assertEquals(NetworkCompatibilityReport.Status.DNS_FAILURE, NetworkErrorClassifier.classify(new UnknownHostException("secret")));
        assertEquals(NetworkCompatibilityReport.Status.TIMEOUT, NetworkErrorClassifier.classify(new SocketTimeoutException()));
        assertEquals(NetworkCompatibilityReport.Status.UNKNOWN, NetworkErrorClassifier.classify(new ConnectException("refused")));
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, NetworkErrorClassifier.classify(new SSLPeerUnverifiedException("secret")));
        SSLHandshakeException tls = new SSLHandshakeException("secret");
        assertEquals(NetworkCompatibilityReport.Status.TLS_HANDSHAKE_FAILURE, NetworkErrorClassifier.classify(tls));
        tls.initCause(new CertificateExpiredException());
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, NetworkErrorClassifier.classify(tls));
    }
    @Test public void httpErrorsRemainResponses() {
        String report = new NetworkCompatibilityReport("resolved", NetworkCompatibilityReport.Status.HTTP_RESPONSE, 401).describe();
        assertTrue(report.contains("HTTP 401"));
        assertFalse(report.contains("secret"));
        assertTrue(report.contains("not tested"));
    }
}

package com.termux.app.compat.network;

import android.system.ErrnoException;
import android.system.OsConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class AndroidNetworkErrorClassifierTest {
    private ConnectException error(int errno) {
        ConnectException error = new ConnectException("private address");
        error.initCause(new ErrnoException("connect", errno));
        return error;
    }
    @Test public void structuredRefusalIsDistinctFromOtherConnectionErrors() throws Exception {
        assertEquals(NetworkCompatibilityReport.Status.CONNECTION_REFUSED, NetworkErrorClassifier.classify(error(OsConstants.ECONNREFUSED)));
        assertEquals(NetworkCompatibilityReport.Status.UNKNOWN, NetworkErrorClassifier.classify(error(OsConstants.ENETUNREACH)));
        assertEquals(NetworkCompatibilityReport.Status.UNKNOWN, NetworkErrorClassifier.classify(new ConnectException("Connection refused")));
        Socket refused = new Socket() {
            @Override public void connect(SocketAddress address, int timeout) throws ConnectException { throw error(OsConstants.ECONNREFUSED); }
        };
        assertEquals(NetworkCompatibilityReport.FamilyStatus.CONNECTION_REFUSED,
            TcpSample.connect(refused, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 443, 50));
    }
    @Test public void tlsAndTimeoutEvidenceRemainsSeparate() {
        SSLHandshakeException handshake = new SSLHandshakeException("private");
        handshake.initCause(error(OsConstants.ECONNREFUSED));
        assertEquals(NetworkCompatibilityReport.Status.TLS_HANDSHAKE_FAILURE, NetworkErrorClassifier.classify(handshake));
        ConnectException wrapper = new ConnectException("private");
        wrapper.initCause(new SocketTimeoutException("private"));
        assertEquals(NetworkCompatibilityReport.Status.TIMEOUT, NetworkErrorClassifier.classify(wrapper));
        NetworkCompatibilityReport report = new NetworkCompatibilityReport("not tested", NetworkCompatibilityReport.Status.CONNECTION_REFUSED, 0);
        assertTrue(report.describe().contains("server or a configured proxy"));
        assertFalse(report.describe().contains("private"));
    }
}

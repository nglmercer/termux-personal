package com.termux.app.compat.network;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import static org.junit.Assert.*;

/** Real local socket mechanics only; this does not test OEM, VPN or internet reachability. */
@RunWith(AndroidJUnit4.class)
public class TcpSampleInstrumentedTest {
    @Test public void sampleConnectsWithoutSendingApplicationData() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress(loopback, 0));
            listener.setSoTimeout(2000);
            assertEquals(NetworkCompatibilityReport.FamilyStatus.TCP_CONNECTED,
                TcpSample.connect(new Socket(), loopback, listener.getLocalPort(), 2000));
            try (Socket accepted = listener.accept()) {
                accepted.setSoTimeout(2000);
                assertEquals(-1, accepted.getInputStream().read());
            }
        }
    }
}

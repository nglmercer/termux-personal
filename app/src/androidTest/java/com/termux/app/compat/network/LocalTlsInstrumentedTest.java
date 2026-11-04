package com.termux.app.compat.network;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real local TLS handshakes with test-only keys and explicitly isolated trust stores. */
@RunWith(AndroidJUnit4.class)
public class LocalTlsInstrumentedTest {
    private static byte[] asset(String name) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("compat-local-tls/" + name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024];
            for (int n; (n = in.read(bytes)) != -1; ) out.write(bytes, 0, n);
            return out.toByteArray();
        }
    }

    private static Certificate certificate(String name) throws Exception {
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("compat-local-tls/" + name)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static SSLContext serverContext(Certificate cert) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(asset("test-key.pk8")));
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        char[] password = "test-only".toCharArray();
        keys.setKeyEntry("test", key, password, new Certificate[]{cert});
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientContext(Certificate trusted) throws Exception {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        anchors.setCertificateEntry("test", trusted);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(anchors);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static NetworkCompatibilityReport probe(SSLContext serverContext, SSLContext clientContext) throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket()) {
            server.bind(new InetSocketAddress(loopback, 0));
            server.setSoTimeout(10000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicBoolean accepted = new AtomicBoolean();
            try {
                Future<?> served = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        accepted.set(true);
                        socket.setSoTimeout(8000);
                        InputStream in = socket.getInputStream();
                        int sequence = 0;
                        for (int i = 0; i < 8192 && sequence < 4; i++) {
                            int c = in.read();
                            if (c < 0) return;
                            int expected = (sequence == 0 || sequence == 2) ? '\r' : '\n';
                            sequence = c == expected ? sequence + 1 : (c == '\r' ? 1 : 0);
                        }
                        if (sequence == 4) {
                            socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    } catch (java.io.IOException ignored) { /* Untrusted client aborts its handshake. */ }
                });
                NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
                    @Override public InetAddress[] resolve() { return new InetAddress[]{loopback}; }
                    @Override public boolean supportsCertificateReason() { return android.os.Build.VERSION.SDK_INT >= 24; }
                    @Override public HttpURLConnection open() throws java.io.IOException {
                        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.getLocalPort() + "/").openConnection(Proxy.NO_PROXY);
                        connection.setSSLSocketFactory(clientContext.getSocketFactory());
                        return connection;
                    }
                };
                NetworkCompatibilityReport report = NetworkProbeRunner.run(backend, new AtomicBoolean(), 8000);
                served.get(12, TimeUnit.SECONDS);
                assertTrue("The local TLS server must accept the connection", accepted.get());
                return report;
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void distinguishesTrustedHttp403FromUntrustedCertificate() throws Exception {
        Certificate serverCert = certificate("test-cert.der");
        SSLContext server = serverContext(serverCert);
        NetworkCompatibilityReport trusted = probe(server, clientContext(serverCert));
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, trusted.status);
        assertEquals(403, trusted.httpStatus);
        NetworkCompatibilityReport untrusted = probe(server, clientContext(certificate("unrelated-cert.der")));
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, untrusted.status);
        assertNotEquals(TlsDiagnostics.Detail.NOT_IDENTIFIED, untrusted.tlsDetail);
    }
}

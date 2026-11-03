package com.termux.app.compat.network;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real host TLS handshakes against a private local server, with explicit test-only trust stores. */
public class LocalTlsProbeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final char[] PASSWORD = "changeit".toCharArray();

    private KeyStore newKeyStore(String name) throws Exception {
        File keytool = new File(System.getProperty("java.home"), "bin/keytool");
        Assume.assumeTrue("JDK keytool is required for the local TLS fixture", keytool.isFile() && keytool.canExecute());
        File path = new File(temp.getRoot(), name + ".p12");
        Process generator = new ProcessBuilder(keytool.getAbsolutePath(), "-genkeypair", "-alias", "test",
            "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", path.getAbsolutePath(),
            "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=localhost", "-validity", "2",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt")
            .redirectErrorStream(true).redirectOutput(new File(temp.getRoot(), name + ".log")).start();
        try {
            assertTrue("certificate fixture timed out", generator.waitFor(20, TimeUnit.SECONDS));
            assertEquals("certificate fixture generation failed", 0, generator.exitValue());
        } finally { generator.destroy(); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(path)) { keys.load(in, PASSWORD); }
        return keys;
    }
    private SSLContext serverContext(KeyStore keys) throws Exception {
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm(), "SunJSSE");
        manager.init(keys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS", "SunJSSE");
        context.init(manager.getKeyManagers(), null, null);
        return context;
    }
    private SSLContext clientContext(KeyStore trusted) throws Exception {
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, PASSWORD);
        anchors.setCertificateEntry("test", trusted.getCertificate("test"));
        TrustManagerFactory manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), "SunJSSE");
        manager.init(anchors);
        SSLContext context = SSLContext.getInstance("TLS", "SunJSSE");
        context.init(null, manager.getTrustManagers(), null);
        return context;
    }
    private NetworkCompatibilityReport run(SSLContext serverContext, SSLContext clientContext) throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket()) {
            server.bind(new InetSocketAddress(loopback, 0));
            server.setSoTimeout(5000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            AtomicBoolean accepted = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger receivedBytes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> serverError = new java.util.concurrent.atomic.AtomicReference<>();
            try {
                Future<?> served = worker.submit(() -> {
                    try (Socket socket = server.accept()) {
                        accepted.set(true);
                        socket.setSoTimeout(12000);
                        InputStream in = socket.getInputStream();
                        int sequence = 0;
                        for (int i = 0; i < 8192 && sequence < 4; i++) {
                            int c = in.read();
                            if (c < 0) return;
                            receivedBytes.incrementAndGet();
                            int expected = (sequence == 0 || sequence == 2) ? '\r' : '\n';
                            sequence = c == expected ? sequence + 1 : (c == '\r' ? 1 : 0);
                        }
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    } catch (java.io.IOException e) { serverError.set(e.getClass().getSimpleName() + ": " + e.getMessage()); }
                });
                NetworkProbeRunner.Backend backend = new NetworkProbeRunner.Backend() {
                    @Override public InetAddress[] resolve() { return new InetAddress[]{loopback}; }
                    @Override public boolean supportsCertificateReason() { return true; }
                    @Override public HttpURLConnection open() throws java.io.IOException {
                        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.getLocalPort() + "/").openConnection(Proxy.NO_PROXY);
                        connection.setSSLSocketFactory(clientContext.getSocketFactory());
                        return connection;
                    }
                };
                NetworkCompatibilityReport result = NetworkProbeRunner.run(backend, new AtomicBoolean(), 10000);
                served.get(14, TimeUnit.SECONDS);
                assertTrue("local TLS server did not accept a connection", accepted.get());
                if (result.status == NetworkCompatibilityReport.Status.TIMEOUT)
                    throw new AssertionError("Local TLS timeout; server error=" + serverError.get() + ", request bytes=" + receivedBytes.get());
                return result;
            } finally { worker.shutdownNow(); }
        }
    }
    @Test public void distinguishesHttpResponseFromUntrustedCertificate() throws Exception {
        File javaExecutable = new File(System.getProperty("java.home"), "bin/java");
        File keytool = new File(System.getProperty("java.home"), "bin/keytool");
        Assume.assumeTrue("JDK tools are required for the local TLS fixture", javaExecutable.canExecute() && keytool.canExecute());
        java.util.LinkedHashSet<String> classpath = new java.util.LinkedHashSet<>();
        for (Class<?> type : new Class<?>[]{LocalTlsProbeTest.class, NetworkProbeRunner.class, org.junit.Assert.class, org.hamcrest.Matcher.class})
            classpath.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        StringBuilder path = new StringBuilder();
        for (String item : classpath) { if (path.length() != 0) path.append(File.pathSeparator); path.append(item); }
        File log = new File(temp.getRoot(), "tls-child.log");
        ProcessBuilder builder = new ProcessBuilder(javaExecutable.getAbsolutePath(), "-cp", path.toString(), LocalTlsProbeTest.class.getName());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Process child = builder.redirectErrorStream(true).redirectOutput(log).start();
        try {
            assertTrue("local TLS fixture timed out", child.waitFor(45, TimeUnit.SECONDS));
            if (child.exitValue() != 0) {
                byte[] details = java.nio.file.Files.readAllBytes(log.toPath());
                throw new AssertionError("local TLS fixture failed: " + new String(details, 0, Math.min(details.length, 4096), StandardCharsets.UTF_8));
            }
        } finally { child.destroy(); }
    }
    public static void main(String[] args) throws Exception {
        LocalTlsProbeTest fixture = new LocalTlsProbeTest();
        fixture.temp.create();
        try { fixture.runTlsFixture(); }
        finally { fixture.temp.delete(); }
    }
    private void runTlsFixture() throws Exception {
        KeyStore serverKeys = newKeyStore("server"), unrelatedKeys = newKeyStore("unrelated");
        SSLContext server = serverContext(serverKeys);
        NetworkCompatibilityReport trusted = run(server, clientContext(serverKeys));
        assertEquals(NetworkCompatibilityReport.Status.HTTP_RESPONSE, trusted.status);
        assertEquals(403, trusted.httpStatus);
        assertEquals(TlsDiagnostics.Detail.NOT_IDENTIFIED, trusted.tlsDetail);
        NetworkCompatibilityReport untrusted = run(server, clientContext(unrelatedKeys));
        assertEquals(NetworkCompatibilityReport.Status.TLS_CERTIFICATE_FAILURE, untrusted.status);
        assertNotEquals(TlsDiagnostics.Detail.NOT_IDENTIFIED, untrusted.tlsDetail);
        assertFalse(untrusted.describe().contains("BEGIN CERTIFICATE"));
    }
}

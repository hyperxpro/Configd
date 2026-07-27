package io.configd.client.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Client-side TLS setup for edge plane: single place the TLS recipe lives. Builds TLSv1.3-only SSLContext,
 * verifies server endpoint (hostname + SAN). Two postures: mutualTls (client cert) or trustOnly (server-only).
 * Always HTTPS endpoint identification. Never downgrades to plaintext.
 */
public final class ClientTls {

    private static final String[] PROTOCOLS = {"TLSv1.3"};
    private static final String[] CIPHERS = {"TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"};

    private final SSLContext context;
    private final Instant clientCertNotAfter;

    private ClientTls(SSLContext context, Instant clientCertNotAfter) {
        this.context = context;
        this.clientCertNotAfter = clientCertNotAfter;
    }

    public static ClientTls mutualTls(Path keyStorePath, char[] keyStorePassword,
                                      Path trustStorePath, char[] trustStorePassword)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(keyStorePath, "keyStorePath");
        Objects.requireNonNull(trustStorePath, "trustStorePath");
        KeyStore keyStore = loadPkcs12(keyStorePath, keyStorePassword);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyStorePassword == null ? new char[0] : keyStorePassword);
        SSLContext ctx = buildContext(kmf.getKeyManagers(), trustStorePath, trustStorePassword);
        return new ClientTls(ctx, earliestNotAfter(keyStore));
    }

    public static ClientTls trustOnly(Path trustStorePath, char[] trustStorePassword)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(trustStorePath, "trustStorePath");
        SSLContext ctx = buildContext(null, trustStorePath, trustStorePassword);
        return new ClientTls(ctx, null);
    }

    public SSLSocket connect(String host, int port, int connectTimeoutMs, int handshakeTimeoutMs)
            throws IOException {
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        boolean handshook = false;
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setEnabledProtocols(PROTOCOLS);
            socket.setEnabledCipherSuites(CIPHERS);
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            List<SNIServerName> sni = sniFor(host);
            if (!sni.isEmpty()) {
                params.setServerNames(sni);
            }
            socket.setSSLParameters(params);
            // Bound handshake so slow-loris server that never completes times out. No app bytes before
            // startHandshake, so nothing pre-handshake to interpret as frame (see libpq CVE-2021-23214/23222).
            socket.setSoTimeout(handshakeTimeoutMs);
            socket.startHandshake();
            handshook = true;
            return socket;
        } finally {
            if (!handshook) {
                closeQuietly(socket);
            }
        }
    }

    /**
     * Underlying SSLContext for transports that manage their own sockets (HTTP plane's JDK HttpClient).
     * Pair with httpsParameters() to keep same TLS profile as connect().
     */
    public SSLContext sslContext() {
        return context;
    }

    /**
     * Frozen TLS parameters for HTTP plane: TLSv1.3 + HTTPS endpoint identification.
     * Fresh instance per call (SSLParameters is mutable). JDK HttpClient derives SNI from request URI host.
     */
    public SSLParameters httpsParameters() {
        SSLParameters params = new SSLParameters();
        params.setProtocols(PROTOCOLS);
        params.setCipherSuites(CIPHERS);
        params.setEndpointIdentificationAlgorithm("HTTPS");
        return params;
    }

    public Optional<Instant> clientCertNotAfter() {
        return Optional.ofNullable(clientCertNotAfter);
    }

    public boolean hasClientCertificate() {
        return clientCertNotAfter != null;
    }

    // -----------------------------------------------------------------------

    private static SSLContext buildContext(KeyManager[] keyManagers, Path trustStorePath,
                                           char[] trustStorePassword)
            throws GeneralSecurityException, IOException {
        KeyStore trustStore = loadPkcs12(trustStorePath, trustStorePassword);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(keyManagers, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadPkcs12(Path path, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }

    private static Instant earliestNotAfter(KeyStore keyStore) throws GeneralSecurityException {
        Instant earliest = null;
        for (Enumeration<String> aliases = keyStore.aliases(); aliases.hasMoreElements(); ) {
            String alias = aliases.nextElement();
            if (keyStore.getCertificate(alias) instanceof X509Certificate x509) {
                Instant notAfter = x509.getNotAfter().toInstant();
                if (earliest == null || notAfter.isBefore(earliest)) {
                    earliest = notAfter;
                }
            }
        }
        return earliest;
    }

    private static List<SNIServerName> sniFor(String host) {
        if (host == null || host.isEmpty() || isIpLiteral(host)) {
            return List.of();
        }
        return List.of(new SNIHostName(host));
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static void closeQuietly(SSLSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort cleanup on a failed connect/handshake
        }
    }
}

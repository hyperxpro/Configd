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
 * The client-side TLS setup for the edge plane — the one place the §06 F9 recipe lives. It builds a
 * TLSv1.3-only {@link SSLContext} (the two AEAD suites) from PKCS12 key/trust material and creates a client
 * {@link SSLSocket} that verifies the <b>server</b> endpoint: an unconnected socket → a bounded connect by
 * <b>hostname</b> → the TLSv1.3 profile → {@code setEndpointIdentificationAlgorithm("HTTPS")} with the host
 * supplied as an SNI name so the server certificate's SAN must cover the host it connected to (F9-4) →
 * a bounded handshake. A trusted CA alone is insufficient without endpoint identification, so it is always on.
 *
 * <p>Two postures: {@link #mutualTls} (a client certificate — the mTLS edge authentication, §03 AU3-2) and
 * {@link #trustOnly} (verify the server only — a certificate-less token/basic client authenticates with an
 * {@code AUTH} frame instead). Either way the server is always verified. This client never downgrades to
 * plaintext (§06 F9-1); a plaintext connection is a separate, test-only path outside this class.
 */
public final class ClientTls {

    /** The frozen TLSv1.3 profile (§06 F9-2), matching the server's {@code TlsConfig}. */
    private static final String[] PROTOCOLS = {"TLSv1.3"};
    private static final String[] CIPHERS = {"TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"};

    private final SSLContext context;
    private final Instant clientCertNotAfter; // nullable: present only for a mTLS keystore

    private ClientTls(SSLContext context, Instant clientCertNotAfter) {
        this.context = context;
        this.clientCertNotAfter = clientCertNotAfter;
    }

    /**
     * An mTLS client: presents a client certificate from {@code keyStorePath} and verifies the server against
     * {@code trustStorePath}. The client leaf's {@code notAfter} is captured for the cert lead-time reconnect
     * (§03 AU5-6).
     */
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

    /**
     * A trust-only client: verifies the server against {@code trustStorePath} but presents no client
     * certificate. Used by a token/basic edge client (it authenticates with an {@code AUTH} frame).
     */
    public static ClientTls trustOnly(Path trustStorePath, char[] trustStorePassword)
            throws GeneralSecurityException, IOException {
        Objects.requireNonNull(trustStorePath, "trustStorePath");
        SSLContext ctx = buildContext(null, trustStorePath, trustStorePassword);
        return new ClientTls(ctx, null);
    }

    /**
     * Connects to {@code host:port} and completes the TLS handshake, returning a ready {@link SSLSocket}. The
     * connect and handshake are each bounded; the socket verifies the server endpoint ({@code HTTPS}) against
     * {@code host}. On return the caller owns the socket (and resets its {@code SO_TIMEOUT} for the read loop).
     *
     * @throws IOException if the connect times out, the handshake fails (including endpoint-identity /
     *                     SAN-mismatch), or the handshake times out
     */
    public SSLSocket connect(String host, int port, int connectTimeoutMs, int handshakeTimeoutMs)
            throws IOException {
        SSLSocketFactory factory = context.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        boolean handshook = false;
        try {
            // Connect by hostname (not a pre-resolved address) so the HTTPS endpoint check has a name to
            // match against the server certificate's SAN (F9-4).
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
            // Bound the handshake so a slow-loris server that never completes it times out rather than
            // parking this reader (§06 F9). No application bytes are read before startHandshake returns, so
            // there is nothing pre-handshake to interpret as a frame (the libpq CVE-2021-23214/23222 lesson).
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
     * The underlying {@link SSLContext} for a transport that manages its own sockets — notably the HTTP plane's
     * JDK {@code HttpClient}, which takes an {@code SSLContext} + {@link SSLParameters} rather than an
     * {@code SSLSocket}. The context carries the same trust material (and, for an mTLS client, the client
     * key manager) as {@link #connect}; pair it with {@link #httpsParameters()} to keep the F9 profile.
     */
    public SSLContext sslContext() {
        return context;
    }

    /**
     * The frozen F9 TLS parameters for the HTTP plane: TLSv1.3 only, with {@code HTTPS} endpoint identification
     * (hostname/SAN verification against the request host). A fresh instance per call — {@link SSLParameters} is
     * mutable and must not be shared. The JDK {@code HttpClient} derives SNI from the request URI host, so
     * server names are not set here (unlike {@link #connect}, which owns the socket and sets SNI explicitly).
     */
    public SSLParameters httpsParameters() {
        SSLParameters params = new SSLParameters();
        params.setProtocols(PROTOCOLS);
        params.setCipherSuites(CIPHERS);
        params.setEndpointIdentificationAlgorithm("HTTPS");
        return params;
    }

    /** The client certificate's {@code notAfter}, when this is an mTLS client — for the cert lead-time reconnect. */
    public Optional<Instant> clientCertNotAfter() {
        return Optional.ofNullable(clientCertNotAfter);
    }

    /** True iff this client presents a client certificate (the mTLS posture). */
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

    /** The earliest {@code notAfter} across the keystore's certificate entries (the binding constraint). */
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

    /** An SNI list for a DNS host; empty for an IP literal (SNI names must not be IP addresses). */
    private static List<SNIServerName> sniFor(String host) {
        if (host == null || host.isEmpty() || isIpLiteral(host)) {
            return List.of();
        }
        return List.of(new SNIHostName(host));
    }

    private static boolean isIpLiteral(String host) {
        // A colon means IPv6; otherwise treat all-digits-and-dots as IPv4. Anything else is a DNS name.
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

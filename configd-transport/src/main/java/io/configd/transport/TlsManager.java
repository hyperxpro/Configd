package io.configd.transport;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;

/**
 * Manages TLS context creation and rotation for Raft transport.
 * <p>
 * Loads certificates and keys from the paths specified in {@link TlsConfig},
 * builds a JDK {@link SSLContext} with TLSv1.3, and supports hot-reload
 * via {@link #reload()} for certificate rotation without restart.
 * <p>
 * Thread safety: the current {@link SSLContext} is published via a volatile
 * field, so callers on any thread see the latest context after a reload.
 */
public final class TlsManager {

    private final TlsConfig config;
    private volatile SSLContext currentContext;

    /**
     * Creates a TLS manager and immediately builds the initial {@link SSLContext}.
     *
     * @param config TLS configuration (non-null)
     * @throws GeneralSecurityException if key/trust material cannot be loaded
     * @throws IOException              if cert/key files cannot be read
     */
    public TlsManager(TlsConfig config) throws GeneralSecurityException, IOException {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.currentContext = createSslContext();
    }

    /**
     * Builds a new {@link SSLContext} from the configured cert/key/trust paths.
     * <p>
     * The keystore is loaded from a PKCS12 file at {@code keyPath}. The trust
     * store is loaded from a PKCS12 file at {@code trustStorePath}. Both use
     * the store password from config. The SSLContext is initialized with TLSv1.3.
     *
     * @return a freshly built SSLContext
     * @throws GeneralSecurityException if cryptographic operations fail
     * @throws IOException              if files cannot be read
     */
    public SSLContext createSslContext() throws GeneralSecurityException, IOException {
        char[] password = config.storePassword();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream kis = Files.newInputStream(config.keyPath())) {
            keyStore.load(kis, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream tis = Files.newInputStream(config.trustStorePath())) {
            trustStore.load(tis, password);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * Re-reads certificate files from disk and rebuilds the {@link SSLContext}.
     * After this call, {@link #currentContext()} returns the new context.
     *
     * @throws GeneralSecurityException if key/trust material cannot be loaded
     * @throws IOException              if cert/key files cannot be read
     */
    public void reload() throws GeneralSecurityException, IOException {
        this.currentContext = createSslContext();
    }

    /**
     * Returns the current {@link SSLContext}. This is safe to call from any
     * thread; the volatile field ensures visibility of the latest reload.
     *
     * @return the current SSLContext (never null)
     */
    public SSLContext currentContext() {
        return currentContext;
    }

    /**
     * Returns the TLS configuration used by this manager.
     *
     * @return the TlsConfig (never null)
     */
    public TlsConfig config() {
        return config;
    }
}

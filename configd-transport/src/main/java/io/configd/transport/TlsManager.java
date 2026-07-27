package io.configd.transport;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <b>Separate peer trust anchor.</b> Optionally, a <b>distinct</b> trust store may be supplied for the
 * Raft interior (etcd {@code --peer-trusted-ca-file} / ZooKeeper {@code ssl.quorum.trustStore}). When
 * present, {@link #peerContext()} is built from the node's own key material but that <b>peer</b> trust
 * store, so a client certificate that does not chain to the peer CA cannot complete the peer handshake -
 * structurally stronger than a marker match on a shared CA. The Raft transports use {@link #peerContext()};
 * the edge/client plane keeps using {@link #currentContext()}. When no peer trust store is supplied,
 * {@link #peerContext()} is the same context as {@link #currentContext()} (byte-identical to the prior
 * single-trust-store behavior).
 * <p>
 * Thread safety: the current {@link SSLContext}s are published via volatile
 * fields, so callers on any thread see the latest context after a reload.
 */
public final class TlsManager {

    private final TlsConfig config;
    /** Optional distinct trust store for the Raft interior; null = share {@link #config}'s trust store. */
    private final Path peerTrustStorePath;
    private final char[] peerTrustStorePassword;
    private volatile SSLContext currentContext;
    private volatile SSLContext peerContext;

    /**
     * Creates a TLS manager and immediately builds the initial {@link SSLContext}. No separate peer trust
     * anchor: {@link #peerContext()} equals {@link #currentContext()}.
     *
     * @throws GeneralSecurityException if key/trust material cannot be loaded
     * @throws IOException              if cert/key files cannot be read
     */
    public TlsManager(TlsConfig config) throws GeneralSecurityException, IOException {
        this(config, null, null);
    }

    /**
     * Creates a TLS manager with an optional distinct trust store for the Raft interior (a separate peer
     * CA). When {@code peerTrustStorePath} is non-null, {@link #peerContext()} is built from the node's
     * own key material plus that trust store; when null, {@link #peerContext()} is the same context as
     * {@link #currentContext()} (byte-identical to the single-arg constructor).
     *
     * @throws GeneralSecurityException if key/trust material cannot be loaded
     * @throws IOException              if cert/key/trust files cannot be read
     */
    public TlsManager(TlsConfig config, Path peerTrustStorePath, char[] peerTrustStorePassword)
            throws GeneralSecurityException, IOException {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.peerTrustStorePath = peerTrustStorePath;
        this.peerTrustStorePassword = peerTrustStorePassword != null
                ? peerTrustStorePassword.clone() : null;
        this.currentContext = createSslContext();
        this.peerContext = peerTrustStorePath == null ? this.currentContext : createPeerSslContext();
    }

    /**
     * Builds a new {@link SSLContext} from the configured cert/key/trust paths.
     * <p>
     * The keystore is loaded from a PKCS12 file at {@code keyPath}. The trust
     * store is loaded from a PKCS12 file at {@code trustStorePath}. Both use
     * the store password from config. The SSLContext is initialized with TLSv1.3.
     *
     * @throws GeneralSecurityException if cryptographic operations fail
     * @throws IOException              if files cannot be read
     */
    public SSLContext createSslContext() throws GeneralSecurityException, IOException {
        return buildContext(config.trustStorePath(), config.storePassword());
    }

    /**
     * Builds the Raft-interior {@link SSLContext}: the node's own key material (from {@code config}) plus
     * the <b>separate</b> peer trust store. Only called when a peer trust store was supplied.
     */
    private SSLContext createPeerSslContext() throws GeneralSecurityException, IOException {
        char[] trustPassword = peerTrustStorePassword != null ? peerTrustStorePassword : config.storePassword();
        return buildContext(peerTrustStorePath, trustPassword);
    }

    /**
     * Builds an {@link SSLContext} from {@code config}'s key material and the given trust store. Shared by
     * the client/edge context ({@code config.trustStorePath()}) and the peer context ({@code peerTrustStorePath}).
     */
    private SSLContext buildContext(Path trustStorePath, char[] trustPassword)
            throws GeneralSecurityException, IOException {
        char[] keyPassword = config.storePassword();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream kis = Files.newInputStream(config.keyPath())) {
            keyStore.load(kis, keyPassword);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword);

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream tis = Files.newInputStream(trustStorePath)) {
            trustStore.load(tis, trustPassword);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * Re-reads certificate files from disk and rebuilds the {@link SSLContext}(s).
     * After this call, {@link #currentContext()} (and {@link #peerContext()}) return the new context(s).
     *
     * @throws GeneralSecurityException if key/trust material cannot be loaded
     * @throws IOException              if cert/key files cannot be read
     */
    public void reload() throws GeneralSecurityException, IOException {
        SSLContext client = createSslContext();
        this.currentContext = client;
        this.peerContext = peerTrustStorePath == null ? client : createPeerSslContext();
    }

    /**
     * Returns the current {@link SSLContext} (client/edge plane). This is safe to call from any
     * thread; the volatile field ensures visibility of the latest reload.
     *
     * @return the current SSLContext (never null)
     */
    public SSLContext currentContext() {
        return currentContext;
    }

    /**
     * Returns the Raft-interior {@link SSLContext}. Identical to {@link #currentContext()} unless a
     * separate peer trust store was supplied, in which case it trusts the peer CA rather than the
     * client/edge CA. The Raft transports use this so a client certificate that does not chain to the
     * peer CA cannot complete the peer handshake.
     *
     * @return the peer SSLContext (never null)
     */
    public SSLContext peerContext() {
        return peerContext;
    }

    public TlsConfig config() {
        return config;
    }
}

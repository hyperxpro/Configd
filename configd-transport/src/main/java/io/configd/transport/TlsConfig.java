package io.configd.transport;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration for TLS transport security. Supports mTLS with
 * certificate rotation via periodic SslContext rebuild.
 */
public record TlsConfig(
    Path certPath,
    Path keyPath,
    Path trustStorePath,
    boolean requireClientAuth,
    java.util.List<String> ciphers,
    java.util.List<String> protocols,
    char[] storePassword
) {
    public TlsConfig {
        Objects.requireNonNull(certPath, "certPath");
        Objects.requireNonNull(keyPath, "keyPath");
        Objects.requireNonNull(trustStorePath, "trustStorePath");
        if (storePassword == null) {
            storePassword = new char[0];
        }
    }

    /**
     * Creates a TlsConfig with an empty store password.
     */
    public TlsConfig(Path certPath, Path keyPath, Path trustStorePath,
                     boolean requireClientAuth,
                     java.util.List<String> ciphers,
                     java.util.List<String> protocols) {
        this(certPath, keyPath, trustStorePath, requireClientAuth,
             ciphers, protocols, new char[0]);
    }

    public static TlsConfig mtls(Path certPath, Path keyPath, Path trustStorePath) {
        return new TlsConfig(certPath, keyPath, trustStorePath, true,
            java.util.List.of("TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"),
            java.util.List.of("TLSv1.3"), new char[0]);
    }
}

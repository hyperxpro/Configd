package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;

/**
 * How the edge client presents its identity — derived from the {@link ConfigdClientConfig}, not asked of the
 * server: there is no negotiation. A driver presents the credential it has and reads the outcome.
 */
public enum AuthMode {

    /** A framed bearer/basic {@code AUTH} credential: the client sends exactly one pre-auth {@code AUTH}. */
    TOKEN,

    /** A client certificate at the TLS handshake: no {@code AUTH} frame is sent. */
    MTLS,

    /** Authentication disabled: the client presents nothing but stays ready. */
    NO_AUTH;

    static AuthMode of(ConfigdClientConfig config) {
        if (config.credentialSource().isPresent()) {
            return TOKEN;
        }
        if (config.tls().map(t -> t.hasClientCertificate()).orElse(false)) {
            return MTLS;
        }
        return NO_AUTH;
    }
}

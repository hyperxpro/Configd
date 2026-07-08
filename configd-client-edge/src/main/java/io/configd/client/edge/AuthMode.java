package io.configd.client.edge;

import io.configd.client.ConfigdClientConfig;

/**
 * How the edge client presents its identity — derived from the {@link ConfigdClientConfig}, not asked of the
 * server (there is no negotiation, §00). A driver presents the credential it has and reads the outcome
 * (§03 AU2-1).
 */
public enum AuthMode {

    /** A framed bearer/basic {@code AUTH} credential: the client sends exactly one pre-auth {@code AUTH}. */
    TOKEN,

    /** A client certificate at the TLS handshake: no {@code AUTH} frame, byte-identical to a pre-auth-arc client. */
    MTLS,

    /** Authentication disabled: the client presents nothing but stays ready to (§03 AU4-3). */
    NO_AUTH;

    /**
     * Resolves the mode from config: a framed credential source ⇒ {@link #TOKEN}; else a client certificate
     * ⇒ {@link #MTLS}; else {@link #NO_AUTH}.
     */
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

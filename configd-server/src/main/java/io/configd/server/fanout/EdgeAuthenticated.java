package io.configd.server.fanout;

import io.configd.common.auth.Principal;

import java.util.Objects;

/**
 * The event the edge auth gate fires down the pipeline once a connection's identity is established -
 * either from a verified client certificate at the TLS handshake or from an accepted {@code AUTH}
 * frame. It carries the verified {@link Principal}; the {@code FanOutConnection} that receives it
 * starts the session bound to {@code principal.id()} (the identity the authorization gate keys on).
 * On a token-authenticated edge the session start is deferred to this event instead of the raw
 * handshake, so no data path opens before the caller has proven who it is.
 */
record EdgeAuthenticated(Principal principal) {

    EdgeAuthenticated {
        Objects.requireNonNull(principal, "principal");
    }
}

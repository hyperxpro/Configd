package io.configd.client.edge.session;

/** Lifecycle states: advance monotonically toward CLOSED (no re-opens; reconnect is a fresh connection). */
public enum EdgeConnectionState {

    CONNECTING,

    TLS_HANDSHAKE,

    AUTHENTICATING,

    AUTHENTICATED,

    CLOSING,

    CLOSED
}

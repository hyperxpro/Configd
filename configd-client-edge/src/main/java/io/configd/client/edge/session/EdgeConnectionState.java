package io.configd.client.edge.session;

/**
 * The {@link EdgeConnection} lifecycle states. A connection advances monotonically toward {@link #CLOSED}; it
 * never re-opens (a reconnect is a fresh connection, §06 F10-1). Steady-state streaming (subscribe / watch) is
 * layered on {@link #AUTHENTICATED} by the later gates.
 */
public enum EdgeConnectionState {

    /** The TCP connect is in progress. */
    CONNECTING,

    /** The TLS/mTLS handshake is in progress (skipped on a test-only plaintext connection). */
    TLS_HANDSHAKE,

    /** Connected and (for mTLS) handshake-authenticated; a token/basic client is presenting its {@code AUTH}. */
    AUTHENTICATING,

    /** The credential is presented (mTLS handshake done, or the {@code AUTH} frame written); ready to operate. */
    AUTHENTICATED,

    /** A close was requested or a terminal error is being delivered. */
    CLOSING,

    /** The socket is closed and the reader thread has stopped. */
    CLOSED
}

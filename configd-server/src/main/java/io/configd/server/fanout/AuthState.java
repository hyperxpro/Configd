package io.configd.server.fanout;

import io.configd.common.auth.Principal;

/**
 * The per-connection authentication state of an edge (M2M) connection - the value both edge transports
 * (the Netty {@code EdgeAuthGateHandler} and the JDK {@code FanOutServer} reader loop) drive. Immutable;
 * each transition installs a new instance. On the Netty transport it lives in a channel attribute so the
 * frame decoder can read it (to apply the pre-auth frame ceiling) and the gate can write it, both on the
 * single event loop; on the JDK transport it is a reader-thread-local.
 *
 * <p>It exists ONLY when token/basic auth is configured for the edge; the mTLS-only / plaintext posture is
 * byte-identical to before and never installs it.
 */
public sealed interface AuthState permits AuthState.Unauthenticated, AuthState.Authenticated {

    /** No credential accepted yet: only an {@code AUTH} frame may be admitted; a pre-auth frame ceiling is in force. */
    AuthState UNAUTHENTICATED = new Unauthenticated();

    /**
     * {@code expiresAtMillis} sentinel for a connection with no active expiry (a cert connection when
     * {@code enforceCertNotAfter} is off - the byte-identical Gate-3 path). No expiry one-shot is armed.
     */
    long NO_EXPIRY = Long.MAX_VALUE;

    static AuthState authenticated(Principal principal, long expiresAtMillis) {
        return new Authenticated(principal, expiresAtMillis);
    }

    default boolean isAuthenticated() {
        return this instanceof Authenticated;
    }

    record Unauthenticated() implements AuthState {
    }

    /**
     * @param principal      the verified caller identity (never carries the credential)
     * @param expiresAtMillis the wall-clock time (ms since epoch) at which the server closes this
     *                        connection for credential expiry, or {@link #NO_EXPIRY} for none. For a
     *                        static token this is {@code now + defaultTokenTtlMs} (server clock, no
     *                        leeway); for an enforced cert it is {@code notAfter + leeway}.
     */
    record Authenticated(Principal principal, long expiresAtMillis) implements AuthState {
    }
}

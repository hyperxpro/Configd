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

    /** {@code expiresAtMillis} sentinel for a credential with no active expiry (the mTLS path in Gate 3). */
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
     * @param expiresAtMillis wall-clock expiry, or {@link #NO_EXPIRY} for none
     */
    record Authenticated(Principal principal, long expiresAtMillis) implements AuthState {
    }
}

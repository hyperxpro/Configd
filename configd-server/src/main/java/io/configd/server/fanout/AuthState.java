package io.configd.server.fanout;

import io.configd.common.auth.Principal;


public sealed interface AuthState permits AuthState.Unauthenticated, AuthState.Authenticated {

    
    AuthState UNAUTHENTICATED = new Unauthenticated();

    
    long NO_EXPIRY = Long.MAX_VALUE;

    static AuthState authenticated(Principal principal, long expiresAtMillis) {
        return new Authenticated(principal, expiresAtMillis);
    }

    default boolean isAuthenticated() {
        return this instanceof Authenticated;
    }

    record Unauthenticated() implements AuthState {
    }

    
    record Authenticated(Principal principal, long expiresAtMillis) implements AuthState {
    }
}

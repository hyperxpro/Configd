package io.configd.authn;

import java.util.List;

/**
 * An ordered chain of {@link Authenticator}s and the <b>resolution</b> over it (authenticator-spi.md §5.1):
 * credential-type dispatch + first-definitive + fail-closed. Built ONCE at boot and shared by BOTH the
 * control-plane and edge enforcement points (RA-5) so a {@link Principal} means the same on both — the property
 * INV-WATCH-READ depends on (authn-authz-boundary.md §3).
 *
 * <p>Design artifact (auth-SPI). NOT production code.
 */
public final class AuthenticatorChain {

    /** The resolved outcome over the whole chain — maps to 200 / 401 / 503. */
    public sealed interface Resolution permits Resolution.Authenticated, Resolution.Unauthenticated, Resolution.Unavailable {
        /** A credential's owner accepted it. */
        record Authenticated(Principal principal) implements Resolution {}
        /** No authenticator accepted a presented credential, or none was presented. → 401. */
        record Unauthenticated(String detail) implements Resolution {}
        /** A configured authenticator was unavailable (RA-1) — fail closed, NOT a fall-through. → 503/401. */
        record Unavailable(String detail) implements Resolution {}
    }

    private final List<Authenticator> chain;

    public AuthenticatorChain(List<Authenticator> chain) {
        this.chain = List.copyOf(chain);
    }

    /** The authenticator types in order (for the startup log). */
    public List<String> types() {
        return chain.stream().map(Authenticator::type).toList();
    }

    /**
     * Resolve a credential against the chain (authenticator-spi.md §5.1). The load-bearing rules:
     * an {@link AuthnUnavailableException} STOPS the chain fail-closed (RA-1, never falls through);
     * an {@code INVALID_CREDENTIAL} STOPS with 401 (RA-2, never falls through to a weaker authenticator);
     * a {@code NOT_THIS_AUTHENTICATOR} continues to the next; an acceptance wins.
     */
    public Resolution resolve(Credential credential) {
        boolean attempted = false;
        for (Authenticator a : chain) {
            if (!a.canAttempt(credential)) {
                continue;                                   // TYPE dispatch: not this authenticator's shape
            }
            attempted = true;
            AuthResult result;
            try {
                result = a.authenticate(credential);
            } catch (AuthnUnavailableException e) {
                return new Resolution.Unavailable(a.type() + ": " + e.getMessage());   // RA-1 STOP — fail closed
            }
            switch (result) {
                case AuthResult.Authenticated ok -> {
                    return new Resolution.Authenticated(ok.principal());               // first acceptance wins
                }
                case AuthResult.Rejected rej -> {
                    switch (rej.reason()) {
                        case INVALID_CREDENTIAL ->
                            // owned + bad: definitive 401, do NOT try a weaker authenticator (RA-2)
                            { return new Resolution.Unauthenticated(a.type() + ": " + rej.detail()); }
                        case NO_CREDENTIAL, NOT_THIS_AUTHENTICATOR -> {
                            // not mine / nothing here: continue down the chain
                        }
                    }
                }
            }
        }
        return new Resolution.Unauthenticated(attempted
                ? "no authenticator accepted the credential"
                : "no/unsupported credential");                                         // RA-4 default-deny
    }
}

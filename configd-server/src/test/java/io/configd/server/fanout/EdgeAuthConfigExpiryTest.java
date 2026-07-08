package io.configd.server.fanout;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.Authenticator;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.DenyReason;
import io.configd.common.auth.Principal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Gate-5 to Gate-6 exp seam at the edge: {@link EdgeAuthConfig#tokenCloseDeadlineMillis} closes a token
 * connection at the authority-issued {@code exp + leeway} when the authenticated result carries one (as an
 * OIDC token does), and falls back to the byte-identical {@code now + defaultTokenTtlMs} for a static token
 * that carries no authority expiry.
 */
final class EdgeAuthConfigExpiryTest {

    private static final long LEEWAY_MS = 60_000L;
    private static final CredentialExpiryPolicy POLICY = new CredentialExpiryPolicy(
            0.20, 30_000L, 300_000L, 0.10, 300_000L, 3_600_000L, LEEWAY_MS);

    private static EdgeAuthConfig config() {
        Authenticator noop = new Authenticator() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public boolean canAttempt(Credential c) {
                return false;
            }

            @Override
            public AuthResult authenticate(Credential c) {
                return new AuthResult.Denied(DenyReason.NOT_THIS_AUTHENTICATOR, "unused");
            }
        };
        return new EdgeAuthConfig(new AuthenticatorChain(List.of(noop)), 16_384, 8_192, 3_600_000L, POLICY);
    }

    private static AuthResult.Authenticated authenticated(long credentialExpiresAtMillis) {
        Principal p = new Principal("svc", Set.of(), "oidc");
        return new AuthResult.Authenticated(p, credentialExpiresAtMillis);
    }

    @Test
    void authorityExpiryClosesAtExpPlusLeeway() {
        long now = 1_000_000_000_000L;
        long tokenExp = now + 900_000L; // 15 minutes out
        assertEquals(tokenExp + LEEWAY_MS,
                config().tokenCloseDeadlineMillis(authenticated(tokenExp), now));
    }

    @Test
    void staticTokenWithoutAuthorityExpiryClosesAtNowPlusTtl() {
        long now = 1_000_000_000_000L;
        // NO_EXPIRY (a static bearer/basic token): byte-identical to the pre-Gate-6 session cap, no leeway.
        assertEquals(now + 3_600_000L,
                config().tokenCloseDeadlineMillis(authenticated(AuthResult.NO_EXPIRY), now));
        assertEquals(config().staticTokenCloseDeadlineMillis(now),
                config().tokenCloseDeadlineMillis(authenticated(AuthResult.NO_EXPIRY), now));
    }

    @Test
    void fourArgConstructorIsBackwardCompatibleForStaticTokens() {
        // The 4-arg ctor defaults the policy; the static-token path never consults it, so the deadline is
        // byte-identical whether or not a policy was supplied.
        EdgeAuthConfig fourArg = new EdgeAuthConfig(config().chain(), 16_384, 8_192, 3_600_000L);
        long now = 42_000L;
        assertEquals(now + 3_600_000L,
                fourArg.tokenCloseDeadlineMillis(authenticated(AuthResult.NO_EXPIRY), now));
    }
}

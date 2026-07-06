package io.configd.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies a static shared secret presented as a bearer token - the mechanism behind the legacy
 * {@code --auth-token}, factored behind the SPI. A presented {@link Credential.BearerToken} is compared to
 * the configured token in CONSTANT TIME ({@link MessageDigest#isEqual}); a match yields a fixed
 * {@link Principal}, a mismatch is {@link DenyReason#INVALID_CREDENTIAL}.
 *
 * <p>This is a CATCH-ALL authenticator: it claims every {@code BearerToken} and hard-rejects any that does
 * not match. It therefore MUST be ordered LAST among bearer-type authenticators in a mixed chain (after any
 * OIDC authenticator, which returns {@link DenyReason#NOT_THIS_AUTHENTICATOR} for a foreign issuer) - see
 * {@link AuthenticatorChain}. It has no remote backend, so it never returns {@link AuthResult.Unavailable}.
 */
public final class BearerTokenAuthenticator implements Authenticator {

    private final byte[] expectedToken;
    private final Principal principal;

    /**
     * @param token     the expected shared secret (non-blank)
     * @param principal the subject id a matching token authenticates as (the legacy wiring uses the root principal)
     * @param roles     the roles carried on the principal (the legacy wiring carries none; break-glass authority
     *                  comes from a static grant, decoupled from config-loadable roles)
     */
    public BearerTokenAuthenticator(String token, String principal, Set<String> roles) {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("bearer token must not be blank");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        this.principal = new Principal(principal, roles, "bearer");
    }

    @Override
    public String type() {
        return "bearer";
    }

    @Override
    public boolean canAttempt(Credential credential) {
        return credential instanceof Credential.BearerToken;
    }

    @Override
    public AuthResult authenticate(Credential credential) {
        Credential.BearerToken bt = (Credential.BearerToken) credential;
        byte[] presented = bt.token().getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(expectedToken, presented)) {
            return new AuthResult.Authenticated(principal);
        }
        return new AuthResult.Denied(DenyReason.INVALID_CREDENTIAL, "invalid bearer token");
    }
}

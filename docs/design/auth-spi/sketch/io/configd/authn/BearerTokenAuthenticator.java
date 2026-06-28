package io.configd.authn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Built-in default #2: bearer-token identity — the built behavior verbatim (built-reality.md §1.1). Zero
 * dependency. The comparison is <b>constant-time</b> ({@code MessageDigest.isEqual}, the F-V7-01 fix) — never
 * a {@code String.equals} token check (RA-6).
 *
 * <p>Design artifact (auth-SPI). NOT production code. Generalises the built {@code TokenValidator} lambda
 * (a single static {@code "root"}/{@code {admin}} identity today) into the SPI shape.
 */
public final class BearerTokenAuthenticator implements Authenticator {

    private final byte[] expected;        // configured admin-token bytes
    private final String principalId;     // "root"
    private final Set<String> roles;      // {"admin"}

    public BearerTokenAuthenticator(String expectedToken, String principalId, Set<String> roles) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        this.principalId = Objects.requireNonNull(principalId, "principalId");
        this.roles = Set.copyOf(roles);
    }

    /** From config: {@code configd.authn.bearer.token} → the built {@code ("root", {"admin"})} identity. */
    public static BearerTokenAuthenticator fromConfig(AuthnConfig config) {
        return new BearerTokenAuthenticator(config.get("configd.authn.bearer.token", ""),
                "root", Set.of("admin"));
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
        if (expected.length == 0) {
            // No token configured: this authenticator authenticates nobody (defensive — never match a blank).
            return new AuthResult.Rejected(RejectReason.INVALID_CREDENTIAL, "bearer auth not configured");
        }
        String token = ((Credential.BearerToken) credential).token();
        boolean ok = MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8));  // constant-time
        if (!ok) {
            return new AuthResult.Rejected(RejectReason.INVALID_CREDENTIAL, "invalid token");
        }
        return new AuthResult.Authenticated(new Principal(principalId, roles, Map.of(), "bearer"));
    }
}

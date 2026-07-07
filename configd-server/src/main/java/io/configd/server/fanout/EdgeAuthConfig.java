package io.configd.server.fanout;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.MtlsAuthenticator;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The edge token-authentication posture, threaded into both fan-out transports. Its mere presence
 * (a non-null value) means token/basic auth is configured for the edge: the transport installs the
 * auth gate, constructs the frame decoder with the pre-auth ceiling, and relaxes the edge TLS from
 * {@code needClientAuth} to {@code wantClientAuth} so a certificate-less token client can connect
 * while certificate clients still authenticate at the handshake. When it is {@code null} the edge is
 * byte-identical to the mTLS-only / plaintext posture that predates the token frame.
 *
 * <p>The {@link AuthenticatorChain} here is the SAME chain the HTTP admin plane resolves (one chain,
 * both planes); it verifies the bearer/basic credential presented in an {@code AUTH}/{@code REFRESH_AUTH}
 * frame. A verified client certificate is authenticated separately at the TLS handshake via an
 * identity-only {@link MtlsAuthenticator} - the edge has always turned a verified peer cert into an
 * identity-only principal (roles resolved downstream by the {@code AclService} from the Subject DN),
 * so the token frame is purely additive and mTLS clients stay byte-identical.
 *
 * <h2>Token expiry (Gate 5)</h2>
 * A static bearer/basic token carries no authority-issued {@code exp} today, so its expiry is a
 * server-computed session-lifetime cap: the connection closes at {@code now + defaultTokenTtlMs},
 * measured on this server's own clock (no clock-skew leeway - the leeway is for a credential whose
 * absolute expiry was issued by an external authority). This is byte-identical to the pre-Gate-5 fixed
 * TTL. When Gate 6 feeds an OIDC {@code exp} the token path will instead close at {@code exp + leeway}.
 * A {@code REFRESH_AUTH} re-arms the cap. Certificate expiry ({@code notAfter}) and online revocation are
 * a separate, token-independent concern owned by {@link EdgeCertGate}.
 *
 * @param chain                the shared authenticator chain (bearer/basic) for {@code AUTH} frames
 * @param preAuthMaxFrameBytes the declared-length ceiling enforced by the frame decoder while a token
 *                             connection is unauthenticated ({@code configd.edge.preAuthMaxFrameBytes})
 * @param maxAuthTokenBytes    the receive-side cap on a bearer token's UTF-8 length, enforced by the
 *                             gate before the (possibly expensive) credential verification runs
 *                             ({@code configd.edge.maxAuthTokenBytes})
 * @param defaultTokenTtlMs    the session lifetime armed on a static token auth (a bearer/basic credential
 *                             with no authority-issued expiry); {@code now + defaultTokenTtlMs} is the close
 *                             deadline, on the server clock, no skew leeway
 * @param expiryPolicy         the Gate-5 window/leeway model; used only to turn an authority-issued token
 *                             expiry (an OIDC/JWT {@code exp}, surfaced on {@link AuthResult.Authenticated})
 *                             into a close deadline of {@code exp + leeway}. Ignored for a static token,
 *                             which has no authority expiry - so the four-argument constructor (which
 *                             defaults it) is byte-identical to the pre-Gate-6 behaviour.
 */
public record EdgeAuthConfig(AuthenticatorChain chain, int preAuthMaxFrameBytes,
                             int maxAuthTokenBytes, long defaultTokenTtlMs, CredentialExpiryPolicy expiryPolicy) {

    /**
     * Backward-compatible constructor for the static-token posture (no authority expiry): the expiry policy
     * defaults to {@link CredentialExpiryPolicy#DEFAULTS} and is never consulted, so this is byte-identical
     * to the pre-Gate-6 edge auth config.
     */
    public EdgeAuthConfig(AuthenticatorChain chain, int preAuthMaxFrameBytes, int maxAuthTokenBytes,
                          long defaultTokenTtlMs) {
        this(chain, preAuthMaxFrameBytes, maxAuthTokenBytes, defaultTokenTtlMs, CredentialExpiryPolicy.DEFAULTS);
    }

    /** Fixed cap on a Basic username's UTF-8 length (a policy bound, not a wire constant). */
    static final int MAX_BASIC_USERNAME_BYTES = 256;
    /** Fixed cap on a Basic password's character length (a policy bound, not a wire constant). */
    static final int MAX_BASIC_PASSWORD_CHARS = 1024;

    /**
     * The edge's identity-only mTLS authenticator: a verified peer certificate becomes a
     * {@code Principal} whose id is the Subject DN, with no default roles - exactly the identity the
     * edge has always bound (the {@code AclService} resolves grants from the DN). Stateless, so a
     * single shared instance is safe.
     */
    private static final MtlsAuthenticator EDGE_MTLS = new MtlsAuthenticator(Set.of());

    public EdgeAuthConfig {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(expiryPolicy, "expiryPolicy");
        if (preAuthMaxFrameBytes <= 0) {
            throw new IllegalArgumentException("preAuthMaxFrameBytes must be positive: " + preAuthMaxFrameBytes);
        }
        if (maxAuthTokenBytes <= 0) {
            throw new IllegalArgumentException("maxAuthTokenBytes must be positive: " + maxAuthTokenBytes);
        }
        if (defaultTokenTtlMs <= 0) {
            throw new IllegalArgumentException("defaultTokenTtlMs must be positive: " + defaultTokenTtlMs);
        }
    }

    /** Resolves a frame-borne bearer/basic credential against the shared chain (fail-closed). */
    AuthResult resolveFrameCredential(Credential credential) {
        return chain.resolve(credential);
    }

    /**
     * Whether mTLS is a configured edge authenticator (the shared chain lists an {@code mtls} provider).
     * A token-only edge (chain without {@code mtls}) must NOT turn a presented trust-store cert into an
     * authenticated identity - the callers gate the handshake cert path on this so a certificate client
     * on such an edge falls through to token auth, symmetric with the HTTP plane (where a cert
     * authenticates only if the chain lists {@code mtls}).
     */
    boolean mtlsConfigured() {
        return chain.providerTypes().contains("mtls");
    }

    /** Authenticates an already-verified peer certificate chain to its identity-only principal. */
    AuthResult authenticateClientCertificate(List<X509Certificate> verifiedChain) {
        if (!mtlsConfigured()) {
            // Defense in depth: a presented cert must not auto-authenticate on a token-only edge (that
            // would admit ANY trust-store cert). Callers already gate on mtlsConfigured(); this fails
            // closed if a future caller forgets.
            return new AuthResult.Denied(io.configd.common.auth.DenyReason.NOT_THIS_AUTHENTICATOR,
                    "mTLS is not a configured edge authenticator");
        }
        return EDGE_MTLS.authenticate(new Credential.ClientCertificate(verifiedChain));
    }

    /**
     * The wall-clock time at which a static token connection is closed for credential expiry:
     * {@code nowMillis + defaultTokenTtlMs}. Measured on the server clock (no skew leeway), so it is
     * byte-identical to the pre-Gate-5 fixed TTL. A {@code REFRESH_AUTH} re-computes it from the new
     * {@code now}.
     */
    long staticTokenCloseDeadlineMillis(long nowMillis) {
        return nowMillis + defaultTokenTtlMs;
    }

    /**
     * The close deadline for a token connection given the authenticated result. When the authenticator
     * surfaced an authority-issued credential expiry (an OIDC/JWT {@code exp}), the connection closes at
     * {@code exp + leeway} per the Gate-5 model (the leeway accommodates cross-clock skew against the
     * issuing authority). When it did not ({@link AuthResult#NO_EXPIRY} - a static bearer/basic token), this
     * falls back to the server-computed {@code now + defaultTokenTtlMs} with no leeway, byte-identical to the
     * pre-Gate-6 static-token path. This is the seam that lets a token connection close exactly when the
     * presented token expires rather than only at a fixed session cap.
     */
    long tokenCloseDeadlineMillis(AuthResult.Authenticated authenticated, long nowMillis) {
        long credentialExpiresAtMillis = authenticated.credentialExpiresAtMillis();
        if (credentialExpiresAtMillis == AuthResult.NO_EXPIRY) {
            return staticTokenCloseDeadlineMillis(nowMillis);
        }
        return expiryPolicy.serverCloseDeadlineMillis(credentialExpiresAtMillis);
    }

    /**
     * Whether a frame credential is within the receive-side size policy, checked BEFORE the credential
     * is verified so a hostile peer cannot drive an unbounded verification cost (PBKDF2 / token parse)
     * with an oversized secret that still fits the pre-auth frame ceiling. A client certificate is
     * never frame-borne, so it is rejected here (defensive; the codec already refuses to decode one).
     */
    boolean credentialWithinCaps(Credential credential) {
        return switch (credential) {
            case Credential.BearerToken t ->
                    t.token().getBytes(StandardCharsets.UTF_8).length <= maxAuthTokenBytes;
            case Credential.BasicCredential b ->
                    b.username().getBytes(StandardCharsets.UTF_8).length <= MAX_BASIC_USERNAME_BYTES
                            && b.password().length <= MAX_BASIC_PASSWORD_CHARS;
            case Credential.ClientCertificate ignored -> false;
        };
    }
}

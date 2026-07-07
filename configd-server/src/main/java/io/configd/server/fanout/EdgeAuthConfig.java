package io.configd.server.fanout;

import io.configd.common.auth.AuthResult;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.Credential;
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
 * @param chain               the shared authenticator chain (bearer/basic) for {@code AUTH} frames
 * @param preAuthMaxFrameBytes the declared-length ceiling enforced by the frame decoder while a token
 *                             connection is unauthenticated ({@code configd.edge.preAuthMaxFrameBytes})
 * @param maxAuthTokenBytes    the receive-side cap on a bearer token's UTF-8 length, enforced by the
 *                             gate before the (possibly expensive) credential verification runs
 *                             ({@code configd.edge.maxAuthTokenBytes})
 * @param tokenTtlMs           the placeholder session lifetime armed on a successful token auth. Gate 5
 *                             replaces this fixed TTL with the real credential-lead-time model
 */
public record EdgeAuthConfig(AuthenticatorChain chain, int preAuthMaxFrameBytes,
                             int maxAuthTokenBytes, long tokenTtlMs) {

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
        if (preAuthMaxFrameBytes <= 0) {
            throw new IllegalArgumentException("preAuthMaxFrameBytes must be positive: " + preAuthMaxFrameBytes);
        }
        if (maxAuthTokenBytes <= 0) {
            throw new IllegalArgumentException("maxAuthTokenBytes must be positive: " + maxAuthTokenBytes);
        }
        if (tokenTtlMs <= 0) {
            throw new IllegalArgumentException("tokenTtlMs must be positive: " + tokenTtlMs);
        }
    }

    /** Resolves a frame-borne bearer/basic credential against the shared chain (fail-closed). */
    AuthResult resolveFrameCredential(Credential credential) {
        return chain.resolve(credential);
    }

    /** Authenticates an already-verified peer certificate chain to its identity-only principal. */
    AuthResult authenticateClientCertificate(List<X509Certificate> verifiedChain) {
        return EDGE_MTLS.authenticate(new Credential.ClientCertificate(verifiedChain));
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

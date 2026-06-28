package io.configd.authn;

/**
 * Pluggable <b>authentication</b>: verify a {@link Credential} → a {@link Principal}, or a typed rejection.
 * This is the SPI an adopter implements to teach Configd a new identity system (OIDC, LDAP, Kubernetes,
 * cloud-IAM). It does exactly one job — answer "who is this caller?" — and is deliberately NOT an
 * authorization API.
 *
 * <p>Design artifact (auth-SPI). NOT production code. The two built mechanisms ship as
 * {@link MtlsAuthenticator} and {@link BearerTokenAuthenticator} (in-core, zero dependency); cloud/external
 * providers are separate optional modules discovered via {@link AuthenticatorFactory}.
 *
 * <h2>The shape forbids the dangerous thing</h2>
 * There is <b>no {@code mayAccess(path)} method.</b> The access decision is the IN-CORE namespace authz
 * engine's job (authn-authz-boundary.md); a pluggable authz black box would destroy the consistency
 * guarantees (policy-as-config, INV-WATCH-READ). So <b>only authentication is the SPI</b> — this interface
 * has no surface on which an implementer could make an authorization decision. (The analogue of the KMS SPI
 * having no per-record {@code encrypt}.)
 *
 * <h2>Implementer requirements (the fail-closed contract — authenticator-spi.md §5.2)</h2>
 * <ul>
 *   <li><b>RA-1 — fail closed, never downgrade.</b> If configured but unavailable (issuer/JWKS unreachable,
 *       directory down), throw {@link AuthnUnavailableException}; the resolver rejects and MUST NOT fall
 *       through to a weaker authenticator.</li>
 *   <li><b>RA-2 — validation failures fail closed.</b> An OWNED-but-bad credential is
 *       {@code Rejected(INVALID_CREDENTIAL)} → a hard 401, never anonymous, never fall-through. A
 *       not-mine credential is {@code Rejected(NOT_THIS_AUTHENTICATOR)} → try the next.</li>
 *   <li><b>RA-3 — never leak the credential.</b> Extract identity and discard the secret; the produced
 *       {@link Principal} has no credential field; reject details MUST NOT echo the credential.</li>
 *   <li><b>RA-6 — established libraries only.</b> mTLS rides the platform TLS; JWT validation uses a vetted
 *       library; never roll crypto/token validation.</li>
 *   <li><b>Role mapping is here.</b> Map external identity (claims/groups) → <b>Configd roles</b> before
 *       putting them in the {@link Principal}; the authz engine only ever sees Configd roles
 *       (authn-authz-boundary.md §2).</li>
 * </ul>
 *
 * <p>Implementations are stateless w.r.t. a request and may be called concurrently; any cached verification
 * material (JWKS keys, LDAP connections) MUST be refreshed within a bounded TTL so steady-state validation is
 * local (authenticator-spi.md §5.3 — authn is on the per-request path, unlike the boot-only KMS SPI).
 */
public interface Authenticator {

    /** Discovery discriminator: {@code "mtls"}, {@code "bearer"}, {@code "oidc"}, {@code "ldap"}, … */
    String type();

    /**
     * Cheap TYPE dispatch: does this authenticator handle this credential's shape at all? (e.g. a bearer
     * authenticator returns true for any {@link Credential.BearerToken}.) No validation, no I/O. The finer
     * "this token isn't for my issuer" is decided in {@link #authenticate} via {@code NOT_THIS_AUTHENTICATOR}.
     *
     * <p><b>MUST NOT throw</b> on a foreign / unparseable credential — return {@code false} and let another
     * authenticator try. A dispatch that throws would fault the whole resolution; the resolver fails closed as a
     * backstop, but a compliant {@code canAttempt} keeps a mixed chain (e.g. static-bearer + OIDC) clean.
     */
    boolean canAttempt(Credential credential);

    /**
     * Verify the credential. Returns {@code Authenticated(principal)} or a typed {@code Rejected}.
     *
     * @throws AuthnUnavailableException if this authenticator is configured but cannot reach what it needs to
     *                                   verify (RA-1) — the resolver fails closed, never falls through.
     */
    AuthResult authenticate(Credential credential) throws AuthnUnavailableException;
}

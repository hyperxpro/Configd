package io.configd.common.auth;

/**
 * Verifies a {@link Credential} and produces a {@link Principal} - the pluggable authentication SPI. Its
 * job is deliberately narrow: "who is this caller?". It is NOT an authorization API - there is no
 * {@code mayAccess(path)} method on which an implementer could make an access decision, because
 * authorization stays in-core (an authenticator only ever hands the authz engine a {@link Principal}).
 *
 * <p><b>Fail-closed contract</b> (enforced by {@link AuthenticatorChain}):
 * <ul>
 *   <li>A credential this authenticator OWNS but that is bad (expired/forged/wrong-password) is
 *       {@link AuthResult.Denied}{@code (INVALID_CREDENTIAL)} - a hard stop, never a fall-through.</li>
 *   <li>A backend that is configured but unreachable is {@link AuthResult.Unavailable} - the chain stops
 *       and rejects retryably (503), never downgrades. (The default in-core authenticators have no remote
 *       backend and never return this.)</li>
 *   <li>{@link #canAttempt} MUST NOT throw on a foreign/unparseable credential - it returns {@code false}.
 *       Any throwable from {@code canAttempt} OR {@code authenticate} is treated by the chain as
 *       fail-closed (stop, reject) - a buggy or hostile provider can never fault the chain open.</li>
 *   <li>Established libraries only: never roll crypto or token validation. mTLS verification is the
 *       platform TLS stack; a JWT authenticator uses a vetted library. The authenticator is never the
 *       verification point for mTLS - it reads identity off an already-verified chain.</li>
 * </ul>
 */
public interface Authenticator {

    /** The discovery discriminator and provenance tag: {@code "mtls"}, {@code "bearer"}, {@code "oidc"}, ... */
    String type();

    /**
     * Cheap TYPE dispatch: does this authenticator handle credentials of this class? (e.g. "I handle
     * {@link Credential.BearerToken}"). No validation, no I/O, and it MUST NOT throw on any input.
     */
    boolean canAttempt(Credential credential);

    /**
     * Verifies {@code credential} to a {@link Principal}, or returns a typed rejection. Called only after
     * {@link #canAttempt} returned {@code true}. It may throw to signal a non-cooperative fault; the chain
     * treats any throwable as fail-closed. A cooperative "backend down" is {@link AuthResult.Unavailable}.
     */
    AuthResult authenticate(Credential credential);
}

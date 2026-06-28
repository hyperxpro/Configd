package io.configd.authn;

/**
 * The outcome of a single {@link Authenticator#authenticate}. Sealed — its two cases are a closed set,
 * generalising the built {@code AuthInterceptor.AuthResult} (built-reality.md §1.1): the built
 * {@code Authenticated(String principal, Set<String> roles)} becomes {@link Authenticated} carrying a full
 * {@link Principal}; the built {@code Denied(String reason)} becomes {@link Rejected} with a <em>typed</em>
 * {@link RejectReason} that drives the chain resolution (authenticator-spi.md §5).
 *
 * <p>Design artifact (auth-SPI). NOT production code. A {@code Rejected} carries a short {@code detail} for
 * audit; it MUST NOT echo the credential (RA-3).
 */
public sealed interface AuthResult permits AuthResult.Authenticated, AuthResult.Rejected {

    /** The credential verified — the caller is this {@link Principal}. */
    record Authenticated(Principal principal) implements AuthResult {}

    /** The credential was not accepted by THIS authenticator; {@link RejectReason} decides what the chain does. */
    record Rejected(RejectReason reason, String detail) implements AuthResult {}
}

package io.configd.client;

/**
 * A cryptographic verification failure of the signed config chain: a bad Ed25519 signature, a broken
 * {@code fromVersion → toVersion} chain, a signature carried on an {@code epoch == 0} delta, or an unsigned
 * delta received while a verifier is configured (the fail-closed {@code DeltaApplier} semantics, §04 / OV7).
 *
 * <p><b>§07 reaction:</b> this is a <b>security control, fail-closed</b> — the frame CRC is integrity, not
 * authenticity (§06 F2-4); a verification failure is <b>never</b> silently dropped and tears the connection
 * down. Defined here in Gate 1 for a complete hierarchy; the {@code SignedChainVerifier} that raises it is
 * Gate 2.
 */
public final class ChainVerificationException extends ConfigdException {

    public ChainVerificationException(String message) {
        super(message);
    }

    public ChainVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

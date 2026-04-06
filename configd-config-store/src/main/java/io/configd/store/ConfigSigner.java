package io.configd.store;

import java.security.*;
import java.util.Objects;

/**
 * Signs and verifies config entries using Ed25519 (or ECDSA fallback).
 * The leader signs at commit time; edges verify before applying.
 * <p>
 * Thread safety: instances are safe for concurrent use. The underlying
 * {@link Signature} objects are created per-call (they are not thread-safe).
 */
public final class ConfigSigner {

    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey signingKey;   // null on verify-only nodes (edges)
    private final PublicKey verifyKey;

    /**
     * Creates a signer for a leader node that can both sign and verify.
     *
     * @param keyPair the Ed25519 key pair (non-null)
     */
    public ConfigSigner(KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        this.signingKey = keyPair.getPrivate();
        this.verifyKey = keyPair.getPublic();
    }

    /**
     * Creates a verify-only signer for edge nodes.
     *
     * @param verifyKey the Ed25519 public key (non-null)
     */
    public ConfigSigner(PublicKey verifyKey) {
        Objects.requireNonNull(verifyKey, "verifyKey must not be null");
        this.signingKey = null;
        this.verifyKey = verifyKey;
    }

    /**
     * Signs config mutation data. Only callable on leader nodes that
     * were constructed with a full key pair.
     *
     * @param data the data to sign (non-null)
     * @return the Ed25519 signature bytes
     * @throws IllegalStateException    if this is a verify-only instance
     * @throws GeneralSecurityException if signing fails
     */
    public byte[] sign(byte[] data) throws GeneralSecurityException {
        Objects.requireNonNull(data, "data must not be null");
        if (signingKey == null) {
            throw new IllegalStateException("this ConfigSigner is verify-only (no signing key)");
        }
        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initSign(signingKey);
        sig.update(data);
        return sig.sign();
    }

    /**
     * Verifies a signature against the given data.
     *
     * @param data      the original data (non-null)
     * @param signature the signature bytes to verify (non-null)
     * @return true if the signature is valid
     * @throws GeneralSecurityException if verification setup fails
     */
    public boolean verify(byte[] data, byte[] signature) throws GeneralSecurityException {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        Signature sig = Signature.getInstance(ALGORITHM);
        sig.initVerify(verifyKey);
        sig.update(data);
        try {
            return sig.verify(signature);
        } catch (SignatureException e) {
            // Corrupted signatures can cause Ed25519 to throw (e.g., invalid point)
            // rather than returning false. Treat as verification failure.
            return false;
        }
    }
}

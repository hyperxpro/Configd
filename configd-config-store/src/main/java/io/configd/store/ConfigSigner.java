package io.configd.store;

import java.security.*;
import java.util.Objects;

/**
 * The leader signs at commit time; edges verify before applying.
 * <p>
 * Thread safety: instances are safe for concurrent use - the underlying {@link Signature} object
 * is created fresh per call, never shared.
 */
public final class ConfigSigner {

    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey signingKey;
    private final PublicKey verifyKey;

    public ConfigSigner(KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "keyPair must not be null");
        this.signingKey = keyPair.getPrivate();
        this.verifyKey = keyPair.getPublic();
    }

    public ConfigSigner(PublicKey verifyKey) {
        Objects.requireNonNull(verifyKey, "verifyKey must not be null");
        this.signingKey = null;
        this.verifyKey = verifyKey;
    }

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

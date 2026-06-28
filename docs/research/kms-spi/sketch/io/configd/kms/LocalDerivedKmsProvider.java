package io.configd.kms;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The built-in, <b>zero-dependency</b> default provider: the per-node root key is
 * <b>derived from the already-loaded cluster signing key via HKDF</b> — no external KMS,
 * no extra config, no new boot dependency. This realises the encryption-at-rest
 * research's Option <b>B-minimal</b>:
 * {@code K_enc = HKDF(IKM = signing-key, salt = keyId, info = "configd/raft-at-rest-encryption/kek/v1")},
 * a third derived key beside the existing {@code K_integrity} / {@code K_audit}
 * ({@code ConfigdServer.deriveRaftIntegrityEnvelope} / {@code deriveAuditLogKey}).
 *
 * <p>Design-research artifact (KMS-SPI). <b>Crypto is intentionally NOT implemented</b>
 * (the research charter forbids working crypto this session); the derivation points are
 * stubbed and the lifecycle shape is real.
 *
 * <p><b>Known property — fate-sharing (documented, not a bug).</b> The encryption key
 * shares fate with the signing key: if the signing key leaks, at-rest data is
 * decryptable — but a signing-key leak already lets the attacker forge committed state,
 * so the marginal loss is bounded. There is <b>no independent key rotation</b> (rotating
 * the encryption key means rotating the signing key). The {@code local} provider inherits
 * the D-1 co-location guard ({@code enforceSigningKeyNotColocated}); graduate to a cloud
 * provider (Option D) when off-host custody or managed rotation is required.
 *
 * <p>Availability: nothing to be unavailable — the key is derivable the instant the
 * signing key is read, so {@link #unwrap} never throws {@link KmsUnavailableException}.
 * This is precisely why it cannot threaten consensus liveness.
 */
public final class LocalDerivedKmsProvider implements KmsProvider {

    static final String KEK_INFO = "configd/raft-at-rest-encryption/kek/v1";

    private final Supplier<byte[]> signingKeyIkm;  // e.g. keyStore.keyPair().getPrivate().getEncoded()
    private final KeyId keyId;                      // providerType="local", reference=signing keyId

    public LocalDerivedKmsProvider(Supplier<byte[]> signingKeyIkm, KeyId keyId) {
        this.signingKeyIkm = java.util.Objects.requireNonNull(signingKeyIkm, "signingKeyIkm");
        this.keyId = java.util.Objects.requireNonNull(keyId, "keyId");
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public KeyId currentKeyId() {
        return keyId;
    }

    @Override
    public Provisioned generateRootKey() {
        // Deterministic: the "fresh" root key IS the HKDF derivation. Persisting the
        // descriptor lets a later boot re-derive without storing any ciphertext.
        return new Provisioned(deriveRootKey(), descriptor());
    }

    @Override
    public WrappedKey wrap(RootKey rootKey) {
        // No sealing: the local root key is reconstructed by re-derivation, so the
        // "wrapped" form is just the (non-secret) derivation descriptor.
        return descriptor();
    }

    @Override
    public RootKey unwrap(WrappedKey wrapped) {
        // Re-derive from the signing key — no external call, so this never fails closed.
        return deriveRootKey();
    }

    private WrappedKey descriptor() {
        return new WrappedKey(keyId, new byte[0],
                Map.of("purpose", "raft-at-rest-kek", "kdf", "HKDF-SHA256", "info", KEK_INFO));
    }

    private RootKey deriveRootKey() {
        // PRODUCTION (not implemented here): with the signing-key encoding as IKM,
        //   byte[] k = Hkdf.deriveKey(signingKeyIkm.get(), saltFromKeyId(keyId),
        //                             KEK_INFO.getBytes(UTF_8), 32);   // or javax.crypto.KDF (JDK 25)
        //   return new RootKey(k, keyId);   // RootKey OWNS + wipes k
        // The signing-key IKM and the derived bytes must both be wiped after use.
        throw new UnsupportedOperationException(
                "design sketch: derive RootKey = HKDF(signing-key, salt=keyId, info=\""
                        + KEK_INFO + "\") — crypto intentionally not implemented this session");
    }
}

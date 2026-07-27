package io.configd.common.kms;

import io.configd.common.Hkdf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * The zero-dependency default {@link KmsProvider}: it DERIVES the per-node root key
 * from the already-loaded cluster signing key via HKDF, rather than sealing it under
 * an external KEK.
 *
 * <pre>
 *   RootKey = HKDF-SHA256(IKM = signing-key encoding,
 *                         salt = signing keyId bytes,
 *                         info = "configd/raft-at-rest-encryption/kek/v1",
 *                         len  = 32)
 * </pre>
 *
 * The {@code info} string is DISTINCT from the at-rest integrity key
 * ({@code "configd/raft-at-rest-integrity/v2"}) and the audit-log key
 * ({@code "configd/audit-log-integrity/v1"}), so the encryption root and those two
 * MAC keys are domain-separated and independent (compromise/analysis of one yields
 * nothing about the others).
 *
 * <h2>Why this satisfies the availability contract trivially</h2>
 * There is nothing external to be unavailable: {@link #unwrap} and
 * {@link #generateRootKey} re-run the deterministic derivation and <b>never</b>
 * throw {@link KmsUnavailableException}. So the default cannot threaten consensus
 * liveness (R1-R5 hold by construction) and adds no new file, no distribution
 * channel, no external call, and no new boot failure mode - the key is available the
 * instant the signing key is read.
 *
 * <h2>Known property - fate-sharing (documented, not a defect)</h2>
 * The encryption root shares fate with the signing key: a signing-key leak makes
 * at-rest data decryptable. The marginal loss is bounded (a signing-key leak already
 * lets an attacker forge committed state), and the trade buys zero new dependencies.
 * The same co-location guard the integrity key relies on (the signing key must
 * live OUTSIDE the data directory) protects this root key too. There is no
 * independent encryption-key rotation for {@code local} - rotating the encryption
 * root means rotating the signing key. Graduate to a cloud/HSM provider when off-host
 * custody or managed rotation is required.
 */
public final class LocalDerivedKmsProvider implements KmsProvider {

    /** Distinct HKDF info string - domain-separates this root from K_integrity / K_audit. */
    static final byte[] KEK_INFO =
            "configd/raft-at-rest-encryption/kek/v1".getBytes(StandardCharsets.UTF_8);

    private static final String PROVIDER_TYPE = "local";
    private static final int ROOT_KEY_LEN = 32;

    private final byte[] signingKeyIkm; // owned copy; zeroed on close()
    private final byte[] salt;          // signing keyId bytes (non-secret)
    private final String reference;     // non-secret KEK reference (signing keyId string)
    private final int term;

    /**
     * @param signingKeyIkm the cluster signing private-key encoding (defensively copied; the
     *                      caller SHOULD zero its own copy afterwards)
     * @param salt          the signing keyId bytes used as the HKDF salt (non-secret)
     * @param reference     a non-secret, loggable KEK reference (e.g. the signing keyId string)
     * @param term          the keyring term for this root (>= 1)
     */
    public LocalDerivedKmsProvider(byte[] signingKeyIkm, byte[] salt, String reference, int term) {
        this.signingKeyIkm = Objects.requireNonNull(signingKeyIkm, "signingKeyIkm").clone();
        this.salt = Objects.requireNonNull(salt, "salt").clone();
        this.reference = Objects.requireNonNull(reference, "reference");
        if (term < 1) {
            throw new IllegalArgumentException("term must be >= 1, was " + term);
        }
        this.term = term;
    }

    @Override
    public String type() {
        return PROVIDER_TYPE;
    }

    @Override
    public KeyId currentKeyId() {
        return new KeyId(PROVIDER_TYPE, reference, term);
    }

    @Override
    public Provisioned generateRootKey() {
        RootKey root = derive();
        // The WrappedKey is a non-secret re-derivation descriptor: empty ciphertext, because
        // reconstruction is by re-derivation from the signing key, not by unsealing bytes.
        WrappedKey wrapped = new WrappedKey(root.keyId(), new byte[0],
                Map.of("scheme", "hkdf-from-signing-key"));
        return new Provisioned(root, wrapped);
    }

    @Override
    public WrappedKey wrap(RootKey rootKey) {
        // Nothing is sealed - return the derivation descriptor for the given key's term.
        return new WrappedKey(rootKey.keyId(), new byte[0],
                Map.of("scheme", "hkdf-from-signing-key"));
    }

    @Override
    public RootKey unwrap(WrappedKey wrapped) {
        // Re-derive from the signing key. No external call -> never fails closed. The persisted
        // WrappedKey's ciphertext is ignored (it is a descriptor); the term comes from its keyId.
        return derive();
    }

    private RootKey derive() {
        // withMaterial-style scoped derivation: HKDF over a transient clone of the IKM, and the
        // derived bytes are zeroed after the RootKey has taken its own defensive copy.
        byte[] rootBytes = Hkdf.deriveKey(signingKeyIkm, salt, KEK_INFO, ROOT_KEY_LEN);
        try {
            return new RootKey(rootBytes, new KeyId(PROVIDER_TYPE, reference, term));
        } finally {
            Arrays.fill(rootBytes, (byte) 0);
        }
    }

    @Override
    public void close() {
        // Release our copy of the signing-key IKM. The RootKey the core cached is unaffected.
        Arrays.fill(signingKeyIkm, (byte) 0);
        Arrays.fill(salt, (byte) 0);
    }
}

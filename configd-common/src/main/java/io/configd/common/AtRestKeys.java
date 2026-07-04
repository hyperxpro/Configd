package io.configd.common;

import javax.crypto.SecretKey;

/**
 * The at-rest encryption key source used by {@link IntegrityEnvelope}'s AES-256-GCM
 * path (algId=2). It separates <b>key management + nonce uniqueness</b> (this
 * contract) from <b>the envelope byte format + the {@code Cipher} calls</b> (the
 * envelope). The default implementation is {@link SegmentKeyManager}.
 *
 * <h2>The one correctness invariant this contract owns</h2>
 * A (key, nonce) pair MUST NEVER be reused under AES-GCM - reuse breaks BOTH
 * confidentiality and authenticity. {@link #nextSeal(int)} is the sole nonce issuer
 * and MUST guarantee that no {@code (segmentId, nonce)} pair it returns for a given
 * keyTerm is ever returned twice, and that a fresh writer session (a new manager
 * instance, e.g. after a restart) uses a fresh {@code segmentId} so its counter -
 * which restarts at 0 - pairs with a brand-new DEK. See {@link SegmentKeyManager}
 * for the by-construction proof.
 */
public interface AtRestKeys {

    /** GCM nonce length in bytes (96-bit IV - the AES-GCM standard/optimal size). */
    int NONCE_LEN = 12;

    /** Per-segment id length in bytes (128 bits of entropy from {@code SecureRandom}). */
    int SEGMENT_ID_LEN = 16;

    /**
     * A single-use encryption context for one record: the keyring term, the segment
     * id (both stamped into the envelope so the reader can re-derive the DEK), a
     * never-before-used nonce, and the per-segment DEK to encrypt with.
     *
     * @param keyTerm   the keyring term selecting the root key (rotation generation)
     * @param segmentId the per-segment id (selects the DEK via HKDF from the root)
     * @param nonce     a {@value #NONCE_LEN}-byte nonce, unique for this (keyTerm, segmentId)
     * @param dek       the per-segment data-encryption key (AES-256)
     */
    record Seal(int keyTerm, byte[] segmentId, byte[] nonce, SecretKey dek) {}

    /**
     * WRITE side: allocate a fresh, never-before-used {@link Seal} for the given
     * artifact {@code magic}. Thread-safe: many owner threads (one per Raft group)
     * may call concurrently.
     *
     * @param magic the artifact discriminator (WAL / snapshot / raft-state); records
     *              of the same magic form one segment stream
     */
    Seal nextSeal(int magic);

    /**
     * READ side: the DEK for {@code (keyTerm, segmentId)} read out of an envelope.
     * FAILS CLOSED - throws {@link IntegrityException} if the keyTerm is unknown (an
     * old term the keyring no longer retains, or a forged term), so a record that
     * cannot be authentically decrypted is refused rather than silently skipped.
     *
     * @param keyTerm   the keyring term stamped in the envelope
     * @param segmentId the segment id stamped in the envelope
     * @return the per-segment DEK
     * @throws IntegrityException if the keyTerm is not in the keyring (fail-closed)
     */
    SecretKey resolveDek(int keyTerm, byte[] segmentId);

    /**
     * The keyring term new writes stamp (both the HMAC and GCM postures use it). New at-rest records
     * carry this as their {@code keyTerm}; a rotation advances it while old terms stay retained.
     *
     * @return the active write term ({@code >= 1})
     */
    int activeTerm();

    /**
     * READ/WRITE side (HMAC posture): the term-versioned integrity MAC key
     * {@code K_integrity[keyTerm] = HKDF(root[keyTerm], salt=nodeKeyId, info)}. This is the HMAC
     * analogue of {@link #resolveDek}: it selects the root for a record's own {@code keyTerm} so an
     * HMAC segment written under an old term still verifies after rotation (non-destructive), and a
     * {@code keyTerm} whose root is absent from the keyring FAILS CLOSED.
     *
     * @param keyTerm the keyring term stamped in the HMAC envelope
     * @return the HMAC-SHA-256 integrity key for that term
     * @throws IntegrityException if the keyTerm is not in the keyring (fail-closed)
     */
    SecretKey macKey(int keyTerm);
}

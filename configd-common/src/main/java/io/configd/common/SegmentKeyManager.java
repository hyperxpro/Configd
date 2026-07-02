package io.configd.common;

import io.configd.common.kms.KmsProvider;
import io.configd.common.kms.KmsUnavailableException;
import io.configd.common.kms.RootKey;
import io.configd.common.kms.WrappedKey;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default {@link AtRestKeys}: a term-versioned keyring of per-node root keys plus
 * the per-segment DEK derivation and the nonce-uniqueness lifecycle for AES-256-GCM.
 *
 * <h2>Key hierarchy</h2>
 * <pre>
 *   RootKey[term]              (the node KEK; unsealed once at boot by a KmsProvider)
 *      |  DEK = HKDF-SHA256(IKM = RootKey[term], salt = segmentId,
 *      |                    info = "configd/raft-at-rest-encryption/dek/v1", len = 32)
 *      v
 *   per-segment DEK            (AES-256; cached per segment; used with a counter nonce)
 * </pre>
 *
 * <h2>The nonce-uniqueness invariant, by construction (the load-bearing property)</h2>
 * A reused {@code (key, nonce)} under GCM breaks both confidentiality and
 * authenticity, so it must be UNREACHABLE. It is, and here is the whole proof:
 * <ol>
 *   <li><b>Within one segment</b> (a fixed {@code (keyTerm, segmentId)}, hence a fixed
 *       DEK), the nonce is a strictly monotonic counter issued by a single
 *       {@link AtomicLong#getAndIncrement()}. Every issued value is distinct, so no
 *       nonce repeats under that DEK - unique <em>by construction</em>, not by luck.</li>
 *   <li><b>Across segments</b>, the {@code segmentId} is {@value AtRestKeys#SEGMENT_ID_LEN}
 *       bytes (128 bits) of fresh {@link SecureRandom} drawn per writer session and per
 *       rekey. Distinct segmentIds derive distinct DEKs, so even identical counter values
 *       pair with different keys (collision of two 128-bit ids is ~2^-64 after 2^32
 *       segments - cryptographically negligible, the standard random-DEK assumption).</li>
 *   <li><b>Across restarts</b>, a restart builds a NEW manager, which draws a NEW random
 *       segmentId on first write; the counter's reset-to-0 therefore pairs with a
 *       brand-new DEK, never re-touching a prior session's {@code (DEK, nonce)}.</li>
 *   <li><b>Bounded invocations per DEK</b>: when a segment's counter reaches
 *       {@link #REKEY_LIMIT} it rolls to a fresh segmentId (a new DEK, counter reset),
 *       honouring NIST SP 800-38D's guidance to rekey well before 2^32 invocations. No
 *       nonce {@code >= REKEY_LIMIT} is ever emitted for a given segmentId.</li>
 * </ol>
 * A "segment" thus maps to a per-artifact writer session: all WAL records of one boot
 * share one segment (rolled only at the rekey ceiling); each of the snapshot and
 * raft-state streams gets its own segment (a distinct DEK) since they carry distinct
 * magics.
 *
 * <h2>Thread-safety</h2>
 * {@link #nextSeal(int)} is safe under concurrent calls from the per-group owner
 * threads: the per-magic current segment lives in an {@link AtomicReference}, nonces
 * come from that segment's {@link AtomicLong}, and a rekey is a lock-free CAS install
 * of a fresh segment. Read-side derivation is cached per {@code (keyTerm, segmentId)}.
 */
public final class SegmentKeyManager implements AtRestKeys {

    /** HKDF info string for per-segment DEK derivation (distinct from the KEK/root info). */
    static final byte[] DEK_INFO =
            "configd/raft-at-rest-encryption/dek/v1".getBytes(StandardCharsets.UTF_8);

    /**
     * Rekey (roll to a fresh segmentId, hence a fresh DEK) before this many records
     * under one DEK. 2^32 is NIST SP 800-38D's invocation ceiling for a single GCM key;
     * rolling AT the ceiling keeps every DEK strictly under it.
     */
    static final long REKEY_LIMIT = 1L << 32;

    private static final String DEK_ALG = "AES";
    private static final int DEK_LEN = 32; // AES-256

    /** term -> root key. Old terms retained so old-term data still decrypts after rotation. */
    private final Map<Integer, RootKey> roots = new ConcurrentHashMap<>();
    private volatile int currentTerm;

    private final SecureRandom rng;

    /** Per-magic current write segment (the artifact's active DEK + nonce counter). */
    private final ConcurrentHashMap<Integer, AtomicReference<WriteSegment>> writeSegments =
            new ConcurrentHashMap<>();

    /** Read-side DEK cache: (keyTerm, hex(segmentId)) -> DEK. Recovery derives each once. */
    private final ConcurrentHashMap<String, SecretKey> readDekCache = new ConcurrentHashMap<>();

    /**
     * @param initialRoot the root key for the initial (current) keyring term
     */
    public SegmentKeyManager(RootKey initialRoot) {
        this(initialRoot, new SecureRandom());
    }

    SegmentKeyManager(RootKey initialRoot, SecureRandom rng) {
        Objects.requireNonNull(initialRoot, "initialRoot");
        this.rng = Objects.requireNonNull(rng, "rng");
        int term = initialRoot.keyId().version();
        this.roots.put(term, initialRoot);
        this.currentTerm = term;
    }

    /**
     * Boots a manager by unsealing the persisted root key through {@code provider}.
     * FAIL-CLOSED: an unavailable provider throws {@link KmsUnavailableException}, which
     * the boot caller must let propagate (refuse to start), never fall back.
     *
     * @param provider the configured KMS provider (unsealed once here, then dropped)
     * @param wrapped  the persisted sealed root key
     * @return a manager holding the unsealed root at its keyring term
     * @throws KmsUnavailableException if the provider cannot unseal the root (fail-closed)
     */
    public static SegmentKeyManager unsealFrom(KmsProvider provider, WrappedKey wrapped)
            throws KmsUnavailableException {
        RootKey root = provider.unwrap(wrapped);
        return new SegmentKeyManager(root);
    }

    /**
     * Installs a new root key at {@code newTerm} and makes it current. Old terms are
     * RETAINED so data written under them still decrypts (the keyring rotation rule).
     * New writes pick up the new term (the per-magic segments re-derive lazily).
     * <p>
     * This is a boot/administrative operation (for {@code local}, rotation means rotating
     * the signing key, i.e. a restart); it is not called on the hot write path.
     *
     * @param newRoot the root key for the new term (its {@code keyId().version()} is the term)
     */
    public void rotateTo(RootKey newRoot) {
        Objects.requireNonNull(newRoot, "newRoot");
        int newTerm = newRoot.keyId().version();
        roots.put(newTerm, newRoot);
        currentTerm = newTerm;
        // Force each artifact's next write to build a fresh segment at the new term. Old
        // segments (old term/segmentId) remain valid for READ via `roots`; a concurrent
        // in-flight writer may emit one more record under the old (still-retained) term.
        writeSegments.clear();
    }

    @Override
    public Seal nextSeal(int magic) {
        AtomicReference<WriteSegment> ref =
                writeSegments.computeIfAbsent(magic, m -> new AtomicReference<>(newSegment()));
        for (;;) {
            WriteSegment seg = ref.get();
            long ctr = seg.counter.getAndIncrement();
            if (ctr < REKEY_LIMIT) {
                return new Seal(seg.keyTerm, seg.segmentId, encodeNonce(ctr), seg.dek);
            }
            // Reached this DEK's invocation ceiling: roll to a fresh segment (a new DEK).
            // Exactly one CAS wins the install; losers simply retry against the current
            // segment. No nonce >= REKEY_LIMIT is ever returned for `seg`'s segmentId.
            ref.compareAndSet(seg, newSegment());
        }
    }

    @Override
    public SecretKey resolveDek(int keyTerm, byte[] segmentId) {
        return readDekCache.computeIfAbsent(
                keyTerm + ":" + HexFormat.of().formatHex(segmentId),
                k -> {
                    RootKey root = roots.get(keyTerm);
                    if (root == null) {
                        // Fail-closed: an unknown term cannot be authentically decrypted.
                        throw new IntegrityException(
                                "unknown at-rest encryption key term " + keyTerm
                                        + " (not in keyring; refusing to decrypt)");
                    }
                    return deriveDek(root, segmentId);
                });
    }

    private WriteSegment newSegment() {
        int term = currentTerm;
        RootKey root = roots.get(term);
        byte[] segmentId = new byte[SEGMENT_ID_LEN];
        rng.nextBytes(segmentId);
        SecretKey dek = deriveDek(root, segmentId);
        return new WriteSegment(term, segmentId, dek, new AtomicLong(0));
    }

    /** DEK = HKDF(IKM = root material, salt = segmentId, info = DEK_INFO, 32). */
    private static SecretKey deriveDek(RootKey root, byte[] segmentId) {
        byte[] dekBytes = root.withMaterial(m -> Hkdf.deriveKey(m, segmentId, DEK_INFO, DEK_LEN));
        try {
            // SecretKeySpec copies the bytes; wipe our transient copy. The SecretKeySpec's own
            // copy is un-wipeable (JDK-8160206) - the documented residual for the JCA bridge.
            return new SecretKeySpec(dekBytes, DEK_ALG);
        } finally {
            Arrays.fill(dekBytes, (byte) 0);
        }
    }

    /** 96-bit nonce: 4 zero bytes + the 8-byte big-endian counter (deterministic, unique per DEK). */
    private static byte[] encodeNonce(long counter) {
        byte[] nonce = new byte[NONCE_LEN];
        for (int i = 0; i < 8; i++) {
            nonce[NONCE_LEN - 1 - i] = (byte) (counter >>> (8 * i));
        }
        return nonce;
    }

    /** An artifact's active write segment: a fixed (term, segmentId, DEK) and a monotonic nonce counter. */
    private record WriteSegment(int keyTerm, byte[] segmentId, SecretKey dek, AtomicLong counter) {}
}

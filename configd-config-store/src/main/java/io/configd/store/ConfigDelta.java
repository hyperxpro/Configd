package io.configd.store;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A minimal diff between two config snapshots.
 * <p>
 * Contains only the mutations required to transform a store at
 * {@code fromVersion} into the state at {@code toVersion}.
 * <p>
 * An optional Ed25519 {@code signature} may be present when the delta
 * was produced by a leader node that has a {@link ConfigSigner} configured.
 * Edge nodes use the signature to verify delta authenticity before applying.
 * <p>
 * <b>F-0052: replay protection.</b> Each signed delta carries a monotonic
 * {@code epoch} (long, strictly increasing per signer) and an 8-byte
 * {@code nonce} (random per delta). Both fields are bound into the signature
 * payload so an attacker cannot replay a captured delta after the receiver
 * has rolled back. Older delta records — produced before F-0052 — use
 * {@code epoch == 0} and an empty nonce, which the verifier interprets as
 * an unversioned / legacy delta.
 *
 * @param fromVersion source snapshot version
 * @param toVersion   target snapshot version
 * @param mutations   ordered list of mutations (puts and deletes)
 * @param signature   optional Ed25519 signature over the delta payload (may be null)
 * @param epoch       monotonic epoch for replay protection (0 = unversioned/legacy)
 * @param nonce       random 8-byte nonce bound into the signature (empty = legacy)
 */
public record ConfigDelta(
        long fromVersion,
        long toVersion,
        List<ConfigMutation> mutations,
        byte[] signature,
        long epoch,
        byte[] nonce
) {

    /** Standard nonce length in bytes (F-0052). */
    public static final int NONCE_LEN = 8;

    public ConfigDelta {
        if (fromVersion < 0) {
            throw new IllegalArgumentException("fromVersion must be non-negative: " + fromVersion);
        }
        if (toVersion < fromVersion) {
            throw new IllegalArgumentException(
                    "toVersion (" + toVersion + ") must be >= fromVersion (" + fromVersion + ")");
        }
        Objects.requireNonNull(mutations, "mutations must not be null");
        mutations = List.copyOf(mutations); // defensive immutable copy
        signature = signature != null ? signature.clone() : null; // defensive copy
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be non-negative: " + epoch);
        }
        nonce = nonce != null ? nonce.clone() : new byte[0];
        if (nonce.length != 0 && nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException(
                    "nonce must be empty or exactly " + NONCE_LEN + " bytes, got "
                            + nonce.length);
        }
    }

    /**
     * Backward-compatible constructor that creates an unsigned legacy delta
     * (epoch 0, empty nonce).
     *
     * @param fromVersion source snapshot version
     * @param toVersion   target snapshot version
     * @param mutations   ordered list of mutations (puts and deletes)
     */
    public ConfigDelta(long fromVersion, long toVersion, List<ConfigMutation> mutations) {
        this(fromVersion, toVersion, mutations, null, 0L, new byte[0]);
    }

    /**
     * Backward-compatible constructor that creates a signed legacy delta
     * (epoch 0, empty nonce).
     *
     * @param fromVersion source snapshot version
     * @param toVersion   target snapshot version
     * @param mutations   ordered list of mutations (puts and deletes)
     * @param signature   Ed25519 signature over the canonical payload (may be null)
     */
    public ConfigDelta(long fromVersion, long toVersion,
                       List<ConfigMutation> mutations, byte[] signature) {
        this(fromVersion, toVersion, mutations, signature, 0L, new byte[0]);
    }

    /** Returns a defensive copy of the signature bytes, or null if unsigned. */
    @Override
    public byte[] signature() {
        return signature != null ? signature.clone() : null;
    }

    /** Returns a defensive copy of the nonce bytes (never null; empty = legacy). */
    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    /** True if this delta contains no mutations. */
    public boolean isEmpty() {
        return mutations.isEmpty();
    }

    /** Number of mutations in this delta. */
    public int size() {
        return mutations.size();
    }

    /**
     * Builds the canonical byte payload that must be signed and verified.
     * <p>
     * For legacy deltas ({@code epoch == 0} and empty nonce) the payload is
     * the plain batch-encoded mutations — byte-identical to the pre-F-0052
     * format, preserving existing signatures.
     * <p>
     * For F-0052 deltas (non-zero epoch) the payload binds the mutation
     * set together with the epoch and nonce, so a replayed delta re-signed
     * under a fresh epoch cannot be substituted:
     * {@code encodeBatch(mutations) || BE(epoch, 8) || nonce}.
     *
     * @return the canonical signable payload
     */
    public byte[] signingPayload() {
        byte[] batch = CommandCodec.encodeBatch(mutations);
        if (epoch == 0L && nonce.length == 0) {
            return batch; // legacy form — keeps old signatures valid
        }
        ByteBuffer buf = ByteBuffer.allocate(batch.length + Long.BYTES + nonce.length);
        buf.put(batch);
        buf.putLong(epoch);
        buf.put(nonce);
        return buf.array();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConfigDelta that
                && this.fromVersion == that.fromVersion
                && this.toVersion == that.toVersion
                && this.mutations.equals(that.mutations)
                && Arrays.equals(this.signature, that.signature)
                && this.epoch == that.epoch
                && Arrays.equals(this.nonce, that.nonce);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(fromVersion);
        result = 31 * result + Long.hashCode(toVersion);
        result = 31 * result + mutations.hashCode();
        result = 31 * result + Arrays.hashCode(signature);
        result = 31 * result + Long.hashCode(epoch);
        result = 31 * result + Arrays.hashCode(nonce);
        return result;
    }

    @Override
    public String toString() {
        return "ConfigDelta[fromVersion=" + fromVersion
                + ", toVersion=" + toVersion
                + ", mutations=" + mutations
                + ", signed=" + (signature != null)
                + ", epoch=" + epoch + "]";
    }
}

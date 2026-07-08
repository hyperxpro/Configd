package io.configd.client.edge.session;

import io.configd.client.ChainVerificationException;
import io.configd.client.EpochStore;
import io.configd.store.ConfigDelta;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;

/**
 * Verifies the signed delta chain the client applies (OV7 / §04), in one of two operator-chosen modes:
 *
 * <ul>
 *   <li><b>VERIFY</b> (a leader public key configured — the production default): a verifier is <b>always</b>
 *       present. Each delta must be signed (else fail-closed), carry {@code epoch > 0} (a signature on an
 *       {@code epoch == 0} delta is refused — it would strip the version-position binding), pass the Ed25519
 *       check over {@link ConfigDelta#signingPayload()}, and advance the monotonic epoch high-water (a delta
 *       whose {@code epoch <= highestSeen} is a replay). Any failure is a security event surfaced as a
 *       {@link ChainVerificationException}, which the caller treats as fail-closed (tear the connection down —
 *       the peer is not trustworthy).</li>
 *   <li><b>TRUST-UNVERIFIED</b> (an explicit opt-out, no key): deltas are applied without a cryptographic
 *       check, signed or not — for a genuinely unsigned deployment.</li>
 * </ul>
 *
 * <p>There is no "no verifier but reject signed" middle state: the mode is a clean binary the operator chooses
 * explicitly. The frozen signing bytes live in {@link ConfigDelta#signingPayload()} (in {@code configd-wire}),
 * so this is a direct {@code Signature("Ed25519")} verify — byte-identical to the server's {@code ConfigSigner}
 * (including its "an invalid Ed25519 point throws {@link SignatureException} ⇒ treat as a failed verify"
 * behavior). This class does not depend on {@code ConfigSigner}.
 *
 * <p><b>Not thread-safe</b> by design: the verify + epoch high-water advance run on the single reader thread,
 * matching the reference {@code DeltaApplier}'s single-writer model.
 */
public final class SignedChainVerifier {

    private static final String ALGORITHM = "Ed25519";

    private final boolean verify;
    private final PublicKey key;          // non-null iff verify
    private final EpochStore epochStore;  // non-null iff verify
    private long highestSeenEpoch;

    private SignedChainVerifier(boolean verify, PublicKey key, EpochStore epochStore) {
        this.verify = verify;
        this.key = key;
        this.epochStore = epochStore;
        this.highestSeenEpoch = verify ? epochStore.load() : 0L;
    }

    /** VERIFY mode: check every delta against {@code leaderKey}, tracking replay in {@code epochStore}. */
    public static SignedChainVerifier verifying(PublicKey leaderKey, EpochStore epochStore) {
        Objects.requireNonNull(leaderKey, "leaderKey");
        Objects.requireNonNull(epochStore, "epochStore");
        return new SignedChainVerifier(true, leaderKey, epochStore);
    }

    /** TRUST-UNVERIFIED mode: apply deltas without a cryptographic check (explicit opt-out). */
    public static SignedChainVerifier trustUnverified() {
        return new SignedChainVerifier(false, null, null);
    }

    /** True iff this verifier cryptographically checks the chain (VERIFY mode). */
    public boolean isVerifying() {
        return verify;
    }

    /**
     * Verifies one delta's authenticity and replay-freshness (VERIFY mode); a no-op in TRUST-UNVERIFIED mode.
     * Does <b>not</b> advance the epoch high-water — that happens only when the delta is actually applied, via
     * {@link #recordApplied(ConfigDelta)} — so a delta that verifies but is then a stale/gap is not counted.
     *
     * @throws ChainVerificationException on an unsigned delta, an {@code epoch == 0} signed delta, a bad
     *                                    signature, or an epoch replay (all fail-closed)
     */
    public void verify(ConfigDelta delta) {
        if (!verify) {
            return;
        }
        byte[] signature = delta.signature();
        if (signature == null) {
            throw new ChainVerificationException(
                    "unsigned delta rejected [" + delta.fromVersion() + "->" + delta.toVersion()
                            + "]: VERIFY mode requires a signature");
        }
        if (delta.epoch() == 0L) {
            throw new ChainVerificationException(
                    "signed delta with epoch 0 rejected [" + delta.fromVersion() + "->" + delta.toVersion()
                            + "]: a signature requires epoch > 0 (the version-position binding)");
        }
        if (!ed25519Verify(delta.signingPayload(), signature)) {
            throw new ChainVerificationException(
                    "delta signature verification failed [" + delta.fromVersion() + "->" + delta.toVersion()
                            + "]");
        }
        if (delta.epoch() <= highestSeenEpoch) {
            throw new ChainVerificationException(
                    "epoch replay rejected [" + delta.fromVersion() + "->" + delta.toVersion() + "]: epoch "
                            + delta.epoch() + " <= highest-seen " + highestSeenEpoch);
        }
    }

    /**
     * Advances (and persists) the epoch high-water after a delta has been successfully applied. Idempotent and
     * monotonic; a no-op in TRUST-UNVERIFIED mode.
     */
    public void recordApplied(ConfigDelta delta) {
        if (!verify) {
            return;
        }
        if (delta.epoch() > highestSeenEpoch) {
            highestSeenEpoch = delta.epoch();
            epochStore.save(highestSeenEpoch);
        }
    }

    /** The highest signing epoch accepted so far (diagnostics / tests). */
    public long highestSeenEpoch() {
        return highestSeenEpoch;
    }

    private boolean ed25519Verify(byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (SignatureException e) {
            // A corrupted signature can make Ed25519 throw (invalid point) rather than return false — treat
            // as a failed verification (the frozen ConfigSigner.verify semantic).
            return false;
        } catch (GeneralSecurityException e) {
            // A verification setup fault (bad key/alg) is fail-closed: never accept an unverifiable delta.
            return false;
        }
    }
}

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
 * Verifies signed delta chain: VERIFY mode (with key, fail-closed on crypto failures, replay detection via
 * epoch monotonic) or TRUST-UNVERIFIED (explicit opt-out, no cryptographic check). Ed25519 verification via
 * signingPayload(); byte-identical to server's ConfigSigner. Single-threaded: reader thread only.
 */
public final class SignedChainVerifier {

    private static final String ALGORITHM = "Ed25519";

    private final boolean verify;
    private final PublicKey key;
    private final EpochStore epochStore;
    private long highestSeenEpoch;

    private SignedChainVerifier(boolean verify, PublicKey key, EpochStore epochStore) {
        this.verify = verify;
        this.key = key;
        this.epochStore = epochStore;
        this.highestSeenEpoch = verify ? epochStore.load() : 0L;
    }

    public static SignedChainVerifier verifying(PublicKey leaderKey, EpochStore epochStore) {
        Objects.requireNonNull(leaderKey, "leaderKey");
        Objects.requireNonNull(epochStore, "epochStore");
        return new SignedChainVerifier(true, leaderKey, epochStore);
    }

    public static SignedChainVerifier trustUnverified() {
        return new SignedChainVerifier(false, null, null);
    }

    public boolean isVerifying() {
        return verify;
    }

    /** Verifies authenticity and replay-freshness (VERIFY) or no-op (TRUST-UNVERIFIED). */
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

    /** Advances epoch high-water after delta applied (VERIFY) or no-op (TRUST-UNVERIFIED). */
    public void recordApplied(ConfigDelta delta) {
        if (!verify) {
            return;
        }
        if (delta.epoch() > highestSeenEpoch) {
            highestSeenEpoch = delta.epoch();
            epochStore.save(highestSeenEpoch);
        }
    }

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
            // Corrupted signatures (invalid Ed25519 point) throw rather than return false; treat as failed.
            return false;
        } catch (GeneralSecurityException e) {
            // Verification setup fault is fail-closed: never accept unverifiable delta.
            return false;
        }
    }
}

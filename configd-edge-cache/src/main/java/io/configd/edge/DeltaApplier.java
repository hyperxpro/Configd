package io.configd.edge;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSigner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32C;

/**
 * Receives deltas from the distribution service and applies them to the
 * {@link EdgeConfigClient}. Handles gap detection: if a delta's
 * {@code fromVersion} does not match the client's current version,
 * flags a gap and requests full sync.
 * <p>
 * When an optional {@link ConfigSigner} verifier is configured, each delta's
 * Ed25519 signature is checked before application. Unsigned deltas or deltas
 * with invalid signatures are rejected (skipped with a warning log).
 * <p>
 * <b>Threading model:</b> this class is designed to be run on a single
 * dedicated thread (or virtual thread). It is NOT thread-safe — all
 * method calls must be serialized by the caller. This is by design:
 * the single-writer model avoids synchronization on the write path.
 * <p>
 * <b>Gap handling flow:</b>
 * <ol>
 *   <li>Delta arrives with {@code fromVersion} != {@code currentVersion} → gap detected</li>
 *   <li>{@link #pendingGap()} returns {@code true}, signaling the caller to request a full snapshot</li>
 *   <li>Caller loads the full snapshot via {@link EdgeConfigClient#loadSnapshot}</li>
 *   <li>Caller calls {@link #resetGap()} to clear the gap flag</li>
 *   <li>Subsequent deltas with matching versions are applied normally</li>
 * </ol>
 *
 * @see EdgeConfigClient
 */
public final class DeltaApplier {

    private static final Logger LOG = Logger.getLogger(DeltaApplier.class.getName());

    /**
     * SEC-017 (iter-2): filename of the epoch sidecar inside the snapshot
     * directory. Layout is fixed at {@code [8B big-endian epoch][4B
     * big-endian CRC32C(epoch)]} so the file is exactly {@value
     * #EPOCH_LOCK_BYTES} bytes; any other size is treated as corruption
     * and silently demoted to {@code epoch = 0}.
     */
    static final String EPOCH_LOCK_FILENAME = "epoch.lock";
    private static final int EPOCH_LOCK_BYTES = 12;

    /**
     * Result of attempting to apply a delta.
     */
    public enum ApplyResult {
        /** Delta was successfully applied. */
        APPLIED,
        /** Delta's fromVersion does not match the current version — gap detected. */
        GAP_DETECTED,
        /** Delta's toVersion is at or behind the current version — stale, ignored. */
        STALE_DELTA,
        /** Delta was rejected because it has no signature and verification is required. */
        UNSIGNED_REJECTED,
        /** Delta was rejected because its signature failed verification. */
        SIGNATURE_INVALID,
        /**
         * Delta was rejected because its epoch is at or below the highest
         * previously seen epoch (replay protection, F-0052).
         */
        REPLAY_REJECTED
    }

    private final EdgeConfigClient client;

    /**
     * Optional verifier for Ed25519 signature checking. When non-null,
     * every delta must carry a valid signature or it will be rejected.
     * <p>
     * F-0052: when {@code null}, signed deltas ({@code signature != null})
     * are rejected fail-closed — operators who forgot to configure a key
     * do not accidentally accept attacker-signed payloads.
     */
    private final ConfigSigner verifier;

    /** True if a gap has been detected and full sync is needed. */
    private boolean gapDetected;

    /** The version of the last successfully applied delta. */
    private long lastAppliedVersion;

    /**
     * F-0052: the highest epoch seen in a successfully verified delta.
     * Any subsequent delta with {@code epoch > 0 && epoch <= highestSeenEpoch}
     * is rejected as a replay. Legacy deltas with {@code epoch == 0} skip
     * this check (there is no monotonic ordering to enforce for them).
     */
    private long highestSeenEpoch;

    /**
     * SEC-017 (iter-2): path to the epoch sidecar file
     * ({@value #EPOCH_LOCK_FILENAME}) inside the local snapshot directory.
     * When non-null, every successful epoch advance is persisted (atomic
     * temp + rename) so a process restart cannot accept an older
     * leader-signed delta as fresh. When null, persistence is disabled —
     * legacy / in-memory test path.
     */
    private final Path epochLockPath;

    /**
     * Creates a delta applier with optional signature verification and
     * <em>no</em> on-disk epoch persistence. Suitable for in-memory tests.
     * Production callers must use the four-argument constructor.
     *
     * @param client   the edge config client to apply deltas to (non-null)
     * @param verifier optional Ed25519 verifier (may be null)
     */
    public DeltaApplier(EdgeConfigClient client, ConfigSigner verifier) {
        this(client, verifier, null);
    }

    /**
     * SEC-017 (iter-2) — full constructor: applier, verifier, and the
     * directory under which the epoch sidecar ({@value #EPOCH_LOCK_FILENAME})
     * is read on construction and rewritten on every successful epoch
     * advance. The sidecar guarantees that a process restart preserves the
     * highest-seen epoch — without it, a hostile principal could replay an
     * older leader-signed delta past a restart boundary.
     *
     * <p>If {@code snapshotDir} is non-null but the sidecar is absent /
     * corrupt / unreadable, the applier starts with {@code highestSeenEpoch
     * = 0} (fail-open for first-boot legacy → migrated nodes). The next
     * successful delta will overwrite the sidecar with a valid record.
     *
     * @param client      the edge config client (non-null)
     * @param verifier    optional Ed25519 verifier (may be null)
     * @param snapshotDir optional directory for {@value #EPOCH_LOCK_FILENAME}
     *                    (may be null to disable persistence)
     */
    public DeltaApplier(EdgeConfigClient client, ConfigSigner verifier, Path snapshotDir) {
        Objects.requireNonNull(client, "client must not be null");
        this.client = client;
        this.verifier = verifier;
        this.lastAppliedVersion = client.currentVersion();
        this.gapDetected = false;
        this.epochLockPath = (snapshotDir == null) ? null : snapshotDir.resolve(EPOCH_LOCK_FILENAME);
        this.highestSeenEpoch = readPersistedEpoch();
    }

    /**
     * Creates a delta applier that applies deltas to the given client.
     * <p>
     * <b>F-0052:</b> with no verifier configured, this applier accepts only
     * <em>unsigned legacy</em> deltas. Any delta carrying a signature is
     * rejected — an operator who forgot to configure the verifier must not
     * silently accept attacker-signed payloads. See the two-argument
     * constructor for production wiring.
     *
     * @param client the edge config client to apply deltas to (non-null)
     */
    public DeltaApplier(EdgeConfigClient client) {
        this(client, null, null);
    }

    /**
     * Offers a delta for application. The delta is evaluated against the
     * client's current version:
     * <ul>
     *   <li>If verifier is configured and delta is unsigned: rejected.</li>
     *   <li>If verifier is configured and signature is invalid: rejected.</li>
     *   <li>If {@code delta.toVersion() <= currentVersion}: stale delta, ignored.</li>
     *   <li>If {@code delta.fromVersion() != currentVersion}: gap detected.</li>
     *   <li>Otherwise: delta is applied successfully.</li>
     * </ul>
     *
     * @param delta the delta to apply (non-null)
     * @return the result of the apply attempt
     */
    public ApplyResult offer(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");

        byte[] signature = delta.signature();

        // F-0052 fail-closed: with no verifier configured, signed deltas
        // must not be trusted. Accepting them silently would reintroduce the
        // "security claim overstated vs. actual wiring" class of bug.
        if (verifier == null && signature != null) {
            LOG.warning("Rejecting signed delta [" + delta.fromVersion()
                    + " -> " + delta.toVersion()
                    + "]: no verifier configured on this DeltaApplier");
            return ApplyResult.UNSIGNED_REJECTED;
        }

        // Signature verification (if verifier is configured)
        if (verifier != null) {
            if (signature == null) {
                LOG.warning("Rejecting unsigned delta [" + delta.fromVersion()
                        + " -> " + delta.toVersion() + "]: signature verification is required");
                return ApplyResult.UNSIGNED_REJECTED;
            }
            try {
                byte[] payload = buildVerificationPayload(delta);
                if (!verifier.verify(payload, signature)) {
                    LOG.warning("Rejecting delta [" + delta.fromVersion()
                            + " -> " + delta.toVersion() + "]: signature verification failed");
                    return ApplyResult.SIGNATURE_INVALID;
                }
            } catch (GeneralSecurityException e) {
                LOG.log(Level.WARNING, "Rejecting delta [" + delta.fromVersion()
                        + " -> " + delta.toVersion() + "]: signature verification error", e);
                return ApplyResult.SIGNATURE_INVALID;
            }

            // F-0052 replay protection: once we have seen an epoch, any
            // future delta must advance it. Epoch 0 is "unversioned" and
            // skipped for back-compat with legacy deltas.
            long deltaEpoch = delta.epoch();
            if (deltaEpoch > 0 && deltaEpoch <= highestSeenEpoch) {
                LOG.warning("Rejecting replay of delta [" + delta.fromVersion()
                        + " -> " + delta.toVersion() + "]: epoch " + deltaEpoch
                        + " <= highestSeenEpoch " + highestSeenEpoch);
                return ApplyResult.REPLAY_REJECTED;
            }
        }

        long currentVersion = client.currentVersion();

        // Stale delta — already at or past this version
        if (delta.toVersion() <= currentVersion) {
            return ApplyResult.STALE_DELTA;
        }

        // Gap detection — fromVersion must match current version
        if (delta.fromVersion() != currentVersion) {
            gapDetected = true;
            return ApplyResult.GAP_DETECTED;
        }

        // Apply the delta
        client.applyDelta(delta);
        lastAppliedVersion = delta.toVersion();
        if (delta.epoch() > highestSeenEpoch) {
            highestSeenEpoch = delta.epoch();
            // SEC-017 (iter-2): persist the epoch advance so a restart
            // cannot replay an older leader-signed delta as fresh. Failure
            // to persist is logged but does not fail the apply — the in-
            // memory check still rejects replays for the lifetime of the
            // process; a subsequent advance will retry.
            persistEpoch(highestSeenEpoch);
        }
        return ApplyResult.APPLIED;
    }

    /**
     * Returns the highest epoch that has been accepted by this applier.
     * Exposed for diagnostics and regression tests (F-0052).
     */
    public long highestSeenEpoch() {
        return highestSeenEpoch;
    }

    /**
     * Builds the canonical byte payload for signature verification.
     * <p>
     * The leader normalizes all commands to batch-canonical form before
     * signing (see {@code ConfigStateMachine.canonicalize}). We must use
     * the same canonical form here: always encode as a BATCH regardless
     * of mutation count. This guarantees byte-identical payloads on both
     * the sign and verify paths.
     *
     * @param delta the delta to build a verification payload from
     * @return the canonical byte payload (batch-encoded)
     */
    private byte[] buildVerificationPayload(ConfigDelta delta) {
        // F-0052: payload binds mutations with epoch and nonce so replayed
        // deltas re-signed under a fresh epoch cannot be substituted.
        // Legacy deltas (epoch=0, empty nonce) reduce to the historical
        // batch-encoded form exactly — byte-identical.
        return delta.signingPayload();
    }

    /**
     * Returns {@code true} if a gap was detected and a full snapshot sync
     * is required to recover.
     *
     * @return {@code true} if gap recovery is pending
     */
    public boolean pendingGap() {
        return gapDetected;
    }

    /**
     * Resets the gap flag after a full snapshot has been loaded.
     * The caller must load the snapshot into the client before calling
     * this method.
     */
    public void resetGap() {
        gapDetected = false;
        lastAppliedVersion = client.currentVersion();
    }

    /**
     * Returns the version of the last successfully applied delta,
     * or the client's initial version if no deltas have been applied.
     *
     * @return the last applied version
     */
    public long lastAppliedVersion() {
        return lastAppliedVersion;
    }

    /**
     * SEC-017 (iter-2): reads the persisted epoch from
     * {@value #EPOCH_LOCK_FILENAME}. Returns 0 if no path is configured,
     * the file is missing, the wrong size, or the CRC32C check fails.
     * Any read failure is treated as "no record" — the next successful
     * delta will rewrite a valid sidecar.
     */
    private long readPersistedEpoch() {
        if (epochLockPath == null || !Files.exists(epochLockPath)) {
            return 0L;
        }
        try {
            byte[] data = Files.readAllBytes(epochLockPath);
            if (data.length != EPOCH_LOCK_BYTES) {
                LOG.warning("epoch.lock has unexpected size " + data.length
                        + " (expected " + EPOCH_LOCK_BYTES + "); ignoring");
                return 0L;
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            long epoch = buf.getLong();
            int storedCrc = buf.getInt();
            CRC32C crc = new CRC32C();
            crc.update(data, 0, 8);
            int actualCrc = (int) crc.getValue();
            if (storedCrc != actualCrc) {
                LOG.warning("epoch.lock CRC32C mismatch (stored=" + storedCrc
                        + " actual=" + actualCrc + "); ignoring");
                return 0L;
            }
            if (epoch < 0) {
                LOG.warning("epoch.lock contains negative epoch " + epoch + "; ignoring");
                return 0L;
            }
            return epoch;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "epoch.lock read failed; treating as absent", e);
            return 0L;
        }
    }

    /**
     * SEC-017 (iter-2): atomically rewrites {@value #EPOCH_LOCK_FILENAME}
     * with the given epoch + CRC32C. Uses temp + {@code ATOMIC_MOVE}; falls
     * back to non-atomic replace on filesystems that don't support it.
     * I/O failures are logged but not propagated — losing one persistence
     * round is preferable to crashing the edge cache mid-replay-rejection.
     */
    private void persistEpoch(long epoch) {
        if (epochLockPath == null) {
            return;
        }
        try {
            Path dir = epochLockPath.getParent();
            if (dir != null && !Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            ByteBuffer buf = ByteBuffer.allocate(EPOCH_LOCK_BYTES);
            buf.putLong(epoch);
            CRC32C crc = new CRC32C();
            crc.update(buf.array(), 0, 8);
            buf.putInt((int) crc.getValue());

            Path tmp = (dir == null ? epochLockPath.resolveSibling(EPOCH_LOCK_FILENAME + ".tmp")
                    : dir.resolve(EPOCH_LOCK_FILENAME + ".tmp"));
            Files.write(tmp, buf.array());
            try {
                Files.move(tmp, epochLockPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, epochLockPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "epoch.lock persist failed for epoch=" + epoch, e);
        }
    }
}

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
 * dedicated thread (or virtual thread). It is NOT thread-safe - all
 * method calls must be serialized by the caller. This is by design:
 * the single-writer model avoids synchronization on the write path.
 * <p>
 * <b>Gap handling flow:</b>
 * <ol>
 *   <li>Delta arrives with {@code fromVersion} != {@code currentVersion} -> gap detected</li>
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
     * Filename of the epoch sidecar inside the snapshot directory. Layout is
     * fixed at {@code [8B big-endian epoch][4B big-endian CRC32C(epoch)]} so
     * the file is exactly {@value #EPOCH_LOCK_BYTES} bytes; any other size is
     * treated as corruption and silently demoted to {@code epoch = 0}.
     */
    static final String EPOCH_LOCK_FILENAME = "epoch.lock";
    private static final int EPOCH_LOCK_BYTES = 12;

    /**
     * Result of attempting to apply a delta.
     */
    public enum ApplyResult {
        /** Delta was successfully applied. */
        APPLIED,
        /** Delta's fromVersion does not match the current version - gap detected. */
        GAP_DETECTED,
        /** Delta's toVersion is at or behind the current version - stale, ignored. */
        STALE_DELTA,
        /** Delta was rejected because it has no signature and verification is required. */
        UNSIGNED_REJECTED,
        /** Delta was rejected because its signature failed verification. */
        SIGNATURE_INVALID,
        /**
         * Delta was rejected because its epoch is at or below the highest
         * previously seen epoch (replay protection).
         */
        REPLAY_REJECTED
    }

    private final EdgeConfigClient client;

    /**
     * Optional verifier for Ed25519 signature checking. When non-null,
     * every delta must carry a valid signature or it will be rejected.
     * <p>
     * When {@code null}, signed deltas ({@code signature != null}) are
     * rejected fail-closed -- operators who forgot to configure a key do
     * not accidentally accept attacker-signed payloads.
     */
    private final ConfigSigner verifier;

    /** True if a gap has been detected and full sync is needed. */
    private boolean gapDetected;

    /**
     * True when the server is filtering this session server-side. In filtered mode
     * the delivered version chain is intentionally non-contiguous - dropped non-matching deltas
     * bumped the global version - so gap detection relaxes to forward-only (a jump is expected;
     * only a regression below the applied version is a genuine gap), and the store apply bridges
     * the jump to {@code toVersion}. Set by {@link #setFilteredMode(boolean)} from the
     * SUBSCRIBE_OK {@code filtered} confirm. Off = the byte-identical classic contiguity path.
     */
    private boolean filteredMode;

    /** The version of the last successfully applied delta. */
    private long lastAppliedVersion;

    /**
     * The highest epoch seen in a successfully verified delta. Any subsequent
     * delta with {@code epoch > 0 && epoch <= highestSeenEpoch} is rejected
     * as a replay. Legacy deltas with {@code epoch == 0} skip this check
     * (there is no monotonic ordering to enforce for them).
     */
    private long highestSeenEpoch;

    /**
     * Path to the epoch sidecar file ({@value #EPOCH_LOCK_FILENAME}) inside
     * the local snapshot directory. When non-null, every successful epoch
     * advance is persisted (atomic temp + rename) so a process restart cannot
     * accept an older leader-signed delta as fresh. When null, persistence is
     * disabled - legacy or in-memory test path.
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
     * Full constructor: applier, verifier, and the directory under which the
     * epoch sidecar ({@value #EPOCH_LOCK_FILENAME}) is read on construction
     * and rewritten on every successful epoch advance. The sidecar guarantees
     * that a process restart preserves the highest-seen epoch -- without it,
     * a hostile principal could replay an older leader-signed delta past a
     * restart boundary.
     *
     * <p>If {@code snapshotDir} is non-null but the sidecar is absent,
     * corrupt, or unreadable, the applier starts with {@code highestSeenEpoch
     * = 0} (fail-open for first-boot or legacy migrated nodes). The next
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
     * Creates a delta applier with no verifier configured. Accepts only
     * <em>unsigned legacy</em> deltas. Any delta carrying a signature is
     * rejected -- an operator who forgot to configure the verifier must not
     * silently accept attacker-signed payloads. See the two-argument
     * constructor for production wiring.
     *
     * @param client the edge config client to apply deltas to (non-null)
     */
    public DeltaApplier(EdgeConfigClient client) {
        this(client, null, null);
    }

    /**
     * Offers a delta for application, threading the leader commit timestamp into the
     * staleness frontier on a successful apply. The delta is evaluated against the
     * client's current version:
     * <ul>
     *   <li>If verifier is configured and delta is unsigned: rejected.</li>
     *   <li>If verifier is configured and signature is invalid: rejected.</li>
     *   <li>If {@code delta.toVersion() <= currentVersion}: stale delta, ignored.</li>
     *   <li>If {@code delta.fromVersion() != currentVersion}: gap detected.</li>
     *   <li>Otherwise: delta is applied and the covered frontier advances to
     *       {@code commitTimestampMillis}.</li>
     * </ul>
     * <p>
     * There is deliberately no convenience overload that defaults the timestamp from a local
     * clock: that would re-create idle-time-proxy staleness ("staleness = time-since-last-apply")
     * for any caller that took the easy path. The commit timestamp is mandatory and validated;
     * a caller without a leader timestamp passes its own clock's now explicitly, stating the
     * meaning at the call site.
     *
     * @param delta                 the delta to apply (non-null)
     * @param commitTimestampMillis the leader commit timestamp (the covered-frontier clock;
     *                              must be {@code >= 0} - {@code CommitNotification}
     *                              guarantees this on the wire path)
     * @return the result of the apply attempt
     */
    public ApplyResult offer(ConfigDelta delta, long commitTimestampMillis) {
        Objects.requireNonNull(delta, "delta must not be null");
        if (commitTimestampMillis < 0) {
            throw new IllegalArgumentException(
                    "commitTimestampMillis must be >= 0 (the local-clock fallback sentinel "
                            + "was deleted — Finding 5): "
                            + commitTimestampMillis);
        }

        byte[] signature = delta.signature();

        // Fail-closed: with no verifier configured, signed deltas must not be
        // trusted. Accepting them silently would reintroduce the "security
        // claim overstated vs. actual wiring" class of bug.
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
            // Defense-in-depth: the leader always signs with epoch > 0 (the position + epoch +
            // nonce payload). A signature carried on an epoch-0 delta is not a shape production
            // emits; reject it rather than fall back to the legacy batch-only verification form,
            // which would strip the position binding.
            if (delta.epoch() == 0L) {
                LOG.warning("Rejecting signed delta [" + delta.fromVersion()
                        + " -> " + delta.toVersion() + "]: a signature requires epoch > 0");
                return ApplyResult.SIGNATURE_INVALID;
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

            // Replay protection: once we have seen an epoch, any future delta
            // must advance it. Epoch 0 is "unversioned" and skipped for
            // back-compat with legacy deltas.
            long deltaEpoch = delta.epoch();
            if (deltaEpoch > 0 && deltaEpoch <= highestSeenEpoch) {
                LOG.warning("Rejecting replay of delta [" + delta.fromVersion()
                        + " -> " + delta.toVersion() + "]: epoch " + deltaEpoch
                        + " <= highestSeenEpoch " + highestSeenEpoch);
                return ApplyResult.REPLAY_REJECTED;
            }
        }

        long currentVersion = client.currentVersion();

        // Stale delta - already at or past this version
        if (delta.toVersion() <= currentVersion) {
            return ApplyResult.STALE_DELTA;
        }

        // Gap detection. Classic mode requires strict contiguity (fromVersion == currentVersion).
        // Filtered mode relaxes to forward-only: a jump (fromVersion > currentVersion) is expected
        // because non-matching deltas were dropped server-side; only a regression below the
        // applied version is a genuine gap.
        if (filteredMode) {
            if (delta.fromVersion() < currentVersion) {
                gapDetected = true;
                return ApplyResult.GAP_DETECTED;
            }
        } else if (delta.fromVersion() != currentVersion) {
            gapDetected = true;
            return ApplyResult.GAP_DETECTED;
        }

        // Apply the delta (subscription filter inside the client; covered frontier from the leader
        // commit timestamp -- always passed explicitly by the caller). Filtered mode bridges the
        // intentional version jump; classic mode applies contiguously.
        if (filteredMode) {
            client.applyDeltaBridged(delta, commitTimestampMillis);
        } else {
            client.applyDelta(delta, commitTimestampMillis);
        }
        lastAppliedVersion = delta.toVersion();
        if (delta.epoch() > highestSeenEpoch) {
            highestSeenEpoch = delta.epoch();
            // Persist the epoch advance so a restart cannot replay an older
            // leader-signed delta as fresh. Failure to persist is logged but
            // does not fail the apply -- the in-memory check still rejects
            // replays for the lifetime of the process; a subsequent advance
            // will retry.
            persistEpoch(highestSeenEpoch);
        }
        return ApplyResult.APPLIED;
    }

    /**
     * Returns the highest epoch that has been accepted by this applier.
     * Exposed for diagnostics and regression tests.
     */
    public long highestSeenEpoch() {
        return highestSeenEpoch;
    }

    /**
     * Selects the server-side-filtered apply mode: forward-only gap detection and a
     * version-bridged store apply. Set from the SUBSCRIBE_OK {@code filtered} confirm; the
     * default is the classic strict-contiguity mode.
     *
     * @param filtered true to accept a non-contiguous (forward-jumping) delivered version chain
     */
    public void setFilteredMode(boolean filtered) {
        this.filteredMode = filtered;
    }

    /** Whether this applier is in the server-side-filtered apply mode. */
    public boolean filteredMode() {
        return filteredMode;
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
        // Payload binds mutations with epoch and nonce so replayed deltas
        // re-signed under a fresh epoch cannot be substituted. Legacy deltas
        // (epoch=0, empty nonce) reduce to the historical batch-encoded form
        // exactly -- byte-identical.
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
     * Reads the persisted epoch from {@value #EPOCH_LOCK_FILENAME}. Returns 0
     * if no path is configured, the file is missing, the wrong size, or the
     * CRC32C check fails. Any read failure is treated as "no record" -- the
     * next successful delta will rewrite a valid sidecar.
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
     * Atomically rewrites {@value #EPOCH_LOCK_FILENAME} with the given epoch
     * and CRC32C. Uses temp + {@code ATOMIC_MOVE}; falls back to non-atomic
     * replace on filesystems that do not support it. I/O failures are logged
     * but not propagated -- losing one persistence round is preferable to
     * crashing the edge cache mid-replay-rejection.
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

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
 * Applies deltas: gap detection, signature verification, replay protection.
 * Single-writer; NOT thread-safe (caller must serialize). Epoch persisted to disk for replay protection.
 */
public final class DeltaApplier {

    private static final Logger LOG = Logger.getLogger(DeltaApplier.class.getName());

    // Epoch sidecar: [8B epoch][4B CRC32C(epoch)]. Persists replay protection across restart.
    static final String EPOCH_LOCK_FILENAME = "epoch.lock";
    private static final int EPOCH_LOCK_BYTES = 12;

    public enum ApplyResult {
        APPLIED,
        GAP_DETECTED,
        STALE_DELTA,
        UNSIGNED_REJECTED,
        SIGNATURE_INVALID,
        REPLAY_REJECTED
    }

    private final EdgeConfigClient client;
    private final ConfigSigner verifier;
    private boolean gapDetected;
    private boolean filteredMode;
    private long lastAppliedVersion;
    private long highestSeenEpoch;
    private final Path epochLockPath;

    public DeltaApplier(EdgeConfigClient client, ConfigSigner verifier) {
        this(client, verifier, null);
    }

    public DeltaApplier(EdgeConfigClient client, ConfigSigner verifier, Path snapshotDir) {
        Objects.requireNonNull(client, "client must not be null");
        this.client = client;
        this.verifier = verifier;
        this.lastAppliedVersion = client.currentVersion();
        this.gapDetected = false;
        this.epochLockPath = (snapshotDir == null) ? null : snapshotDir.resolve(EPOCH_LOCK_FILENAME);
        this.highestSeenEpoch = readPersistedEpoch();
    }

    public DeltaApplier(EdgeConfigClient client) {
        this(client, null, null);
    }

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

    public long highestSeenEpoch() {
        return highestSeenEpoch;
    }

    public void setFilteredMode(boolean filtered) {
        this.filteredMode = filtered;
    }

    public boolean filteredMode() {
        return filteredMode;
    }

    private byte[] buildVerificationPayload(ConfigDelta delta) {
        // Payload binds mutations with epoch and nonce so replayed deltas
        // re-signed under a fresh epoch cannot be substituted. Legacy deltas
        // (epoch=0, empty nonce) reduce to the historical batch-encoded form
        // exactly -- byte-identical.
        return delta.signingPayload();
    }

    public boolean pendingGap() {
        return gapDetected;
    }

    public void resetGap() {
        gapDetected = false;
        lastAppliedVersion = client.currentVersion();
    }

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

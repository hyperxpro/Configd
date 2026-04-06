package io.configd.store;

import io.configd.common.Clock;
import io.configd.raft.StateMachine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies committed Raft log entries to the {@link VersionedConfigStore}.
 * <p>
 * This is the bridge between the Raft consensus layer and the MVCC config
 * store. Each committed log entry is deserialized using {@link CommandCodec}
 * and applied to the store as a put, delete, or atomic batch.
 * <p>
 * <b>Thread safety:</b> This class is designed to be called from the Raft
 * apply thread only (single-threaded). No internal synchronization is provided
 * for the {@link #apply} method. Listeners are stored in a
 * {@link CopyOnWriteArrayList} so that {@link #addListener} may be called
 * from any thread, but listener notification happens on the apply thread.
 * <p>
 * <b>Snapshot format (binary):</b>
 * <pre>
 *   [8-byte sequence counter]
 *   [4-byte entry count]
 *   for each entry:
 *     [4-byte key length][key bytes][4-byte value length][value bytes]
 * </pre>
 * The snapshot format is deterministic: entries are serialized in the order
 * returned by {@link HamtMap#forEach}, which is consistent for the same
 * logical map contents.
 *
 * @see StateMachine
 * @see VersionedConfigStore
 * @see CommandCodec
 */
public final class ConfigStateMachine implements StateMachine {

    private static final Logger LOG = Logger.getLogger(ConfigStateMachine.class.getName());

    private final VersionedConfigStore store;
    private final Clock clock;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Optional invariant monitor for runtime assertion checking (Rule 13).
     * When non-null, every apply checks sequence monotonicity and gap-freedom.
     * In test mode, violations throw immediately; in production, they increment
     * a metric counter.
     */
    private final InvariantChecker invariantChecker;

    /**
     * Optional Ed25519 signer for signing applied entries. When non-null,
     * each PUT/DELETE/BATCH apply computes a signature over the command
     * bytes. The signature can be retrieved via {@link #lastSignature()}.
     */
    private final ConfigSigner signer;

    /**
     * Cached signature of the last applied command, or null if no signer
     * is configured or no command has been applied yet.
     */
    private byte[] lastSignature;

    /**
     * F-0052: monotonic epoch counter assigned to each signed delta. Starts
     * at 0 (no delta yet) and increments <em>before</em> each sign call so
     * every successful signature carries a unique epoch.
     */
    private long signingEpoch;

    /**
     * F-0052: 8-byte random nonce bound into the last signed payload,
     * or null if no signed delta has been produced yet.
     */
    private byte[] lastNonce;

    /**
     * F-0052: epoch attached to the last signed delta, or 0 if no signed
     * delta has been produced yet.
     */
    private long lastEpoch;

    /** Secure random source for nonces (lazy; costs nothing when unsigned). */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * F5 (Tier-1-METRIC-DRIFT, iter-2): observability sink for write-commit
     * and snapshot-install outcomes. Defaults to {@link StateMachineMetrics#NOOP}
     * so existing constructors stay byte-equivalent.
     */
    private final StateMachineMetrics metrics;

    /**
     * Monotonic sequence counter incremented on each non-noop apply.
     * This is used as the version/sequence number passed to the store.
     */
    private long sequenceCounter;

    /**
     * Creates a state machine wrapping the given store, clock, invariant checker,
     * and optional config signer.
     *
     * @param store            the versioned config store to apply mutations to (non-null)
     * @param clock            the clock to use for timestamps during snapshot restore (non-null)
     * @param invariantChecker optional runtime invariant checker (may be null for no-op checking)
     * @param signer           optional Ed25519 signer for signing applied entries (may be null)
     */
    public ConfigStateMachine(VersionedConfigStore store, Clock clock,
                              InvariantChecker invariantChecker, ConfigSigner signer) {
        this(store, clock, invariantChecker, signer, StateMachineMetrics.NOOP);
    }

    /**
     * F5 (Tier-1-METRIC-DRIFT, iter-2): full constructor — accepts a
     * {@link StateMachineMetrics} sink so {@code configd_write_commit_*} and
     * {@code configd_snapshot_install_failed_total} get values. All previous
     * constructors delegate here with {@link StateMachineMetrics#NOOP}.
     */
    public ConfigStateMachine(VersionedConfigStore store, Clock clock,
                              InvariantChecker invariantChecker, ConfigSigner signer,
                              StateMachineMetrics metrics) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.store = store;
        this.clock = clock;
        this.invariantChecker = invariantChecker != null ? invariantChecker : InvariantChecker.NOOP;
        this.signer = signer;
        this.metrics = metrics != null ? metrics : StateMachineMetrics.NOOP;
        this.sequenceCounter = store.currentVersion();
    }

    /**
     * Creates a state machine wrapping the given store, clock, and invariant checker.
     *
     * @param store            the versioned config store to apply mutations to (non-null)
     * @param clock            the clock to use for timestamps during snapshot restore (non-null)
     * @param invariantChecker optional runtime invariant checker (may be null for no-op checking)
     */
    public ConfigStateMachine(VersionedConfigStore store, Clock clock, InvariantChecker invariantChecker) {
        this(store, clock, invariantChecker, null);
    }

    /**
     * Creates a state machine wrapping the given store, clock, and config signer
     * (no invariant checking).
     *
     * @param store  the versioned config store to apply mutations to (non-null)
     * @param clock  the clock to use for timestamps during snapshot restore (non-null)
     * @param signer the Ed25519 signer for signing applied entries (non-null)
     */
    public ConfigStateMachine(VersionedConfigStore store, Clock clock, ConfigSigner signer) {
        this(store, clock, null, signer);
    }

    /**
     * Creates a state machine wrapping the given store and clock (no invariant checking,
     * no signing).
     */
    public ConfigStateMachine(VersionedConfigStore store, Clock clock) {
        this(store, clock, null, null);
    }

    /**
     * Creates a state machine wrapping the given store, using the system clock.
     *
     * @param store the versioned config store to apply mutations to (non-null)
     */
    public ConfigStateMachine(VersionedConfigStore store) {
        this(store, Clock.system(), null, null);
    }

    /**
     * Runtime invariant checker interface. Implementations bridge to
     * {@code InvariantMonitor} in the observability module, or to a
     * no-op for testing.
     * <p>
     * This is a functional interface to avoid a hard dependency from
     * config-store → observability.
     */
    @FunctionalInterface
    public interface InvariantChecker {
        /**
         * Checks an invariant condition. Behavior on violation depends
         * on the implementation (throw in test, metric in production).
         *
         * @param name      invariant name (e.g., "sequence_monotonic")
         * @param condition true if invariant holds
         * @param message   description of violation if condition is false
         */
        void check(String name, boolean condition, String message);

        /** No-op checker that never throws and never records. */
        InvariantChecker NOOP = (name, condition, message) -> {};
    }

    // -----------------------------------------------------------------------
    // StateMachine implementation
    // -----------------------------------------------------------------------

    /**
     * Applies a committed Raft log entry to the config store.
     * <p>
     * Empty commands (no-op entries committed for leader election) are
     * silently ignored — the sequence counter is not incremented.
     * <p>
     * After a successful mutation, all registered {@link ConfigChangeListener}s
     * are notified with the list of applied mutations and the new version.
     *
     * @param index   the log index of the committed entry
     * @param term    the term of the committed entry
     * @param command the opaque command bytes (may be empty for no-op entries)
     */
    @Override
    public void apply(long index, long term, byte[] command) {
        CommandCodec.DecodedCommand decoded = CommandCodec.decode(command);
        long applyStart = System.nanoTime();
        boolean mutating = !(decoded instanceof CommandCodec.DecodedCommand.Noop);

        try {
            applySwitch(decoded, command);
            if (mutating) {
                metrics.onWriteCommitSuccess(System.nanoTime() - applyStart);
            }
        } catch (RuntimeException e) {
            if (mutating) {
                metrics.onWriteCommitFailure();
            }
            throw e;
        }
    }

    /**
     * F5 (iter-2): apply switch extracted so the metrics try/catch in
     * {@link #apply} is the only entry point that decides
     * success-vs-failure for {@code configd_write_commit_*}.
     */
    private void applySwitch(CommandCodec.DecodedCommand decoded, byte[] command) {
        switch (decoded) {
            case CommandCodec.DecodedCommand.Noop _ -> {
                // No-op entry — nothing to apply
            }
            case CommandCodec.DecodedCommand.Put put -> {
                long prevSeq = sequenceCounter;
                long seq = prevSeq + 1;
                // SEC-018 (iter-2): sign BEFORE mutating so a sign failure
                // leaves the store untouched. The signing payload is
                // computed from the input command — no post-mutation state
                // is needed — so this re-ordering is byte-equivalent on the
                // happy path.
                signCommand(command);
                // INV-V1: sequence_monotonic — new seq must exceed previous
                invariantChecker.check("sequence_monotonic", seq > prevSeq,
                        "Sequence " + seq + " not > previous " + prevSeq);
                // INV-V2: sequence_gap_free — new seq must be exactly prev + 1
                invariantChecker.check("sequence_gap_free", seq == prevSeq + 1,
                        "Sequence " + seq + " != expected " + (prevSeq + 1));
                // INV-W1: per_key_order — new version for key must exceed existing
                ReadResult existing = store.get(put.key());
                if (existing.found()) {
                    invariantChecker.check("per_key_order", seq > existing.version(),
                            "Key '" + put.key() + "' new version " + seq
                                    + " not > existing " + existing.version());
                }
                sequenceCounter = seq;
                store.put(put.key(), put.value(), seq);
                notifyListeners(List.of(new ConfigMutation.Put(put.key(), put.value())), seq);
            }
            case CommandCodec.DecodedCommand.Delete del -> {
                long prevSeq = sequenceCounter;
                long seq = prevSeq + 1;
                signCommand(command);
                invariantChecker.check("sequence_monotonic", seq > prevSeq,
                        "Sequence " + seq + " not > previous " + prevSeq);
                invariantChecker.check("sequence_gap_free", seq == prevSeq + 1,
                        "Sequence " + seq + " != expected " + (prevSeq + 1));
                sequenceCounter = seq;
                store.delete(del.key(), seq);
                notifyListeners(List.of(new ConfigMutation.Delete(del.key())), seq);
            }
            case CommandCodec.DecodedCommand.Batch batch -> {
                long prevSeq = sequenceCounter;
                long seq = prevSeq + 1;
                signCommand(command);
                invariantChecker.check("sequence_monotonic", seq > prevSeq,
                        "Sequence " + seq + " not > previous " + prevSeq);
                invariantChecker.check("sequence_gap_free", seq == prevSeq + 1,
                        "Sequence " + seq + " != expected " + (prevSeq + 1));
                sequenceCounter = seq;
                store.applyBatch(batch.mutations(), seq);
                notifyListeners(batch.mutations(), seq);
            }
        }
    }

    /**
     * Serializes the current state machine state for snapshot transfer.
     * <p>
     * Format:
     * <pre>
     *   [8-byte sequence counter]
     *   [4-byte entry count]
     *   for each entry:
     *     [4-byte key length][key bytes][4-byte value length][value bytes]
     * </pre>
     *
     * @return serialized snapshot bytes
     */
    @Override
    public byte[] snapshot() {
        ConfigSnapshot snap = store.snapshot();

        // First pass: collect entries and compute total size
        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        snap.data().forEach((key, vv) -> {
            keys.add(key.getBytes(StandardCharsets.UTF_8));
            values.add(vv.valueUnsafe());
        });

        // F-0013 fix: Use 4-byte int for key length instead of 2-byte short.
        // Short truncates keys > 65535 bytes silently. While config keys are
        // typically short paths, the snapshot format must be safe for all inputs.
        // R-002 (iter-2): write a TLV trailer carrying signingEpoch so the
        // post-D-004 monotonic-epoch carry-forward survives snapshot install,
        // and so future fields (nonceCounter, bridgeWatermark, ...) can be
        // appended without breaking older readers.
        int trailerPayloadLen = 8; // signingEpoch (long) only, today
        int size = 8 + 4 + 4 + 4 + trailerPayloadLen;
        for (int i = 0; i < keys.size(); i++) {
            size += 4 + keys.get(i).length + 4 + values.get(i).length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putLong(sequenceCounter);
        buf.putInt(keys.size());

        for (int i = 0; i < keys.size(); i++) {
            byte[] keyBytes = keys.get(i);
            byte[] valueBytes = values.get(i);
            buf.putInt(keyBytes.length);
            buf.put(keyBytes);
            buf.putInt(valueBytes.length);
            buf.put(valueBytes);
        }

        buf.putInt(SNAPSHOT_TRAILER_MAGIC);
        buf.putInt(trailerPayloadLen);
        buf.putLong(signingEpoch);

        return buf.array();
    }

    /**
     * Restores the state machine from a previously taken snapshot.
     * <p>
     * This rebuilds the store from scratch by applying all entries from the
     * snapshot as a single atomic batch. The sequence counter is restored
     * to the value at the time the snapshot was taken.
     *
     * @param snapshot serialized snapshot bytes produced by {@link #snapshot()}
     */
    @Override
    public void restoreSnapshot(byte[] snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try {
            restoreSnapshotImpl(snapshot);
            metrics.onSnapshotRebuildSuccess();
        } catch (RuntimeException e) {
            metrics.onSnapshotInstallFailed();
            throw e;
        }
    }

    private void restoreSnapshotImpl(byte[] snapshot) {
        ByteBuffer buf = ByteBuffer.wrap(snapshot);
        long restoredSequence = buf.getLong();
        int entryCount = buf.getInt();

        // F-0053 fix: bound-check envelope fields BEFORE allocating. Without
        // these checks, a malicious or corrupted InstallSnapshot payload
        // could trigger OOM (huge positive length) or NegativeArraySizeException
        // on the receiving node during a critical recovery path.
        if (entryCount < 0 || entryCount > MAX_SNAPSHOT_ENTRIES) {
            throw new IllegalArgumentException(
                    "Snapshot entryCount out of range: " + entryCount
                            + " (max " + MAX_SNAPSHOT_ENTRIES + ")");
        }

        // Build a new HAMT from the snapshot entries
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        long timestamp = clock.currentTimeMillis();

        for (int i = 0; i < entryCount; i++) {
            int keyLen = buf.getInt();
            if (keyLen < 0 || keyLen > MAX_SNAPSHOT_KEY_LEN) {
                throw new IllegalArgumentException(
                        "Snapshot keyLen out of range at entry " + i + ": " + keyLen
                                + " (max " + MAX_SNAPSHOT_KEY_LEN + ")");
            }
            if (buf.remaining() < keyLen) {
                throw new IllegalArgumentException(
                        "Snapshot truncated: expected " + keyLen + " key bytes at entry " + i
                                + ", only " + buf.remaining() + " remaining");
            }
            byte[] keyBytes = new byte[keyLen];
            buf.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            int valueLen = buf.getInt();
            if (valueLen < 0 || valueLen > MAX_SNAPSHOT_VALUE_LEN) {
                throw new IllegalArgumentException(
                        "Snapshot valueLen out of range at entry " + i + ": " + valueLen
                                + " (max " + MAX_SNAPSHOT_VALUE_LEN + ")");
            }
            if (buf.remaining() < valueLen) {
                throw new IllegalArgumentException(
                        "Snapshot truncated: expected " + valueLen + " value bytes at entry " + i
                                + ", only " + buf.remaining() + " remaining");
            }
            byte[] value = new byte[valueLen];
            buf.get(value);

            VersionedValue vv = new VersionedValue(value, restoredSequence, timestamp);
            data = data.put(key, vv);
        }

        // Atomically replace the store's state with the rebuilt snapshot.
        ConfigSnapshot newSnapshot = new ConfigSnapshot(data, restoredSequence, timestamp);
        store.restoreSnapshot(newSnapshot);
        this.sequenceCounter = restoredSequence;

        decodeTrailer(buf);
    }

    /**
     * R-002 (iter-2): dispatch across snapshot trailer forms.
     * Order matters — empty first (legacy pre-D-004), then magic-prefixed TLV
     * (canonical), then raw 8-byte epoch (iter-1 transitional). Anything else
     * is malformed and rejected.
     */
    private void decodeTrailer(ByteBuffer buf) {
        int remaining = buf.remaining();
        if (remaining == 0) {
            return; // legacy pre-D-004 snapshot — no trailer
        }
        if (remaining >= 8 && buf.getInt(buf.position()) == SNAPSHOT_TRAILER_MAGIC) {
            buf.getInt(); // consume magic
            int trailerLen = buf.getInt();
            if (trailerLen < 0 || trailerLen > MAX_SNAPSHOT_TRAILER_LEN) {
                throw new IllegalArgumentException(
                        "Snapshot trailer length out of range: " + trailerLen
                                + " (max " + MAX_SNAPSHOT_TRAILER_LEN + ")");
            }
            if (buf.remaining() < trailerLen) {
                throw new IllegalArgumentException(
                        "Snapshot trailer truncated: expected " + trailerLen
                                + " bytes, only " + buf.remaining() + " remaining");
            }
            if (trailerLen >= Long.BYTES) {
                long restoredEpoch = buf.getLong();
                // D-004 (iter-1) carry-forward semantics: take the higher epoch
                // so a leader's stale snapshot can never roll the follower back.
                if (restoredEpoch > this.signingEpoch) {
                    this.signingEpoch = restoredEpoch;
                }
                int unknownTail = trailerLen - Long.BYTES;
                if (unknownTail > 0) {
                    buf.position(buf.position() + unknownTail);
                }
            } else {
                buf.position(buf.position() + trailerLen);
            }
            return;
        }
        if (remaining == Long.BYTES) {
            // iter-1 transitional raw 8-byte epoch trailer
            long restoredEpoch = buf.getLong();
            if (restoredEpoch > this.signingEpoch) {
                this.signingEpoch = restoredEpoch;
            }
            return;
        }
        throw new IllegalArgumentException(
                "Snapshot trailer malformed: " + remaining + " bytes after entries, "
                        + "expected 0 (legacy), TLV (magic 0xC0FD7A11), or 8 (raw epoch)");
    }

    /**
     * Maximum entry count accepted from an InstallSnapshot payload. Matches
     * the typical cluster capacity headroom and bounds allocation for
     * malicious / corrupted peers (F-0053).
     */
    private static final int MAX_SNAPSHOT_ENTRIES = 100_000_000;

    /**
     * Maximum key length accepted from an InstallSnapshot payload (F-0053).
     * Bounded at 1 MiB to reject adversarial sizes while still permitting
     * the long-key snapshot round-trip guaranteed by the F-0013 fix (which
     * lifted the short-based 65535 cap from the write path).
     */
    private static final int MAX_SNAPSHOT_KEY_LEN = 1_048_576;

    /**
     * Maximum value length accepted from an InstallSnapshot payload (F-0053).
     * Matches {@code CommandCodec.MAX_VALUE_SIZE} (1 MiB).
     */
    private static final int MAX_SNAPSHOT_VALUE_LEN = 1_048_576;

    /**
     * R-002 (iter-2): magic value identifying a TLV-formatted snapshot trailer.
     * The decoder dispatches across three forms in order:
     * <ol>
     *   <li>Empty trailer — pre-D-004 (legacy) snapshots.</li>
     *   <li>Magic-prefixed TLV: [4B magic][4B length][payload bytes] — canonical.</li>
     *   <li>Raw 8-byte signingEpoch — iter-1 D-004 transitional form.</li>
     * </ol>
     * Chosen to be statistically distinct from any plausible {@code signingEpoch}
     * upper int (HLC physical-millis epochs stay ≤ 0x000001FF for the next 70 years).
     */
    private static final int SNAPSHOT_TRAILER_MAGIC = 0xC0FD7A11;

    /**
     * R-002: hard cap on TLV trailer payload length to bound allocation
     * if the magic happens to be matched by a corrupted peer.
     */
    private static final int MAX_SNAPSHOT_TRAILER_LEN = 65_536;

    // -----------------------------------------------------------------------
    // Listener management
    // -----------------------------------------------------------------------

    /**
     * Registers a listener that will be notified after each successful mutation.
     * Listeners are invoked on the Raft apply thread in registration order.
     *
     * @param listener the listener to register (non-null)
     */
    public void addListener(ConfigChangeListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was found and removed
     */
    public boolean removeListener(ConfigChangeListener listener) {
        return listeners.remove(listener);
    }

    // -----------------------------------------------------------------------
    // Signing
    // -----------------------------------------------------------------------

    /**
     * Signs the given command bytes using the configured signer, caching
     * the result in {@link #lastSignature}. If no signer is configured,
     * this is a no-op and {@link #lastSignature} remains null.
     * <p>
     * The command is normalized to batch-canonical form before signing so
     * that the edge verifier (which only has the mutation list, not the
     * original encoding) can reconstruct the same byte sequence. Without
     * this normalization, a single-mutation batch command ({@code 0x03})
     * would be signed differently from a standalone PUT ({@code 0x01})
     * even though they carry the same logical mutation.
     *
     * @param command the raw command bytes to sign
     */
    private void signCommand(byte[] command) {
        if (signer == null) {
            return;
        }
        // SEC-018 (iter-2): a sign failure must propagate so the caller
        // (apply()) aborts BEFORE mutating the store. Silently swallowing
        // the exception (the historical behavior) caused signed writes to
        // be broadcast unsigned to the edge, which then rejected them and
        // wedged into a permanent gap. Throwing here also ensures the
        // sign-then-mutate ordering's "leave store untouched on signing
        // failure" guarantee actually holds.
        long epoch = signingEpoch + 1;
        byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
        secureRandom.nextBytes(nonce);
        try {
            // F-0052: bind epoch + nonce into the signed payload so replays
            // under a rolled-back edge are rejected. The payload layout
            // matches ConfigDelta.signingPayload(). The canonical form is
            // computed from the input command — no post-mutation state is
            // referenced — so this can run before store.put / applyBatch.
            byte[] canonical = canonicalize(command);
            ByteBuffer buf = ByteBuffer.allocate(canonical.length + Long.BYTES + nonce.length);
            buf.put(canonical);
            buf.putLong(epoch);
            buf.put(nonce);
            byte[] sig = signer.sign(buf.array());
            // Commit only after a successful sign. The previous code mutated
            // signingEpoch / lastEpoch / lastNonce inside the try-block and
            // partially "reset" them on catch — that left the field cluster
            // in an inconsistent state if the next call also failed.
            lastSignature = sig;
            lastEpoch = epoch;
            lastNonce = nonce;
            signingEpoch = epoch;
        } catch (GeneralSecurityException e) {
            LOG.log(Level.SEVERE,
                    "Failed to sign applied command — aborting apply (fail-close) to keep store consistent", e);
            throw new IllegalStateException(
                    "Failed to sign applied command — fail-close abort (epoch=" + epoch + ")", e);
        } catch (IllegalStateException e) {
            // F5 / SEC-018: a verify-only signer (or any signer that throws
            // IllegalStateException directly — e.g., misconfigured key) must
            // also abort apply via the same fail-close path; let the caller
            // observe a consistent message and onWriteCommitFailure metric.
            LOG.log(Level.SEVERE,
                    "Signer threw IllegalStateException — aborting apply (fail-close) to keep store consistent", e);
            throw new IllegalStateException(
                    "Failed to sign applied command — fail-close abort (epoch=" + epoch + "): " + e.getMessage(), e);
        }
    }

    /**
     * Converts a command to its canonical batch-encoded form.
     * Single PUT and DELETE commands are wrapped in a batch with one
     * mutation. Batch commands are re-encoded through the same path to
     * guarantee byte-identical output regardless of which encoder
     * originally produced the bytes.
     */
    private static byte[] canonicalize(byte[] command) {
        CommandCodec.DecodedCommand decoded = CommandCodec.decode(command);
        return switch (decoded) {
            case CommandCodec.DecodedCommand.Put put ->
                    CommandCodec.encodeBatch(List.of(
                            new ConfigMutation.Put(put.key(), put.value())));
            case CommandCodec.DecodedCommand.Delete del ->
                    CommandCodec.encodeBatch(List.of(
                            new ConfigMutation.Delete(del.key())));
            case CommandCodec.DecodedCommand.Batch batch ->
                    CommandCodec.encodeBatch(batch.mutations());
            case CommandCodec.DecodedCommand.Noop _ -> command;
        };
    }

    /**
     * Returns the Ed25519 signature of the last applied command, or null
     * if no signer is configured or no mutating command has been applied.
     * <p>
     * The server layer can call this after {@link #apply} to attach the
     * signature to the outgoing {@link ConfigDelta} for distribution.
     *
     * @return signature bytes (defensive copy), or null
     */
    public byte[] lastSignature() {
        return lastSignature != null ? lastSignature.clone() : null;
    }

    /**
     * Returns the monotonic epoch attached to the last signed delta
     * (F-0052). Returns 0 if no signed delta has been produced.
     */
    public long lastEpoch() {
        return lastEpoch;
    }

    /**
     * Returns the 8-byte nonce bound into the last signed delta (F-0052),
     * or null if no signed delta has been produced. Defensive copy.
     */
    public byte[] lastNonce() {
        return lastNonce != null ? lastNonce.clone() : null;
    }

    /**
     * R-002 (iter-2): returns the monotonic signing epoch — the current
     * floor that future signed deltas will start from. After
     * {@link #restoreSnapshot} this reflects the epoch carried in the
     * snapshot trailer (D-004 carry-forward semantics).
     */
    public long signingEpoch() {
        return signingEpoch;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Returns the current monotonic sequence counter. */
    public long sequenceCounter() {
        return sequenceCounter;
    }

    /** Returns the underlying versioned config store. */
    public VersionedConfigStore store() {
        return store;
    }

    // -----------------------------------------------------------------------
    // Listener notification
    // -----------------------------------------------------------------------

    private void notifyListeners(List<ConfigMutation> mutations, long version) {
        for (ConfigChangeListener listener : listeners) {
            listener.onConfigChange(mutations, version);
        }
    }

    // -----------------------------------------------------------------------
    // ConfigChangeListener
    // -----------------------------------------------------------------------

    /**
     * Callback interface for receiving notifications when config mutations
     * are applied to the store.
     * <p>
     * Implementations are invoked on the Raft apply thread. They must be
     * fast and non-blocking — any expensive work should be dispatched to
     * a separate thread.
     */
    @FunctionalInterface
    public interface ConfigChangeListener {

        /**
         * Called after one or more mutations have been applied to the store.
         *
         * @param mutations the mutations that were applied (immutable)
         * @param version   the new store version after applying the mutations
         */
        void onConfigChange(List<ConfigMutation> mutations, long version);
    }
}

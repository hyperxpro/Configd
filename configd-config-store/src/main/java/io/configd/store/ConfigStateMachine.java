package io.configd.store;

import io.configd.common.Clock;
import io.configd.raft.StateMachine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <b>Thread safety:</b> Only {@link #apply} runs on the single Raft apply thread; no internal
 * synchronization guards it. {@link #addListener} may be called from any thread - listeners are
 * stored in a {@link CopyOnWriteArrayList} - but notification still happens on the apply thread.
 */
public final class ConfigStateMachine implements StateMachine {

    private static final Logger LOG = Logger.getLogger(ConfigStateMachine.class.getName());

    private final VersionedConfigStore store;
    private final Clock clock;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    // Listeners notified AFTER a successful snapshot install (restoreSnapshot). A snapshot install
    // wholesale-replaces the store WITHOUT any per-mutation apply notification, so a consumer that
    // only watches apply() (e.g. the config-policy loader) would MISS `_acl/` keys delivered via
    // InstallSnapshot (follower catch-up / runtime restore). Invoked on the apply/owner thread;
    // must be fast and non-blocking.
    private final List<Runnable> snapshotListeners = new CopyOnWriteArrayList<>();

    private final InvariantChecker invariantChecker;

    private final ConfigSigner signer;

    private byte[] lastSignature;

    /**
     * Monotonic epoch counter assigned to each signed delta. Starts at 0 (no delta yet) and
     * increments before each sign call so every successful signature carries a unique epoch.
     * <p>
     * {@code volatile} because it is written on the apply/owner thread but also read off-owner by
     * {@link #stateMachineHashHex()} (the {@code configd_state_machine_hash} scrape path), which folds
     * it into the hashed snapshot trailer; the volatile read gives that off-owner scrape visibility.
     */
    private volatile long signingEpoch;

    /**
     * Memoized state-machine hash, keyed by the exact store snapshot + signing epoch it was computed
     * over. {@link VersionedConfigStore#snapshot()} returns the same immutable {@link ConfigSnapshot}
     * instance between writes, so a reference-identity hit means the hashed state is unchanged and the
     * cached digest is still current - keeping {@link #stateMachineHashHex()} allocation-free on an idle
     * node (the restore-conformance case) and at most once-per-write under load. {@code volatile}: the
     * scrape thread publishes and reads it.
     */
    private volatile HashCache hashCache;

    private record HashCache(ConfigSnapshot snap, long epoch, String hex) {}

    private byte[] lastNonce;

    private long lastEpoch;

    private final SecureRandom secureRandom = new SecureRandom();

    private final StateMachineMetrics metrics;

    private long sequenceCounter;

    /**
     * The thread bound on first {@link #apply}. All subsequent applies must run on this same
     * thread (the Raft apply / tick thread). Lazily bound (not via constructor) because the
     * owning thread is created after the state machine. Written and read only from the apply
     * path; the single-writer invariant this field guards is exactly what makes that safe.
     */
    private Thread applyOwnerThread;

    public ConfigStateMachine(VersionedConfigStore store, Clock clock,
                              InvariantChecker invariantChecker, ConfigSigner signer) {
        this(store, clock, invariantChecker, signer, StateMachineMetrics.NOOP);
    }

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

    public ConfigStateMachine(VersionedConfigStore store, Clock clock, InvariantChecker invariantChecker) {
        this(store, clock, invariantChecker, null);
    }

    public ConfigStateMachine(VersionedConfigStore store, Clock clock, ConfigSigner signer) {
        this(store, clock, null, signer);
    }

    public ConfigStateMachine(VersionedConfigStore store, Clock clock) {
        this(store, clock, null, null);
    }

    public ConfigStateMachine(VersionedConfigStore store) {
        this(store, Clock.system(), null, null);
    }

    /**
     * Runtime invariant checker interface. Implementations bridge to
     * {@code InvariantMonitor} in the observability module, or to a
     * no-op for testing.
     * <p>
     * This is a functional interface to avoid a hard dependency from
     * config-store to observability.
     */
    @FunctionalInterface
    public interface InvariantChecker {
        /**
         * Behavior on violation depends on the implementation: throws in test, records a metric
         * in production.
         */
        void check(String name, boolean condition, String message);

        InvariantChecker NOOP = (name, condition, message) -> {};
    }

    /**
     * Empty commands (no-op entries committed for leader election) are silently ignored; the
     * sequence counter is not incremented.
     * <p>
     * Returns the applied-mutation sequence (the client's commit-sequence / read cursor), or
     * {@link StateMachine#NON_MUTATING} ({@code -1}) for a no-op.
     * <p>
     * The first call binds this state machine to the calling (Raft apply / tick) thread; every
     * later call asserts it runs on that same owner thread. A violation throws in test/sim and
     * increments a metric in production.
     */
    @Override
    public long apply(long index, long term, byte[] command) {
        assertOwnerThread();
        CommandCodec.DecodedCommand decoded;
        try {
            decoded = CommandCodec.decode(command);
        } catch (CommandCodec.MalformedCommandException e) {
            // Poison-pill defense. A committed command that framed cleanly (passed the outer
            // AppendEntries cmdLen bound) but is grammatically malformed would, if we let this throw,
            // propagate out of apply -> RaftNode.applyCommitted throws BEFORE advancing lastApplied ->
            // the entry re-applies every tick AND on WAL replay -> durable, cluster-wide crash-loop.
            // decode() is deterministic, so EVERY replica reaches this branch on the SAME entry and
            // skips it identically: treat it as NON_MUTATING so applyCommitted advances lastApplied
            // past it. No crash, no wedge, no divergence. We catch ONLY MalformedCommandException here
            // (the malformed-decode case) - any other RuntimeException from applySwitch is a real bug
            // and must still surface via the catch below.
            metrics.onMalformedCommittedCommand();
            LOG.log(Level.SEVERE,
                    "Skipping malformed committed command at index=" + index + " term=" + term
                            + " (len=" + (command == null ? -1 : command.length)
                            + ") as non-mutating - poison-pill entry from a Byzantine leader or WAL "
                            + "corruption; deterministic skip keeps the apply loop live", e);
            return StateMachine.NON_MUTATING;
        }
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
        // After a mutating apply, sequenceCounter holds the seq just assigned.
        return mutating ? sequenceCounter : StateMachine.NON_MUTATING;
    }

    private void assertOwnerThread() {
        Thread current = Thread.currentThread();
        Thread owner = applyOwnerThread;
        if (owner == null) {
            applyOwnerThread = current;
            return;
        }
        if (owner != current) {
            metrics.onApplyOwnerThreadViolation();
            invariantChecker.check("apply_owner_thread", false,
                    "ConfigStateMachine.apply invoked off the owner thread: bound to '"
                            + owner.getName() + "' (id=" + owner.threadId() + ") but called from '"
                            + current.getName() + "' (id=" + current.threadId()
                            + ") — single-writer apply invariant violated (RR-029/W-1)");
        }
    }

    /**
     * Apply switch extracted so the metrics try/catch in {@link #apply} is the only entry point
     * that decides success-vs-failure for {@code configd_write_commit_*}.
     */
    private void applySwitch(CommandCodec.DecodedCommand decoded, byte[] command) {
        switch (decoded) {
            case CommandCodec.DecodedCommand.Noop _ -> {
            }
            case CommandCodec.DecodedCommand.Put put -> {
                long prevSeq = sequenceCounter;
                long seq = prevSeq + 1;
                // Sign BEFORE mutating so a sign failure leaves the store untouched. The signing
                // payload is computed from the input command and seq - no post-mutation state is
                // needed - so this ordering is byte-equivalent on the happy path.
                signCommand(decoded, command, seq);
                // sequence_monotonic and sequence_gap_free are not checked here: with
                // seq := prevSeq + 1 they are tautologies that can never fire.
                // Global apply-order is enforced by RaftNode; per-key order is the real check below.
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
                signCommand(decoded, command, seq);
                // sequence_monotonic/sequence_gap_free are not checked here - vacuous (see Put case).
                sequenceCounter = seq;
                store.delete(del.key(), seq);
                notifyListeners(List.of(new ConfigMutation.Delete(del.key())), seq);
            }
            case CommandCodec.DecodedCommand.Batch batch -> {
                long prevSeq = sequenceCounter;
                long seq = prevSeq + 1;
                signCommand(decoded, command, seq);
                // sequence_monotonic/sequence_gap_free are not checked here - vacuous (see Put case).
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
     */
    @Override
    public byte[] snapshot() {
        ConfigSnapshot snap = store.snapshot();

        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        snap.data().forEach((key, vv) -> {
            keys.add(key.getBytes(StandardCharsets.UTF_8));
            values.add(vv.valueUnsafe());
        });

        // Use 4-byte int for key length instead of 2-byte short. Short truncates keys > 65535
        // bytes silently; the snapshot format must be safe for all valid inputs.
        // Write a TLV trailer carrying signingEpoch so the monotonic-epoch carry-forward
        // survives snapshot install, and so future fields can be appended without breaking
        // older readers.
        int trailerPayloadLen = 8; // signingEpoch (long) only
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

        metrics.onSnapshotTaken(size);
        return buf.array();
    }

    /**
     * Returns a lowercase-hex SHA-256 digest of the snapshot <em>payload region</em> - the bytes
     * {@link #snapshot()} emits after its 12-byte {@code [8B sequence][4B entry count]} header. By
     * construction this equals {@code sha256(snapshot()[12:])}, which is exactly the value the
     * restore-conformance check ({@code ops/scripts/restore-conformance-check.sh}) computes over the
     * snapshot file's payload - so an operator can compare a restored node's live state against the
     * snapshot it was bootstrapped from by reading the {@code configd_state_machine_hash} metric.
     * <p>
     * The payload layout is reproduced byte-for-byte from {@link #snapshot()} (the per-entry
     * {@code [4B keyLen][key][4B valLen][value]} records in {@link HamtMap#forEach} order, then the
     * canonical TLV trailer carrying {@code signingEpoch}). This method and {@link #snapshot()} MUST
     * stay in lockstep; {@code StateMachineSnapshotHashTest} pins the equivalence so a format change to
     * one that is not mirrored in the other fails the build rather than silently drifting the digest.
     * <p>
     * Unlike {@link #snapshot()} this has no side effects (no {@code onSnapshotTaken}) and is safe to
     * call off the owner thread: the store snapshot is an immutable, atomically-published view and
     * {@code signingEpoch} is read {@code volatile}. Under concurrent writes the epoch may momentarily
     * pair with a one-write-newer store snapshot; the digest is therefore a best-effort point-in-time
     * value, and exact on a quiescent node (the restore-verification case).
     */
    public String stateMachineHashHex() {
        ConfigSnapshot snap = store.snapshot();
        long epoch = signingEpoch;
        HashCache cache = hashCache;
        if (cache != null && cache.snap() == snap && cache.epoch() == epoch) {
            return cache.hex();
        }

        List<byte[]> keys = new ArrayList<>();
        List<byte[]> values = new ArrayList<>();
        snap.data().forEach((key, vv) -> {
            keys.add(key.getBytes(StandardCharsets.UTF_8));
            values.add(vv.valueUnsafe());
        });

        // trailer = [4B magic][4B trailerPayloadLen=8][8B signingEpoch]; entries precede it.
        int size = 4 + 4 + 8;
        for (int i = 0; i < keys.size(); i++) {
            size += 4 + keys.get(i).length + 4 + values.get(i).length;
        }
        ByteBuffer buf = ByteBuffer.allocate(size);
        for (int i = 0; i < keys.size(); i++) {
            byte[] keyBytes = keys.get(i);
            byte[] valueBytes = values.get(i);
            buf.putInt(keyBytes.length);
            buf.put(keyBytes);
            buf.putInt(valueBytes.length);
            buf.put(valueBytes);
        }
        buf.putInt(SNAPSHOT_TRAILER_MAGIC);
        buf.putInt(8); // trailerPayloadLen (signingEpoch only) - mirrors snapshot()
        buf.putLong(epoch);

        String hex = sha256Hex(buf.array());
        hashCache = new HashCache(snap, epoch, hex);
        return hex;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JCA algorithm on every conformant JVM; its absence is a broken
            // runtime, not a recoverable condition.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

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
        // A snapshot install changed the store contents wholesale with no per-mutation notification -
        // let snapshot listeners (e.g. the config-policy loader) re-derive. Fired only after a
        // SUCCESSFUL, fully-accounted install (outside the try) so a misbehaving listener can
        // neither be mis-counted as an install failure nor abort a restore that already replaced
        // the store; each listener is additionally isolated (see notifySnapshotListeners) so it
        // cannot break this Raft-critical path.
        notifySnapshotListeners();
    }

    private void restoreSnapshotImpl(byte[] snapshot) {
        ByteBuffer buf = ByteBuffer.wrap(snapshot);
        long restoredSequence = buf.getLong();
        int entryCount = buf.getInt();

        // Bound-check envelope fields BEFORE allocating. Without these checks, a malicious or
        // corrupted InstallSnapshot payload could trigger OOM (huge positive length) or
        // NegativeArraySizeException on the receiving node during a critical recovery path.
        if (entryCount < 0 || entryCount > MAX_SNAPSHOT_ENTRIES) {
            throw new IllegalArgumentException(
                    "Snapshot entryCount out of range: " + entryCount
                            + " (max " + MAX_SNAPSHOT_ENTRIES + ")");
        }

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

        ConfigSnapshot newSnapshot = new ConfigSnapshot(data, restoredSequence, timestamp);
        store.restoreSnapshot(newSnapshot);
        this.sequenceCounter = restoredSequence;

        decodeTrailer(buf);
    }

    /**
     * Reads the snapshot trailer. Only the canonical magic-TLV trailer that {@link #snapshot()}
     * always writes is accepted: {@code [SNAPSHOT_TRAILER_MAGIC][trailerLen][signingEpoch][unknown
     * tail]}. A trailer-less snapshot or a bare 8-byte epoch is refused: a snapshot must
     * self-identify its trailer, never be parsed by structural guesswork. Unknown fields beyond
     * the known 8-byte epoch inside the TLV payload are still tolerated, so an older reader can
     * load a newer snapshot that appended a field.
     */
    private void decodeTrailer(ByteBuffer buf) {
        int remaining = buf.remaining();
        if (remaining >= 8 && buf.getInt(buf.position()) == SNAPSHOT_TRAILER_MAGIC) {
            buf.getInt();
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
                // Carry-forward semantics: take the higher epoch so a leader's stale snapshot
                // can never roll the follower back.
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
        throw new IllegalArgumentException(
                "Snapshot trailer malformed: " + remaining + " bytes after entries; the frozen "
                        + "format requires the canonical TLV trailer (magic 0x"
                        + Integer.toHexString(SNAPSHOT_TRAILER_MAGIC)
                        + ") - trailer-less and bare-8-byte-epoch forms are no longer accepted");
    }

    /**
     * Maximum entry count accepted from an InstallSnapshot payload. Bounds allocation against
     * malicious or corrupted peers.
     */
    private static final int MAX_SNAPSHOT_ENTRIES = 100_000_000;

    /**
     * Maximum key length accepted from an InstallSnapshot payload. Bounded at 1 MiB to reject
     * adversarial sizes while still permitting long-key snapshot round-trips (the 4-byte key
     * length field lifted the previous short-based 65535 cap).
     */
    private static final int MAX_SNAPSHOT_KEY_LEN = 1_048_576;

    /**
     * Maximum value length accepted from an InstallSnapshot payload. Matches
     * {@code CommandCodec.MAX_VALUE_SIZE} (1 MiB).
     */
    private static final int MAX_SNAPSHOT_VALUE_LEN = 1_048_576;

    /**
     * Magic value identifying the canonical TLV snapshot trailer
     * {@code [4B magic][4B length][payload bytes]} - the only trailer form accepted (a
     * trailer-less or bare-8-byte-epoch snapshot is refused; see {@link #decodeTrailer}).
     * Chosen to be statistically distinct from any plausible {@code signingEpoch} upper int
     * (HLC physical-millis epochs stay <= 0x000001FF for the next 70 years), so the magic can
     * never collide with a legitimate epoch value.
     */
    private static final int SNAPSHOT_TRAILER_MAGIC = 0xC0FD7A11;

    /**
     * Hard cap on TLV trailer payload length to bound allocation if the magic happens to be
     * matched by a corrupted peer.
     */
    private static final int MAX_SNAPSHOT_TRAILER_LEN = 65_536;

    /** Listeners are invoked on the Raft apply thread, in registration order. */
    public void addListener(ConfigChangeListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
    }

    public boolean removeListener(ConfigChangeListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Registers a listener invoked AFTER a successful {@link #restoreSnapshot}, in registration
     * order. Implementations must be fast and non-blocking; a throwing listener is isolated and
     * logged rather than allowed to fail the install.
     */
    public void addSnapshotListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        snapshotListeners.add(listener);
    }

    /**
     * No-op if no signer is configured. The command is normalized to batch-canonical form before
     * signing so the edge verifier (which only has the mutation list, not the original encoding)
     * can reconstruct the same byte sequence - without this, a single-mutation batch
     * ({@code 0x03}) would sign differently from a standalone PUT ({@code 0x01}) despite carrying
     * the same mutation.
     * <p>
     * {@code decoded} is the command already decoded once at the {@link #apply} boundary, threaded
     * through so signing never re-decodes the raw bytes; {@code command} is kept only for the no-op
     * passthrough. {@code seq} is the applied-mutation sequence this command commits at
     * ({@code seq - 1} is the fromVersion) and is bound into the signed payload so the version
     * position is authenticated.
     */
    private void signCommand(CommandCodec.DecodedCommand decoded, byte[] command, long seq) {
        if (signer == null) {
            return;
        }
        // A sign failure must propagate so the caller (apply()) aborts BEFORE mutating the store.
        // Silently swallowing the exception caused signed writes to be broadcast unsigned to the
        // edge, which then rejected them and wedged into a permanent gap. Throwing here ensures
        // the sign-then-mutate ordering's "leave store untouched on signing failure" guarantee
        // actually holds.
        long epoch = signingEpoch + 1;
        byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
        secureRandom.nextBytes(nonce);
        try {
            // Bind the version position (fromVersion=seq-1, toVersion=seq) plus epoch + nonce into
            // the signed payload so a relay can neither replay under a rolled-back edge nor rewrite
            // the version linkage undetectably. The payload layout matches
            // ConfigDelta.signingPayload(). The canonical form and the seq are both known before
            // any store mutation, so this can run before store.put / applyBatch.
            byte[] canonical = canonicalize(decoded, command);
            ByteBuffer buf = ByteBuffer.allocate(canonical.length + 3 * Long.BYTES + nonce.length);
            buf.put(canonical);
            buf.putLong(seq - 1);
            buf.putLong(seq);
            buf.putLong(epoch);
            buf.put(nonce);
            byte[] sig = signer.sign(buf.array());
            // Commit only after a successful sign. The previous code mutated
            // signingEpoch / lastEpoch / lastNonce inside the try-block and
            // partially "reset" them on catch - that left the field cluster
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
            // A verify-only signer (or any signer that throws IllegalStateException directly,
            // e.g. misconfigured key) must also abort apply via the same fail-close path; let
            // the caller observe a consistent message and onWriteCommitFailure metric.
            LOG.log(Level.SEVERE,
                    "Signer threw IllegalStateException — aborting apply (fail-close) to keep store consistent", e);
            throw new IllegalStateException(
                    "Failed to sign applied command — fail-close abort (epoch=" + epoch + "): " + e.getMessage(), e);
        }
    }

    /**
     * Converts an already-decoded command to its canonical batch-encoded form.
     * Single PUT and DELETE commands are wrapped in a batch with one
     * mutation. Batch commands are re-encoded through the same path to
     * guarantee byte-identical output regardless of which encoder
     * originally produced the bytes.
     * <p>
     * Takes the {@link CommandCodec.DecodedCommand} decoded once at the {@link #apply} boundary
     * rather than re-decoding the raw bytes: the malformed-command guard therefore lives at exactly
     * one decode site. {@code command} is retained only for the no-op passthrough (a no-op never
     * reaches signing, so that branch is defensive).
     */
    private static byte[] canonicalize(CommandCodec.DecodedCommand decoded, byte[] command) {
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
     * Returns the Ed25519 signature of the last applied command (defensive copy), or null if no
     * signer is configured or no mutating command has been applied. The server layer calls this
     * after {@link #apply} to attach the signature to the outgoing {@link ConfigDelta}.
     */
    public byte[] lastSignature() {
        return lastSignature != null ? lastSignature.clone() : null;
    }

    /**
     * Returns the monotonic epoch attached to the last signed delta. Returns 0 if no signed
     * delta has been produced.
     */
    public long lastEpoch() {
        return lastEpoch;
    }

    /**
     * Returns the 8-byte nonce bound into the last signed delta, or null if no signed delta has
     * been produced. Defensive copy.
     */
    public byte[] lastNonce() {
        return lastNonce != null ? lastNonce.clone() : null;
    }

    /**
     * Returns the monotonic signing epoch - the current floor that future signed deltas will
     * start from. After {@link #restoreSnapshot} this reflects the epoch carried in the snapshot
     * trailer (carry-forward semantics: never rolled back below the restored value).
     */
    public long signingEpoch() {
        return signingEpoch;
    }

    public long sequenceCounter() {
        return sequenceCounter;
    }

    public VersionedConfigStore store() {
        return store;
    }

    private void notifyListeners(List<ConfigMutation> mutations, long version) {
        for (ConfigChangeListener listener : listeners) {
            listener.onConfigChange(mutations, version);
        }
    }

    private void notifySnapshotListeners() {
        for (Runnable listener : snapshotListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // A snapshot-install listener must be fast + non-blocking; isolate a misbehaving one so it
                // cannot fail an already-successful InstallSnapshot (this runs on the Raft-critical restore
                // path). The wired loader is itself fail-closed and never throws; this guards the general hook.
                LOG.log(Level.WARNING,
                        "Snapshot-install listener threw; ignoring to protect the restore path", e);
            }
        }
    }

    /**
     * Implementations are invoked on the Raft apply thread; they must be fast and non-blocking,
     * and should dispatch expensive work to a separate thread.
     */
    @FunctionalInterface
    public interface ConfigChangeListener {

        void onConfigChange(List<ConfigMutation> mutations, long version);
    }
}

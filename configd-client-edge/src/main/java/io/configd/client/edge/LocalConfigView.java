package io.configd.client.edge;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The client's own materialized, verified config store — the read model a {@link Subscription} maintains by
 * applying the verified signed delta chain and the hydration snapshot. It is built independently over the
 * frozen {@code configd-wire} value types ({@link HamtMap} / {@link VersionedValue} / {@link ConfigSnapshot} /
 * {@link ConfigDelta}); it does <b>not</b> reuse the edge-cache's {@code EdgeConfigClient} (which would drag in
 * the server data plane).
 *
 * <p><b>Trust model (an explicit boundary, not a hole).</b> The incremental delta chain carries per-delta
 * Ed25519 tamper-evidence, verified by {@code SignedChainVerifier} <b>before</b> {@link #applyDelta} is called.
 * The hydration <b>snapshot</b>, by contrast, carries <b>no</b> per-snapshot signature — the edge snapshot body
 * is trailer-less (just entries) and its authenticity rests on the <b>server's mTLS identity</b> plus
 * the frame CRC (transport integrity), not a cryptographic signature. So a client hydrating from a snapshot
 * <b>trusts the server's base-state bytes on the strength of the authenticated transport</b>, and the signed
 * chain is the tamper-evidence on every subsequent increment. {@link #loadSnapshot} therefore applies the
 * snapshot wholesale, unsigned — this is correct and deliberate, not an unverified-input gap.
 *
 * <p><b>Threading:</b> writes ({@link #applyDelta} / {@link #loadSnapshot}) are single-writer (the reader
 * thread); reads ({@link #get} / {@link #currentVersion}) are lock-free via a volatile snapshot pointer.
 */
public final class LocalConfigView {

    /** Optional storage filter for a prefix subscription (null = full store); the chain version advances regardless. */
    private final Predicate<String> keyFilter;

    private volatile ConfigSnapshot state = ConfigSnapshot.EMPTY;

    public LocalConfigView() {
        this(null);
    }

    public LocalConfigView(Predicate<String> keyFilter) {
        this.keyFilter = keyFilter;
    }

    /**
     * Applies one verified delta (single-writer). {@code PUT}s stamp the value with the delta's
     * {@code toVersion}; {@code DELETE}s remove the key. A prefix filter drops non-matching mutations from
     * storage but the store version still advances to {@code toVersion} (the chain advances regardless — the
     * server always streams the full signed chain).
     */
    public void applyDelta(ConfigDelta delta, long commitTimestampMillis) {
        HamtMap<String, VersionedValue> data = state.data();
        long ts = Math.max(0L, commitTimestampMillis);
        for (ConfigMutation mutation : delta.mutations()) {
            if (keyFilter != null && !keyFilter.test(mutation.key())) {
                continue;
            }
            switch (mutation) {
                case ConfigMutation.Put put ->
                        data = data.put(put.key(), new VersionedValue(put.valueUnsafe(), delta.toVersion(), ts));
                case ConfigMutation.Delete del -> data = data.remove(del.key());
            }
        }
        state = new ConfigSnapshot(data, delta.toVersion(), ts > 0 ? ts : state.timestamp());
    }

    /**
     * Replaces the whole store with a hydration snapshot (single-writer). The snapshot is the server-chosen
     * cumulative slice and is applied wholesale, unsigned — see the class-level trust-model note.
     */
    public void loadSnapshot(ConfigSnapshot snapshot) {
        this.state = snapshot;
    }

    /** The current monotonic store version (the last-applied {@code toVersion} / snapshot version). */
    public long currentVersion() {
        return state.version();
    }

    /** The current value for {@code key} (a defensive copy), or empty if absent. Lock-free, any thread. */
    public Optional<byte[]> get(String key) {
        byte[] value = state.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /**
     * A monotonic-guarded read: returns the value only if this view has advanced to at least
     * {@code minVersion} (the caller's last-observed store version). If the view is <b>behind</b> that cursor —
     * it would serve a value older than one the caller has already seen — the read is refused with an
     * {@link IllegalStateException}, mirroring the edge server's {@code cursor-behind} refusal. A view only
     * advances, so this fires only when a caller passes a version ahead of what this view has applied.
     */
    public Optional<byte[]> get(String key, long minVersion) {
        ConfigSnapshot current = state;
        if (minVersion > current.version()) {
            throw new IllegalStateException("monotonic-read refused: view at version " + current.version()
                    + " is behind the read cursor " + minVersion);
        }
        byte[] value = current.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /** True iff the store currently holds {@code key}. */
    public boolean containsKey(String key) {
        return state.containsKey(key);
    }

    /** The number of entries currently stored. */
    public int size() {
        return state.size();
    }
}

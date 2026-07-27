package io.configd.client.edge;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Materialized, verified config store. Trust model: deltas carry per-delta Ed25519 signatures (verified
 * before apply); snapshot is unsigned and trusts authenticated transport (mTLS + frame CRC). Snapshot applies
 * wholesale. Threading: single-writer (reader thread) for writes; lock-free reads via volatile pointer.
 */
public final class LocalConfigView {

    private final Predicate<String> keyFilter;

    private volatile ConfigSnapshot state = ConfigSnapshot.EMPTY;

    public LocalConfigView() {
        this(null);
    }

    public LocalConfigView(Predicate<String> keyFilter) {
        this.keyFilter = keyFilter;
    }

    /** Prefix filter drops non-matching mutations from storage; store version still advances to toVersion. */
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

    public void loadSnapshot(ConfigSnapshot snapshot) {
        this.state = snapshot;
    }

    public long currentVersion() {
        return state.version();
    }

    public Optional<byte[]> get(String key) {
        byte[] value = state.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    /** Monotonic-guarded read: refuses if view is behind minVersion (would serve older state). */
    public Optional<byte[]> get(String key, long minVersion) {
        ConfigSnapshot current = state;
        if (minVersion > current.version()) {
            throw new IllegalStateException("monotonic-read refused: view at version " + current.version()
                    + " is behind the read cursor " + minVersion);
        }
        byte[] value = current.get(key);
        return value == null ? Optional.empty() : Optional.of(value.clone());
    }

    public boolean containsKey(String key) {
        return state.containsKey(key);
    }

    public int size() {
        return state.size();
    }
}

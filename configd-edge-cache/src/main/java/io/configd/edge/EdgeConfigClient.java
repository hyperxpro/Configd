package io.configd.edge;

import io.configd.common.Clock;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ReadResult;

import java.util.Objects;
import java.util.Set;

/**
 * High-level client for reading config at the edge. Wraps
 * {@link LocalConfigStore}, {@link StalenessTracker}, and
 * {@link PrefixSubscription} into a single facade.
 * <p>
 * <b>Thread safety:</b> reads ({@link #get(String)},
 * {@link #get(String, VersionCursor)}, {@link #currentVersion()}) are
 * lock-free — they delegate to the volatile-pointer-based
 * {@link LocalConfigStore}. Delta application ({@link #applyDelta},
 * {@link #loadSnapshot}) is single-threaded and must be externally
 * serialized (typically by a single {@link DeltaApplier} thread or
 * virtual thread).
 * <p>
 * Subscription management ({@link #addSubscription}, {@link #removeSubscription})
 * is thread-safe via the underlying {@link PrefixSubscription}'s copy-on-write
 * semantics.
 *
 * @see LocalConfigStore
 * @see StalenessTracker
 * @see DeltaApplier
 */
public final class EdgeConfigClient {

    private final Clock clock;
    private final LocalConfigStore store;
    private final StalenessTracker stalenessTracker;
    private final PrefixSubscription subscriptions;

    /**
     * Creates a new edge config client using the given clock for staleness
     * tracking and timestamp generation.
     *
     * @param clock the clock to use for staleness measurement and timestamps (non-null)
     */
    public EdgeConfigClient(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.clock = clock;
        this.store = new LocalConfigStore(clock);
        this.stalenessTracker = new StalenessTracker(clock);
        this.subscriptions = new PrefixSubscription();
    }

    // -----------------------------------------------------------------------
    // Read path — any thread, lock-free
    // -----------------------------------------------------------------------

    /**
     * Reads the current value for a config key.
     * <p>
     * Returns {@link ReadResult#NOT_FOUND} (pre-allocated singleton) on miss.
     * On hit, allocates a single {@link ReadResult}.
     *
     * @param key config key (non-null)
     * @return read result (never null)
     */
    public ReadResult get(String key) {
        return store.get(key);
    }

    /**
     * Reads a value with monotonic read enforcement via a version cursor.
     * <p>
     * If the client's cursor version exceeds this store's current version,
     * the store is stale relative to that client — returns
     * {@link ReadResult#NOT_FOUND}.
     *
     * @param key    config key (non-null)
     * @param cursor the client's last-read cursor (non-null)
     * @return read result; NOT_FOUND if store is stale or key is absent
     */
    public ReadResult get(String key, VersionCursor cursor) {
        return store.get(key, cursor);
    }

    /**
     * Returns the current monotonic version of the local config store.
     */
    public long currentVersion() {
        return store.currentVersion();
    }

    // -----------------------------------------------------------------------
    // Staleness — any thread (volatile reads)
    // -----------------------------------------------------------------------

    /**
     * Returns the current staleness state of this edge node relative to
     * the control plane.
     *
     * @return the discrete staleness state
     */
    public StalenessTracker.State staleness() {
        return stalenessTracker.currentState();
    }

    /**
     * Returns the number of milliseconds since the last successful update
     * from the control plane.
     *
     * @return staleness in milliseconds
     */
    public long stalenessMs() {
        return stalenessTracker.stalenessMs();
    }

    // -----------------------------------------------------------------------
    // Write path — single DeltaApplier thread only
    // -----------------------------------------------------------------------

    /**
     * Applies a delta to the local config store and records the update
     * in the staleness tracker.
     * <p>
     * The delta's {@code fromVersion} must match the store's current version.
     * If it does not, the store throws {@link IllegalArgumentException}.
     *
     * @param delta the delta to apply (non-null)
     * @throws IllegalArgumentException if delta.fromVersion != currentVersion
     */
    public void applyDelta(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        store.applyDelta(delta);
        stalenessTracker.recordUpdate(delta.toVersion(), clock.currentTimeMillis());
    }

    /**
     * Replaces the entire local store with a full snapshot. Used for initial
     * sync or recovery after gap detection.
     *
     * @param snapshot the snapshot to load (non-null)
     */
    public void loadSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        store.loadSnapshot(snapshot);
        stalenessTracker.recordUpdate(snapshot.version(), snapshot.timestamp());
    }

    // -----------------------------------------------------------------------
    // Subscriptions — thread-safe via copy-on-write
    // -----------------------------------------------------------------------

    /**
     * Subscribes to a key prefix. The distribution service will send
     * deltas for keys matching this prefix to this edge node.
     *
     * @param prefix the key prefix to subscribe to (non-null, non-blank)
     */
    public void addSubscription(String prefix) {
        subscriptions.subscribe(prefix);
    }

    /**
     * Unsubscribes from a key prefix.
     *
     * @param prefix the key prefix to unsubscribe from (non-null)
     */
    public void removeSubscription(String prefix) {
        subscriptions.unsubscribe(prefix);
    }

    /**
     * Returns an unmodifiable snapshot of the currently subscribed prefixes.
     *
     * @return unmodifiable set of subscribed prefixes
     */
    public Set<String> subscriptions() {
        return subscriptions.prefixes();
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    /**
     * Returns a point-in-time metrics snapshot for this edge node.
     * <p>
     * Note: the individual fields are read independently (not under a single
     * lock), so there is a small window where version and staleness may be
     * from slightly different instants. This is acceptable for monitoring
     * purposes.
     *
     * @return metrics snapshot
     */
    public EdgeMetrics metrics() {
        return new EdgeMetrics(
                store.currentVersion(),
                stalenessTracker.stalenessMs(),
                stalenessTracker.currentState(),
                subscriptions.prefixes().size(),
                store.snapshot().size()
        );
    }
}

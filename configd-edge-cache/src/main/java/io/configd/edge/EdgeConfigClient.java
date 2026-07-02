package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
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
 * lock-free - they delegate to the volatile-pointer-based
 * {@link LocalConfigStore}. Delta application ({@link #applyDelta},
 * {@link #loadSnapshot}) is single-threaded and must be externally
 * serialized (typically by a single {@link DeltaApplier} thread or
 * virtual thread).
 * <p>
 * Subscription management ({@link #addSubscription}, {@link #removeSubscription})
 * is thread-safe via the underlying {@link PrefixSubscription}'s copy-on-write
 * semantics.
 *
 * <h2>Storage filter and covered-frontier staleness</h2>
 * The full signed chain reaches every edge, but only the subscribed slice (plus
 * strong-read keys, always) is stored - see {@link #applyDelta(ConfigDelta, long)} and
 * {@link #filterForStorage(ConfigDelta)}. Staleness is measured against the covered
 * frontier: {@link #applyDelta(ConfigDelta, long)} feeds the leader commit timestamp and
 * {@link #recordHeartbeatFrontier(long, long, long)} feeds the heartbeat frontier. There
 * is deliberately NO local-clock fallback: the legacy one-arg
 * {@code applyDelta(ConfigDelta)} was DELETED because it silently reinstated the
 * idle-time-proxy staleness; every caller states which clock stamps the frontier, and
 * a negative commit timestamp is rejected loudly.
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
    private final PrefixStorageFilter storageFilter;

    /**
     * Creates a new edge config client using the given clock for staleness
     * tracking and timestamp generation. No invariant monitor or implausibility
     * counter is wired (sim or direct-test path).
     *
     * @param clock the clock to use for staleness measurement and timestamps (non-null)
     */
    public EdgeConfigClient(Clock clock) {
        this(clock, null, null, StrongReadKeyClass.DEFAULT);
    }

    /**
     * Full constructor wiring the frontier staleness instrumentation and the strong-read
     * key class.
     *
     * @param clock              the wall clock (non-null)
     * @param invariantMonitor   optional INV-S1 staleness-bound monitor (may be null)
     * @param implausibleCounter optional implausible-frontier counter (may be null)
     * @param strongReadKeyClass the strong-read key class (always-store keys; non-null)
     */
    public EdgeConfigClient(Clock clock, InvariantMonitor invariantMonitor,
                            MetricsRegistry.Counter implausibleCounter,
                            StrongReadKeyClass strongReadKeyClass) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
        this.clock = clock;
        this.store = new LocalConfigStore(clock);
        this.stalenessTracker = new StalenessTracker(clock, invariantMonitor, implausibleCounter);
        this.subscriptions = new PrefixSubscription();
        this.storageFilter = new PrefixStorageFilter(subscriptions, strongReadKeyClass);
    }

    // -----------------------------------------------------------------------
    // Read path - any thread, lock-free
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
     * the store is stale relative to that client - returns
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
    // Staleness - any thread (volatile reads)
    // -----------------------------------------------------------------------

    /**
     * Returns the current staleness state of this edge node relative to
     * the control plane (covered-frontier measure).
     *
     * @return the discrete staleness state
     */
    public StalenessTracker.State staleness() {
        return stalenessTracker.currentState();
    }

    /**
     * Returns the current frontier staleness in milliseconds
     * ({@code wall_now - frontier}).
     *
     * @return staleness in milliseconds
     */
    public long stalenessMs() {
        return stalenessTracker.stalenessMs();
    }

    /**
     * Records a HEARTBEAT-carried frontier. The frontier advances to
     * {@code serverNowMillis} only when {@code heartbeatLatestSeq == cursor}; otherwise
     * the heartbeat is a cursor-lag signal and the frontier is unchanged.
     *
     * @param heartbeatLatestSeq the heartbeat's {@code latestSeq}
     * @param cursor             the edge's current applied cursor
     * @param serverNowMillis    the heartbeat's {@code serverNowMillis}
     * @return {@code true} if the frontier advanced (cursor matched)
     */
    public boolean recordHeartbeatFrontier(long heartbeatLatestSeq, long cursor,
                                           long serverNowMillis) {
        return stalenessTracker.recordFrontier(heartbeatLatestSeq, cursor, serverNowMillis);
    }

    /** The underlying staleness tracker (frontier, implausibility counter, INV-S1). */
    StalenessTracker stalenessTracker() {
        return stalenessTracker;
    }

    // -----------------------------------------------------------------------
    // Write path - single DeltaApplier thread only
    // -----------------------------------------------------------------------

    /**
     * Applies a delta to the local config store and advances the covered frontier to
     * {@code commitTimestampMillis} (the leader's commit clock).
     * <p>
     * The one-arg {@code applyDelta(ConfigDelta)} overload - which silently fell back
     * to the LOCAL clock as the frontier, recreating the idle-time-proxy staleness - is
     * DELETED. Every caller must state which clock stamps the frontier; tests that
     * previously relied on the fallback pass their fixture clock's
     * {@code currentTimeMillis()} explicitly (byte-identical behavior, one meaning).
     * <p>
     * Storage filter: the delta is filtered to the subscribed slice (plus strong-read
     * keys) before apply via {@link #filterForStorage(ConfigDelta)}; the chain version
     * still advances for filtered-out mutations (same from/to versions). Signature
     * verification has already happened over the ORIGINAL delta upstream
     * ({@link DeltaApplier}); the filtered delta is never re-verified or re-serialized.
     *
     * @param delta                 the original (verified) delta (non-null)
     * @param commitTimestampMillis the leader commit timestamp (the covered-frontier clock)
     * @throws IllegalArgumentException if delta.fromVersion != currentVersion
     */
    public void applyDelta(ConfigDelta delta, long commitTimestampMillis) {
        Objects.requireNonNull(delta, "delta must not be null");
        store.applyDelta(filterForStorage(delta));
        stalenessTracker.recordUpdate(delta.toVersion(), commitTimestampMillis);
    }

    /**
     * Filtered-mode apply (ADR-0044): applies the storage-filtered delta but bridges the
     * intentional version jump. Under server-side filtering the delivered version chain is
     * non-contiguous - non-matching deltas were dropped server-side and bumped the global
     * version - so this edge's store steps straight from its current version to
     * {@code delta.toVersion()} rather than requiring {@code fromVersion == currentVersion}. The
     * bridged apply delta is unsigned/legacy form (never re-verified, never re-serialized);
     * signature verification already happened over the ORIGINAL delta upstream
     * ({@link DeltaApplier}).
     *
     * @param delta                 the original (verified) delta (non-null)
     * @param commitTimestampMillis the leader commit timestamp (the covered-frontier clock)
     */
    public void applyDeltaBridged(ConfigDelta delta, long commitTimestampMillis) {
        Objects.requireNonNull(delta, "delta must not be null");
        ConfigDelta filtered = filterForStorage(delta);
        store.applyDelta(new ConfigDelta(
                store.currentVersion(), filtered.toVersion(), filtered.mutations()));
        stalenessTracker.recordUpdate(delta.toVersion(), commitTimestampMillis);
    }

    /**
     * Returns the subscription-filtered view of {@code delta} this edge stores: only
     * mutations whose key matches a subscribed prefix (empty subscription = full store) or
     * is a strong-read key, with {@code fromVersion}/{@code toVersion} preserved so the
     * chain version advances regardless. Exposed so a caller maintaining a mirror store
     * (the {@link EdgeClientCore} monitor-wired read store) applies the byte-identical
     * filtered delta and stays in lockstep.
     *
     * @param delta the original (verified) delta (non-null)
     * @return the filtered delta to apply to the store
     */
    public ConfigDelta filterForStorage(ConfigDelta delta) {
        return storageFilter.filter(delta);
    }

    /**
     * Replaces the entire local store with a full snapshot and advances the covered
     * frontier to the snapshot's timestamp. Used for initial sync or recovery after gap
     * detection.
     * <p>
     * A snapshot is the cumulative committed state, so it is NOT prefix-filtered for
     * storage here -- the snapshot already reflects the slice the server chose to send.
     * Storing the snapshot wholesale keeps the snapshot-delta equivalence invariant a
     * plain full-store compare.
     *
     * @param snapshot the snapshot to load (non-null)
     */
    public void loadSnapshot(ConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        store.loadSnapshot(snapshot);
        if (snapshot.timestamp() > 0) {
            stalenessTracker.recordUpdate(snapshot.version(), snapshot.timestamp());
        } else {
            // Snapshot bodies carry no commit timestamp (deserializes with timestamp 0 =
            // "unknown"). Record the version but leave the frontier untouched: advancing
            // it to 0 would trip the implausibility counter on every legitimate cutover
            // (false positives mask real skew). The first post-snapshot NOTIFY commitTs
            // or cursor-matched HEARTBEAT heals the frontier.
            stalenessTracker.recordVersion(snapshot.version());
        }
    }

    // -----------------------------------------------------------------------
    // Subscriptions - thread-safe via copy-on-write
    // -----------------------------------------------------------------------

    /**
     * Subscribes to a key prefix. The distribution service streams the full signed chain
     * regardless; this prefix scopes the edge-side <b>storage</b> filter.
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

    /**
     * True iff this edge's store is authoritative for {@code key}: the subscription is
     * full-store (empty prefix set) or the key matches a subscribed prefix. Within the
     * slice a store miss IS authoritative non-existence (authoritative for miss, not just
     * hit); outside it the serving surface refuses with a distinct reason instead of
     * consulting the store. Lock-free (copy-on-write prefix set).
     *
     * @param key the key being read (non-null)
     */
    public boolean servesKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return subscriptions.isEmpty() || subscriptions.matches(key);
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

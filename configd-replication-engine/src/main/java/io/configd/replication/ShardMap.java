package io.configd.replication;

import io.configd.common.ConfigScope;

import java.util.stream.IntStream;

/**
 * The routing indirection between a write's {@code (scope, key)} and the Raft group (shard) that
 * owns it.
 *
 * <p>A {@code ShardMap} answers three questions and nothing more:
 * <ul>
 *   <li><b>Routing</b> - {@link #shardFor(ConfigScope, String)} maps a stable {@code (scope, key)}
 *       to an opaque {@code groupId}. The mapping is a pure function: the same key always resolves
 *       to the same group, so all writes to a key are totally ordered by that group's log
 *       (single-key linearizability preserved).</li>
 *   <li><b>Membership</b> - {@link #shardIds()} enumerates the groups that exist.</li>
 *   <li><b>Version</b> - {@link #epoch()} is monotonic and bumps on any split/merge/rebalance.</li>
 * </ul>
 *
 * <h2>Three invariants that make future dynamic resharding additive, not a rewrite</h2>
 * <ol>
 *   <li><b>Opaque, stable shard IDs.</b> A {@code groupId} is an identity, never a source of
 *       behavior - no {@code groupId == 0} special-casing. A future split must be able to mint a
 *       brand-new id without disturbing its siblings.</li>
 *   <li><b>Routing is always {@code shardFor(...)}, never an inlined {@code mod N}.</b> The day a
 *       caller hardcodes {@code hash % 16}, dynamic routing becomes a full rewrite. Callers must
 *       always ask the map.</li>
 *   <li><b>An {@link #epoch()} on the routing envelope.</b> Carry it so a stale router that
 *       targets a shard that has since split is told "wrong epoch, re-resolve" rather than
 *       mis-committing (the TiKV RegionEpoch pattern). The v1 {@link StaticShardMap} never bumps
 *       it (returns {@code 0} forever); a future dynamic map depends on it existing. The epoch is
 *       in-memory only; static-N never bumps it, so nothing requires the wire field in v1.</li>
 * </ol>
 *
 * <h2>v1 / v2</h2>
 * <ul>
 *   <li><b>v1</b> - {@link StaticShardMap}: {@code shardFor = hash(scope, key) mod N};
 *       {@code shardIds = [0..N)}; {@code epoch = 0} forever. N is a deploy-time constant,
 *       identical on all nodes; online resharding is out.</li>
 *   <li><b>v2 (future)</b> - the same interface but {@code shardFor} consults a versioned table
 *       that a placement driver mutates on split/merge, and {@code epoch} bumps on every change.
 *       Swapping the implementation is the entire v2 routing delta - no caller changes.</li>
 * </ul>
 *
 * <p>Implementations must be safe to call from any thread (routing happens on request threads,
 * owner threads, and the inbound path); {@link StaticShardMap} is immutable and therefore
 * trivially so.
 *
 * @see StaticShardMap
 * @see MultiRaftDriver
 */
public interface ShardMap {

    /**
     * Resolves the shard (Raft group id) that owns {@code key} within {@code scope}. A pure, total
     * function of its arguments: the same {@code (scope, key)} always returns the same group id, and the
     * returned id is always a member of {@link #shardIds()}.
     *
     * @param scope the configuration scope tier (selects the pool of groups; never null)
     * @param key   the configuration key (the routing key; never null)
     * @return the opaque group id owning the key - always one of {@link #shardIds()}
     */
    int shardFor(ConfigScope scope, String key);

    /**
     * The set of group ids this map routes to, as a fresh stream. Membership is stable for the
     * life of a {@link StaticShardMap}; under a future dynamic map it reflects the current
     * {@link #epoch()}.
     *
     * @return a stream of the live group ids; never empty
     */
    IntStream shardIds();

    /**
     * The membership version. Monotonically non-decreasing; bumps on any split/merge/rebalance. The v1
     * {@link StaticShardMap} returns {@code 0} for its whole life (membership never changes).
     *
     * @return the current epoch; {@code 0} under static-N
     */
    long epoch();
}

package io.configd.server.fanout;

import io.configd.common.ConfigScope;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.replication.ShardMap;

import java.util.Objects;

/**
 * The server-side {@link ShardResolver}: it answers "which shards can this watch target's keys live
 * on" by asking the same {@link ShardMap} the write path routes through, so the coordinator's
 * coverage is exactly the routing truth.
 * <ul>
 *   <li>a whole-store target - FULL, or any target carrying {@code full_chain_verify} (a flag
 *       independent of the target kind: it is authorized at root and matches every key) - scatters
 *       across every shard, {@link ShardMap#shardIds()}. This is checked first, so a
 *       KEY+{@code full_chain_verify} target covers all shards rather than the single shard its
 *       literal path would hash to (matching {@link WatchTarget#isMatchAll()} /
 *       {@link WatchTarget#matches(String)});</li>
 *   <li>a concrete KEY target hashes to exactly one shard -
 *       {@link ShardMap#shardFor(ConfigScope, String)} over the target's scope and canonical path
 *       (the full key), so a KEY watch covers precisely the shard that key is committed to;</li>
 *   <li>a PREFIX target is not hash-contiguous, so it scatters across every shard.</li>
 * </ul>
 *
 * <p>The covered gids are ascending (KEY is a single element; the scatter cases are
 * {@code shardIds()}, already {@code [0, N)} ascending), which the coalesced
 * {@code WATCH_CREATED}/{@code WATCH_PROGRESS} vectors inherit. At {@code N = 1} every target
 * resolves to {@code {0}} - byte-identical to the single-shard drain.
 */
public final class ShardMapResolver implements ShardResolver {

    private final ShardMap shardMap;
    private final int[] allGids;

    public ShardMapResolver(ShardMap shardMap) {
        this.shardMap = Objects.requireNonNull(shardMap, "shardMap");
        this.allGids = shardMap.shardIds().toArray(); // [0, N) ascending
    }

    @Override
    public int[] coveredGids(WatchTarget target) {
        // A whole-store target (FULL, or any kind carrying full_chain_verify - authorized at root,
        // matches every key) scatters to every shard. Checked first so a KEY+full_chain_verify target
        // - which matches all keys and is root-authorized - covers all shards, not just the single
        // shard its literal path hashes to (the coverage vector must agree with matches()/isMatchAll()).
        if (target.isMatchAll()) {
            return allGids.clone();
        }
        // A concrete KEY hashes to exactly one shard for the cluster's lifetime.
        if (target.targetKind() == EdgeFrame.WATCH_TARGET_KEY) {
            return new int[]{shardMap.shardFor(scopeOf(target.scope()), target.path())};
        }
        // A PREFIX is not hash-contiguous, so it scatters to every shard.
        return allGids.clone();
    }

    /**
     * The watch target's scope ordinal as a {@link ConfigScope}. The grammar validator already
     * bounded the ordinal before a target is built; guard defensively so an out-of-range ordinal
     * fails loud rather than routing to a wrong scope's hash.
     */
    private static ConfigScope scopeOf(int ordinal) {
        ConfigScope[] scopes = ConfigScope.values();
        if (ordinal < 0 || ordinal >= scopes.length) {
            throw new IllegalArgumentException("watch target scope ordinal out of range: " + ordinal);
        }
        return scopes[ordinal];
    }
}

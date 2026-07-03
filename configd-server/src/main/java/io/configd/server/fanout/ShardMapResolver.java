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
 *   <li>a KEY target hashes to exactly one shard - {@link ShardMap#shardFor(ConfigScope, String)}
 *       over the target's scope and canonical path (the full key), so a KEY watch covers precisely
 *       the shard that key is committed to;</li>
 *   <li>a PREFIX / FULL / {@code full_chain_verify} target is not hash-contiguous, so it scatters
 *       across every shard - {@link ShardMap#shardIds()}.</li>
 * </ul>
 *
 * <p>The covered gids are ascending (KEY is a single element; PREFIX/FULL is {@code shardIds()},
 * already {@code [0, N)} ascending), which the coalesced {@code WATCH_CREATED}/{@code WATCH_PROGRESS}
 * vectors inherit. At {@code N = 1} every target resolves to {@code {0}} - byte-identical to the
 * pre-Gate-3 single drain.
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
        if (target.targetKind() == EdgeFrame.WATCH_TARGET_KEY) {
            ConfigScope scope = scopeOf(target.scope());
            return new int[]{shardMap.shardFor(scope, target.path())};
        }
        // PREFIX / FULL / full_chain_verify: not hash-contiguous, scatters to every shard.
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

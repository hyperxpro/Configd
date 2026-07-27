package io.configd.server.fanout;

import io.configd.common.ConfigScope;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.replication.ShardMap;

import java.util.Objects;


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

    
    private static ConfigScope scopeOf(int ordinal) {
        ConfigScope[] scopes = ConfigScope.values();
        if (ordinal < 0 || ordinal >= scopes.length) {
            throw new IllegalArgumentException("watch target scope ordinal out of range: " + ordinal);
        }
        return scopes[ordinal];
    }
}

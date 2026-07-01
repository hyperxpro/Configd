package io.configd.replication;

import io.configd.common.ConfigScope;

import java.util.stream.IntStream;

/**
 * The v1 {@link ShardMap}: a fixed set of {@code N} shards with
 * {@code shardFor = hash(scope, key) mod N}. Immutable, thread-safe, identical on every node.
 * N is a deploy-time constant; online resharding is out. {@link #epoch()} is {@code 0} for the
 * life of the process.
 *
 * <h2>Partitioning</h2>
 * All scopes share the single pool {@code [0, N)}. The {@link ConfigScope} is folded into the
 * hash, so it is a routing input (a future variant can give a scope its own dedicated pool
 * without changing this seam), but v1 spreads every scope across the shared pool. The hash
 * (64-bit FNV-1a over the scope ordinal and the key's code units, then a SplitMix64 finalizer)
 * avalanches so the keyspace spreads evenly; {@link Math#floorMod} maps a negative hash to a
 * non-negative shard.
 *
 * <h2>Routing invariants (preserving the v1/v2 seam)</h2>
 * <ul>
 *   <li><b>Opaque, stable ids</b> - ids are exactly {@code [0, N)}; a key that hashes to
 *       {@code 0} is ordinary, never special-cased.</li>
 *   <li><b>Routing is always {@code shardFor(...)}</b> - this class is the only place
 *       {@code mod N} lives; callers must never inline it.</li>
 *   <li><b>Stable function</b> - {@code shardFor(scope, key)} depends only on its arguments,
 *       so the same key always routes to the same group (single-key linearizability preserved).</li>
 * </ul>
 *
 * <p>N = 1 is the default: every key resolves to group 0, so the deployment is a single group.
 *
 * @see ShardMap
 */
public final class StaticShardMap implements ShardMap {

    private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
    private static final long FNV_PRIME = 1099511628211L;

    private final int shardCount;

    /**
     * Creates a static shard map over {@code shardCount} groups (ids {@code [0, shardCount)}).
     *
     * @param shardCount the number of shards (Raft groups); must be {@code >= 1}
     * @throws IllegalArgumentException if {@code shardCount < 1}
     */
    public StaticShardMap(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        this.shardCount = shardCount;
    }

    @Override
    public int shardFor(ConfigScope scope, String key) {
        if (scope == null) {
            throw new NullPointerException("scope");
        }
        if (key == null) {
            throw new NullPointerException("key");
        }
        // 64-bit FNV-1a over the scope ordinal then the key's UTF-16 code units (defined + stable across
        // JVMs/restarts), then a SplitMix64 finalizer to avalanche the low bits before the modulo.
        long h = FNV_OFFSET_BASIS;
        h ^= scope.ordinal();
        h *= FNV_PRIME;
        for (int i = 0, n = key.length(); i < n; i++) {
            h ^= key.charAt(i);
            h *= FNV_PRIME;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return Math.floorMod(h, shardCount);
    }

    @Override
    public IntStream shardIds() {
        return IntStream.range(0, shardCount);
    }

    @Override
    public long epoch() {
        return 0L;
    }

    /** The shard count {@code N} (membership size). */
    public int shardCount() {
        return shardCount;
    }

    @Override
    public String toString() {
        return "StaticShardMap[N=" + shardCount + ", epoch=0]";
    }
}

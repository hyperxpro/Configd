package io.configd.replication;

import io.configd.common.ConfigScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-shard multi-key write guard.
 *
 * <p>Configd does NOT offer cross-shard atomicity. A multi-key write (a {@code BATCH}) is atomic
 * only when all its keys reside on ONE shard (co-located) - that becomes a single-group atomic log
 * entry. A BATCH whose keys resolve to more than one shard cannot be made atomic and is REJECTED
 * with a clear error naming the offending keys, rather than silently committing a partial write.
 * The co-location obligation is unenforced by the caller; this guard makes a violation observable.
 *
 * <p>Under the default N=1 ({@link StaticShardMap#StaticShardMap(int) StaticShardMap(1)}) every
 * key resolves to group 0, so this guard never rejects - a single-shard deployment retains
 * whole-keyspace atomic BATCH.
 *
 * <p>Pure and stateless; the caller supplies the already-extracted keys (e.g. from
 * {@code CommandCodec.decode(cmd).Batch.mutations()}), so this stays free of any codec dependency.
 */
public final class CrossShardWriteGuard {

    private CrossShardWriteGuard() {
    }

    public static int requireSingleShard(ShardMap map, ConfigScope scope, List<String> keys) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("a multi-key write must have at least one key");
        }
        int first = map.shardFor(scope, Objects.requireNonNull(keys.get(0), "keys[0]"));
        // Fast path: confirm all keys land on `first`; only build the detail map if a divergence appears.
        for (int i = 1; i < keys.size(); i++) {
            String key = Objects.requireNonNull(keys.get(i), "keys[" + i + "]");
            if (map.shardFor(scope, key) != first) {
                throw new CrossShardBatchException(scope, shardOf(map, scope, keys));
            }
        }
        return first;
    }

    /**
     * Whether all keys are co-located on one shard (the non-throwing form of
     * {@link #requireSingleShard}). Returns {@code true} for an empty key list (vacuously co-located).
     */
    public static boolean isSingleShard(ShardMap map, ConfigScope scope, List<String> keys) {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty()) {
            return true;
        }
        int first = map.shardFor(scope, keys.get(0));
        for (int i = 1; i < keys.size(); i++) {
            if (map.shardFor(scope, keys.get(i)) != first) {
                return false;
            }
        }
        return true;
    }

    /** Resolve each key to its shard, preserving order (for the rejection detail). */
    private static Map<String, Integer> shardOf(ShardMap map, ConfigScope scope, List<String> keys) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String key : keys) {
            m.put(key, map.shardFor(scope, key));
        }
        return m;
    }
}

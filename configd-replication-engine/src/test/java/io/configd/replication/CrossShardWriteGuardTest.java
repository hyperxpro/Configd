package io.configd.replication;

import io.configd.common.ConfigScope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CrossShardWriteGuard} / {@link CrossShardBatchException}.
 * Co-located keys (one shard) pass; a cross-shard multi-key write is rejected with a clear,
 * named error; under N=1 the guard never rejects (whole-keyspace atomic BATCH retained).
 */
class CrossShardWriteGuardTest {

    private static final ConfigScope SCOPE = ConfigScope.GLOBAL;

    @Test
    void singleKeyIsAlwaysSingleShard() {
        StaticShardMap map = new StaticShardMap(8);
        int s = CrossShardWriteGuard.requireSingleShard(map, SCOPE, List.of("svc/cfg/only-key"));
        assertEquals(map.shardFor(SCOPE, "svc/cfg/only-key"), s);
    }

    @Test
    void coLocatedKeysReturnTheirSharedShard() {
        StaticShardMap map = new StaticShardMap(8);
        List<String> coLocated = keysOnSameShard(map, 3);
        int expected = map.shardFor(SCOPE, coLocated.get(0));
        assertTrue(CrossShardWriteGuard.isSingleShard(map, SCOPE, coLocated));
        assertEquals(expected, CrossShardWriteGuard.requireSingleShard(map, SCOPE, coLocated));
    }

    @Test
    void crossShardBatchIsRejectedWithNamedKeys() {
        StaticShardMap map = new StaticShardMap(8);
        String[] pair = keysOnDifferentShards(map);
        List<String> keys = List.of(pair[0], pair[1]);
        assertFalse(CrossShardWriteGuard.isSingleShard(map, SCOPE, keys));
        CrossShardBatchException ex = assertThrows(CrossShardBatchException.class,
                () -> CrossShardWriteGuard.requireSingleShard(map, SCOPE, keys));
        assertTrue(ex.getMessage().contains(pair[0]) && ex.getMessage().contains(pair[1]),
                "rejection must name the offending keys: " + ex.getMessage());
        assertEquals(2, ex.keyToShard().size());
        assertEquals(map.shardFor(SCOPE, pair[0]), ex.keyToShard().get(pair[0]));
        assertEquals(map.shardFor(SCOPE, pair[1]), ex.keyToShard().get(pair[1]));
    }

    @Test
    void manyKeysSpanningShardsAreRejected() {
        StaticShardMap map = new StaticShardMap(8);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            keys.add("svc/cfg/key-" + i); // 40 keys over 8 shards - guaranteed to span > 1
        }
        assertFalse(CrossShardWriteGuard.isSingleShard(map, SCOPE, keys));
        assertThrows(CrossShardBatchException.class,
                () -> CrossShardWriteGuard.requireSingleShard(map, SCOPE, keys));
    }

    @Test
    void nEqualsOneNeverRejects() {
        StaticShardMap map = new StaticShardMap(1);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            keys.add("any/key-" + i);
        }
        assertTrue(CrossShardWriteGuard.isSingleShard(map, SCOPE, keys),
                "under N=1 every key is on group 0 → whole-keyspace atomic BATCH retained");
        assertEquals(0, CrossShardWriteGuard.requireSingleShard(map, SCOPE, keys));
    }

    @Test
    void emptyKeysRejected() {
        StaticShardMap map = new StaticShardMap(4);
        assertThrows(IllegalArgumentException.class,
                () -> CrossShardWriteGuard.requireSingleShard(map, SCOPE, List.of()));
        assertTrue(CrossShardWriteGuard.isSingleShard(map, SCOPE, List.of()),
                "an empty key list is vacuously co-located");
    }

    @Test
    void nullArgumentsRejected() {
        StaticShardMap map = new StaticShardMap(4);
        assertThrows(NullPointerException.class,
                () -> CrossShardWriteGuard.requireSingleShard(null, SCOPE, List.of("k")));
        assertThrows(NullPointerException.class,
                () -> CrossShardWriteGuard.requireSingleShard(map, null, List.of("k")));
        assertThrows(NullPointerException.class,
                () -> CrossShardWriteGuard.requireSingleShard(map, SCOPE, null));
    }

    private static List<String> keysOnSameShard(StaticShardMap map, int count) {
        java.util.Map<Integer, List<String>> byShard = new java.util.HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            String key = "svc/cfg/key-" + i;
            List<String> bucket = byShard.computeIfAbsent(map.shardFor(SCOPE, key), k -> new ArrayList<>());
            bucket.add(key);
            if (bucket.size() >= count) {
                return bucket;
            }
        }
        throw new IllegalStateException("could not find " + count + " co-located keys");
    }

    private static String[] keysOnDifferentShards(StaticShardMap map) {
        String anchor = "svc/cfg/key-0";
        int anchorShard = map.shardFor(SCOPE, anchor);
        for (int i = 1; i < 10_000; i++) {
            String key = "svc/cfg/key-" + i;
            if (map.shardFor(SCOPE, key) != anchorShard) {
                return new String[] {anchor, key};
            }
        }
        throw new IllegalStateException("could not find two keys on different shards");
    }
}

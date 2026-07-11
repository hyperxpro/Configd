package io.configd.replication;

import io.configd.common.ConfigScope;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StaticShardMap} - the hash-within-scope shard map. Proves the routing
 * invariants: a stable function, opaque ids in {@code [0,N)} (no {@code 0} special-casing),
 * {@code epoch()==1}, the N=1 single-group equivalence, and a non-degenerate spread. The sim-level
 * routing-correctness / disjoint-ownership / N=1-equivalence proof lives in {@code MultiShardSimTest}.
 */
class StaticShardMapTest {

    @Test
    void shardForIsAStableFunction() {
        StaticShardMap map = new StaticShardMap(8);
        for (String key : new String[] {"svc/a", "team-x/regional/feature.flag", "", "k", "/deep/nested/key"}) {
            int first = map.shardFor(ConfigScope.GLOBAL, key);
            for (int i = 0; i < 100; i++) {
                assertEquals(first, map.shardFor(ConfigScope.GLOBAL, key),
                        "shardFor must be a stable function for key '" + key + "'");
            }
        }
    }

    @Test
    void shardForAlwaysInRange() {
        for (int n : new int[] {1, 2, 3, 4, 8, 16, 17, 64}) {
            StaticShardMap map = new StaticShardMap(n);
            for (int i = 0; i < 5000; i++) {
                String key = "svc/cfg/key-" + i + "-" + (i * 31);
                for (ConfigScope scope : ConfigScope.values()) {
                    int s = map.shardFor(scope, key);
                    assertTrue(s >= 0 && s < n,
                            "shard " + s + " out of range [0," + n + ") for key '" + key + "' scope " + scope);
                }
            }
        }
    }

    @Test
    void shardIdsAreExactlyZeroToN() {
        StaticShardMap map = new StaticShardMap(5);
        int[] ids = map.shardIds().toArray();
        assertEquals(5, ids.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(i, ids[i]);
        }
    }

    @Test
    void epochIsInitialTopologyEpochUnderStaticN() {
        // epoch() returns the deploy-time topology-descriptor epoch; static-N never bumps it.
        // 0 is reserved-illegal.
        assertEquals(1L, new StaticShardMap(16).epoch());
        assertEquals(1L, new StaticShardMap(1).epoch());
        // The explicit-epoch constructor threads the descriptor's epoch through unchanged.
        assertEquals(1L, new StaticShardMap(4, 1L).epoch());
        assertThrows(IllegalArgumentException.class, () -> new StaticShardMap(4, 0L));
    }

    @Test
    void nEqualsOneRoutesEveryKeyToGroupZero() {
        StaticShardMap map = new StaticShardMap(1);
        for (int i = 0; i < 1000; i++) {
            for (ConfigScope scope : ConfigScope.values()) {
                assertEquals(0, map.shardFor(scope, "any/key-" + i),
                        "N=1 must route every key to the single group 0 (single-group equivalence)");
            }
        }
    }

    /** Opaque ids: shard 0 is a NORMAL output (not reserved/special-cased) - some keys hash to it. */
    @Test
    void shardZeroIsAnOrdinaryOutput() {
        StaticShardMap map = new StaticShardMap(4);
        boolean sawZero = false;
        boolean sawNonZero = false;
        for (int i = 0; i < 200; i++) {
            int s = map.shardFor(ConfigScope.GLOBAL, "key-" + i);
            sawZero |= (s == 0);
            sawNonZero |= (s != 0);
        }
        assertTrue(sawZero, "shard 0 must be a normal hash output (some keys land on it)");
        assertTrue(sawNonZero, "keys must also land on non-zero shards (no collapse to 0)");
    }

    /** Non-degenerate spread: 10k keys over N=8 use every shard, with a bounded max/min imbalance. */
    @Test
    void spreadIsNotDegenerate() {
        int n = 8;
        StaticShardMap map = new StaticShardMap(n);
        int[] counts = new int[n];
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            counts[map.shardFor(ConfigScope.GLOBAL, "svc/cfg/key-" + i)]++;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int c : counts) {
            assertTrue(c > 0, "every shard must receive keys (a degenerate hash leaves some empty)");
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        // Expected ~1250/shard; a healthy hash keeps max/min well under 1.5. Generous bound catches a
        // genuinely skewed/degenerate hash without flaking on normal variance.
        assertTrue((double) max / min < 1.5,
                "hash spread too skewed: min=" + min + " max=" + max + " (max/min=" + ((double) max / min) + ")");
    }

    @Test
    void scopeIsPartOfTheRoutingFunction() {
        // Scope is folded into the hash, so for at least some keys the same key under different scopes
        // routes to different shards (scope is a routing input, not ignored). Not every key need differ.
        StaticShardMap map = new StaticShardMap(16);
        boolean anyDiffers = false;
        for (int i = 0; i < 200 && !anyDiffers; i++) {
            String key = "svc/cfg/key-" + i;
            anyDiffers = map.shardFor(ConfigScope.GLOBAL, key) != map.shardFor(ConfigScope.REGIONAL, key);
        }
        assertTrue(anyDiffers, "scope must influence routing for at least some keys");
    }

    @Test
    void distinctKeysSpanMultipleShards() {
        StaticShardMap map = new StaticShardMap(4);
        Map<Integer, Integer> hist = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            hist.merge(map.shardFor(ConfigScope.GLOBAL, "svc/cfg/key-" + i), 1, Integer::sum);
        }
        assertTrue(hist.size() >= 3, "40 keys should span >=3 of 4 shards, got " + hist.keySet());
    }

    @Test
    void rejectsInvalidShardCount() {
        assertThrows(IllegalArgumentException.class, () -> new StaticShardMap(0));
        assertThrows(IllegalArgumentException.class, () -> new StaticShardMap(-1));
    }

    @Test
    void rejectsNullArguments() {
        StaticShardMap map = new StaticShardMap(4);
        assertThrows(NullPointerException.class, () -> map.shardFor(null, "k"));
        assertThrows(NullPointerException.class, () -> map.shardFor(ConfigScope.GLOBAL, null));
    }

    @Test
    void differentNGivesDifferentRouting() {
        // Sanity: the modulus actually participates - a key's shard generally changes with N.
        StaticShardMap m4 = new StaticShardMap(4);
        StaticShardMap m7 = new StaticShardMap(7);
        int differ = 0;
        for (int i = 0; i < 100; i++) {
            if (m4.shardFor(ConfigScope.GLOBAL, "key-" + i) != m7.shardFor(ConfigScope.GLOBAL, "key-" + i)) {
                differ++;
            }
        }
        assertNotEquals(0, differ, "changing N must change routing for some keys");
    }
}

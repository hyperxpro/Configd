package io.configd.testkit;

import io.configd.common.ConfigScope;
import io.configd.replication.ShardMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * DELIBERATELY-BROKEN {@link ShardMap} implementations for the multi-shard simulator, used
 * only to prove the new invariants are non-vacuous - a broken router must drive a RED (see
 * {@link MultiShardSimTest}). The CORRECT router the green tests route through is the PRODUCTION
 * {@link io.configd.replication.StaticShardMap} itself, so the sim judges the real hash (not a stand-in).
 */
final class ShardRouters {

    private ShardRouters() {
    }

    /**
     * A BROKEN router that is NOT a function: it returns a different shard for the same key on successive
     * calls (a per-key rotating counter). This violates routing correctness (shardFor is not stable) and,
     * because a key then gets written to two shards over the run, disjoint ownership. The non-vacuity
     * driver for those two invariants. (At {@code n==1} it is trivially stable - use {@code n>=2}.)
     */
    static ShardMap rotating(int n) {
        final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        return new ShardMap() {
            @Override
            public int shardFor(ConfigScope scope, String key) {
                int c = calls.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement();
                return Math.floorMod(key.hashCode() + c, n);
            }

            @Override
            public IntStream shardIds() {
                return IntStream.range(0, n);
            }

            @Override
            public long epoch() {
                return 0L;
            }
        };
    }
}

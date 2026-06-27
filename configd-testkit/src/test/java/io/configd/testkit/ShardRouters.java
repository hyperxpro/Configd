package io.configd.testkit;

import io.configd.common.ConfigScope;
import io.configd.replication.ShardMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * {@link ShardMap} implementations for the multi-shard simulator (charter §2). One is a CORRECT
 * stable hash-within-scope reference — the V stand-in the sim routes through before C1 delivers the
 * production {@code StaticShardMap}. The others are DELIBERATELY BROKEN, used only to prove the new
 * invariants are non-vacuous (a broken router must drive a RED; see {@link MultiShardSimTest}).
 */
final class ShardRouters {

    private ShardRouters() {
    }

    /**
     * A correct, stable {@code hash(scope, key) mod N} router — a pure function: the same {@code (scope,
     * key)} always returns the same group id in {@code [0, n)}. Uses a 64-bit FNV-1a-style mix so the
     * spread is reasonable and {@link Math#floorMod} so a negative hash never yields a negative shard.
     * Honors the D-B invariants: opaque ids {@code [0,n)}, never special-casing {@code 0}; epoch {@code 0}.
     */
    static ShardMap hashReference(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("shard count must be >= 1, got " + n);
        }
        return new ShardMap() {
            @Override
            public int shardFor(ConfigScope scope, String key) {
                long h = 1469598103934665603L;             // FNV offset basis
                h ^= scope.ordinal();
                h *= 1099511628211L;                        // FNV prime
                for (int i = 0; i < key.length(); i++) {
                    h ^= key.charAt(i);
                    h *= 1099511628211L;
                }
                // SplitMix64 finalizer to avalanche the low bits before the modulo.
                h ^= h >>> 33;
                h *= 0xFF51AFD7ED558CCDL;
                h ^= h >>> 33;
                return Math.floorMod(h, n);
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

    /**
     * A BROKEN router that is NOT a function: it returns a different shard for the same key on successive
     * calls (a per-key rotating counter). This violates routing correctness (shardFor is not stable) and,
     * because a key then gets written to two shards over the run, disjoint ownership. The non-vacuity
     * driver for those two invariants. (At {@code n==1} it is trivially stable — use {@code n>=2}.)
     */
    static ShardMap rotating(int n) {
        final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        return new ShardMap() {
            @Override
            public int shardFor(ConfigScope scope, String key) {
                int c = calls.computeIfAbsent(key, k -> new AtomicInteger()).getAndIncrement();
                return Math.floorMod(key.hashCode() + c, n); // shifts with each call → non-functional
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

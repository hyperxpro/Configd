package io.configd.bench;

import io.configd.common.NodeId;
import io.configd.distribution.SubscriptionManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link SubscriptionManager#matchingNodes(String)} — the
 * propagation hot path that decides which edge nodes a mutation must be
 * delivered to.
 *
 * <p>Today the implementation is a linear scan of every distinct prefix
 * (O(P × K) per match where P = prefix count, K = average prefix length).
 * Gap-closure D13 wants this replaced with a radix-trie index for O(K)
 * lookup. This benchmark establishes the baseline so the trie patch can
 * land with a measured before/after delta and a regression guard.
 *
 * <p>Workload sweep:
 * <ul>
 *   <li>{@code prefixes} — 100, 1 000, 10 000 distinct subscribed prefixes.</li>
 *   <li>{@code nodesPerPrefix} — fan-out of 4: realistic mid-cluster setting.</li>
 * </ul>
 *
 * <p>The lookup key is drawn from a pre-rolled set of strings whose half
 * actually have a matching prefix and whose other half are guaranteed
 * misses, exercising both the hit and miss paths uniformly.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class SubscriptionMatchBenchmark {

    @Param({"100", "1000", "10000"})
    int prefixes;

    private SubscriptionManager mgr;
    private String[] lookupKeys;
    private int[] randomIndices;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        mgr = new SubscriptionManager();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Build a realistic prefix set: "service/{i}/region/{j}"
        // with 4 nodes subscribed to each prefix.
        String[] prefixSet = new String[prefixes];
        for (int i = 0; i < prefixes; i++) {
            String prefix = "service/" + i + "/region/" + (i % 32);
            prefixSet[i] = prefix;
            for (int n = 0; n < 4; n++) {
                mgr.subscribe(NodeId.of(i * 4 + n), prefix);
            }
        }

        // Half hits, half misses. Hits use a real prefix + tail; misses
        // use a path that no subscribed prefix can match.
        lookupKeys = new String[1024];
        for (int i = 0; i < lookupKeys.length; i++) {
            if ((i & 1) == 0) {
                String p = prefixSet[rng.nextInt(prefixes)];
                lookupKeys[i] = p + "/key/" + i;
            } else {
                lookupKeys[i] = "no-match/zzz/" + i;
            }
        }

        randomIndices = new int[65536];
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(lookupKeys.length);
        }
        cursor = 0;
    }

    @Benchmark
    public void matchingNodes(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(mgr.matchingNodes(lookupKeys[idx]));
    }
}

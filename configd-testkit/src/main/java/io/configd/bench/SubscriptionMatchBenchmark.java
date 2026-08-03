package io.configd.bench;

import io.configd.common.NodeId;
import io.configd.distribution.SubscriptionManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

        String[] prefixSet = new String[prefixes];
        for (int i = 0; i < prefixes; i++) {
            String prefix = "service/" + i + "/region/" + (i % 32);
            prefixSet[i] = prefix;
            for (int n = 0; n < 4; n++) {
                mgr.subscribe(NodeId.of(i * 4 + n), prefix);
            }
        }

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

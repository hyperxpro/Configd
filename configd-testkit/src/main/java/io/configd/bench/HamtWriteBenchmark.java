package io.configd.bench;

import io.configd.store.HamtMap;
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
public class HamtWriteBenchmark {

    @Param({"1000", "10000", "100000"})
    int size;

    private HamtMap<String, byte[]> map;
    private String[] existingKeys;
    private byte[] newValue;

    private int[] randomIndices;
    private int cursor;
    private int newKeyCursor;

    @Setup(Level.Trial)
    public void setUp() {
        map = HamtMap.empty();
        existingKeys = new String[size];
        newValue = new byte[64];

        for (int i = 0; i < size; i++) {
            String key = "config/service/" + i;
            existingKeys[i] = key;
            map = map.put(key, new byte[64]);
        }

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        cursor = 0;
        newKeyCursor = size;
    }

    @Benchmark
    public void putNew(Blackhole bh) {
        String key = "new/key/" + (newKeyCursor++);
        bh.consume(map.put(key, newValue));
    }

    /**
     * Path-copies nodes from root to leaf; allocates no new bitmap slots (unlike putNew).
     */
    @Benchmark
    public void putOverwrite(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(map.put(existingKeys[idx], newValue));
    }
}

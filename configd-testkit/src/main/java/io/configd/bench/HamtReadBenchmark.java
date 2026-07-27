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
public class HamtReadBenchmark {

    @Param({"1000", "10000", "100000", "1000000"})
    int size;

    private HamtMap<String, byte[]> map;
    private String[] keys;

    /** Pre-rolled random indices to avoid RNG in the hot loop. */
    private int[] randomIndices;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        map = HamtMap.empty();
        keys = new String[size];
        byte[] value = new byte[64];

        for (int i = 0; i < size; i++) {
            String key = "config/service/" + i;
            keys[i] = key;
            map = map.put(key, value);
        }

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        cursor = 0;
    }

    @Benchmark
    public void get(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(map.get(keys[idx]));
    }

    @Benchmark
    public void getMiss(Blackhole bh) {
        bh.consume(map.get("nonexistent/key/path"));
    }
}

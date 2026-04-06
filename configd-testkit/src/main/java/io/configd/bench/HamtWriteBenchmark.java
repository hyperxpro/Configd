package io.configd.bench;

import io.configd.store.HamtMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link HamtMap#put(Object, Object)} with structural sharing.
 * <p>
 * Measures the cost of inserting a new key into an existing map and the
 * allocation overhead per operation. Run with {@code -prof gc} to see
 * {@code gc.alloc.rate.norm} — this quantifies the bytes allocated per
 * put due to path-copying (expected: O(log32 N) node copies).
 * <p>
 * Two scenarios are benchmarked:
 * <ul>
 *   <li><b>putNew</b> — insert a key not present in the map (triggers node creation)</li>
 *   <li><b>putOverwrite</b> — overwrite an existing key with a new value (path copy only)</li>
 * </ul>
 */
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

    /**
     * Inserts a key that does not exist in the map.
     * Each invocation uses a fresh key to guarantee a new insertion.
     * The returned map is consumed but not retained — so the base map
     * stays constant across iterations.
     */
    @Benchmark
    public void putNew(Blackhole bh) {
        String key = "new/key/" + (newKeyCursor++);
        bh.consume(map.put(key, newValue));
    }

    /**
     * Overwrites an existing key with a new value.
     * This triggers path-copying of nodes from root to leaf
     * but no new bitmap slots.
     */
    @Benchmark
    public void putOverwrite(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(map.put(existingKeys[idx], newValue));
    }
}

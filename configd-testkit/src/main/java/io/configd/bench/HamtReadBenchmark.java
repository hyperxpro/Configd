package io.configd.bench;

import io.configd.store.HamtMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link HamtMap#get(Object)} for varying map sizes.
 * <p>
 * Validates two properties:
 * <ol>
 *   <li><b>O(log32 N) lookup</b> — throughput should degrade very gradually
 *       as N grows from 1K to 1M (at most ~4 trie levels at 1M).</li>
 *   <li><b>Zero allocation on the read path</b> — run with {@code -prof gc}
 *       to verify {@code gc.alloc.rate.norm == 0 B/op}.</li>
 * </ol>
 * <p>
 * Keys are pre-generated {@code String} instances stored in an array;
 * the benchmark selects a random key per invocation via a pre-rolled index
 * to avoid measuring key construction or random number generation overhead.
 */
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

        // Pre-roll 64K random indices to avoid ThreadLocalRandom in the benchmark
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
        // Lookup a key that does not exist — verifies zero-alloc on miss path
        bh.consume(map.get("nonexistent/key/path"));
    }
}

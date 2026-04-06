package io.configd.bench;

import io.configd.store.HamtMap;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import io.configd.store.VersionedValue;
import io.configd.store.ConfigSnapshot;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end read-path benchmark for the versioned config store.
 * <p>
 * This is the <b>critical hot path</b> in production: a volatile load
 * of the snapshot pointer followed by an HAMT traversal. The entire
 * read path must be <b>zero-allocation</b>.
 * <p>
 * Run with {@code -prof gc} to verify:
 * <pre>
 *   gc.alloc.rate.norm == 0 B/op  (for hits returning existing ReadResult)
 * </pre>
 * <p>
 * Note: {@link VersionedConfigStore#get(String)} returns a new
 * {@link ReadResult} on hit via {@code ReadResult.found()}, which is
 * a single record allocation. The miss path returns the pre-allocated
 * {@link ReadResult#NOT_FOUND} singleton (true zero-alloc).
 * The HAMT traversal itself allocates nothing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class VersionedStoreReadBenchmark {

    @Param({"1000", "10000", "100000"})
    int size;

    private VersionedConfigStore store;
    private String[] keys;
    private int[] randomIndices;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        // Build the HAMT externally and construct the store with a pre-built snapshot
        // to avoid repeated put() calls through the store's sequence validation.
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        keys = new String[size];
        byte[] value = new byte[64];

        for (int i = 0; i < size; i++) {
            String key = "config/service/" + i;
            keys[i] = key;
            VersionedValue vv = new VersionedValue(value, i + 1, System.currentTimeMillis());
            data = data.put(key, vv);
        }

        ConfigSnapshot snapshot = new ConfigSnapshot(data, size, System.currentTimeMillis());
        store = new VersionedConfigStore(snapshot);

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        cursor = 0;
    }

    /**
     * Full read path: volatile snapshot load + HAMT get + ReadResult construction.
     * The HAMT traversal is zero-alloc; ReadResult.found() is a single allocation.
     */
    @Benchmark
    public void getHit(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx]));
    }

    /**
     * Miss path: volatile snapshot load + HAMT get returning null +
     * pre-allocated NOT_FOUND sentinel. True zero-allocation.
     */
    @Benchmark
    public void getMiss(Blackhole bh) {
        bh.consume(store.get("nonexistent/key/path"));
    }

    /**
     * Read path with minimum version check.
     * Exercises the version comparison before HAMT lookup.
     */
    @Benchmark
    public void getWithMinVersion(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], 1));
    }

    /**
     * Raw snapshot access: read the snapshot pointer (single volatile load),
     * then perform HAMT get directly. Isolates the HAMT-only cost from
     * the ReadResult wrapping overhead.
     */
    @Benchmark
    public void snapshotGet(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        ConfigSnapshot snap = store.snapshot();
        bh.consume(snap.data().get(keys[idx]));
    }
}

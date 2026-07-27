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
        // Build externally to avoid store's sequence validation overhead.
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

    @Benchmark
    public void getHit(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx]));
    }

    @Benchmark
    public void getMiss(Blackhole bh) {
        bh.consume(store.get("nonexistent/key/path"));
    }

    @Benchmark
    public void getWithMinVersion(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], 1));
    }

    @Benchmark
    public void snapshotGet(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        ConfigSnapshot snap = store.snapshot();
        bh.consume(snap.data().get(keys[idx]));
    }
}

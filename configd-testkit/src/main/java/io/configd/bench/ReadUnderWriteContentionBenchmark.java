package io.configd.bench;

import io.configd.edge.LocalConfigStore;
import io.configd.edge.VersionCursor;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1)
public class ReadUnderWriteContentionBenchmark {

    static final int READER_THREADS = 4;

    @Param({"1000000"})
    int size;

    private LocalConfigStore store;
    private String[] keys;
    private int[] randomIndices;
    private VersionCursor satisfiableCursor;

    // Pre-built: writer only publishes volatile pointer, not HAMT work.
    private ConfigSnapshot snapA;
    private ConfigSnapshot snapB;

    // Thread-scoped to avoid reader-counter contention polluting the measured latency.
    @State(Scope.Thread)
    public static class ReaderCursor {
        int cursor;
    }

    @Setup(Level.Trial)
    public void setUp() {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        keys = new String[size];
        byte[] value = new byte[64];
        long now = System.currentTimeMillis();
        for (int i = 0; i < size; i++) {
            String key = "config/service/" + i;
            keys[i] = key;
            data = data.put(key, new VersionedValue(value, i + 1, now));
        }
        snapA = new ConfigSnapshot(data, size, now);
        // snapB: same data, version bumped; structural sharing = traversal cost identical.
        snapB = new ConfigSnapshot(data, size + 1, now + 1);
        store = new LocalConfigStore(snapA);

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        // Cursor at version=size: passes the cursor gate against snapA; snapB is
        // version size+1 (>= cursor) so it also passes. The reader therefore
        // always takes the full traverse path regardless of which snapshot is live.
        satisfiableCursor = new VersionCursor(size, 0L);
    }

    @Benchmark
    @Group("readWhileWriting")
    @GroupThreads(READER_THREADS)
    public void read(ReaderCursor c, Blackhole bh) {
        int idx = randomIndices[c.cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], satisfiableCursor));
    }

    @Benchmark
    @Group("readWhileWriting")
    @GroupThreads(1)
    public void write() {
        store.loadSnapshot((store.currentVersion() == size) ? snapB : snapA);
    }

    @Benchmark
    @Group("readOnly")
    @GroupThreads(READER_THREADS)
    public void readNoWriter(ReaderCursor c, Blackhole bh) {
        int idx = randomIndices[c.cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], satisfiableCursor));
    }
}

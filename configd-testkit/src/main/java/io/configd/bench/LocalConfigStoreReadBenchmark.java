package io.configd.bench;

import io.configd.edge.LocalConfigStore;
import io.configd.edge.VersionCursor;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.ReadResult;
import io.configd.store.VersionedValue;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The edge node's <b>in-process read path</b>
 * ({@link LocalConfigStore#get}) must stay lock-free, allocate nothing in steady state, and
 * avoid reflection. Run with {@code -prof gc} and verify:
 * <pre>
 *   getMiss            gc.alloc.rate.norm == 0 B/op   (pre-allocated NOT_FOUND singleton)
 *   getIntoHit         gc.alloc.rate.norm == 0 B/op   (the strict-zero-alloc API)
 *   getHit             gc.alloc.rate.norm == one ReadResult (32 B/op measured) - the
 *                      documented, accepted nursery allocation shared with
 *                      VersionedConfigStore (see ReadResult's javadoc: the flyweight
 *                      alternative was removed for an aliasing hazard); the HAMT
 *                      traversal itself allocates nothing
 *   getHitWithCursor   same as getHit (the cursor gate adds one branch, no alloc)
 * </pre>
 *
 * <p>Measured on the gate box (size=10000): getMiss ~6 ns / 0 B; getIntoHit ~117 ns /
 * 0 B; getHit ~89 ns / 32 B; getHitWithCursor ~89 ns / 32 B.
 *
 * <p><b>Hit-leg variance (why the gate binds only getMiss/getIntoHit):</b> the hit
 * legs' 32 B/op can read ~0 B/op on some runs - the JVM's escape analysis sometimes
 * scalar-replaces the ReadResult once fully warmed (observed run-to-run on the gate
 * box). The miss and getInto legs are zero-alloc STRUCTURALLY (no allocation in their
 * bytecode), so {@code gates/jmh-gc-check.sh} gates exactly those two - a deterministic
 * zero-vs-nonzero check, immune to JIT luck; the hit legs are captured for trend only.
 *
 * <p><b>Scope honesty:</b> the HTTP serving shell above this path
 * ({@code EdgeHttpServer}) allocates per request (exchange, headers, strings) and is
 * deliberately NOT measured here - it sits outside this library's read-path contract and is
 * honestly priced as non-hot-path in the edge client design. The zero-steady-state-allocation
 * claim is made for, and proven on, the in-process paths above.
 *
 * <p>Smoke run (~2 min on the 2-vCPU box):
 * <pre>
 *   java -jar configd-testkit/target/benchmarks.jar LocalConfigStoreReadBenchmark \
 *       -p size=10000 -prof gc -f 1 -wi 3 -i 3
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class LocalConfigStoreReadBenchmark {

    @Param({"1000", "10000", "100000"})
    int size;

    private LocalConfigStore store;
    private String[] keys;
    private int[] randomIndices;
    private int cursor;
    private VersionCursor satisfiableCursor;
    private byte[] dst;
    private long[] versionOut;

    @Setup(Level.Trial)
    public void setUp() {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        keys = new String[size];
        byte[] value = new byte[64];
        for (int i = 0; i < size; i++) {
            String key = "config/service/" + i;
            keys[i] = key;
            data = data.put(key, new VersionedValue(value, i + 1, System.currentTimeMillis()));
        }
        store = new LocalConfigStore(
                new ConfigSnapshot(data, size, System.currentTimeMillis()));

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        cursor = 0;
        // A satisfiable cursor (store version == size): the cursor gate passes; allocated
        // once at setup, NOT per op (a real client reuses its cursor between reads).
        satisfiableCursor = new VersionCursor(size, 0L);
        dst = new byte[64];
        versionOut = new long[1];
    }

    @Benchmark
    public void getHit(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx]));
    }

    @Benchmark
    public void getMiss(Blackhole bh) {
        bh.consume(store.get("config/absent/key"));
    }

    @Benchmark
    public void getHitWithCursor(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], satisfiableCursor));
    }

    @Benchmark
    public void getIntoHit(Blackhole bh) {
        int idx = randomIndices[cursor++ & 0xFFFF];
        bh.consume(store.getInto(keys[idx], dst, versionOut));
        bh.consume(versionOut[0]);
    }

    @Benchmark
    public boolean missIsSingleton() {
        return store.get("config/absent/key") == ReadResult.NOT_FOUND;
    }
}

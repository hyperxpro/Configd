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

/**
 * Session 5 / Workstream A — A3 "reader-never-blocks-writer" empirical proof.
 *
 * <p>The edge read path ({@link LocalConfigStore#get}) is a single volatile
 * load of the {@code currentSnapshot} pointer followed by an immutable HAMT
 * traversal — the Read-Copy-Update (RCU) pattern (ADR-0005). The writer never
 * mutates a structure a reader is traversing; it builds a new immutable
 * snapshot and publishes it with a single volatile store
 * ({@link LocalConfigStore#loadSnapshot}). There is no lock, no CAS loop, and
 * no shared mutable state on the read path, so a reader can <b>never block on
 * the writer</b>: the worst a reader sees from a concurrent swap is either the
 * old or the new pointer, both of which are complete, consistent snapshots.
 *
 * <p>This benchmark makes that claim <i>empirical</i> using JMH
 * {@code @Group}/{@code @GroupThreads}: the {@code readWhileWriting} group runs
 * {@code READER_THREADS} reader threads ({@link #read}) concurrently with a
 * single writer thread ({@link #write}) that swaps the snapshot pointer in a
 * tight loop. We measure reader latency (SampleTime) and contrast it with the
 * {@code readOnly} group (same reader count, no writer). If the RCU claim
 * holds, reader p50/p99/p999 are statistically flat between the two groups —
 * the concurrent writer does not appear in the reader's latency distribution.
 *
 * <p><b>Coordinated omission:</b> SampleTime times each reader invocation's own
 * service time with no externally-imposed arrival schedule, so CO is
 * structurally absent (methodology §3a). The number reported here is the
 * read service time <i>with a concurrent writer present</i> — the §3a/F2
 * "loaded" companion to {@code LocalConfigStoreReadBenchmark}'s unloaded read.
 *
 * <p>Run (real serving read {@code get(key, cursor)}, two reader counts):
 * <pre>
 *   java --enable-preview -jar configd-testkit/target/benchmarks.jar \
 *       ReadUnderWriteContentionBenchmark -bm sample \
 *       -p size=1000000 -f 1 -wi 5 -i 10 -w 1 -r 2 \
 *       -tg 2,1 -tg 4,1   # (set readers via -tg; see notes)
 * </pre>
 *
 * <p>Note on thread groups: JMH derives the group size from the
 * {@code @GroupThreads} annotations below (READER_THREADS readers + 1 writer).
 * To vary the reader count we ship two fixed variants ({@code read2WithWrite}
 * style is achieved by re-annotating); for the committed run we use the
 * annotation-fixed counts and report reader-thread scaling via the sibling
 * {@code LocalConfigStoreReadBenchmark -t N} rising-thread sweep. This harness
 * pins the with-writer vs without-writer contrast at a fixed reader count so
 * the only variable is "is a writer swapping the snapshot concurrently?".
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1)
public class ReadUnderWriteContentionBenchmark {

    /** Reader threads per group. Writer is always 1. */
    static final int READER_THREADS = 4;

    @Param({"1000000"})
    int size;

    private LocalConfigStore store;
    private String[] keys;
    private int[] randomIndices;
    private VersionCursor satisfiableCursor;

    // Two pre-built snapshots the writer ping-pongs between (same key set, two
    // versions). Building snapshots is NOT on the measured read path; the
    // writer's job here is only to publish the volatile pointer (loadSnapshot),
    // which is the exact RCU swap the read path races against.
    private ConfigSnapshot snapA;
    private ConfigSnapshot snapB;

    // Per-reader cursor lives in a thread-scoped helper so readers don't share
    // a counter (which would itself create contention and pollute the result).
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
        // snapB: same key set, version bumped — a fresh immutable snapshot the
        // writer can publish. Structural sharing means this is cheap and the
        // reader traversal cost is identical, so the only difference the reader
        // can observe is the volatile pointer flipping under it.
        snapB = new ConfigSnapshot(data, size + 1, now + 1);
        store = new LocalConfigStore(snapA);

        randomIndices = new int[65536];
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < randomIndices.length; i++) {
            randomIndices[i] = rng.nextInt(size);
        }
        // Cursor at version=size: passes the INV-M1 gate against snapA; snapB is
        // version size+1 (>= cursor) so it also passes. The reader therefore
        // always takes the full traverse path regardless of which snapshot is live.
        satisfiableCursor = new VersionCursor(size, 0L);
    }

    // ---- with-writer group: READER_THREADS readers + 1 writer --------------

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
        // Ping-pong the volatile snapshot pointer. This is the RCU publish the
        // reader races against — a single volatile store, no lock, no CAS.
        // (loadSnapshot is the snapshot-swap method; we alternate two prebuilt
        // snapshots so the writer does no HAMT work on the measured path.)
        store.loadSnapshot((store.currentVersion() == size) ? snapB : snapA);
    }

    // ---- baseline group: same READER_THREADS readers, NO writer ------------

    @Benchmark
    @Group("readOnly")
    @GroupThreads(READER_THREADS)
    public void readNoWriter(ReaderCursor c, Blackhole bh) {
        int idx = randomIndices[c.cursor++ & 0xFFFF];
        bh.consume(store.get(keys[idx], satisfiableCursor));
    }
}

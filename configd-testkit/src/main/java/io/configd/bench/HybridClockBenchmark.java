package io.configd.bench;

import io.configd.common.Clock;
import io.configd.common.HybridClock;
import io.configd.common.HybridTimestamp;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link HybridClock#now()} and {@link HybridClock#receive(HybridTimestamp)}
 * throughput.
 * <p>
 * The HybridClock is synchronized (uses {@code synchronized} methods),
 * so this benchmark measures the uncontended synchronized cost as a
 * baseline. In production, HLC operations happen on the write path
 * (not the read path), so they are infrequent and contention is low.
 * <p>
 * Two clock implementations are tested:
 * <ul>
 *   <li><b>system</b> — uses {@link System#currentTimeMillis()}, which
 *       exercises the real OS clock call under synchronized.</li>
 *   <li><b>fixed</b> — uses a fixed-time clock to isolate the HLC
 *       logic cost from the OS clock call overhead.</li>
 * </ul>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class HybridClockBenchmark {

    @Param({"system", "fixed"})
    String clockType;

    private HybridClock clock;
    private long incomingPacked;

    @Setup(Level.Trial)
    public void setUp() {
        Clock physicalClock = switch (clockType) {
            case "system" -> Clock.system();
            case "fixed" -> new FixedClock(System.currentTimeMillis());
            default -> throw new IllegalArgumentException("Unknown clock type: " + clockType);
        };
        clock = new HybridClock(physicalClock);

        // Pre-generate a representative incoming timestamp for receive() benchmarks
        incomingPacked = HybridClock.encode(System.currentTimeMillis(), 0);
    }

    /**
     * Measures throughput of generating new HLC timestamps (primitive, zero-alloc).
     */
    @Benchmark
    public long now() {
        return clock.now();
    }

    /**
     * Measures throughput of the receive path (primitive, zero-alloc).
     */
    @Benchmark
    public long receive() {
        return clock.receive(incomingPacked);
    }

    /**
     * Benchmark the structured-form {@code nowStructured()}. This one DOES
     * allocate a {@link HybridTimestamp} on every call, so it serves as a
     * baseline for the old-API cost.
     */
    @Benchmark
    public void nowStructured(Blackhole bh) {
        bh.consume(clock.nowStructured());
    }

    /**
     * Fixed-time clock for isolating HLC logic from OS clock overhead.
     */
    private static final class FixedClock implements Clock {
        private final long fixedTimeMs;

        FixedClock(long fixedTimeMs) {
            this.fixedTimeMs = fixedTimeMs;
        }

        @Override
        public long currentTimeMillis() {
            return fixedTimeMs;
        }

        @Override
        public long nanoTime() {
            return fixedTimeMs * 1_000_000L;
        }
    }
}

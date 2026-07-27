package io.configd.bench;

import io.configd.common.Clock;
import io.configd.common.HybridClock;
import io.configd.common.HybridTimestamp;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * The clock is synchronized; production HLC calls happen on the write path only, so
 * contention is rare, making this uncontended measurement representative. The "fixed"
 * variant isolates HLC logic cost from the OS {@code currentTimeMillis()} call overhead.
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

        incomingPacked = HybridClock.encode(System.currentTimeMillis(), 0);
    }

    @Benchmark
    public long now() {
        return clock.now();
    }

    @Benchmark
    public long receive() {
        return clock.receive(incomingPacked);
    }

    /**
     * DOES allocate a {@link HybridTimestamp} per call; baseline for the old (structured)
     * API's cost, unlike now()/receive().
     */
    @Benchmark
    public void nowStructured(Blackhole bh) {
        bh.consume(clock.nowStructured());
    }

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

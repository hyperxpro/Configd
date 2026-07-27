package io.configd.bench;

import io.configd.common.Clock;
import io.configd.distribution.WatchCoalescer;
import io.configd.distribution.WatchService;
import io.configd.store.ConfigMutation;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class WatchFanOutBenchmark {

    @Param({"1", "10", "100", "1000"})
    int watcherCount;

    private WatchService service;
    private long versionCounter;
    private byte[] payload;
    private BenchClock clock;

    @Setup(Level.Trial)
    public void setUp() {
        clock = new BenchClock();
        WatchCoalescer coalescer = new WatchCoalescer(clock, 1L, 100_000);
        service = new WatchService(coalescer);
        payload = new byte[128];
        versionCounter = 0;

        for (int i = 0; i < watcherCount; i++) {
            service.register("", event -> {});
        }
    }

    @Benchmark
    public int dispatchToWatchers(Blackhole bh) {
        versionCounter++;
        service.onConfigChange(
                List.of(new ConfigMutation.Put("config.key", payload)),
                versionCounter);
        clock.advance();
        int dispatched = service.tick();
        bh.consume(dispatched);
        return dispatched;
    }

    @Benchmark
    public int prefixFilteredDispatch(Blackhole bh) {
        versionCounter++;
        service.onConfigChange(
                List.of(new ConfigMutation.Put("db.host", payload)),
                versionCounter);
        clock.advance();
        int dispatched = service.tick();
        bh.consume(dispatched);
        return dispatched;
    }

    @Benchmark
    public int coalescedBurstDispatch(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            versionCounter++;
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key-" + (i % 10), payload)),
                    versionCounter);
        }
        clock.advance();
        int dispatched = service.tick();
        bh.consume(dispatched);
        return dispatched;
    }

    @Setup(Level.Trial)
    public void setUpPrefixWatchers() {
    }

    private static final class BenchClock implements Clock {
        private long nanos = 1_000_000_000L;

        @Override
        public long currentTimeMillis() {
            return nanos / 1_000_000L;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advance() {
            nanos += 2; // Exceeds 1ns window
        }
    }
}

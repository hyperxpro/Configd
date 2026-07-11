package io.configd.common;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that HybridClock does not allocate on the hot path.
 * <p>
 * Uses {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long)}
 * to measure thread-local allocation delta across a batch of clock operations.
 * <p>
 * A naive implementation would allocate a fresh {@code HybridTimestamp} per call
 * (24 bytes on a 64-bit JVM with compressed oops), so 10,000 calls would allocate
 * ~240 KB. Packing HLC state into a primitive {@code long} keeps steady-state
 * allocation at zero.
 */
class HybridClockAllocationTest {

    private static final int ITERATIONS = 10_000;
    /**
     * Budget: 8 KB. An unoptimized per-call-allocation baseline would be ~240 KB (24 B/op x 10K),
     * so a value well under that shows the packed representation eliminates hot-path allocation.
     * Steady state should be 0 bytes; the 8 KB headroom absorbs background noise (class
     * unloading, JIT profiling side effects, safepoint bookkeeping).
     */
    private static final long MAX_BYTES = 8_192L;

    private static final ThreadMXBean TMX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void nowIsZeroAllocationSteadyState() {
        var clock = new HybridClock(Clock.system());

        // Warm up to let the JIT finish class loading / profiling.
        long sink = 0;
        for (int i = 0; i < 2_000; i++) {
            sink ^= clock.now();
        }
        blackhole(sink);

        long before = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long acc = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            acc ^= clock.now();
        }
        long after = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        blackhole(acc);

        long allocated = after - before;
        assertTrue(allocated < MAX_BYTES,
                "HybridClock.now() allocated " + allocated +
                        " bytes over " + ITERATIONS +
                        " calls; expected < " + MAX_BYTES +
                        ". Pre-fix baseline was ~240 KB (24 B/op × 10K).");
    }

    @Test
    void receiveIsZeroAllocationSteadyState() {
        var clock = new HybridClock(Clock.system());
        long remote = HybridClock.encode(System.currentTimeMillis(), 0);

        long sink = 0;
        for (int i = 0; i < 2_000; i++) {
            sink ^= clock.receive(remote);
        }
        blackhole(sink);

        long before = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long acc = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            acc ^= clock.receive(remote);
        }
        long after = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        blackhole(acc);

        long allocated = after - before;
        assertTrue(allocated < MAX_BYTES,
                "HybridClock.receive() allocated " + allocated +
                        " bytes over " + ITERATIONS +
                        " calls; expected < " + MAX_BYTES + ".");
    }

    @SuppressWarnings("unused")
    private static void blackhole(long v) {
        if (v == 0xDEADBEEF_CAFEBABEL) {
            // unreachable: prevents DCE
            System.out.println(v);
        }
    }
}

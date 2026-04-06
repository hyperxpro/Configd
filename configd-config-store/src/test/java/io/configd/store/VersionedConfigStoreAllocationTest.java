package io.configd.store;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for F-0042: {@link VersionedConfigStore#getInto(String, byte[], long[])}
 * must be zero-allocation on both hit and miss paths.
 * <p>
 * The pre-existing {@link VersionedConfigStore#get(String)} allocates a
 * {@link ReadResult} (~24 B) on every hit — that is accepted under VDR-0001.
 * The primitive {@code getInto} variant is offered for throughput-critical
 * callers that want strict zero allocation; this test enforces that claim.
 */
class VersionedConfigStoreAllocationTest {

    private static final int ITERATIONS = 10_000;
    /** Budget: 8 KB. Pre-fix {@code get(...)} would have allocated ~240 KB. */
    private static final long MAX_BYTES = 8_192L;

    private static final ThreadMXBean TMX =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    void getIntoIsZeroAllocationOnHitPath() {
        var store = new VersionedConfigStore();
        byte[] payload = new byte[64];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        store.put("a", payload, 1);

        byte[] dst = new byte[256];
        long[] versionOut = new long[1];

        // Warm up to let JIT finish.
        int acc = 0;
        for (int i = 0; i < 4_000; i++) {
            acc ^= store.getInto("a", dst, versionOut);
        }
        blackhole(acc);

        long before = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        int sink = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sink ^= store.getInto("a", dst, versionOut);
        }
        long after = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        blackhole(sink);

        long allocated = after - before;
        assertTrue(allocated < MAX_BYTES,
                "getInto() hit path allocated " + allocated + " B over " +
                        ITERATIONS + " calls; expected < " + MAX_BYTES);
        assertEquals(64, store.getInto("a", dst, versionOut),
                "value length must be returned");
        assertEquals(1L, versionOut[0], "version must be stored in versionOut[0]");
    }

    @Test
    void getIntoIsZeroAllocationOnMissPath() {
        var store = new VersionedConfigStore();
        store.put("a", new byte[]{1, 2, 3}, 1);

        byte[] dst = new byte[32];
        long[] versionOut = new long[1];

        int acc = 0;
        for (int i = 0; i < 4_000; i++) {
            acc ^= store.getInto("missing-key", dst, versionOut);
        }
        blackhole(acc);

        long before = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        int sink = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sink ^= store.getInto("missing-key", dst, versionOut);
        }
        long after = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        blackhole(sink);

        long allocated = after - before;
        assertTrue(allocated < MAX_BYTES,
                "getInto() miss path allocated " + allocated + " B over " +
                        ITERATIONS + " calls; expected < " + MAX_BYTES);
        assertEquals(-1, store.getInto("missing-key", dst, versionOut));
    }

    @Test
    void getIntoReturnsNegativeRequiredLengthWhenDstTooSmall() {
        var store = new VersionedConfigStore();
        store.put("big", new byte[128], 1);
        byte[] dst = new byte[16];
        long[] versionOut = new long[1];
        int rc = store.getInto("big", dst, versionOut);
        assertEquals(-128 - 1, rc, "expected -(N+1) when dst too small");
    }

    @SuppressWarnings("unused")
    private static void blackhole(int v) {
        if (v == 0xCAFEBABE) System.out.println(v);
    }
}

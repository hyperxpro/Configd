package io.configd.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HybridClockTest {

    @Test
    void nowReturnsMonotonicallyIncreasingTimestamps() {
        var clock = new HybridClock(Clock.system());
        long prev = clock.now();
        for (int i = 0; i < 1000; i++) {
            long next = clock.now();
            assertTrue(Long.compareUnsigned(next, prev) > 0,
                "Timestamp must be strictly increasing: " + prev + " vs " + next);
            prev = next;
        }
    }

    @Test
    void nowStructuredReturnsMonotonicallyIncreasingTimestamps() {
        var clock = new HybridClock(Clock.system());
        HybridTimestamp prev = clock.nowStructured();
        for (int i = 0; i < 1000; i++) {
            HybridTimestamp next = clock.nowStructured();
            assertTrue(next.compareTo(prev) > 0,
                "Timestamp must be strictly increasing: " + prev + " vs " + next);
            prev = next;
        }
    }

    @Test
    void receiveAdvancesClockBeyondRemoteTimestamp() {
        // Use a fixed clock to control wall time
        var fixedClock = new Clock() {
            long time = 1000;
            public long currentTimeMillis() { return time; }
            public long nanoTime() { return time * 1_000_000; }
        };

        var hlc = new HybridClock(fixedClock);

        // Remote timestamp is far in the future
        var remote = new HybridTimestamp(2000, 5);
        HybridTimestamp result = hlc.receive(remote);

        // Result must be greater than remote
        assertTrue(result.compareTo(remote) > 0,
            "Received timestamp must exceed remote: " + result + " vs " + remote);
    }

    @Test
    void receiveWithSameWallTimeIncreasesLogical() {
        var fixedClock = new Clock() {
            long time = 1000;
            public long currentTimeMillis() { return time; }
            public long nanoTime() { return time * 1_000_000; }
        };

        var hlc = new HybridClock(fixedClock);

        var remote = new HybridTimestamp(1000, 10);
        HybridTimestamp result = hlc.receive(remote);

        assertEquals(1000, result.wallTime());
        assertTrue(result.logical() > 10);
    }

    @Test
    void packedRoundTrip() {
        var ts = new HybridTimestamp(1_700_000_000_000L, 42);
        long packed = ts.packed();
        var restored = HybridTimestamp.fromPacked(packed);
        assertEquals(ts.wallTime(), restored.wallTime());
        assertEquals(ts.logical(), restored.logical());
    }

    @Test
    void encodeDecodeRoundTrip() {
        long packed = HybridClock.encode(1_700_000_000_000L, 42);
        assertEquals(1_700_000_000_000L, HybridClock.physicalOf(packed));
        assertEquals(42, HybridClock.logicalOf(packed));
    }

    @Test
    void encodedFormatMatchesHybridTimestampPacked() {
        var ts = new HybridTimestamp(1_700_000_000_000L, 42);
        assertEquals(ts.packed(), HybridClock.fromTimestamp(ts));
        assertEquals(HybridClock.toTimestamp(ts.packed()), ts);
    }

    @Test
    void currentDoesNotAdvanceClock() {
        var fixedClock = new Clock() {
            public long currentTimeMillis() { return 1000; }
            public long nanoTime() { return 1_000_000_000L; }
        };
        var hlc = new HybridClock(fixedClock);
        long a = hlc.current();
        long b = hlc.current();
        assertEquals(a, b, "current() must be idempotent");
    }
}

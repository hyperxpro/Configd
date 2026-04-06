package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchCoalescerTest {

    private static final byte[] VALUE = "val".getBytes();
    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
    }

    @Nested
    class Construction {

        @Test
        void defaultsCreateSuccessfully() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            assertTrue(coalescer.isEmpty());
            assertEquals(0, coalescer.pendingCount());
            assertEquals(0, coalescer.pendingVersion());
        }

        @Test
        void nullClockThrows() {
            assertThrows(NullPointerException.class, () -> new WatchCoalescer(null));
        }

        @Test
        void zeroWindowThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WatchCoalescer(clock, 0, 64));
        }

        @Test
        void zeroBatchThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WatchCoalescer(clock, 10_000_000L, 0));
        }
    }

    @Nested
    class Adding {

        @Test
        void addSingleMutationBatchesPending() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(new ConfigMutation.Put("key", VALUE)), 1);

            assertFalse(coalescer.isEmpty());
            assertEquals(1, coalescer.pendingCount());
            assertEquals(1, coalescer.pendingVersion());
        }

        @Test
        void addMultipleMutationsAccumulates() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 1);
            coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 2);
            coalescer.add(List.of(new ConfigMutation.Delete("a")), 3);

            assertEquals(3, coalescer.pendingCount());
            assertEquals(3, coalescer.pendingVersion());
        }

        @Test
        void addEmptyListIsNoOp() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(), 1);
            assertTrue(coalescer.isEmpty());
        }

        @Test
        void addNonMonotonicVersionThrows() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 5);

            assertThrows(IllegalArgumentException.class,
                    () -> coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 3));
        }

        @Test
        void addSameVersionThrows() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 5);

            assertThrows(IllegalArgumentException.class,
                    () -> coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 5));
        }
    }

    @Nested
    class TimeBasedFlush {

        @Test
        void doesNotFlushBeforeWindowExpires() {
            // 10ms window
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 64);
            coalescer.add(List.of(new ConfigMutation.Put("key", VALUE)), 1);

            // Advance 9ms — not enough
            clock.advanceNanos(9_000_000L);
            assertFalse(coalescer.shouldFlush());
        }

        @Test
        void flushesAfterWindowExpires() {
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 64);
            coalescer.add(List.of(new ConfigMutation.Put("key", VALUE)), 1);

            // Advance 10ms — exactly at window
            clock.advanceNanos(10_000_000L);
            assertTrue(coalescer.shouldFlush());
        }

        @Test
        void flushReturnsCoalescedEvent() {
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 64);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 1);
            coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 2);
            coalescer.add(List.of(new ConfigMutation.Delete("a")), 3);

            clock.advanceNanos(10_000_000L);
            assertTrue(coalescer.shouldFlush());

            WatchEvent event = coalescer.flush();
            assertNotNull(event);
            assertEquals(3, event.size());
            assertEquals(3, event.version());
        }

        @Test
        void flushClearsPendingState() {
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 64);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 1);

            clock.advanceNanos(10_000_000L);
            WatchEvent event = coalescer.flush();
            assertNotNull(event);

            assertTrue(coalescer.isEmpty());
            assertEquals(0, coalescer.pendingCount());
            assertFalse(coalescer.shouldFlush());
        }

        @Test
        void flushWhenEmptyReturnsNull() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            assertNull(coalescer.flush());
        }

        @Test
        void timerResetsAfterFlush() {
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 64);
            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 1);
            clock.advanceNanos(10_000_000L);
            coalescer.flush();

            // Add new entry — timer starts fresh
            coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 2);
            assertFalse(coalescer.shouldFlush()); // Not enough time elapsed

            clock.advanceNanos(10_000_000L);
            assertTrue(coalescer.shouldFlush());
        }
    }

    @Nested
    class CountBasedFlush {

        @Test
        void flushesWhenBatchReachesMax() {
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 3);

            coalescer.add(List.of(new ConfigMutation.Put("a", VALUE)), 1);
            assertFalse(coalescer.shouldFlush());

            coalescer.add(List.of(new ConfigMutation.Put("b", VALUE)), 2);
            assertFalse(coalescer.shouldFlush());

            coalescer.add(List.of(new ConfigMutation.Put("c", VALUE)), 3);
            assertTrue(coalescer.shouldFlush()); // maxBatch=3 reached
        }

        @Test
        void batchedMutationsCountTowardMax() {
            // maxBatch = 3, but a single add with 3 mutations should trigger
            WatchCoalescer coalescer = new WatchCoalescer(clock, 10_000_000L, 3);
            coalescer.add(List.of(
                    new ConfigMutation.Put("a", VALUE),
                    new ConfigMutation.Put("b", VALUE),
                    new ConfigMutation.Put("c", VALUE)
            ), 1);

            assertTrue(coalescer.shouldFlush());
        }
    }

    @Nested
    class Reset {

        @Test
        void resetDiscardsPending() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            coalescer.add(List.of(new ConfigMutation.Put("key", VALUE)), 1);

            coalescer.reset();
            assertTrue(coalescer.isEmpty());
            assertEquals(0, coalescer.pendingCount());
            assertNull(coalescer.flush());
        }
    }

    @Nested
    class ShouldFlushWhenEmpty {

        @Test
        void emptyCoalescerNeverFlushes() {
            WatchCoalescer coalescer = new WatchCoalescer(clock);
            clock.advanceNanos(100_000_000L); // 100ms
            assertFalse(coalescer.shouldFlush());
        }
    }

    // -----------------------------------------------------------------------
    // Test clock with nanoTime control
    // -----------------------------------------------------------------------

    private static final class TestClock implements Clock {
        private long nanos = 1_000_000_000L; // 1 second

        @Override
        public long currentTimeMillis() {
            return nanos / 1_000_000L;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advanceNanos(long deltaNanos) {
            nanos += deltaNanos;
        }
    }
}

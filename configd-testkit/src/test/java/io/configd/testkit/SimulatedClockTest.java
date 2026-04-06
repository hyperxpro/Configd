package io.configd.testkit;

import io.configd.common.Clock;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedClockTest {

    @Nested
    class DefaultConstructor {

        @Test
        void startsAtFixedEpoch() {
            SimulatedClock clock = new SimulatedClock();
            assertEquals(1_700_000_000_000L, clock.currentTimeMillis());
        }

        @Test
        void nanoTimeConsistentWithDefaultEpoch() {
            SimulatedClock clock = new SimulatedClock();
            assertEquals(1_700_000_000_000L * 1_000_000L, clock.nanoTime());
        }
    }

    @Nested
    class CustomInitialTime {

        @Test
        void startsAtSpecifiedTime() {
            SimulatedClock clock = new SimulatedClock(5000L);
            assertEquals(5000L, clock.currentTimeMillis());
        }

        @Test
        void nanoTimeReflectsCustomInitialTime() {
            SimulatedClock clock = new SimulatedClock(5000L);
            assertEquals(5000L * 1_000_000L, clock.nanoTime());
        }

        @Test
        void zeroInitialTime() {
            SimulatedClock clock = new SimulatedClock(0L);
            assertEquals(0L, clock.currentTimeMillis());
            assertEquals(0L, clock.nanoTime());
        }
    }

    @Nested
    class AdvanceMs {

        @Test
        void incrementsByGivenMilliseconds() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceMs(500);
            assertEquals(1500L, clock.currentTimeMillis());
        }

        @Test
        void advanceByZeroIsNoOp() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceMs(0);
            assertEquals(1000L, clock.currentTimeMillis());
        }

        @Test
        void negativeThrowsIllegalArgumentException() {
            SimulatedClock clock = new SimulatedClock(1000L);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> clock.advanceMs(-1)
            );
            assertTrue(ex.getMessage().contains("-1"));
        }

        @Test
        void multipleAdvancesAccumulate() {
            SimulatedClock clock = new SimulatedClock(0L);
            clock.advanceMs(100);
            clock.advanceMs(200);
            clock.advanceMs(300);
            assertEquals(600L, clock.currentTimeMillis());
        }

        @Test
        void nanoTimeAdvancesProportionally() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceMs(50);
            assertEquals(1050L * 1_000_000L, clock.nanoTime());
        }
    }

    @Nested
    class AdvanceNanos {

        @Test
        void incrementsSubMillisecondPrecision() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(500_000);
            // 500,000 nanos is less than 1ms, so ms unchanged
            assertEquals(1000L, clock.currentTimeMillis());
            // nanoTime = 1000 * 1_000_000 + 500_000
            assertEquals(1000L * 1_000_000L + 500_000L, clock.nanoTime());
        }

        @Test
        void rollsOverToMillisecondsAtExactBoundary() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(1_000_000); // exactly 1ms
            assertEquals(1001L, clock.currentTimeMillis());
            // nanoOffset should be 0 after rollover
            assertEquals(1001L * 1_000_000L, clock.nanoTime());
        }

        @Test
        void rollsOverWithRemainder() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(1_500_000); // 1ms + 500,000 nanos
            assertEquals(1001L, clock.currentTimeMillis());
            assertEquals(1001L * 1_000_000L + 500_000L, clock.nanoTime());
        }

        @Test
        void negativeThrowsIllegalArgumentException() {
            SimulatedClock clock = new SimulatedClock(1000L);
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> clock.advanceNanos(-100)
            );
            assertTrue(ex.getMessage().contains("-100"));
        }

        @Test
        void advanceByZeroIsNoOp() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(0);
            assertEquals(1000L, clock.currentTimeMillis());
            assertEquals(1000L * 1_000_000L, clock.nanoTime());
        }

        @Test
        void multipleSubMsAdvancesAccumulateAndRollOver() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(400_000);
            clock.advanceNanos(400_000);
            clock.advanceNanos(400_000);
            // Total: 1,200,000 nanos = 1ms + 200,000 nanos
            assertEquals(1001L, clock.currentTimeMillis());
            assertEquals(1001L * 1_000_000L + 200_000L, clock.nanoTime());
        }

        @Test
        void largeNanoAdvanceRollsOverMultipleMilliseconds() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(3_500_000); // 3ms + 500,000 nanos
            assertEquals(1003L, clock.currentTimeMillis());
            assertEquals(1003L * 1_000_000L + 500_000L, clock.nanoTime());
        }
    }

    @Nested
    class SetTimeMs {

        @Test
        void setsAbsoluteTime() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.setTimeMs(9999L);
            assertEquals(9999L, clock.currentTimeMillis());
        }

        @Test
        void resetsNanoOffsetToZero() {
            SimulatedClock clock = new SimulatedClock(1000L);
            clock.advanceNanos(500_000); // set a non-zero nanoOffset
            assertEquals(1000L * 1_000_000L + 500_000L, clock.nanoTime());

            clock.setTimeMs(2000L);
            assertEquals(2000L, clock.currentTimeMillis());
            // nanoOffset is reset, so nanoTime = ms * 1_000_000
            assertEquals(2000L * 1_000_000L, clock.nanoTime());
        }

        @Test
        void canSetTimeBackward() {
            SimulatedClock clock = new SimulatedClock(5000L);
            clock.setTimeMs(1000L);
            assertEquals(1000L, clock.currentTimeMillis());
        }
    }

    @Nested
    class MixedOperations {

        @Test
        void advanceMsThenAdvanceNanos() {
            SimulatedClock clock = new SimulatedClock(0L);
            clock.advanceMs(10);
            clock.advanceNanos(500_000);
            assertEquals(10L, clock.currentTimeMillis());
            assertEquals(10L * 1_000_000L + 500_000L, clock.nanoTime());
        }

        @Test
        void advanceNanosThenAdvanceMs() {
            SimulatedClock clock = new SimulatedClock(0L);
            clock.advanceNanos(500_000);
            clock.advanceMs(10);
            assertEquals(10L, clock.currentTimeMillis());
            // advanceMs does not reset nanoOffset, so offset is preserved
            assertEquals(10L * 1_000_000L + 500_000L, clock.nanoTime());
        }

        @Test
        void setTimeMsThenAdvance() {
            SimulatedClock clock = new SimulatedClock(0L);
            clock.advanceNanos(300_000);
            clock.setTimeMs(5000L); // resets nanoOffset
            clock.advanceNanos(200_000);
            assertEquals(5000L, clock.currentTimeMillis());
            assertEquals(5000L * 1_000_000L + 200_000L, clock.nanoTime());
        }
    }

    @Nested
    class ClockInterface {

        @Test
        void implementsClockInterface() {
            SimulatedClock clock = new SimulatedClock();
            assertInstanceOf(Clock.class, clock);
        }

        @Test
        void usableThroughClockInterface() {
            Clock clock = new SimulatedClock(42_000L);
            assertEquals(42_000L, clock.currentTimeMillis());
            assertEquals(42_000L * 1_000_000L, clock.nanoTime());
        }
    }
}

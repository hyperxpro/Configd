package io.configd.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HybridTimestampTest {

    // ── compareTo ────────────────────────────────────────────────────────

    @Nested
    class CompareTo {

        @Test
        void equalTimestampsCompareToZero() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1000, 5);
            assertEquals(0, a.compareTo(b));
            assertEquals(0, b.compareTo(a));
        }

        @Test
        void earlierWallTimeIsLess() {
            var earlier = new HybridTimestamp(999, 100);
            var later = new HybridTimestamp(1000, 0);
            assertTrue(earlier.compareTo(later) < 0);
            assertTrue(later.compareTo(earlier) > 0);
        }

        @Test
        void laterWallTimeIsGreater() {
            var a = new HybridTimestamp(2000, 0);
            var b = new HybridTimestamp(1000, 999);
            assertTrue(a.compareTo(b) > 0);
        }

        @Test
        void sameWallTimeDifferentLogicalUsesLogicalAsBreaker() {
            var low = new HybridTimestamp(1000, 3);
            var high = new HybridTimestamp(1000, 7);
            assertTrue(low.compareTo(high) < 0);
            assertTrue(high.compareTo(low) > 0);
        }

        @Test
        void compareToIsConsistentWithEquals() {
            var a = new HybridTimestamp(500, 10);
            var b = new HybridTimestamp(500, 10);
            assertEquals(0, a.compareTo(b));
            assertEquals(a, b);
        }
    }

    // ── packed / fromPacked round-trip ───────────────────────────────────

    @Nested
    class PackedRoundTrip {

        @Test
        void roundTripSmallValues() {
            var ts = new HybridTimestamp(1, 1);
            assertEquals(ts.wallTime(), HybridTimestamp.fromPacked(ts.packed()).wallTime());
            assertEquals(ts.logical(), HybridTimestamp.fromPacked(ts.packed()).logical());
        }

        @Test
        void roundTripZero() {
            var ts = HybridTimestamp.ZERO;
            var restored = HybridTimestamp.fromPacked(ts.packed());
            assertEquals(0, restored.wallTime());
            assertEquals(0, restored.logical());
        }

        @Test
        void roundTripLargeWallTime() {
            // Typical epoch-millis value
            var ts = new HybridTimestamp(1_700_000_000_000L, 0);
            var restored = HybridTimestamp.fromPacked(ts.packed());
            assertEquals(ts.wallTime(), restored.wallTime());
            assertEquals(ts.logical(), restored.logical());
        }

        @Test
        void roundTripMaxLogical() {
            // Logical uses bottom 16 bits, max is 0xFFFF = 65535
            var ts = new HybridTimestamp(42, 0xFFFF);
            var restored = HybridTimestamp.fromPacked(ts.packed());
            assertEquals(42, restored.wallTime());
            assertEquals(0xFFFF, restored.logical());
        }

        @Test
        void roundTripLargeWallTimeAndMaxLogical() {
            var ts = new HybridTimestamp(1_700_000_000_000L, 0xFFFF);
            var restored = HybridTimestamp.fromPacked(ts.packed());
            assertEquals(ts.wallTime(), restored.wallTime());
            assertEquals(ts.logical(), restored.logical());
        }

        @Test
        void packedPreservesOrdering() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1000, 6);
            var c = new HybridTimestamp(1001, 0);
            assertTrue(a.packed() < b.packed());
            assertTrue(b.packed() < c.packed());
        }
    }

    // ── equals and hashCode ─────────────────────────────────────────────

    @Nested
    class EqualsAndHashCode {

        @Test
        void equalObjectsAreEqual() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1000, 5);
            assertEquals(a, b);
            assertEquals(b, a);
        }

        @Test
        void equalObjectsHaveSameHashCode() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1000, 5);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentWallTimeNotEqual() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1001, 5);
            assertNotEquals(a, b);
        }

        @Test
        void differentLogicalNotEqual() {
            var a = new HybridTimestamp(1000, 5);
            var b = new HybridTimestamp(1000, 6);
            assertNotEquals(a, b);
        }

        @Test
        void notEqualToNull() {
            var a = new HybridTimestamp(1000, 5);
            assertNotEquals(null, a);
        }

        @Test
        void notEqualToDifferentType() {
            var a = new HybridTimestamp(1000, 5);
            assertNotEquals("1000:5", a);
        }

        @Test
        void reflexiveEquals() {
            var a = new HybridTimestamp(1000, 5);
            assertEquals(a, a);
        }
    }

    // ── ZERO constant ───────────────────────────────────────────────────

    @Nested
    class ZeroConstant {

        @Test
        void zeroHasWallTimeZero() {
            assertEquals(0, HybridTimestamp.ZERO.wallTime());
        }

        @Test
        void zeroHasLogicalZero() {
            assertEquals(0, HybridTimestamp.ZERO.logical());
        }

        @Test
        void zeroEqualsNewZero() {
            assertEquals(HybridTimestamp.ZERO, new HybridTimestamp(0, 0));
        }
    }

    // ── toString ────────────────────────────────────────────────────────

    @Nested
    class ToStringFormat {

        @Test
        void formatIsWallTimeColonLogical() {
            var ts = new HybridTimestamp(1000, 5);
            assertEquals("1000:5", ts.toString());
        }

        @Test
        void zeroToString() {
            assertEquals("0:0", HybridTimestamp.ZERO.toString());
        }

        @Test
        void largeValuesToString() {
            var ts = new HybridTimestamp(1_700_000_000_000L, 65535);
            assertEquals("1700000000000:65535", ts.toString());
        }
    }
}

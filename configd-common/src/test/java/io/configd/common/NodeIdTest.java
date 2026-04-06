package io.configd.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeIdTest {

    // ── of() factory method ─────────────────────────────────────────────

    @Nested
    class OfFactory {

        @Test
        void createsNodeIdWithGivenId() {
            var node = NodeId.of(1);
            assertEquals(1, node.id());
        }

        @Test
        void factoryAndConstructorProduceEqualInstances() {
            assertEquals(new NodeId(5), NodeId.of(5));
        }

        @Test
        void supportsZeroId() {
            var node = NodeId.of(0);
            assertEquals(0, node.id());
        }

        @Test
        void supportsNegativeId() {
            var node = NodeId.of(-1);
            assertEquals(-1, node.id());
        }
    }

    // ── compareTo ordering ──────────────────────────────────────────────

    @Nested
    class CompareTo {

        @Test
        void lowerIdIsLess() {
            var a = NodeId.of(1);
            var b = NodeId.of(2);
            assertTrue(a.compareTo(b) < 0);
        }

        @Test
        void higherIdIsGreater() {
            var a = NodeId.of(5);
            var b = NodeId.of(3);
            assertTrue(a.compareTo(b) > 0);
        }

        @Test
        void sameIdComparesToZero() {
            var a = NodeId.of(7);
            var b = NodeId.of(7);
            assertEquals(0, a.compareTo(b));
        }

        @Test
        void negativeIdOrdersBeforePositive() {
            var neg = NodeId.of(-1);
            var pos = NodeId.of(1);
            assertTrue(neg.compareTo(pos) < 0);
        }
    }

    // ── equals and hashCode ─────────────────────────────────────────────

    @Nested
    class EqualsAndHashCode {

        @Test
        void equalIds() {
            var a = NodeId.of(3);
            var b = NodeId.of(3);
            assertEquals(a, b);
        }

        @Test
        void equalIdsHaveSameHashCode() {
            var a = NodeId.of(3);
            var b = NodeId.of(3);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentIdsNotEqual() {
            var a = NodeId.of(1);
            var b = NodeId.of(2);
            assertNotEquals(a, b);
        }

        @Test
        void notEqualToNull() {
            assertNotEquals(null, NodeId.of(1));
        }

        @Test
        void reflexiveEquals() {
            var a = NodeId.of(42);
            assertEquals(a, a);
        }
    }

    // ── toString ────────────────────────────────────────────────────────

    @Nested
    class ToStringFormat {

        @Test
        void formatIsNodeDashId() {
            assertEquals("Node-1", NodeId.of(1).toString());
        }

        @Test
        void zeroNodeId() {
            assertEquals("Node-0", NodeId.of(0).toString());
        }

        @Test
        void largeNodeId() {
            assertEquals("Node-999", NodeId.of(999).toString());
        }
    }
}

package io.configd.edge;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PoisonPillDetectorTest {

    @Nested
    class Quarantine {
        @Test
        void keyQuarantinedAfterMaxRetries() {
            PoisonPillDetector detector = new PoisonPillDetector(3);
            assertFalse(detector.recordFailure("key1", "bad format"));
            assertFalse(detector.recordFailure("key1", "bad format"));
            assertTrue(detector.recordFailure("key1", "bad format")); // 3rd failure
            assertTrue(detector.isPoisoned("key1"));
        }

        @Test
        void healthyKeyNotPoisoned() {
            PoisonPillDetector detector = new PoisonPillDetector(3);
            assertFalse(detector.isPoisoned("key1"));
        }

        @Test
        void successResetsFailureCount() {
            PoisonPillDetector detector = new PoisonPillDetector(3);
            detector.recordFailure("key1", "error");
            detector.recordFailure("key1", "error");
            detector.recordSuccess("key1"); // reset
            detector.recordFailure("key1", "error");
            detector.recordFailure("key1", "error");
            assertFalse(detector.isPoisoned("key1")); // only 2 consecutive
        }

        @Test
        void releaseUnquarantinesKey() {
            PoisonPillDetector detector = new PoisonPillDetector(1);
            detector.recordFailure("key1", "corrupt");
            assertTrue(detector.isPoisoned("key1"));
            assertTrue(detector.release("key1"));
            assertFalse(detector.isPoisoned("key1"));
        }

        @Test
        void releaseReturnsFalseIfNotPoisoned() {
            PoisonPillDetector detector = new PoisonPillDetector(3);
            assertFalse(detector.release("key1"));
        }
    }

    @Nested
    class Listener {
        @Test
        void listenerCalledOnPoisoning() {
            AtomicInteger calls = new AtomicInteger();
            PoisonPillDetector detector = new PoisonPillDetector(2,
                    (key, count, reason) -> calls.incrementAndGet());
            detector.recordFailure("key1", "error");
            assertEquals(0, calls.get());
            detector.recordFailure("key1", "error");
            assertEquals(1, calls.get());
        }
    }

    @Nested
    class Metrics {
        @Test
        void poisonedKeysReturnsSnapshot() {
            PoisonPillDetector detector = new PoisonPillDetector(1);
            detector.recordFailure("a", "err");
            detector.recordFailure("b", "err");
            assertEquals(2, detector.poisonedCount());
            assertTrue(detector.poisonedKeys().contains("a"));
            assertTrue(detector.poisonedKeys().contains("b"));
        }
    }

    @Nested
    class Validation {
        @Test
        void maxRetriesMustBePositive() {
            assertThrows(IllegalArgumentException.class, () -> new PoisonPillDetector(0));
        }
    }
}

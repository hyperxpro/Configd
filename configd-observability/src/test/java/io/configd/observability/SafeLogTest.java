package io.configd.observability;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class SafeLogTest {

    @Nested
    class Redact {
        @Test
        void producesStableHexFingerprint() {
            String r1 = SafeLog.redact("super-secret-token");
            String r2 = SafeLog.redact("super-secret-token");
            assertEquals(r1, r2, "redact must be deterministic");
            assertEquals(16, r1.length());
            assertTrue(r1.matches("[0-9a-f]+"), "must be lowercase hex");
        }

        @Test
        void differentInputsProduceDifferentFingerprints() {
            assertNotEquals(SafeLog.redact("a"), SafeLog.redact("b"));
        }

        @Test
        void rejectsNull() {
            assertThrows(NullPointerException.class, () -> SafeLog.redact(null));
        }

        @Test
        void doesNotLeakRawValueInReturnString() {
            String secret = "my-credit-card-4242-4242-4242-4242";
            String redacted = SafeLog.redact(secret);
            assertFalse(redacted.contains("4242"), "raw value must not appear in fingerprint");
            assertFalse(redacted.contains("credit"));
        }
    }

    @Nested
    class CardinalityGuard {
        @Test
        void unknownForNullInput() {
            assertEquals("unknown", SafeLog.cardinalityGuard(null));
        }

        @Test
        void deterministicBucketing() {
            assertEquals(SafeLog.cardinalityGuard("config.foo.bar"),
                    SafeLog.cardinalityGuard("config.foo.bar"));
        }

        @Test
        void respectsBucketCount() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                seen.add(SafeLog.cardinalityGuard("k" + i, 8));
            }
            assertTrue(seen.size() <= 8,
                    "must not produce more than 8 distinct buckets, got " + seen.size());
            // Random data with 1000 keys into 8 buckets should hit all 8 with overwhelming
            // probability — confirms we're actually distributing rather than always hashing
            // to the same bucket.
            assertTrue(seen.size() >= 4, "expected reasonable spread, got " + seen.size());
        }

        @Test
        void rejectsNonPositiveBuckets() {
            assertThrows(IllegalArgumentException.class, () -> SafeLog.cardinalityGuard("x", 0));
            assertThrows(IllegalArgumentException.class, () -> SafeLog.cardinalityGuard("x", -1));
        }

        @Test
        void prefixFormatIsBucketDashIndex() {
            String b = SafeLog.cardinalityGuard("anything", 64);
            assertTrue(b.startsWith("bucket-"), b);
            int idx = Integer.parseInt(b.substring("bucket-".length()));
            assertTrue(idx >= 0 && idx < 64);
        }
    }

    @Nested
    class IsSafeForLog {
        @Test
        void acceptsAllowlistedAlphanumericPunctuation() {
            assertTrue(SafeLog.isSafeForLog("config.svc.region-west/01"));
            assertTrue(SafeLog.isSafeForLog("abc_DEF-123"));
        }

        @Test
        void rejectsControlCharacters() {
            assertFalse(SafeLog.isSafeForLog("hello\nworld"));
            assertFalse(SafeLog.isSafeForLog("ansi\u001b[31m"));
        }

        @Test
        void rejectsNullAndEmpty() {
            assertFalse(SafeLog.isSafeForLog(null));
            assertFalse(SafeLog.isSafeForLog(""));
        }

        @Test
        void rejectsExcessiveLength() {
            String tooLong = "a".repeat(129);
            assertFalse(SafeLog.isSafeForLog(tooLong));
            assertTrue(SafeLog.isSafeForLog("a".repeat(128)));
        }

        @Test
        void rejectsRandomBinaryNoise() {
            byte[] noise = new byte[32];
            ThreadLocalRandom.current().nextBytes(noise);
            assertFalse(SafeLog.isSafeForLog(new String(noise)));
        }
    }
}

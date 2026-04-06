package io.configd.edge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PrefixSubscription} — prefix-based subscription management.
 */
class PrefixSubscriptionTest {

    private PrefixSubscription subs;

    @BeforeEach
    void setUp() {
        subs = new PrefixSubscription();
    }

    // -----------------------------------------------------------------------
    // Subscribe / Unsubscribe
    // -----------------------------------------------------------------------

    @Nested
    class SubscribeUnsubscribe {

        @Test
        void subscribeNewPrefixReturnsTrue() {
            assertTrue(subs.subscribe("app."));
        }

        @Test
        void subscribeDuplicatePrefixReturnsFalse() {
            subs.subscribe("app.");
            assertFalse(subs.subscribe("app."));
        }

        @Test
        void unsubscribeExistingPrefixReturnsTrue() {
            subs.subscribe("app.");
            assertTrue(subs.unsubscribe("app."));
        }

        @Test
        void unsubscribeAbsentPrefixReturnsFalse() {
            assertFalse(subs.unsubscribe("app."));
        }

        @Test
        void subscribeAfterUnsubscribeWorks() {
            subs.subscribe("app.");
            subs.unsubscribe("app.");
            assertTrue(subs.subscribe("app."));
        }
    }

    // -----------------------------------------------------------------------
    // Prefix matching
    // -----------------------------------------------------------------------

    @Nested
    class PrefixMatching {

        @Test
        void matchesKeyWithSubscribedPrefix() {
            subs.subscribe("app.");
            assertTrue(subs.matches("app.db.pool.size"));
        }

        @Test
        void doesNotMatchUnrelatedKey() {
            subs.subscribe("app.");
            assertFalse(subs.matches("cache.ttl"));
        }

        @Test
        void matchesExactPrefix() {
            subs.subscribe("app.config");
            assertTrue(subs.matches("app.config"));
        }

        @Test
        void noSubscriptionsMatchNothing() {
            assertFalse(subs.matches("anything"));
        }

        @Test
        void multipleSubscriptionsMatchOverlap() {
            subs.subscribe("app.");
            subs.subscribe("app.db.");
            assertTrue(subs.matches("app.db.pool.size"));
        }

        @Test
        void matchFirstPrefixOnly() {
            subs.subscribe("app.");
            subs.subscribe("cache.");
            assertTrue(subs.matches("app.setting"));
            assertTrue(subs.matches("cache.ttl"));
            assertFalse(subs.matches("db.host"));
        }
    }

    // -----------------------------------------------------------------------
    // Matching prefixes
    // -----------------------------------------------------------------------

    @Nested
    class MatchingPrefixes {

        @Test
        void returnsAllMatchingPrefixes() {
            subs.subscribe("app.");
            subs.subscribe("app.db.");
            subs.subscribe("cache.");

            Set<String> matching = subs.matchingPrefixes("app.db.pool.size");
            assertEquals(Set.of("app.", "app.db."), matching);
        }

        @Test
        void returnsEmptySetWhenNoMatch() {
            subs.subscribe("app.");
            Set<String> matching = subs.matchingPrefixes("cache.ttl");
            assertTrue(matching.isEmpty());
        }

        @Test
        void returnsEmptySetWhenNoSubscriptions() {
            Set<String> matching = subs.matchingPrefixes("anything");
            assertTrue(matching.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Prefixes snapshot
    // -----------------------------------------------------------------------

    @Nested
    class PrefixesSnapshot {

        @Test
        void prefixesReturnsCurrentSet() {
            subs.subscribe("app.");
            subs.subscribe("db.");
            assertEquals(Set.of("app.", "db."), subs.prefixes());
        }

        @Test
        void prefixesReturnsUnmodifiableSet() {
            subs.subscribe("app.");
            Set<String> snapshot = subs.prefixes();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add("hack."));
        }

        @Test
        void prefixesSnapshotIsIsolatedFromMutations() {
            subs.subscribe("app.");
            Set<String> snapshot = subs.prefixes();
            subs.subscribe("db.");
            // Snapshot should not include "db." added after snapshot was taken
            assertFalse(snapshot.contains("db."));
        }

        @Test
        void emptyPrefixes() {
            assertTrue(subs.prefixes().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void subscribeNullThrows() {
            assertThrows(NullPointerException.class, () -> subs.subscribe(null));
        }

        @Test
        void subscribeBlankThrows() {
            assertThrows(IllegalArgumentException.class, () -> subs.subscribe("  "));
        }

        @Test
        void unsubscribeNullThrows() {
            assertThrows(NullPointerException.class, () -> subs.unsubscribe(null));
        }

        @Test
        void matchesNullThrows() {
            assertThrows(NullPointerException.class, () -> subs.matches(null));
        }

        @Test
        void matchingPrefixesNullThrows() {
            assertThrows(NullPointerException.class, () -> subs.matchingPrefixes(null));
        }
    }
}

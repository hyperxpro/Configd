package io.configd.edge;

import io.configd.common.Clock;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CT-25 (C2 half) — the ADR-0038 edge-side prefix STORAGE filter
 * ({@link EdgeConfigClient#applyDelta} / {@link EdgeConfigClient#filterForStorage}).
 *
 * <p>Contract clause (architecture §7 / charter §4 C1; re-anchored by ADR-0038):
 * prefix subscription is an edge-side <b>storage/serving</b> filter, not a transport filter
 * — the full signed chain is delivered; the edge stores only the subscribed slice (plus
 * strong-read keys, always), and the chain version still advances for filtered-out mutations
 * so gap detection is unaffected.
 *
 * <p>Verifies: matching keys stored; non-matching keys NOT stored but version advances;
 * {@code secure/} keys ALWAYS stored regardless of subscription (store-and-fail-closed-serve);
 * empty subscription = full store; chain validation (from/to version + gap detection)
 * unaffected by filtering.
 */
class EdgePrefixStorageFilterTest {

    static final class TestClock implements Clock {
        long timeMs;
        TestClock(long initial) { this.timeMs = initial; }
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
        void advance(long ms) { timeMs += ms; }
    }

    private TestClock clock;
    private EdgeConfigClient client;

    @BeforeEach
    void setUp() {
        clock = new TestClock(10_000);
        client = new EdgeConfigClient(clock);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ConfigDelta put(long from, long to, String... keyValues) {
        java.util.List<ConfigMutation> ms = new java.util.ArrayList<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            ms.add(new ConfigMutation.Put(keyValues[i], bytes(keyValues[i + 1])));
        }
        return new ConfigDelta(from, to, ms);
    }

    // -----------------------------------------------------------------------
    // Empty subscription = full store
    // -----------------------------------------------------------------------

    @Nested
    class EmptySubscriptionIsFullStore {

        @Test
        void emptySubscriptionStoresEveryKey() {
            // No addSubscription() calls → empty prefix set → full store.
            client.applyDelta(put(0, 1, "svc/a", "1", "other/b", "2", "misc/c", "3"));

            assertEquals(1, client.currentVersion());
            assertTrue(client.get("svc/a").found());
            assertTrue(client.get("other/b").found());
            assertTrue(client.get("misc/c").found());
        }
    }

    // -----------------------------------------------------------------------
    // Prefix filter: matching stored, non-matching version-advances-only
    // -----------------------------------------------------------------------

    @Nested
    class PrefixFilter {

        @BeforeEach
        void subscribe() {
            client.addSubscription("svc/");
        }

        @Test
        void matchingKeyIsStored() {
            client.applyDelta(put(0, 1, "svc/a", "1"));
            assertTrue(client.get("svc/a").found());
            assertArrayEquals(bytes("1"), client.get("svc/a").value());
        }

        @Test
        void nonMatchingKeyIsNotStoredButVersionAdvances() {
            client.applyDelta(put(0, 1, "other/b", "2"));

            // Not stored — outside the subscription, no payload kept.
            assertFalse(client.get("other/b").found());
            // But the chain version STILL advanced (gap detection unaffected).
            assertEquals(1, client.currentVersion());
        }

        @Test
        void mixedBatchStoresOnlyMatchingButAdvancesVersionOnce() {
            client.applyDelta(put(0, 1, "svc/a", "1", "other/b", "2", "svc/c", "3"));

            assertTrue(client.get("svc/a").found());
            assertFalse(client.get("other/b").found());
            assertTrue(client.get("svc/c").found());
            assertEquals(1, client.currentVersion());
        }

        @Test
        void allNonMatchingBatchStillAdvancesVersion() {
            // Every mutation filtered out → an empty filtered delta — version still advances.
            client.applyDelta(put(0, 1, "other/b", "2", "misc/c", "3"));
            assertEquals(1, client.currentVersion());
            assertFalse(client.get("other/b").found());
            assertFalse(client.get("misc/c").found());
        }
    }

    // -----------------------------------------------------------------------
    // Strong-read keys ALWAYS stored (store-and-fail-closed-serve)
    // -----------------------------------------------------------------------

    @Nested
    class StrongReadKeysAlwaysStored {

        @Test
        void secureKeyStoredEvenWhenNotSubscribed() {
            client.addSubscription("svc/"); // does NOT cover secure/
            client.applyDelta(put(0, 1, "secure/killswitch", "ON", "other/x", "y"));

            // secure/ is a strong-read key — ALWAYS stored regardless of subscription.
            assertTrue(client.get("secure/killswitch").found(),
                    "secure/ keys must be stored regardless of subscription (CT-37)");
            // The non-matching, non-secure key is still filtered out.
            assertFalse(client.get("other/x").found());
            assertEquals(1, client.currentVersion());
        }

        @Test
        void secureKeyStoredUnderUnrelatedSubscription() {
            client.addSubscription("app/");
            client.applyDelta(put(0, 1, "secure/acl", "deny"));
            assertTrue(client.get("secure/acl").found());
        }
    }

    // -----------------------------------------------------------------------
    // Chain validation unaffected by filtering
    // -----------------------------------------------------------------------

    @Nested
    class ChainValidationUnaffected {

        @Test
        void sequentialFilteredDeltasAdvanceTheChain() {
            client.addSubscription("svc/");
            client.applyDelta(put(0, 1, "other/a", "1")); // filtered out, v→1
            client.applyDelta(put(1, 2, "svc/b", "2"));   // stored, v→2
            client.applyDelta(put(2, 3, "other/c", "3")); // filtered out, v→3

            assertEquals(3, client.currentVersion());
            assertTrue(client.get("svc/b").found());
            assertFalse(client.get("other/a").found());
            assertFalse(client.get("other/c").found());
        }

        @Test
        void filteredDeltaWithWrongFromVersionStillThrowsGap() {
            client.addSubscription("svc/");
            client.applyDelta(put(0, 1, "svc/a", "1"));
            // fromVersion 5 != current 1 — the store's gap guard fires even though the
            // mutation is in-subscription (filtering preserves from/to versions).
            assertThrows(IllegalArgumentException.class,
                    () -> client.applyDelta(put(5, 6, "svc/b", "2")));
        }

        @Test
        void filterPreservesFromAndToVersions() {
            client.addSubscription("svc/");
            // Even when every mutation is dropped, from/to are preserved so the store
            // advances exactly from 7→8 (here we bootstrap to 7 first via a full snapshot).
            client.loadSnapshot(new io.configd.store.ConfigSnapshot(
                    io.configd.store.HamtMap.empty(), 7, 7));
            client.applyDelta(put(7, 8, "other/x", "y"));
            assertEquals(8, client.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Filter as a pure function (lockstep contract for the read-store mirror)
    // -----------------------------------------------------------------------

    @Nested
    class FilterForStorageIsDeterministic {

        @Test
        void filterForStorageReturnsSameSubsetForMirrorStore() {
            client.addSubscription("svc/");
            ConfigDelta original = put(0, 1, "svc/a", "1", "other/b", "2");
            ConfigDelta filtered = client.filterForStorage(original);

            // Same versions, only the matching mutation kept.
            assertEquals(0, filtered.fromVersion());
            assertEquals(1, filtered.toVersion());
            assertEquals(1, filtered.mutations().size());
            assertEquals("svc/a", filtered.mutations().get(0).key());

            // Idempotent: a second call yields the same subset (lockstep with the read store).
            ConfigDelta again = client.filterForStorage(original);
            assertEquals(filtered.mutations(), again.mutations());
        }

        @Test
        void filterForStorageReturnsOriginalWhenNothingDropped() {
            // Full-store (empty subscription): the original is returned (no allocation).
            ConfigDelta original = put(0, 1, "a", "1", "b", "2");
            assertSame(original, client.filterForStorage(original));
        }
    }
}

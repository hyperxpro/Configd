package io.configd.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HamtMap} — the persistent HAMT with structural sharing.
 */
class HamtMapTest {

    // -----------------------------------------------------------------------
    // Basic operations
    // -----------------------------------------------------------------------

    @Nested
    class BasicOperations {

        @Test
        void emptyMapHasZeroSize() {
            HamtMap<String, String> map = HamtMap.empty();
            assertEquals(0, map.size());
            assertTrue(map.isEmpty());
        }

        @Test
        void putAndGet() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key1", "value1");
            assertEquals("value1", map.get("key1"));
            assertEquals(1, map.size());
        }

        @Test
        void putOverwritesExistingValue() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key", "v1")
                    .put("key", "v2");
            assertEquals("v2", map.get("key"));
            assertEquals(1, map.size());
        }

        @Test
        void putWithSameValueReturnsSameMap() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key", "value");
            HamtMap<String, String> same = map.put("key", "value");
            assertSame(map, same);
        }

        @Test
        void getReturnsNullForAbsentKey() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key", "value");
            assertNull(map.get("other"));
        }

        @Test
        void containsKeyForPresentAndAbsent() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("present", "yes");
            assertTrue(map.containsKey("present"));
            assertFalse(map.containsKey("absent"));
        }

        @Test
        void removeExistingKey() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("a", "1")
                    .put("b", "2")
                    .remove("a");
            assertNull(map.get("a"));
            assertEquals("2", map.get("b"));
            assertEquals(1, map.size());
        }

        @Test
        void removeAbsentKeyReturnsSameMap() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key", "value");
            HamtMap<String, String> same = map.remove("other");
            assertSame(map, same);
        }

        @Test
        void removeLastKeyReturnsEmptyMap() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("key", "value")
                    .remove("key");
            assertTrue(map.isEmpty());
            assertEquals(0, map.size());
        }

        @Test
        void removeFromEmptyMapReturnsSameMap() {
            HamtMap<String, String> map = HamtMap.empty();
            assertSame(map, map.remove("anything"));
        }

        @Test
        void nullKeyThrows() {
            HamtMap<String, String> map = HamtMap.empty();
            assertThrows(NullPointerException.class, () -> map.get(null));
            assertThrows(NullPointerException.class, () -> map.put(null, "v"));
            assertThrows(NullPointerException.class, () -> map.put("k", null));
            assertThrows(NullPointerException.class, () -> map.remove(null));
        }
    }

    // -----------------------------------------------------------------------
    // Structural sharing
    // -----------------------------------------------------------------------

    @Nested
    class StructuralSharing {

        @Test
        void putDoesNotModifyOriginal() {
            HamtMap<String, String> original = HamtMap.<String, String>empty()
                    .put("a", "1")
                    .put("b", "2");

            HamtMap<String, String> modified = original.put("c", "3");

            // Original unchanged
            assertEquals(2, original.size());
            assertNull(original.get("c"));

            // Modified has all three
            assertEquals(3, modified.size());
            assertEquals("1", modified.get("a"));
            assertEquals("2", modified.get("b"));
            assertEquals("3", modified.get("c"));
        }

        @Test
        void removeDoesNotModifyOriginal() {
            HamtMap<String, String> original = HamtMap.<String, String>empty()
                    .put("a", "1")
                    .put("b", "2");

            HamtMap<String, String> modified = original.remove("a");

            // Original unchanged
            assertEquals(2, original.size());
            assertEquals("1", original.get("a"));

            // Modified lacks "a"
            assertEquals(1, modified.size());
            assertNull(modified.get("a"));
            assertEquals("2", modified.get("b"));
        }

        @Test
        void multipleVersionsCoexist() {
            HamtMap<String, String> v1 = HamtMap.<String, String>empty()
                    .put("key", "v1");
            HamtMap<String, String> v2 = v1.put("key", "v2");
            HamtMap<String, String> v3 = v2.put("extra", "data");

            assertEquals("v1", v1.get("key"));
            assertEquals("v2", v2.get("key"));
            assertEquals("v2", v3.get("key"));
            assertEquals("data", v3.get("extra"));
            assertNull(v1.get("extra"));
            assertNull(v2.get("extra"));
        }
    }

    // -----------------------------------------------------------------------
    // Hash collision handling
    // -----------------------------------------------------------------------

    @Nested
    class HashCollisions {

        /**
         * Key wrapper that forces all keys to share the same hash code,
         * triggering CollisionNode creation deep in the trie.
         */
        record FixedHashKey(String value) {
            @Override
            public int hashCode() {
                return 42; // all keys collide
            }

            @Override
            public boolean equals(Object o) {
                return o instanceof FixedHashKey fhk && value.equals(fhk.value);
            }
        }

        @Test
        void handlesFullHashCollisions() {
            HamtMap<FixedHashKey, String> map = HamtMap.empty();
            map = map.put(new FixedHashKey("a"), "1");
            map = map.put(new FixedHashKey("b"), "2");
            map = map.put(new FixedHashKey("c"), "3");

            assertEquals("1", map.get(new FixedHashKey("a")));
            assertEquals("2", map.get(new FixedHashKey("b")));
            assertEquals("3", map.get(new FixedHashKey("c")));
            assertEquals(3, map.size());
        }

        @Test
        void updateValueInCollisionNode() {
            HamtMap<FixedHashKey, String> map = HamtMap.<FixedHashKey, String>empty()
                    .put(new FixedHashKey("a"), "1")
                    .put(new FixedHashKey("b"), "2")
                    .put(new FixedHashKey("a"), "updated");

            assertEquals("updated", map.get(new FixedHashKey("a")));
            assertEquals("2", map.get(new FixedHashKey("b")));
            assertEquals(2, map.size());
        }

        @Test
        void removeFromCollisionNode() {
            HamtMap<FixedHashKey, String> map = HamtMap.<FixedHashKey, String>empty()
                    .put(new FixedHashKey("a"), "1")
                    .put(new FixedHashKey("b"), "2")
                    .put(new FixedHashKey("c"), "3");

            map = map.remove(new FixedHashKey("b"));
            assertEquals(2, map.size());
            assertEquals("1", map.get(new FixedHashKey("a")));
            assertNull(map.get(new FixedHashKey("b")));
            assertEquals("3", map.get(new FixedHashKey("c")));
        }

        @Test
        void removeAllFromCollisionNode() {
            HamtMap<FixedHashKey, String> map = HamtMap.<FixedHashKey, String>empty()
                    .put(new FixedHashKey("a"), "1")
                    .put(new FixedHashKey("b"), "2");

            map = map.remove(new FixedHashKey("a")).remove(new FixedHashKey("b"));
            assertTrue(map.isEmpty());
        }

        @Test
        void getAbsentKeyWithSameHash() {
            HamtMap<FixedHashKey, String> map = HamtMap.<FixedHashKey, String>empty()
                    .put(new FixedHashKey("a"), "1");
            assertNull(map.get(new FixedHashKey("absent")));
        }
    }

    // -----------------------------------------------------------------------
    // forEach
    // -----------------------------------------------------------------------

    @Nested
    class ForEach {

        @Test
        void forEachVisitsAllEntries() {
            HamtMap<String, String> map = HamtMap.<String, String>empty()
                    .put("a", "1")
                    .put("b", "2")
                    .put("c", "3");

            Map<String, String> collected = new HashMap<>();
            map.forEach(collected::put);

            assertEquals(3, collected.size());
            assertEquals("1", collected.get("a"));
            assertEquals("2", collected.get("b"));
            assertEquals("3", collected.get("c"));
        }

        @Test
        void forEachOnEmptyMap() {
            HamtMap<String, String> map = HamtMap.empty();
            map.forEach((k, v) -> fail("Should not be called"));
        }
    }

    // -----------------------------------------------------------------------
    // Large dataset
    // -----------------------------------------------------------------------

    @Nested
    class LargeDataset {

        @Test
        void hundredThousandEntries() {
            int count = 100_000;
            HamtMap<String, Integer> map = HamtMap.empty();

            // Insert
            for (int i = 0; i < count; i++) {
                map = map.put("key-" + i, i);
            }
            assertEquals(count, map.size());

            // Verify all present
            for (int i = 0; i < count; i++) {
                assertEquals(i, map.get("key-" + i), "Missing key-" + i);
            }

            // Remove half
            HamtMap<String, Integer> reduced = map;
            for (int i = 0; i < count; i += 2) {
                reduced = reduced.remove("key-" + i);
            }
            assertEquals(count / 2, reduced.size());

            // Verify remaining
            for (int i = 0; i < count; i++) {
                if (i % 2 == 0) {
                    assertNull(reduced.get("key-" + i));
                } else {
                    assertEquals(i, reduced.get("key-" + i));
                }
            }

            // Original still intact
            assertEquals(count, map.size());
        }

        @Test
        void forEachVisitsAllEntriesInLargeMap() {
            int count = 10_000;
            HamtMap<String, Integer> map = HamtMap.empty();
            for (int i = 0; i < count; i++) {
                map = map.put("key-" + i, i);
            }

            Set<String> visited = new HashSet<>();
            map.forEach((k, v) -> visited.add(k));
            assertEquals(count, visited.size());
        }
    }

    // -----------------------------------------------------------------------
    // ArrayNode promotion/demotion
    // -----------------------------------------------------------------------

    @Nested
    class ArrayNodeTransitions {

        @Test
        void promotesToArrayNodeAndDemotes() {
            // Insert enough keys with diverse hash fragments at level 0 to
            // trigger BitmapIndexedNode -> ArrayNode promotion (>16 children)
            HamtMap<Integer, String> map = HamtMap.empty();
            for (int i = 0; i < 32; i++) {
                map = map.put(i, "v" + i);
            }
            assertEquals(32, map.size());

            // Verify all accessible
            for (int i = 0; i < 32; i++) {
                assertEquals("v" + i, map.get(i));
            }

            // Remove enough to trigger demotion
            for (int i = 0; i < 24; i++) {
                map = map.remove(i);
            }
            assertEquals(8, map.size());

            // Remaining still accessible
            for (int i = 24; i < 32; i++) {
                assertEquals("v" + i, map.get(i));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent read safety (immutability guarantees)
    // -----------------------------------------------------------------------

    @Nested
    class ConcurrentReadSafety {

        @Test
        void concurrentReadsOnSharedSnapshot() throws InterruptedException {
            int entries = 10_000;
            HamtMap<String, Integer> map = HamtMap.empty();
            for (int i = 0; i < entries; i++) {
                map = map.put("key-" + i, i);
            }

            final HamtMap<String, Integer> shared = map;
            int threadCount = 8;
            ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
            Thread[] threads = new Thread[threadCount];

            for (int t = 0; t < threadCount; t++) {
                threads[t] = Thread.ofPlatform().start(() -> {
                    try {
                        for (int i = 0; i < entries; i++) {
                            Integer val = shared.get("key-" + i);
                            if (val == null || val != i) {
                                errors.put(Thread.currentThread().getName(),
                                        new AssertionError("Expected " + i + " but got " + val));
                                return;
                            }
                        }
                    } catch (Throwable ex) {
                        errors.put(Thread.currentThread().getName(), ex);
                    }
                });
            }

            for (Thread t : threads) {
                t.join(10_000);
            }

            if (!errors.isEmpty()) {
                fail("Concurrent read errors: " + errors);
            }
        }
    }
}

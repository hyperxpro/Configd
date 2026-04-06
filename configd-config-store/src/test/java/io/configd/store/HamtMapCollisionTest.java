package io.configd.store;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link HamtMap} correctly handles hash collisions -- keys with
 * identical {@code hashCode()} values that differ in {@code equals()}.
 * <p>
 * The HAMT uses 5-bit chunks of the (spread) hash code at each level,
 * giving 32-way branching. When two keys share the same full 32-bit spread
 * hash, they end up in a {@code CollisionNode}. When they share only a
 * prefix (e.g. the same low 5 bits), they diverge at a deeper level and
 * live in separate bitmap-indexed sub-nodes.
 */
class HamtMapCollisionTest {

    // -- Test key type that forces controlled hash collisions ---------------

    /**
     * A key wrapper that lets tests control the exact hashCode value returned,
     * while keeping equals based solely on the string value. This lets us
     * force full collisions (same hash) or partial/shallow collisions (same
     * 5-bit prefix at level 0 but different deeper bits).
     * <p>
     * Note: HamtMap applies {@code spread(h) = h ^ (h >>> 16)} internally.
     * For hash values below 65536 the spread function is the identity, so we
     * can reason about trie paths directly.
     */
    record CollisionKey(String value, int hash) {
        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CollisionKey ck && value.equals(ck.value);
        }
    }

    // -----------------------------------------------------------------------
    // Deep collision: identical full 32-bit hash
    // -----------------------------------------------------------------------

    @Nested
    class DeepCollision {

        /** All keys share hash = 42, producing a CollisionNode. */
        private static final int SHARED_HASH = 42;

        private CollisionKey key(String value) {
            return new CollisionKey(value, SHARED_HASH);
        }

        @Test
        void insertMultipleCollisionKeysAndRetrieveAll() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B")
                    .put(key("charlie"), "C")
                    .put(key("delta"), "D");

            assertEquals(4, map.size());
            assertEquals("A", map.get(key("alpha")));
            assertEquals("B", map.get(key("bravo")));
            assertEquals("C", map.get(key("charlie")));
            assertEquals("D", map.get(key("delta")));
        }

        @Test
        void absentKeyWithSameHashReturnsNull() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B");

            assertNull(map.get(key("unknown")));
            assertFalse(map.containsKey(key("unknown")));
        }

        @Test
        void removeOneCollisionKeyOthersSurvive() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B")
                    .put(key("charlie"), "C");

            HamtMap<CollisionKey, String> after = map.remove(key("bravo"));

            assertEquals(2, after.size());
            assertEquals("A", after.get(key("alpha")));
            assertNull(after.get(key("bravo")));
            assertEquals("C", after.get(key("charlie")));

            // Original unchanged (immutability)
            assertEquals(3, map.size());
            assertEquals("B", map.get(key("bravo")));
        }

        @Test
        void removeAbsentCollisionKeyReturnsSameMap() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B");

            HamtMap<CollisionKey, String> same = map.remove(key("unknown"));
            assertSame(map, same);
        }

        @Test
        void removeDownToOneCollisionKeyCollapsesNode() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B");

            HamtMap<CollisionKey, String> after = map.remove(key("alpha"));

            assertEquals(1, after.size());
            assertNull(after.get(key("alpha")));
            assertEquals("B", after.get(key("bravo")));
        }

        @Test
        void removeAllCollisionKeysYieldsEmptyMap() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B")
                    .put(key("charlie"), "C");

            map = map.remove(key("alpha"))
                     .remove(key("bravo"))
                     .remove(key("charlie"));

            assertTrue(map.isEmpty());
            assertEquals(0, map.size());
        }

        @Test
        void overwriteOneCollisionKeyLeavesOthersUnchanged() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B")
                    .put(key("charlie"), "C");

            HamtMap<CollisionKey, String> updated = map.put(key("bravo"), "B-updated");

            assertEquals(3, updated.size());
            assertEquals("A", updated.get(key("alpha")));
            assertEquals("B-updated", updated.get(key("bravo")));
            assertEquals("C", updated.get(key("charlie")));

            // Original unchanged
            assertEquals("B", map.get(key("bravo")));
        }

        @Test
        void overwriteWithSameValueReturnsSameMap() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B");

            HamtMap<CollisionKey, String> same = map.put(key("bravo"), "B");
            assertSame(map, same);
        }

        @Test
        void forEachVisitsAllCollisionEntries() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(key("alpha"), "A")
                    .put(key("bravo"), "B")
                    .put(key("charlie"), "C")
                    .put(key("delta"), "D");

            Map<String, String> collected = new HashMap<>();
            map.forEach((k, v) -> collected.put(k.value(), v));

            assertEquals(4, collected.size());
            assertEquals("A", collected.get("alpha"));
            assertEquals("B", collected.get("bravo"));
            assertEquals("C", collected.get("charlie"));
            assertEquals("D", collected.get("delta"));
        }

        @Test
        void sizeTracksInsertsAndRemovesAccurately() {
            HamtMap<CollisionKey, String> map = HamtMap.empty();
            assertEquals(0, map.size());

            map = map.put(key("a"), "1");
            assertEquals(1, map.size());

            map = map.put(key("b"), "2");
            assertEquals(2, map.size());

            map = map.put(key("c"), "3");
            assertEquals(3, map.size());

            // Overwrite does not change size
            map = map.put(key("b"), "2-new");
            assertEquals(3, map.size());

            map = map.remove(key("a"));
            assertEquals(2, map.size());

            map = map.remove(key("b"));
            assertEquals(1, map.size());

            map = map.remove(key("c"));
            assertEquals(0, map.size());
            assertTrue(map.isEmpty());
        }

        @Test
        void manyCollisionKeysStress() {
            int count = 50;
            HamtMap<CollisionKey, Integer> map = HamtMap.empty();
            for (int i = 0; i < count; i++) {
                map = map.put(key("key-" + i), i);
            }
            assertEquals(count, map.size());

            for (int i = 0; i < count; i++) {
                assertEquals(i, map.get(key("key-" + i)), "Missing key-" + i);
            }

            // Remove every other one
            HamtMap<CollisionKey, Integer> reduced = map;
            for (int i = 0; i < count; i += 2) {
                reduced = reduced.remove(key("key-" + i));
            }
            assertEquals(count / 2, reduced.size());

            for (int i = 0; i < count; i++) {
                if (i % 2 == 0) {
                    assertNull(reduced.get(key("key-" + i)));
                } else {
                    assertEquals(i, reduced.get(key("key-" + i)));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Shallow collision: same 5-bit prefix at level 0, different deeper bits
    // -----------------------------------------------------------------------

    @Nested
    class ShallowCollision {

        /**
         * Constructs keys that share the same 5-bit fragment at level 0
         * (bits 0-4) but differ at deeper levels. Since hash values below
         * 65536 pass through spread() unchanged, we can directly craft the
         * bit patterns.
         * <p>
         * Level 0 fragment: bits [4:0] of the spread hash.
         * We fix these to 0b01010 (= 10) and vary bits [9:5] so the keys
         * diverge at level 1.
         */
        private CollisionKey shallowKey(String value, int level1Fragment) {
            // Low 5 bits: fixed fragment (10)
            // Bits [9:5]: the level-1 fragment
            int hash = 10 | (level1Fragment << 5);
            return new CollisionKey(value, hash);
        }

        @Test
        void shallowCollisionKeysAreAllRetrievable() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(shallowKey("x", 0), "X0")
                    .put(shallowKey("y", 1), "Y1")
                    .put(shallowKey("z", 2), "Z2");

            assertEquals(3, map.size());
            assertEquals("X0", map.get(shallowKey("x", 0)));
            assertEquals("Y1", map.get(shallowKey("y", 1)));
            assertEquals("Z2", map.get(shallowKey("z", 2)));
        }

        @Test
        void removeShallowCollisionKeyPreservesOthers() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(shallowKey("x", 0), "X0")
                    .put(shallowKey("y", 1), "Y1")
                    .put(shallowKey("z", 2), "Z2");

            HamtMap<CollisionKey, String> after = map.remove(shallowKey("y", 1));

            assertEquals(2, after.size());
            assertEquals("X0", after.get(shallowKey("x", 0)));
            assertNull(after.get(shallowKey("y", 1)));
            assertEquals("Z2", after.get(shallowKey("z", 2)));
        }

        @Test
        void overwriteShallowCollisionKey() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(shallowKey("x", 0), "X0")
                    .put(shallowKey("y", 1), "Y1");

            HamtMap<CollisionKey, String> updated = map.put(shallowKey("x", 0), "X0-new");

            assertEquals(2, updated.size());
            assertEquals("X0-new", updated.get(shallowKey("x", 0)));
            assertEquals("Y1", updated.get(shallowKey("y", 1)));
        }

        @Test
        void forEachVisitsAllShallowCollisionEntries() {
            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(shallowKey("a", 3), "A3")
                    .put(shallowKey("b", 7), "B7")
                    .put(shallowKey("c", 15), "C15")
                    .put(shallowKey("d", 31), "D31");

            Map<String, String> collected = new HashMap<>();
            map.forEach((k, v) -> collected.put(k.value(), v));

            assertEquals(4, collected.size());
            assertEquals("A3", collected.get("a"));
            assertEquals("B7", collected.get("b"));
            assertEquals("C15", collected.get("c"));
            assertEquals("D31", collected.get("d"));
        }
    }

    // -----------------------------------------------------------------------
    // Mixed: collision keys coexisting with normal keys
    // -----------------------------------------------------------------------

    @Nested
    class MixedCollisionAndNormal {

        @Test
        void collisionKeysCoexistWithNonCollidingKeys() {
            // Two keys with identical hash (deep collision)
            CollisionKey ck1 = new CollisionKey("col-1", 99);
            CollisionKey ck2 = new CollisionKey("col-2", 99);

            // Two keys with distinct hashes (no collision)
            CollisionKey nk1 = new CollisionKey("normal-1", 1000);
            CollisionKey nk2 = new CollisionKey("normal-2", 2000);

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(ck1, "C1")
                    .put(ck2, "C2")
                    .put(nk1, "N1")
                    .put(nk2, "N2");

            assertEquals(4, map.size());
            assertEquals("C1", map.get(ck1));
            assertEquals("C2", map.get(ck2));
            assertEquals("N1", map.get(nk1));
            assertEquals("N2", map.get(nk2));
        }

        @Test
        void removeNonCollidingKeyDoesNotAffectCollisionNode() {
            CollisionKey ck1 = new CollisionKey("col-1", 99);
            CollisionKey ck2 = new CollisionKey("col-2", 99);
            CollisionKey nk = new CollisionKey("normal", 5000);

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(ck1, "C1")
                    .put(ck2, "C2")
                    .put(nk, "N");

            HamtMap<CollisionKey, String> after = map.remove(nk);

            assertEquals(2, after.size());
            assertEquals("C1", after.get(ck1));
            assertEquals("C2", after.get(ck2));
            assertNull(after.get(nk));
        }

        @Test
        void addCollisionKeyToExistingNonCollidingMap() {
            // Start with non-colliding keys, then add collision keys
            CollisionKey nk1 = new CollisionKey("n1", 100);
            CollisionKey nk2 = new CollisionKey("n2", 200);
            CollisionKey ck1 = new CollisionKey("c1", 300);
            CollisionKey ck2 = new CollisionKey("c2", 300);
            CollisionKey ck3 = new CollisionKey("c3", 300);

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(nk1, "N1")
                    .put(nk2, "N2")
                    .put(ck1, "C1")
                    .put(ck2, "C2")
                    .put(ck3, "C3");

            assertEquals(5, map.size());
            assertEquals("N1", map.get(nk1));
            assertEquals("N2", map.get(nk2));
            assertEquals("C1", map.get(ck1));
            assertEquals("C2", map.get(ck2));
            assertEquals("C3", map.get(ck3));
        }

        @Test
        void insertKeyWithDifferentHashIntoCollisionNodeSplits() {
            // When a CollisionNode receives a put with a different hash,
            // it must wrap itself in a BitmapIndexedNode and insert the
            // new key alongside.
            CollisionKey ck1 = new CollisionKey("col-1", 77);
            CollisionKey ck2 = new CollisionKey("col-2", 77);
            CollisionKey ck3 = new CollisionKey("col-3", 77);
            CollisionKey diff = new CollisionKey("diff", 78);

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(ck1, "C1")
                    .put(ck2, "C2")
                    .put(ck3, "C3")
                    .put(diff, "D");

            assertEquals(4, map.size());
            assertEquals("C1", map.get(ck1));
            assertEquals("C2", map.get(ck2));
            assertEquals("C3", map.get(ck3));
            assertEquals("D", map.get(diff));

            // Removing the different-hash key should leave the collision keys
            HamtMap<CollisionKey, String> afterRemove = map.remove(diff);
            assertEquals(3, afterRemove.size());
            assertEquals("C1", afterRemove.get(ck1));
            assertEquals("C2", afterRemove.get(ck2));
            assertEquals("C3", afterRemove.get(ck3));
            assertNull(afterRemove.get(diff));
        }
    }

    // -----------------------------------------------------------------------
    // Deep vs Shallow collision comparison
    // -----------------------------------------------------------------------

    @Nested
    class DeepVsShallowComparison {

        /**
         * Verifies that deep collisions (identical 32-bit spread hash) and
         * shallow collisions (same 5-bit prefix only) both work correctly
         * and can coexist in the same map.
         */
        @Test
        void deepAndShallowCollisionsCoexist() {
            // Deep collision group: all keys share hash = 500
            CollisionKey deep1 = new CollisionKey("deep-1", 500);
            CollisionKey deep2 = new CollisionKey("deep-2", 500);
            CollisionKey deep3 = new CollisionKey("deep-3", 500);

            // Shallow collision group: same low 5 bits (500 & 0x1F = 20),
            // but different overall hash.
            // 500 in binary: 0b111110100  -> low 5 bits = 0b10100 = 20
            // We need another hash with low 5 bits = 20 but different overall.
            // 20 + 32 = 52 -> 0b110100, low 5 bits = 0b10100 = 20
            CollisionKey shallow1 = new CollisionKey("shallow-1", 20);
            CollisionKey shallow2 = new CollisionKey("shallow-2", 52); // 20 + 32

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(deep1, "D1")
                    .put(deep2, "D2")
                    .put(deep3, "D3")
                    .put(shallow1, "S1")
                    .put(shallow2, "S2");

            assertEquals(5, map.size());

            // All deep collision keys retrievable
            assertEquals("D1", map.get(deep1));
            assertEquals("D2", map.get(deep2));
            assertEquals("D3", map.get(deep3));

            // All shallow collision keys retrievable
            assertEquals("S1", map.get(shallow1));
            assertEquals("S2", map.get(shallow2));

            // forEach visits all
            Map<String, String> collected = new HashMap<>();
            map.forEach((k, v) -> collected.put(k.value(), v));
            assertEquals(5, collected.size());
        }

        @Test
        void removeFromDeepDoesNotAffectShallow() {
            CollisionKey deep1 = new CollisionKey("deep-1", 500);
            CollisionKey deep2 = new CollisionKey("deep-2", 500);
            CollisionKey shallow = new CollisionKey("shallow", 20); // same low 5 bits as 500

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(deep1, "D1")
                    .put(deep2, "D2")
                    .put(shallow, "S");

            HamtMap<CollisionKey, String> after = map.remove(deep1);
            assertEquals(2, after.size());
            assertNull(after.get(deep1));
            assertEquals("D2", after.get(deep2));
            assertEquals("S", after.get(shallow));
        }

        @Test
        void removeFromShallowDoesNotAffectDeep() {
            CollisionKey deep1 = new CollisionKey("deep-1", 500);
            CollisionKey deep2 = new CollisionKey("deep-2", 500);
            CollisionKey shallow = new CollisionKey("shallow", 20);

            HamtMap<CollisionKey, String> map = HamtMap.<CollisionKey, String>empty()
                    .put(deep1, "D1")
                    .put(deep2, "D2")
                    .put(shallow, "S");

            HamtMap<CollisionKey, String> after = map.remove(shallow);
            assertEquals(2, after.size());
            assertEquals("D1", after.get(deep1));
            assertEquals("D2", after.get(deep2));
            assertNull(after.get(shallow));
        }
    }
}

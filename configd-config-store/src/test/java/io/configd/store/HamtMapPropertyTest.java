package io.configd.store;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link HamtMap}. These tests verify structural
 * invariants that must hold regardless of the insertion/deletion sequence.
 */
class HamtMapPropertyTest {

    @Property(tries = 500)
    void putThenGetReturnsValue(
            @ForAll @StringLength(min = 1, max = 50) String key,
            @ForAll @StringLength(min = 1, max = 100) String value) {
        HamtMap<String, String> map = HamtMap.<String, String>empty().put(key, value);
        assertEquals(value, map.get(key));
    }

    @Property(tries = 200)
    void sizeEqualsDistinctKeyCount(@ForAll List<@StringLength(min = 1, max = 20) String> keys) {
        HamtMap<String, String> map = HamtMap.empty();
        Set<String> distinct = new HashSet<>();
        for (String key : keys) {
            map = map.put(key, "v");
            distinct.add(key);
        }
        assertEquals(distinct.size(), map.size());
    }

    @Property(tries = 200)
    void removedKeyNotFound(
            @ForAll List<@StringLength(min = 1, max = 20) String> keys,
            @ForAll @IntRange(min = 0) int removeIdx) {
        Assume.that(!keys.isEmpty());
        HamtMap<String, String> map = HamtMap.empty();
        for (String key : keys) {
            map = map.put(key, "v");
        }
        String toRemove = keys.get(removeIdx % keys.size());
        map = map.remove(toRemove);
        assertNull(map.get(toRemove));
    }

    @Property(tries = 200)
    void structuralSharingPreservesOriginal(
            @ForAll List<@StringLength(min = 1, max = 20) String> keys) {
        Assume.that(!keys.isEmpty());
        HamtMap<String, String> original = HamtMap.empty();
        for (String key : keys) {
            original = original.put(key, "v1");
        }
        int originalSize = original.size();

        // Modify a copy — original must be unchanged
        HamtMap<String, String> modified = original.put("__new_key__", "v2");

        assertEquals(originalSize, original.size());
        assertNull(original.get("__new_key__"));
        assertEquals("v2", modified.get("__new_key__"));
    }

    @Property(tries = 200)
    void putOverwriteDoesNotChangeSize(
            @ForAll @StringLength(min = 1, max = 20) String key) {
        HamtMap<String, String> map = HamtMap.<String, String>empty()
                .put(key, "v1")
                .put(key, "v2");
        assertEquals(1, map.size());
        assertEquals("v2", map.get(key));
    }

    @Property(tries = 100)
    void allInsertedKeysRetrievable(
            @ForAll List<@StringLength(min = 1, max = 30) String> keys) {
        HamtMap<String, String> map = HamtMap.empty();
        Map<String, String> reference = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String val = "val-" + i;
            map = map.put(key, val);
            reference.put(key, val);
        }
        for (var entry : reference.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey()),
                    "Mismatch for key: " + entry.getKey());
        }
    }

    @Property(tries = 100)
    void forEachVisitsAllEntries(
            @ForAll List<@StringLength(min = 1, max = 20) String> keys) {
        HamtMap<String, String> map = HamtMap.empty();
        Set<String> inserted = new HashSet<>();
        for (String key : keys) {
            map = map.put(key, "v");
            inserted.add(key);
        }
        Set<String> visited = new HashSet<>();
        map.forEach((k, v) -> visited.add(k));
        assertEquals(inserted, visited);
    }
}

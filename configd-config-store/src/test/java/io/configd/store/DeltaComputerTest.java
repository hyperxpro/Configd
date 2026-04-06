package io.configd.store;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DeltaComputer}.
 */
class DeltaComputerTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds a snapshot from varargs key-value pairs at the given version.
     * Pairs are alternating: key1, value1, key2, value2, ...
     */
    private static ConfigSnapshot buildSnapshot(long version, long timestamp, String... keyValues) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < keyValues.length; i += 2) {
            data = data.put(keyValues[i],
                    new VersionedValue(bytes(keyValues[i + 1]), version, timestamp));
        }
        return new ConfigSnapshot(data, version, timestamp);
    }

    /** Collects mutations into a map keyed by config key for easy assertions. */
    private static Map<String, ConfigMutation> indexByKey(ConfigDelta delta) {
        Map<String, ConfigMutation> map = new HashMap<>();
        for (ConfigMutation m : delta.mutations()) {
            assertNull(map.put(m.key(), m), "Duplicate mutation for key: " + m.key());
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Null / empty source
    // -----------------------------------------------------------------------

    @Nested
    class NullOrEmptySource {

        @Test
        void nullSourceProducesAllPuts() {
            ConfigSnapshot target = buildSnapshot(3, 3000, "a", "1", "b", "2");

            ConfigDelta delta = DeltaComputer.compute(null, target);

            assertEquals(2, delta.size());
            for (ConfigMutation m : delta.mutations()) {
                assertInstanceOf(ConfigMutation.Put.class, m);
            }
        }

        @Test
        void emptySourceProducesAllPuts() {
            ConfigSnapshot target = buildSnapshot(3, 3000, "a", "1", "b", "2");

            ConfigDelta delta = DeltaComputer.compute(ConfigSnapshot.EMPTY, target);

            assertEquals(2, delta.size());
            Map<String, ConfigMutation> indexed = indexByKey(delta);
            assertInstanceOf(ConfigMutation.Put.class, indexed.get("a"));
            assertInstanceOf(ConfigMutation.Put.class, indexed.get("b"));
        }

        @Test
        void nullSourceVersionsMatchEmptyAndTarget() {
            ConfigSnapshot target = buildSnapshot(5, 5000, "x", "val");

            ConfigDelta delta = DeltaComputer.compute(null, target);

            assertEquals(0, delta.fromVersion(), "fromVersion should be 0 for null source");
            assertEquals(5, delta.toVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Identical snapshots
    // -----------------------------------------------------------------------

    @Nested
    class IdenticalSnapshots {

        @Test
        void identicalSnapshotsProduceEmptyDelta() {
            ConfigSnapshot snap = buildSnapshot(2, 2000, "a", "1", "b", "2");

            ConfigDelta delta = DeltaComputer.compute(snap, snap);

            assertTrue(delta.isEmpty());
            assertEquals(0, delta.size());
        }

        @Test
        void bothEmptyProducesEmptyDelta() {
            ConfigDelta delta = DeltaComputer.compute(ConfigSnapshot.EMPTY, ConfigSnapshot.EMPTY);

            assertTrue(delta.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Single-mutation scenarios
    // -----------------------------------------------------------------------

    @Nested
    class SingleMutation {

        @Test
        void newKeyInTargetIsPut() {
            ConfigSnapshot from = buildSnapshot(1, 1000, "a", "1");
            ConfigSnapshot to = buildSnapshot(2, 2000, "a", "1", "b", "2");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(1, delta.size());
            ConfigMutation.Put put = assertInstanceOf(ConfigMutation.Put.class,
                    delta.mutations().getFirst());
            assertEquals("b", put.key());
            assertArrayEquals(bytes("2"), put.value());
        }

        @Test
        void removedKeyInTargetIsDelete() {
            ConfigSnapshot from = buildSnapshot(1, 1000, "a", "1", "b", "2");
            ConfigSnapshot to = buildSnapshot(2, 2000, "b", "2");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(1, delta.size());
            ConfigMutation.Delete del = assertInstanceOf(ConfigMutation.Delete.class,
                    delta.mutations().getFirst());
            assertEquals("a", del.key());
        }

        @Test
        void changedValueIsPutWithNewValue() {
            ConfigSnapshot from = buildSnapshot(1, 1000, "a", "old");
            ConfigSnapshot to = buildSnapshot(2, 2000, "a", "new");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(1, delta.size());
            ConfigMutation.Put put = assertInstanceOf(ConfigMutation.Put.class,
                    delta.mutations().getFirst());
            assertEquals("a", put.key());
            assertArrayEquals(bytes("new"), put.value());
        }

        @Test
        void unchangedKeyIsNotInMutations() {
            ConfigSnapshot from = buildSnapshot(1, 1000, "a", "1", "b", "2");
            ConfigSnapshot to = buildSnapshot(2, 2000, "a", "1", "b", "updated");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(1, delta.size());
            // Only "b" should be present; "a" is unchanged
            assertEquals("b", delta.mutations().getFirst().key());
        }
    }

    // -----------------------------------------------------------------------
    // Multiple changes at once
    // -----------------------------------------------------------------------

    @Nested
    class MultipleChanges {

        @Test
        void addsRemovesAndUpdatesInOneDelta() {
            ConfigSnapshot from = buildSnapshot(1, 1000,
                    "keep", "same",
                    "update", "old",
                    "remove", "gone");
            ConfigSnapshot to = buildSnapshot(5, 5000,
                    "keep", "same",
                    "update", "new",
                    "add", "fresh");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            // update(update), add(add), delete(remove) = 3 mutations
            assertEquals(3, delta.size());

            Map<String, ConfigMutation> indexed = indexByKey(delta);

            // "keep" should NOT be in mutations
            assertNull(indexed.get("keep"));

            // "update" should be a Put with new value
            ConfigMutation.Put putUpdate = assertInstanceOf(ConfigMutation.Put.class,
                    indexed.get("update"));
            assertArrayEquals(bytes("new"), putUpdate.value());

            // "add" should be a Put
            ConfigMutation.Put putAdd = assertInstanceOf(ConfigMutation.Put.class,
                    indexed.get("add"));
            assertArrayEquals(bytes("fresh"), putAdd.value());

            // "remove" should be a Delete
            assertInstanceOf(ConfigMutation.Delete.class, indexed.get("remove"));
        }

        @Test
        void manyKeysAddedAtOnce() {
            ConfigSnapshot from = ConfigSnapshot.EMPTY;
            HamtMap<String, VersionedValue> data = HamtMap.empty();
            for (int i = 0; i < 50; i++) {
                data = data.put("key-" + i,
                        new VersionedValue(bytes("val-" + i), 1, 1000));
            }
            ConfigSnapshot to = new ConfigSnapshot(data, 1, 1000);

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(50, delta.size());
            for (ConfigMutation m : delta.mutations()) {
                assertInstanceOf(ConfigMutation.Put.class, m);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Version numbers in delta
    // -----------------------------------------------------------------------

    @Nested
    class VersionNumbers {

        @Test
        void deltaVersionsMatchSourceAndTargetVersions() {
            ConfigSnapshot from = buildSnapshot(3, 3000, "a", "1");
            ConfigSnapshot to = buildSnapshot(7, 7000, "a", "1", "b", "2");

            ConfigDelta delta = DeltaComputer.compute(from, to);

            assertEquals(3, delta.fromVersion());
            assertEquals(7, delta.toVersion());
        }

        @Test
        void emptySourceDeltaStartsAtVersionZero() {
            ConfigSnapshot to = buildSnapshot(10, 10000, "x", "y");

            ConfigDelta delta = DeltaComputer.compute(ConfigSnapshot.EMPTY, to);

            assertEquals(0, delta.fromVersion());
            assertEquals(10, delta.toVersion());
        }

        @Test
        void identicalSnapshotVersionsArePreserved() {
            ConfigSnapshot snap = buildSnapshot(4, 4000, "a", "1");

            ConfigDelta delta = DeltaComputer.compute(snap, snap);

            assertEquals(4, delta.fromVersion());
            assertEquals(4, delta.toVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Target null check
    // -----------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void nullTargetThrowsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> DeltaComputer.compute(ConfigSnapshot.EMPTY, null));
        }
    }
}

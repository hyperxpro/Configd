package io.configd.store;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for {@link VersionedConfigStore}. Verifies MVCC
 * invariants: version monotonicity, snapshot isolation, and read-your-writes.
 */
class VersionedConfigStorePropertyTest {

    @Property(tries = 200)
    void versionAlwaysIncreases(
            @ForAll @IntRange(min = 1, max = 50) int numWrites) {
        VersionedConfigStore store = new VersionedConfigStore();
        long prevVersion = store.currentVersion();
        for (int i = 1; i <= numWrites; i++) {
            store.put("key-" + i, ("val-" + i).getBytes(StandardCharsets.UTF_8), i);
            long newVersion = store.currentVersion();
            assertTrue(newVersion > prevVersion,
                    "Version did not increase: " + prevVersion + " -> " + newVersion);
            prevVersion = newVersion;
        }
    }

    @Property(tries = 200)
    void snapshotIsolation(@ForAll @IntRange(min = 1, max = 20) int numWrites) {
        VersionedConfigStore store = new VersionedConfigStore();
        for (int i = 1; i <= numWrites; i++) {
            store.put("key", ("val-" + i).getBytes(StandardCharsets.UTF_8), i);
        }
        // Take a snapshot
        ConfigSnapshot snap = store.snapshot();
        long snapVersion = snap.version();

        // Write more
        store.put("key", "after-snapshot".getBytes(StandardCharsets.UTF_8), numWrites + 1);

        // Snapshot must be unchanged
        assertEquals(snapVersion, snap.version());
        VersionedValue vv = snap.data().get("key");
        assertNotNull(vv);
        assertNotEquals("after-snapshot", new String(vv.valueUnsafe(), StandardCharsets.UTF_8));
    }

    @Property(tries = 200)
    void readYourWritesWithinSameStore(
            @ForAll @StringLength(min = 1, max = 30) String key,
            @ForAll @StringLength(min = 1, max = 100) String value) {
        VersionedConfigStore store = new VersionedConfigStore();
        byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
        store.put(key, valBytes, 1);
        ReadResult result = store.get(key);
        assertTrue(result.found());
        assertArrayEquals(valBytes, result.value());
    }

    @Property(tries = 200)
    void deleteRemovesKey(
            @ForAll @StringLength(min = 1, max = 30) String key) {
        VersionedConfigStore store = new VersionedConfigStore();
        store.put(key, "v".getBytes(StandardCharsets.UTF_8), 1);
        assertTrue(store.get(key).found());
        store.delete(key, 2);
        assertFalse(store.get(key).found());
    }

    @Property(tries = 100)
    void batchAtomicity(
            @ForAll List<@StringLength(min = 1, max = 20) String> keys) {
        // Filter out blank keys since ConfigMutation.Put rejects them
        List<String> validKeys = keys.stream()
                .filter(k -> !k.isBlank())
                .toList();
        Assume.that(!validKeys.isEmpty() && validKeys.size() <= 20);
        VersionedConfigStore store = new VersionedConfigStore();
        List<ConfigMutation> mutations = new ArrayList<>();
        for (String key : validKeys) {
            mutations.add(new ConfigMutation.Put(key, "v".getBytes(StandardCharsets.UTF_8)));
        }
        store.applyBatch(mutations, 1);

        // All keys must be visible at the same version
        long version = store.currentVersion();
        for (String key : validKeys) {
            ReadResult result = store.get(key);
            assertTrue(result.found(), "Key not found after batch: " + key);
            assertEquals(version, result.version());
        }
    }
}

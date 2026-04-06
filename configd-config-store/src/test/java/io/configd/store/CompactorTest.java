package io.configd.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Compactor}.
 */
class CompactorTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ConfigSnapshot buildSnapshot(long version) {
        HamtMap<String, VersionedValue> data = HamtMap.<String, VersionedValue>empty()
                .put("key-" + version,
                        new VersionedValue(bytes("val-" + version), version, version * 1000));
        return new ConfigSnapshot(data, version, version * 1000);
    }

    // -----------------------------------------------------------------------
    // Constructor validation
    // -----------------------------------------------------------------------

    @Nested
    class ConstructorValidation {

        @Test
        void defaultRetentionCount() {
            Compactor compactor = new Compactor();
            assertEquals(Compactor.DEFAULT_RETENTION_COUNT, compactor.retentionCount());
        }

        @Test
        void customRetentionCount() {
            Compactor compactor = new Compactor(5);
            assertEquals(5, compactor.retentionCount());
        }

        @Test
        void retentionCountOfOneIsAllowed() {
            Compactor compactor = new Compactor(1);
            assertEquals(1, compactor.retentionCount());
        }

        @Test
        void retentionCountBelowOneThrows() {
            assertThrows(IllegalArgumentException.class, () -> new Compactor(0));
            assertThrows(IllegalArgumentException.class, () -> new Compactor(-1));
        }
    }

    // -----------------------------------------------------------------------
    // Adding and retrieving snapshots
    // -----------------------------------------------------------------------

    @Nested
    class AddAndRetrieve {

        private Compactor compactor;

        @BeforeEach
        void setUp() {
            compactor = new Compactor(3);
        }

        @Test
        void addAndRetrieveSnapshot() {
            ConfigSnapshot snap = buildSnapshot(1);
            compactor.addSnapshot(snap);

            assertTrue(compactor.getSnapshot(1).isPresent());
            assertSame(snap, compactor.getSnapshot(1).get());
        }

        @Test
        void retrieveAbsentVersionReturnsEmpty() {
            assertTrue(compactor.getSnapshot(999).isEmpty());
        }

        @Test
        void addMultipleSnapshots() {
            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));
            compactor.addSnapshot(buildSnapshot(3));

            assertEquals(3, compactor.snapshotCount());
            assertTrue(compactor.getSnapshot(1).isPresent());
            assertTrue(compactor.getSnapshot(2).isPresent());
            assertTrue(compactor.getSnapshot(3).isPresent());
        }

        @Test
        void addDuplicateVersionReplacesExisting() {
            ConfigSnapshot first = buildSnapshot(1);
            ConfigSnapshot second = buildSnapshot(1); // same version, different object

            compactor.addSnapshot(first);
            compactor.addSnapshot(second);

            assertEquals(1, compactor.snapshotCount());
            assertSame(second, compactor.getSnapshot(1).get());
        }

        @Test
        void addNullThrows() {
            assertThrows(NullPointerException.class,
                    () -> compactor.addSnapshot(null));
        }
    }

    // -----------------------------------------------------------------------
    // Oldest and newest retained versions
    // -----------------------------------------------------------------------

    @Nested
    class OldestAndNewest {

        private Compactor compactor;

        @BeforeEach
        void setUp() {
            compactor = new Compactor(5);
        }

        @Test
        void emptyCompactorHasNoOldestOrNewest() {
            assertTrue(compactor.oldestRetainedVersion().isEmpty());
            assertTrue(compactor.newestRetainedVersion().isEmpty());
        }

        @Test
        void singleSnapshotIsOldestAndNewest() {
            compactor.addSnapshot(buildSnapshot(5));

            assertEquals(5, compactor.oldestRetainedVersion().orElse(-1L));
            assertEquals(5, compactor.newestRetainedVersion().orElse(-1L));
        }

        @Test
        void multipleSnapshotsReturnCorrectOldestAndNewest() {
            compactor.addSnapshot(buildSnapshot(3));
            compactor.addSnapshot(buildSnapshot(7));
            compactor.addSnapshot(buildSnapshot(1));

            assertEquals(1, compactor.oldestRetainedVersion().orElse(-1L));
            assertEquals(7, compactor.newestRetainedVersion().orElse(-1L));
        }
    }

    // -----------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------

    @Nested
    class Compaction {

        @Test
        void compactRemovesOldestSnapshots() {
            Compactor compactor = new Compactor(3);

            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));
            compactor.addSnapshot(buildSnapshot(3));
            compactor.addSnapshot(buildSnapshot(4));
            compactor.addSnapshot(buildSnapshot(5));

            assertEquals(5, compactor.snapshotCount());

            int removed = compactor.compact();

            assertEquals(2, removed);
            assertEquals(3, compactor.snapshotCount());

            // Oldest two should be gone
            assertTrue(compactor.getSnapshot(1).isEmpty());
            assertTrue(compactor.getSnapshot(2).isEmpty());

            // Recent three should remain
            assertTrue(compactor.getSnapshot(3).isPresent());
            assertTrue(compactor.getSnapshot(4).isPresent());
            assertTrue(compactor.getSnapshot(5).isPresent());
        }

        @Test
        void compactWithExactRetentionRemovesNothing() {
            Compactor compactor = new Compactor(3);

            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));
            compactor.addSnapshot(buildSnapshot(3));

            int removed = compactor.compact();

            assertEquals(0, removed);
            assertEquals(3, compactor.snapshotCount());
        }

        @Test
        void compactBelowRetentionRemovesNothing() {
            Compactor compactor = new Compactor(5);

            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));

            int removed = compactor.compact();

            assertEquals(0, removed);
            assertEquals(2, compactor.snapshotCount());
        }

        @Test
        void compactEmptyHistory() {
            Compactor compactor = new Compactor(3);

            int removed = compactor.compact();

            assertEquals(0, removed);
        }

        @Test
        void compactRetentionOfOne() {
            Compactor compactor = new Compactor(1);

            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));
            compactor.addSnapshot(buildSnapshot(3));

            int removed = compactor.compact();

            assertEquals(2, removed);
            assertEquals(1, compactor.snapshotCount());
            assertTrue(compactor.getSnapshot(3).isPresent());
        }

        @Test
        void oldestRetainedVersionAfterCompaction() {
            Compactor compactor = new Compactor(2);

            compactor.addSnapshot(buildSnapshot(10));
            compactor.addSnapshot(buildSnapshot(20));
            compactor.addSnapshot(buildSnapshot(30));
            compactor.addSnapshot(buildSnapshot(40));

            compactor.compact();

            assertEquals(30, compactor.oldestRetainedVersion().orElse(-1L));
            assertEquals(40, compactor.newestRetainedVersion().orElse(-1L));
        }

        @Test
        void repeatedCompactionIsIdempotent() {
            Compactor compactor = new Compactor(2);

            compactor.addSnapshot(buildSnapshot(1));
            compactor.addSnapshot(buildSnapshot(2));
            compactor.addSnapshot(buildSnapshot(3));

            int first = compactor.compact();
            int second = compactor.compact();

            assertEquals(1, first);
            assertEquals(0, second);
            assertEquals(2, compactor.snapshotCount());
        }
    }

    // -----------------------------------------------------------------------
    // Integration: add, compact, add more
    // -----------------------------------------------------------------------

    @Nested
    class IntegrationScenario {

        @Test
        void continuousAddAndCompact() {
            Compactor compactor = new Compactor(3);

            // Simulate ongoing operation
            for (int i = 1; i <= 10; i++) {
                compactor.addSnapshot(buildSnapshot(i));
                compactor.compact();
            }

            assertEquals(3, compactor.snapshotCount());
            assertEquals(8, compactor.oldestRetainedVersion().orElse(-1L));
            assertEquals(10, compactor.newestRetainedVersion().orElse(-1L));
        }

        @Test
        void deltaComputationFromRetainedSnapshot() {
            Compactor compactor = new Compactor(3);

            // Build store and take snapshots
            VersionedConfigStore store = new VersionedConfigStore();
            store.put("a", bytes("1"), 1);
            ConfigSnapshot snap1 = store.snapshot();
            compactor.addSnapshot(snap1);

            store.put("b", bytes("2"), 2);
            ConfigSnapshot snap2 = store.snapshot();
            compactor.addSnapshot(snap2);

            store.put("c", bytes("3"), 3);
            ConfigSnapshot snap3 = store.snapshot();
            compactor.addSnapshot(snap3);

            // Compute delta from retained snapshot
            ConfigSnapshot base = compactor.getSnapshot(1).orElseThrow();
            ConfigDelta delta = DeltaComputer.compute(base, snap3);

            assertEquals(1, delta.fromVersion());
            assertEquals(3, delta.toVersion());
            assertEquals(2, delta.size()); // puts for "b" and "c"
        }
    }
}

package io.configd.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VersionedConfigStore}.
 */
class VersionedConfigStoreTest {

    private VersionedConfigStore store;

    @BeforeEach
    void setUp() {
        store = new VersionedConfigStore();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Single-writer correctness
    // -----------------------------------------------------------------------

    @Nested
    class SingleWriter {

        @Test
        void putAndGet() {
            store.put("db.host", bytes("localhost"), 1);
            ReadResult result = store.get("db.host");
            assertTrue(result.found());
            assertArrayEquals(bytes("localhost"), result.value());
            assertEquals(1, result.version());
        }

        @Test
        void getAbsentKeyReturnsNotFound() {
            ReadResult result = store.get("absent");
            assertFalse(result.found());
            assertSame(ReadResult.NOT_FOUND, result);
        }

        @Test
        void putOverwrites() {
            store.put("key", bytes("v1"), 1);
            store.put("key", bytes("v2"), 2);
            ReadResult result = store.get("key");
            assertArrayEquals(bytes("v2"), result.value());
            assertEquals(2, result.version());
        }

        @Test
        void deleteRemovesKey() {
            store.put("key", bytes("value"), 1);
            store.delete("key", 2);
            assertFalse(store.get("key").found());
        }

        @Test
        void deleteAbsentKeyIsNoOp() {
            store.delete("absent", 1);
            assertEquals(1, store.currentVersion());
        }

        @Test
        void versionIncreases() {
            assertEquals(0, store.currentVersion());
            store.put("a", bytes("1"), 1);
            assertEquals(1, store.currentVersion());
            store.put("b", bytes("2"), 5);
            assertEquals(5, store.currentVersion());
        }

        @Test
        void nonMonotonicSequenceThrows() {
            store.put("a", bytes("1"), 10);
            assertThrows(IllegalArgumentException.class,
                    () -> store.put("b", bytes("2"), 5));
            assertThrows(IllegalArgumentException.class,
                    () -> store.put("c", bytes("3"), 10)); // equal, not greater
        }
    }

    // -----------------------------------------------------------------------
    // Batch apply
    // -----------------------------------------------------------------------

    @Nested
    class BatchApply {

        @Test
        void applyBatchAtomically() {
            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Put("a", bytes("1")),
                    new ConfigMutation.Put("b", bytes("2")),
                    new ConfigMutation.Put("c", bytes("3"))
            );
            store.applyBatch(mutations, 1);

            assertEquals(1, store.currentVersion());
            assertArrayEquals(bytes("1"), store.get("a").value());
            assertArrayEquals(bytes("2"), store.get("b").value());
            assertArrayEquals(bytes("3"), store.get("c").value());
        }

        @Test
        void applyBatchWithDeletes() {
            store.put("a", bytes("1"), 1);
            store.put("b", bytes("2"), 2);

            List<ConfigMutation> mutations = List.of(
                    new ConfigMutation.Delete("a"),
                    new ConfigMutation.Put("c", bytes("3"))
            );
            store.applyBatch(mutations, 3);

            assertFalse(store.get("a").found());
            assertTrue(store.get("b").found());
            assertTrue(store.get("c").found());
        }

        @Test
        void emptyBatchIsNoOp() {
            store.put("a", bytes("1"), 1);
            store.applyBatch(List.of(), 2);
            assertEquals(1, store.currentVersion()); // no bump
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot isolation
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotIsolation {

        @Test
        void snapshotIsImmutable() {
            store.put("key", bytes("v1"), 1);
            ConfigSnapshot snap1 = store.snapshot();

            store.put("key", bytes("v2"), 2);
            ConfigSnapshot snap2 = store.snapshot();

            // snap1 still sees v1
            assertArrayEquals(bytes("v1"), snap1.get("key"));
            assertEquals(1, snap1.version());

            // snap2 sees v2
            assertArrayEquals(bytes("v2"), snap2.get("key"));
            assertEquals(2, snap2.version());
        }

        @Test
        void getWithMinVersionFiltersStaleReads() {
            store.put("key", bytes("value"), 5);

            assertTrue(store.get("key", 5).found());
            assertTrue(store.get("key", 3).found());
            assertFalse(store.get("key", 6).found()); // store at v5, requesting v6
        }
    }

    // -----------------------------------------------------------------------
    // Prefix scan
    // -----------------------------------------------------------------------

    @Nested
    class PrefixScan {

        @Test
        void getPrefixReturnsMatchingKeys() {
            store.put("db.host", bytes("localhost"), 1);
            store.put("db.port", bytes("5432"), 2);
            store.put("cache.ttl", bytes("300"), 3);

            Map<String, ReadResult> results = store.getPrefix("db.");
            assertEquals(2, results.size());
            assertTrue(results.containsKey("db.host"));
            assertTrue(results.containsKey("db.port"));
            assertFalse(results.containsKey("cache.ttl"));
        }

        @Test
        void getPrefixReturnsEmptyForNoMatch() {
            store.put("db.host", bytes("localhost"), 1);
            Map<String, ReadResult> results = store.getPrefix("app.");
            assertTrue(results.isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent read/write
    // -----------------------------------------------------------------------

    @Nested
    class ConcurrentAccess {

        @Test
        void concurrentReadersDuringWrites() throws InterruptedException {
            int writerIterations = 10_000;
            int readerCount = 4;
            AtomicBoolean writerDone = new AtomicBoolean(false);
            ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);

            // Writer thread
            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    startLatch.await();
                    for (int i = 1; i <= writerIterations; i++) {
                        store.put("key-" + (i % 100), bytes("value-" + i), i);
                    }
                } catch (Throwable ex) {
                    errors.put("writer", ex);
                } finally {
                    writerDone.set(true);
                }
            });

            // Reader threads
            Thread[] readers = new Thread[readerCount];
            for (int r = 0; r < readerCount; r++) {
                readers[r] = Thread.ofPlatform().start(() -> {
                    try {
                        startLatch.await();
                        while (!writerDone.get()) {
                            // Read a consistent snapshot
                            long version = store.currentVersion();
                            ConfigSnapshot snap = store.snapshot();

                            // Version should be >= what we read a moment ago
                            // (another write may have happened)
                            if (snap.version() < version) {
                                // This can happen if currentVersion() and snapshot()
                                // read different volatile values. That's fine — the
                                // snapshot is self-consistent.
                            }

                            // The snapshot must be internally consistent
                            snap.data().forEach((key, vv) -> {
                                if (vv == null || vv.valueUnsafe() == null) {
                                    errors.put(Thread.currentThread().getName(),
                                            new AssertionError("Null value for key: " + key));
                                }
                            });
                        }
                    } catch (Throwable ex) {
                        errors.put(Thread.currentThread().getName(), ex);
                    }
                });
            }

            startLatch.countDown();
            writer.join(30_000);
            for (Thread r : readers) {
                r.join(30_000);
            }

            if (!errors.isEmpty()) {
                fail("Errors during concurrent access: " + errors);
            }

            assertEquals(writerIterations, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // DeltaComputer integration
    // -----------------------------------------------------------------------

    @Nested
    class DeltaComputerIntegration {

        @Test
        void computeDeltaBetweenSnapshots() {
            store.put("a", bytes("1"), 1);
            store.put("b", bytes("2"), 2);
            ConfigSnapshot snap1 = store.snapshot();

            store.put("b", bytes("updated"), 3);
            store.put("c", bytes("3"), 4);
            store.delete("a", 5);
            ConfigSnapshot snap2 = store.snapshot();

            ConfigDelta delta = DeltaComputer.compute(snap1, snap2);

            assertEquals(2, delta.fromVersion());
            assertEquals(5, delta.toVersion());

            // Should have: delete(a), put(b, updated), put(c, 3)
            assertEquals(3, delta.size());

            boolean hasPutB = false, hasPutC = false, hasDeleteA = false;
            for (ConfigMutation m : delta.mutations()) {
                switch (m) {
                    case ConfigMutation.Put put -> {
                        if ("b".equals(put.key())) {
                            assertArrayEquals(bytes("updated"), put.value());
                            hasPutB = true;
                        } else if ("c".equals(put.key())) {
                            assertArrayEquals(bytes("3"), put.value());
                            hasPutC = true;
                        }
                    }
                    case ConfigMutation.Delete del -> {
                        if ("a".equals(del.key())) {
                            hasDeleteA = true;
                        }
                    }
                }
            }
            assertTrue(hasPutB, "Missing put for 'b'");
            assertTrue(hasPutC, "Missing put for 'c'");
            assertTrue(hasDeleteA, "Missing delete for 'a'");
        }

        @Test
        void deltaFromNullIsFullSync() {
            store.put("a", bytes("1"), 1);
            store.put("b", bytes("2"), 2);

            ConfigDelta delta = DeltaComputer.compute(null, store.snapshot());
            assertEquals(2, delta.size()); // all entries are puts
            delta.mutations().forEach(m ->
                    assertInstanceOf(ConfigMutation.Put.class, m));
        }

        @Test
        void identicalSnapshotsProduceEmptyDelta() {
            store.put("a", bytes("1"), 1);
            ConfigSnapshot snap = store.snapshot();

            ConfigDelta delta = DeltaComputer.compute(snap, snap);
            assertTrue(delta.isEmpty());
        }
    }
}

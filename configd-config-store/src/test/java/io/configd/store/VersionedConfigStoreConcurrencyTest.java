package io.configd.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency tests for {@link VersionedConfigStore}.
 * <p>
 * Validates that the volatile snapshot pointer (RCU pattern) is safe for
 * concurrent readers while a single writer is mutating, and that snapshots
 * provide point-in-time isolation.
 */
class VersionedConfigStoreConcurrencyTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // RCU concurrent readers + single writer
    // -----------------------------------------------------------------------

    @Test
    void concurrentReadersObserveConsistentSnapshotsWhileWriterMutates() throws InterruptedException {
        VersionedConfigStore store = new VersionedConfigStore();

        int writerIterations = 10_000;
        int readerCount = 4;
        AtomicBoolean writerDone = new AtomicBoolean(false);
        ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1 + readerCount);

        // Writer thread: puts key "k" with value "v-{version}" for versions 1..10000
        Thread writer = Thread.ofPlatform().name("writer").start(() -> {
            try {
                startLatch.countDown();
                startLatch.await();
                for (int i = 1; i <= writerIterations; i++) {
                    store.put("k", bytes("v-" + i), i);
                }
            } catch (Throwable ex) {
                errors.put("writer", ex);
            } finally {
                writerDone.set(true);
            }
        });

        // Reader threads: continuously read "k" and verify consistency
        Thread[] readers = new Thread[readerCount];
        for (int r = 0; r < readerCount; r++) {
            final int readerId = r;
            readers[r] = Thread.ofPlatform().name("reader-" + r).start(() -> {
                try {
                    startLatch.countDown();
                    startLatch.await();

                    long lastSeenVersion = -1;

                    while (!writerDone.get()) {
                        ReadResult result = store.get("k");

                        if (!result.found()) {
                            // Not-found is valid only before the first write
                            if (result != ReadResult.NOT_FOUND) {
                                errors.put("reader-" + readerId,
                                        new AssertionError("Non-singleton NOT_FOUND result"));
                                return;
                            }
                            continue;
                        }

                        // Validate the value matches the expected pattern
                        long version = result.version();
                        if (version < 1 || version > writerIterations) {
                            errors.put("reader-" + readerId,
                                    new AssertionError("Version out of range: " + version));
                            return;
                        }

                        String value = string(result.value());
                        String expectedValue = "v-" + version;
                        if (!expectedValue.equals(value)) {
                            errors.put("reader-" + readerId,
                                    new AssertionError(
                                            "Torn read: version=" + version
                                                    + " but value=\"" + value
                                                    + "\" (expected \"" + expectedValue + "\")"));
                            return;
                        }

                        // Versions seen by this reader must be monotonically non-decreasing
                        if (version < lastSeenVersion) {
                            errors.put("reader-" + readerId,
                                    new AssertionError(
                                            "Version went backwards: saw " + version
                                                    + " after " + lastSeenVersion));
                            return;
                        }
                        lastSeenVersion = version;
                    }

                    // After writer finishes, do one final read to confirm final state
                    ReadResult finalResult = store.get("k");
                    if (!finalResult.found()) {
                        errors.put("reader-" + readerId,
                                new AssertionError("Key 'k' not found after writer completed"));
                    }
                } catch (Throwable ex) {
                    errors.put("reader-" + readerId, ex);
                }
            });
        }

        writer.join(30_000);
        for (Thread reader : readers) {
            reader.join(30_000);
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Concurrent access errors:\n");
            errors.forEach((thread, ex) ->
                    sb.append("  [").append(thread).append("] ").append(ex.getMessage()).append('\n'));
            fail(sb.toString());
        }

        assertEquals(writerIterations, store.currentVersion());
        ReadResult finalResult = store.get("k");
        assertTrue(finalResult.found());
        assertEquals("v-" + writerIterations, string(finalResult.value()));
        assertEquals(writerIterations, finalResult.version());
    }

    // -----------------------------------------------------------------------
    // Snapshot isolation: snapshots are frozen in time
    // -----------------------------------------------------------------------

    @Test
    void snapshotIsolationAcrossBulkWrites() {
        VersionedConfigStore store = new VersionedConfigStore();

        // Take snapshot A before any writes
        ConfigSnapshot snapshotA = store.snapshot();

        // Write 1000 keys
        int keyCount = 1000;
        for (int i = 1; i <= keyCount; i++) {
            store.put("key-" + i, bytes("value-" + i), i);
        }

        // Take snapshot B after all writes
        ConfigSnapshot snapshotB = store.snapshot();

        // Snapshot A must not contain any of the new keys
        assertEquals(0, snapshotA.size(), "Snapshot A should be empty");
        assertEquals(0, snapshotA.version(), "Snapshot A version should be 0");
        for (int i = 1; i <= keyCount; i++) {
            assertFalse(snapshotA.containsKey("key-" + i),
                    "Snapshot A should not contain key-" + i);
            assertNull(snapshotA.get("key-" + i),
                    "Snapshot A get(key-" + i + ") should return null");
        }

        // Snapshot B must contain all 1000 keys with correct values
        assertEquals(keyCount, snapshotB.size(),
                "Snapshot B should contain all " + keyCount + " keys");
        assertEquals(keyCount, snapshotB.version(),
                "Snapshot B version should be " + keyCount);
        for (int i = 1; i <= keyCount; i++) {
            assertTrue(snapshotB.containsKey("key-" + i),
                    "Snapshot B should contain key-" + i);
            byte[] val = snapshotB.get("key-" + i);
            assertNotNull(val, "Snapshot B value for key-" + i + " should not be null");
            assertEquals("value-" + i, string(val),
                    "Snapshot B value mismatch for key-" + i);
        }

        // Further writes after snapshot B should not affect either snapshot
        store.put("late-key", bytes("late-value"), keyCount + 1);

        assertFalse(snapshotA.containsKey("late-key"),
                "Snapshot A should not see late-key");
        assertFalse(snapshotB.containsKey("late-key"),
                "Snapshot B should not see late-key");
    }
}

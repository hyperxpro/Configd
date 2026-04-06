package io.configd.distribution;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FanOutBufferTest {

    private static final List<ConfigMutation> MUTATIONS = List.of(
            new ConfigMutation.Put("key", "val".getBytes()));

    /** Helper — creates a delta spanning fromVersion -> toVersion. */
    private static ConfigDelta delta(long from, long to) {
        return new ConfigDelta(from, to, MUTATIONS);
    }

    @Nested
    class Construction {

        @Test
        void validCapacityCreatesEmptyBuffer() {
            FanOutBuffer buf = new FanOutBuffer(8);
            assertTrue(buf.isEmpty());
            assertEquals(0, buf.size());
        }

        @Test
        void capacityOfOneIsAllowed() {
            FanOutBuffer buf = new FanOutBuffer(1);
            assertTrue(buf.isEmpty());
        }

        @Test
        void zeroCapacityThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FanOutBuffer(0));
        }

        @Test
        void negativeCapacityThrows() {
            assertThrows(IllegalArgumentException.class, () -> new FanOutBuffer(-1));
        }
    }

    @Nested
    class EmptyBuffer {

        private FanOutBuffer buf;

        @BeforeEach
        void setUp() {
            buf = new FanOutBuffer(8);
        }

        @Test
        void latestReturnsNull() {
            assertNull(buf.latest());
        }

        @Test
        void latestVersionReturnsMinusOne() {
            assertEquals(-1, buf.latestVersion());
        }

        @Test
        void oldestVersionReturnsMinusOne() {
            assertEquals(-1, buf.oldestVersion());
        }

        @Test
        void deltasSinceReturnsEmptyList() {
            assertTrue(buf.deltasSince(0).isEmpty());
        }

        @Test
        void canReplayFromReturnsFalse() {
            assertFalse(buf.canReplayFrom(0));
        }

        @Test
        void sizeIsZero() {
            assertEquals(0, buf.size());
        }

        @Test
        void isEmptyReturnsTrue() {
            assertTrue(buf.isEmpty());
        }
    }

    @Nested
    class AppendAndRetrieve {

        private FanOutBuffer buf;

        @BeforeEach
        void setUp() {
            buf = new FanOutBuffer(8);
        }

        @Test
        void appendNullThrows() {
            assertThrows(NullPointerException.class, () -> buf.append(null));
        }

        @Test
        void singleAppendMakesBufferNonEmpty() {
            buf.append(delta(0, 1));

            assertFalse(buf.isEmpty());
            assertEquals(1, buf.size());
        }

        @Test
        void latestReturnsMostRecentEntry() {
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));
            buf.append(delta(2, 3));

            ConfigDelta latest = buf.latest();
            assertNotNull(latest);
            assertEquals(2, latest.fromVersion());
            assertEquals(3, latest.toVersion());
        }

        @Test
        void latestVersionReflectsLastAppend() {
            buf.append(delta(0, 1));
            assertEquals(1, buf.latestVersion());

            buf.append(delta(1, 5));
            assertEquals(5, buf.latestVersion());
        }

        @Test
        void oldestVersionReflectsFirstEntry() {
            buf.append(delta(0, 1));
            assertEquals(0, buf.oldestVersion());

            buf.append(delta(1, 2));
            assertEquals(0, buf.oldestVersion());
        }

        @Test
        void deltasSinceReturnsAllMatchingEntries() {
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));
            buf.append(delta(2, 3));

            List<ConfigDelta> deltas = buf.deltasSince(1);
            assertEquals(2, deltas.size());
            assertEquals(1, deltas.get(0).fromVersion());
            assertEquals(2, deltas.get(1).fromVersion());
        }

        @Test
        void deltasSinceZeroReturnsAll() {
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));
            buf.append(delta(2, 3));

            List<ConfigDelta> deltas = buf.deltasSince(0);
            assertEquals(3, deltas.size());
        }

        @Test
        void deltasSinceHighVersionReturnsEmpty() {
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));

            assertTrue(buf.deltasSince(100).isEmpty());
        }

        @Test
        void canReplayFromReturnsTrueWhenOldestIsOldEnough() {
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));

            assertTrue(buf.canReplayFrom(0));
            assertTrue(buf.canReplayFrom(1));
        }

        @Test
        void canReplayFromReturnsFalseWhenOldestIsTooNew() {
            buf.append(delta(5, 6));
            buf.append(delta(6, 7));

            assertFalse(buf.canReplayFrom(3));
        }
    }

    @Nested
    class CapacityBoundary {

        @Test
        void fillExactlyToCapacity() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            for (int i = 0; i < cap; i++) {
                buf.append(delta(i, i + 1));
            }

            assertEquals(cap, buf.size());
            assertFalse(buf.isEmpty());
            assertEquals(0, buf.oldestVersion());
            assertEquals(cap, buf.latestVersion());
        }

        @Test
        void allEntriesRetrievableAtCapacity() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            for (int i = 0; i < cap; i++) {
                buf.append(delta(i, i + 1));
            }

            List<ConfigDelta> deltas = buf.deltasSince(0);
            assertEquals(cap, deltas.size());
            for (int i = 0; i < cap; i++) {
                assertEquals(i, deltas.get(i).fromVersion());
            }
        }

        @Test
        void sizeNeverExceedsCapacity() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            for (int i = 0; i < cap * 3; i++) {
                buf.append(delta(i, i + 1));
                assertTrue(buf.size() <= cap,
                        "size should never exceed capacity, but was " + buf.size());
            }
        }

        @Test
        void capacityOneEvictsOnEveryAppend() {
            FanOutBuffer buf = new FanOutBuffer(1);

            buf.append(delta(0, 1));
            assertEquals(1, buf.size());
            assertEquals(0, buf.oldestVersion());

            buf.append(delta(1, 2));
            assertEquals(1, buf.size());
            assertEquals(1, buf.oldestVersion());
            assertEquals(2, buf.latestVersion());
        }
    }

    @Nested
    class RingBufferWraparound {

        @Test
        void oldestEvictedAfterWraparound() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            // Fill to capacity + 2 to force eviction
            for (int i = 0; i < cap + 2; i++) {
                buf.append(delta(i, i + 1));
            }

            assertEquals(cap, buf.size());
            // Oldest should now be entry at index 2 (entries 0 and 1 evicted)
            assertEquals(2, buf.oldestVersion());
            assertEquals(cap + 2, buf.latestVersion());
        }

        @Test
        void deltasSinceOnlyReturnsNonEvictedEntries() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            for (int i = 0; i < 8; i++) {
                buf.append(delta(i, i + 1));
            }

            // Entries 0-3 are evicted; entries 4-7 remain
            List<ConfigDelta> deltas = buf.deltasSince(0);
            assertEquals(4, deltas.size());
            assertEquals(4, deltas.get(0).fromVersion());
            assertEquals(7, deltas.get(3).fromVersion());
        }

        @Test
        void latestCorrectAfterMultipleWraps() {
            int cap = 3;
            FanOutBuffer buf = new FanOutBuffer(cap);

            // Wrap around multiple times
            for (int i = 0; i < 20; i++) {
                buf.append(delta(i, i + 1));
            }

            ConfigDelta latest = buf.latest();
            assertNotNull(latest);
            assertEquals(19, latest.fromVersion());
            assertEquals(20, latest.toVersion());
            assertEquals(cap, buf.size());
        }

        @Test
        void canReplayFromBecomesFalseAfterEviction() {
            int cap = 4;
            FanOutBuffer buf = new FanOutBuffer(cap);

            for (int i = 0; i < cap; i++) {
                buf.append(delta(i, i + 1));
            }
            assertTrue(buf.canReplayFrom(0));

            // One more append evicts entry 0
            buf.append(delta(cap, cap + 1));
            assertFalse(buf.canReplayFrom(0));
            assertTrue(buf.canReplayFrom(1));
        }
    }

    @Nested
    class DeltasSinceFiltering {

        private FanOutBuffer buf;

        @BeforeEach
        void setUp() {
            buf = new FanOutBuffer(16);
            // Append deltas: 0->1, 1->2, 2->3, 5->6, 10->11
            buf.append(delta(0, 1));
            buf.append(delta(1, 2));
            buf.append(delta(2, 3));
            buf.append(delta(5, 6));
            buf.append(delta(10, 11));
        }

        @Test
        void filtersByFromVersion() {
            List<ConfigDelta> deltas = buf.deltasSince(5);
            assertEquals(2, deltas.size());
            assertEquals(5, deltas.get(0).fromVersion());
            assertEquals(10, deltas.get(1).fromVersion());
        }

        @Test
        void exactMatchIncludes() {
            List<ConfigDelta> deltas = buf.deltasSince(2);
            assertEquals(3, deltas.size());
            assertEquals(2, deltas.get(0).fromVersion());
        }

        @Test
        void beyondAllVersionsReturnsEmpty() {
            assertTrue(buf.deltasSince(100).isEmpty());
        }

        @Test
        void zeroReturnsAll() {
            assertEquals(5, buf.deltasSince(0).size());
        }
    }

    @Nested
    class ConcurrentReadDuringWrite {

        @Test
        void readersDoNotSeeCorruptDataDuringContinuousWrites() throws InterruptedException {
            int capacity = 64;
            int totalWrites = 10_000;
            int readerCount = 4;
            FanOutBuffer buf = new FanOutBuffer(capacity);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writeDone = new CountDownLatch(1);
            AtomicBoolean writerFailed = new AtomicBoolean(false);
            CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

            // Writer thread
            Thread writer = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < totalWrites; i++) {
                        buf.append(delta(i, i + 1));
                    }
                } catch (Throwable t) {
                    writerFailed.set(true);
                    errors.add(t);
                } finally {
                    writeDone.countDown();
                }
            }, "writer");

            // Reader threads calling deltasSince
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < readerCount; r++) {
                String name = "reader-deltasSince-" + r;
                Thread reader = new Thread(() -> {
                    try {
                        startLatch.await();
                        while (!writeDone.await(0, TimeUnit.MILLISECONDS)) {
                            List<ConfigDelta> deltas = buf.deltasSince(0);
                            // Verify each delta is structurally valid — no corrupt
                            // data, no null fields, no impossible version pairs.
                            // Note: strict ordering across the full list is NOT
                            // guaranteed under concurrent writes because a reader
                            // can observe a slot that was overwritten mid-iteration
                            // (inherent to lock-free ring buffer design).
                            for (ConfigDelta d : deltas) {
                                if (d.toVersion() < d.fromVersion()) {
                                    throw new AssertionError(
                                            "invalid delta: toVersion < fromVersion");
                                }
                                assertNotNull(d.mutations());
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }, name);
                readers.add(reader);
            }

            // Reader threads calling latest
            for (int r = 0; r < readerCount; r++) {
                String name = "reader-latest-" + r;
                Thread reader = new Thread(() -> {
                    try {
                        startLatch.await();
                        while (!writeDone.await(0, TimeUnit.MILLISECONDS)) {
                            ConfigDelta latest = buf.latest();
                            if (latest != null) {
                                assertTrue(latest.toVersion() > latest.fromVersion()
                                                || latest.toVersion() == latest.fromVersion(),
                                        "latest() returned invalid delta");
                                assertNotNull(latest.mutations());
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }, name);
                readers.add(reader);
            }

            writer.start();
            readers.forEach(Thread::start);
            startLatch.countDown();

            assertTrue(writeDone.await(10, TimeUnit.SECONDS), "writer did not finish in time");
            for (Thread reader : readers) {
                reader.join(5000);
            }

            assertTrue(errors.isEmpty(),
                    "concurrent errors: " + errors);
            assertFalse(writerFailed.get(), "writer thread failed");

            // Final consistency checks
            assertEquals(totalWrites - 1, buf.latest().fromVersion());
            assertEquals(totalWrites, buf.latest().toVersion());
            assertEquals(capacity, buf.size());
        }

        @Test
        void concurrentDeltasSinceWithVaryingVersions() throws InterruptedException {
            int capacity = 32;
            int totalWrites = 5_000;
            FanOutBuffer buf = new FanOutBuffer(capacity);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch writeDone = new CountDownLatch(1);
            CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

            Thread writer = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < totalWrites; i++) {
                        buf.append(delta(i, i + 1));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    writeDone.countDown();
                }
            }, "writer");

            // Readers that request deltas since a sliding version window
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                Thread reader = new Thread(() -> {
                    try {
                        startLatch.await();
                        long queryVersion = 0;
                        while (!writeDone.await(0, TimeUnit.MILLISECONDS)) {
                            List<ConfigDelta> deltas = buf.deltasSince(queryVersion);
                            // All returned deltas should have fromVersion >= queryVersion
                            for (ConfigDelta d : deltas) {
                                if (d.fromVersion() < queryVersion) {
                                    throw new AssertionError(
                                            "deltasSince(" + queryVersion
                                                    + ") returned delta with fromVersion="
                                                    + d.fromVersion());
                                }
                            }
                            // Advance query version
                            if (!deltas.isEmpty()) {
                                queryVersion = deltas.get(deltas.size() - 1).fromVersion();
                            }
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }, "sliding-reader-" + r);
                readers.add(reader);
            }

            writer.start();
            readers.forEach(Thread::start);
            startLatch.countDown();

            assertTrue(writeDone.await(10, TimeUnit.SECONDS));
            for (Thread reader : readers) {
                reader.join(5000);
            }

            assertTrue(errors.isEmpty(), "concurrent errors: " + errors);
        }
    }
}

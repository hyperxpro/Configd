package io.configd.edge;

import io.configd.common.Clock;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.ReadResult;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the edge cache module: {@link LocalConfigStore},
 * {@link VersionCursor}, and {@link StalenessTracker}.
 */
class LocalConfigStoreTest {

    private LocalConfigStore store;

    @BeforeEach
    void setUp() {
        store = new LocalConfigStore();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ConfigSnapshot buildSnapshot(long version, String... keyValues) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < keyValues.length; i += 2) {
            data = data.put(keyValues[i],
                    new VersionedValue(bytes(keyValues[i + 1]), version, version));
        }
        return new ConfigSnapshot(data, version, version);
    }

    // -----------------------------------------------------------------------
    // Basic read/write
    // -----------------------------------------------------------------------

    @Nested
    class BasicOperations {

        @Test
        void emptyStoreReturnsNotFound() {
            ReadResult result = store.get("any-key");
            assertFalse(result.found());
            assertSame(ReadResult.NOT_FOUND, result);
        }

        @Test
        void loadSnapshotAndRead() {
            ConfigSnapshot snap = buildSnapshot(1, "key", "value");
            store.loadSnapshot(snap);

            ReadResult result = store.get("key");
            assertTrue(result.found());
            assertArrayEquals(bytes("value"), result.value());
            assertEquals(1, result.version());
        }

        @Test
        void applyDeltaAddsPuts() {
            // Start with a snapshot
            ConfigSnapshot initial = buildSnapshot(1, "a", "1");
            store.loadSnapshot(initial);

            // Apply delta adding "b" and updating "a"
            ConfigDelta delta = new ConfigDelta(1, 2, List.of(
                    new ConfigMutation.Put("a", bytes("updated")),
                    new ConfigMutation.Put("b", bytes("2"))
            ));
            store.applyDelta(delta);

            assertEquals(2, store.currentVersion());
            assertArrayEquals(bytes("updated"), store.get("a").value());
            assertArrayEquals(bytes("2"), store.get("b").value());
        }

        @Test
        void applyDeltaWithDeletes() {
            ConfigSnapshot initial = buildSnapshot(1, "a", "1", "b", "2");
            store.loadSnapshot(initial);

            ConfigDelta delta = new ConfigDelta(1, 2, List.of(
                    new ConfigMutation.Delete("a")
            ));
            store.applyDelta(delta);

            assertFalse(store.get("a").found());
            assertTrue(store.get("b").found());
        }

        @Test
        void deltaVersionMismatchThrows() {
            ConfigSnapshot initial = buildSnapshot(5, "a", "1");
            store.loadSnapshot(initial);

            ConfigDelta delta = new ConfigDelta(3, 6, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));
            assertThrows(IllegalArgumentException.class,
                    () -> store.applyDelta(delta));
        }
    }

    // -----------------------------------------------------------------------
    // Monotonic reads via VersionCursor
    // -----------------------------------------------------------------------

    @Nested
    class MonotonicReads {

        @Test
        void readWithCursorBehindStoreSucceeds() {
            store.loadSnapshot(buildSnapshot(5, "key", "value"));
            VersionCursor cursor = new VersionCursor(3, 3);

            ReadResult result = store.get("key", cursor);
            assertTrue(result.found());
        }

        @Test
        void readWithCursorAtStoreVersionSucceeds() {
            store.loadSnapshot(buildSnapshot(5, "key", "value"));
            VersionCursor cursor = new VersionCursor(5, 5);

            ReadResult result = store.get("key", cursor);
            assertTrue(result.found());
        }

        @Test
        void readWithCursorAheadOfStoreReturnsNotFound() {
            store.loadSnapshot(buildSnapshot(5, "key", "value"));
            VersionCursor cursor = new VersionCursor(10, 10);

            ReadResult result = store.get("key", cursor);
            assertFalse(result.found());
        }

        @Test
        void initialCursorAlwaysSucceeds() {
            store.loadSnapshot(buildSnapshot(1, "key", "value"));
            ReadResult result = store.get("key", VersionCursor.INITIAL);
            assertTrue(result.found());
        }
    }

    // -----------------------------------------------------------------------
    // Staleness tracker
    // -----------------------------------------------------------------------

    @Nested
    class StalenessTracking {

        @Test
        void initialStateIsDisconnected() {
            StalenessTracker tracker = new StalenessTracker();
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());
        }

        @Test
        void afterUpdateStateIsCurrent() {
            StalenessTracker tracker = new StalenessTracker();
            tracker.recordUpdate(1, System.currentTimeMillis());
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void stateTransitionsWithControlledClock() {
            AtomicLong nanoTime = new AtomicLong(1_000_000_000L);
            Clock testClock = new Clock() {
                @Override
                public long currentTimeMillis() {
                    return nanoTime.get() / 1_000_000L;
                }

                @Override
                public long nanoTime() {
                    return nanoTime.get();
                }
            };

            StalenessTracker tracker = new StalenessTracker(testClock);
            tracker.recordUpdate(1, 1000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());

            // Advance 600ms -> STALE
            nanoTime.addAndGet(600_000_000L);
            assertEquals(StalenessTracker.State.STALE, tracker.currentState());

            // Advance to 6s total -> DEGRADED
            nanoTime.addAndGet(5_400_000_000L);
            assertEquals(StalenessTracker.State.DEGRADED, tracker.currentState());

            // Advance to 31s total -> DISCONNECTED
            nanoTime.addAndGet(25_000_000_000L);
            assertEquals(StalenessTracker.State.DISCONNECTED, tracker.currentState());

            // Record update -> back to CURRENT
            tracker.recordUpdate(2, 31000);
            assertEquals(StalenessTracker.State.CURRENT, tracker.currentState());
        }

        @Test
        void stalenessMillisecondsIsAccurate() {
            AtomicLong nanoTime = new AtomicLong(0L);
            Clock testClock = new Clock() {
                @Override
                public long currentTimeMillis() {
                    return nanoTime.get() / 1_000_000L;
                }

                @Override
                public long nanoTime() {
                    return nanoTime.get();
                }
            };

            StalenessTracker tracker = new StalenessTracker(testClock);
            tracker.recordUpdate(1, 0);

            nanoTime.set(250_000_000L); // 250ms
            assertEquals(250, tracker.stalenessMs());
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent read/write correctness
    // -----------------------------------------------------------------------

    @Nested
    class ConcurrentAccess {

        @Test
        void multipleReadersOneWriter() throws InterruptedException {
            int writerIterations = 5_000;
            int readerCount = 4;
            AtomicBoolean writerDone = new AtomicBoolean(false);
            ConcurrentHashMap<String, Throwable> errors = new ConcurrentHashMap<>();
            CountDownLatch startLatch = new CountDownLatch(1);

            // Seed with initial snapshot
            store.loadSnapshot(buildSnapshot(0, "counter", "0"));

            // Writer thread applies sequential deltas
            Thread writer = Thread.ofPlatform().start(() -> {
                try {
                    startLatch.await();
                    for (int i = 1; i <= writerIterations; i++) {
                        ConfigDelta delta = new ConfigDelta(i - 1, i, List.of(
                                new ConfigMutation.Put("counter", bytes(String.valueOf(i)))
                        ));
                        store.applyDelta(delta);
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
                        long lastSeenVersion = -1;
                        while (!writerDone.get() || store.currentVersion() != writerIterations) {
                            ReadResult result = store.get("counter");
                            if (!result.found()) {
                                errors.put(Thread.currentThread().getName(),
                                        new AssertionError("counter key not found"));
                                return;
                            }
                            long ver = result.version();
                            if (ver < lastSeenVersion) {
                                errors.put(Thread.currentThread().getName(),
                                        new AssertionError("Version went backwards: "
                                                + lastSeenVersion + " -> " + ver));
                                return;
                            }
                            lastSeenVersion = ver;

                            // Value must match version
                            String val = new String(result.value(), StandardCharsets.UTF_8);
                            long parsedVal = Long.parseLong(val);
                            if (parsedVal != ver) {
                                errors.put(Thread.currentThread().getName(),
                                        new AssertionError("Value/version mismatch: value="
                                                + parsedVal + ", version=" + ver));
                                return;
                            }
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
                fail("Concurrent access errors: " + errors);
            }

            assertEquals(writerIterations, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot immutability
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotImmutability {

        @Test
        void snapshotRemainsUnchangedAfterWrite() {
            store.loadSnapshot(buildSnapshot(1, "key", "v1"));
            ConfigSnapshot snap1 = store.snapshot();

            store.applyDelta(new ConfigDelta(1, 2, List.of(
                    new ConfigMutation.Put("key", bytes("v2"))
            )));

            // snap1 still shows old value
            assertArrayEquals(bytes("v1"), snap1.get("key"));
            assertEquals(1, snap1.version());

            // Current store shows new value
            assertArrayEquals(bytes("v2"), store.get("key").value());
            assertEquals(2, store.currentVersion());
        }
    }

    // -----------------------------------------------------------------------
    // VersionCursor edge cases
    // -----------------------------------------------------------------------

    @Nested
    class VersionCursorEdgeCases {

        @Test
        void cursorComparison() {
            VersionCursor c1 = new VersionCursor(5, 100);
            VersionCursor c2 = new VersionCursor(3, 200);
            assertTrue(c1.isNewerThan(c2));
            assertFalse(c2.isNewerThan(c1));
        }

        @Test
        void sameVersionCursorIsNotNewer() {
            VersionCursor c1 = new VersionCursor(5, 100);
            VersionCursor c2 = new VersionCursor(5, 200);
            assertFalse(c1.isNewerThan(c2));
        }

        @Test
        void negativeVersionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new VersionCursor(-1, 0));
        }
    }

    // -----------------------------------------------------------------------
    // F-0073: InvariantMonitor wiring for INV-M1 monotonic_read
    // -----------------------------------------------------------------------

    @Nested
    class MonotonicReadInvariant {

        @Test
        void cursorAheadOfStoreFiresMonitor() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);

            // Store at version 0; client's cursor at version 5.
            LocalConfigStore store = new LocalConfigStore(
                    ConfigSnapshot.EMPTY, Clock.system(), monitor);

            ReadResult result = store.get("key", new VersionCursor(5, 0));
            assertFalse(result.found(),
                    "cursor ahead of local version must return NOT_FOUND");
            assertEquals(1L,
                    monitor.violations().get(InvariantMonitor.MONOTONIC_READ),
                    "monotonic_read invariant must fire once when cursor is ahead");
            assertEquals(1L,
                    metrics.counter("invariant.violation.monotonic_read").get());
        }

        @Test
        void cursorAtOrBehindDoesNotFireMonitor() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, false);

            // Store at version 10; cursor at 5.
            ConfigSnapshot snap = new ConfigSnapshot(HamtMap.empty(), 10, 0);
            LocalConfigStore store = new LocalConfigStore(snap, Clock.system(), monitor);

            store.get("key", new VersionCursor(5, 0));
            store.get("key", new VersionCursor(10, 0));
            assertTrue(monitor.violations().isEmpty());
        }

        @Test
        void inTestModeCursorAheadThrows() {
            MetricsRegistry metrics = new MetricsRegistry();
            InvariantMonitor monitor = new InvariantMonitor(metrics, true);
            LocalConfigStore store = new LocalConfigStore(
                    ConfigSnapshot.EMPTY, Clock.system(), monitor);

            assertThrows(AssertionError.class,
                    () -> store.get("k", new VersionCursor(7, 0)));
        }

        @Test
        void noMonitorIsTolerated() {
            // Backwards compatibility.
            LocalConfigStore store = new LocalConfigStore();
            ReadResult result = store.get("k", new VersionCursor(5, 0));
            assertFalse(result.found());
        }
    }
}

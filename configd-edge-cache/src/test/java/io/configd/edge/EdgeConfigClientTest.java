package io.configd.edge;

import io.configd.common.Clock;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EdgeConfigClient} — the high-level edge config facade.
 */
class EdgeConfigClientTest {

    /**
     * Simple test clock with explicit time control.
     */
    static class TestClock implements Clock {
        long timeMs;

        TestClock(long initial) {
            this.timeMs = initial;
        }

        @Override
        public long currentTimeMillis() {
            return timeMs;
        }

        @Override
        public long nanoTime() {
            return timeMs * 1_000_000L;
        }

        void advance(long ms) {
            timeMs += ms;
        }
    }

    private TestClock clock;
    private EdgeConfigClient client;

    @BeforeEach
    void setUp() {
        clock = new TestClock(10_000);
        client = new EdgeConfigClient(clock);
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
    // Initial state
    // -----------------------------------------------------------------------

    @Nested
    class InitialState {

        @Test
        void emptyClientReturnsNotFound() {
            ReadResult result = client.get("any-key");
            assertFalse(result.found());
        }

        @Test
        void initialVersionIsZero() {
            assertEquals(0, client.currentVersion());
        }

        @Test
        void initialStalenessIsDisconnected() {
            assertEquals(StalenessTracker.State.DISCONNECTED, client.staleness());
        }

        @Test
        void noInitialSubscriptions() {
            assertTrue(client.subscriptions().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // Reads — delegation to LocalConfigStore
    // -----------------------------------------------------------------------

    @Nested
    class ReadOperations {

        @Test
        void getAfterLoadSnapshot() {
            client.loadSnapshot(buildSnapshot(1, "key", "value"));

            ReadResult result = client.get("key");
            assertTrue(result.found());
            assertArrayEquals(bytes("value"), result.value());
            assertEquals(1, result.version());
        }

        @Test
        void getReturnsNotFoundForMissingKey() {
            client.loadSnapshot(buildSnapshot(1, "key", "value"));

            ReadResult result = client.get("other");
            assertFalse(result.found());
        }

        @Test
        void getWithCursorBehindStoreSucceeds() {
            client.loadSnapshot(buildSnapshot(5, "key", "value"));
            VersionCursor cursor = new VersionCursor(3, 3);

            ReadResult result = client.get("key", cursor);
            assertTrue(result.found());
        }

        @Test
        void getWithCursorAheadOfStoreReturnsNotFound() {
            client.loadSnapshot(buildSnapshot(5, "key", "value"));
            VersionCursor cursor = new VersionCursor(10, 10);

            ReadResult result = client.get("key", cursor);
            assertFalse(result.found());
        }

        @Test
        void getWithInitialCursorAlwaysSucceeds() {
            client.loadSnapshot(buildSnapshot(1, "key", "value"));
            ReadResult result = client.get("key", VersionCursor.INITIAL);
            assertTrue(result.found());
        }
    }

    // -----------------------------------------------------------------------
    // Delta application
    // -----------------------------------------------------------------------

    @Nested
    class DeltaApplication {

        @Test
        void applyDeltaUpdatesStoreAndVersion() {
            client.loadSnapshot(buildSnapshot(1, "a", "1"));

            ConfigDelta delta = new ConfigDelta(1, 2, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));
            client.applyDelta(delta);

            assertEquals(2, client.currentVersion());
            assertTrue(client.get("b").found());
            assertArrayEquals(bytes("2"), client.get("b").value());
        }

        @Test
        void applyDeltaResetsStaleness() {
            client.loadSnapshot(buildSnapshot(1, "a", "1"));
            clock.advance(600); // Would be STALE
            assertEquals(StalenessTracker.State.STALE, client.staleness());

            ConfigDelta delta = new ConfigDelta(1, 2, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));
            client.applyDelta(delta);

            assertEquals(StalenessTracker.State.CURRENT, client.staleness());
        }

        @Test
        void applyDeltaWithVersionMismatchThrows() {
            client.loadSnapshot(buildSnapshot(5, "a", "1"));

            ConfigDelta delta = new ConfigDelta(3, 6, List.of(
                    new ConfigMutation.Put("b", bytes("2"))
            ));
            assertThrows(IllegalArgumentException.class,
                    () -> client.applyDelta(delta));
        }

        @Test
        void applySequentialDeltas() {
            client.loadSnapshot(buildSnapshot(0));

            for (int i = 1; i <= 10; i++) {
                ConfigDelta delta = new ConfigDelta(i - 1, i, List.of(
                        new ConfigMutation.Put("key-" + i, bytes("val-" + i))
                ));
                client.applyDelta(delta);
            }

            assertEquals(10, client.currentVersion());
            for (int i = 1; i <= 10; i++) {
                assertTrue(client.get("key-" + i).found());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Snapshot loading
    // -----------------------------------------------------------------------

    @Nested
    class SnapshotLoading {

        @Test
        void loadSnapshotReplacesEntireStore() {
            client.loadSnapshot(buildSnapshot(1, "old", "data"));
            client.loadSnapshot(buildSnapshot(5, "new", "data"));

            assertEquals(5, client.currentVersion());
            assertFalse(client.get("old").found());
            assertTrue(client.get("new").found());
        }

        @Test
        void loadSnapshotResetsStaleness() {
            assertEquals(StalenessTracker.State.DISCONNECTED, client.staleness());
            client.loadSnapshot(buildSnapshot(1, "key", "value"));
            assertEquals(StalenessTracker.State.CURRENT, client.staleness());
        }
    }

    // -----------------------------------------------------------------------
    // Staleness tracking
    // -----------------------------------------------------------------------

    @Nested
    class StalenessTracking {

        @Test
        void stalenessTransitionsWithTime() {
            client.loadSnapshot(buildSnapshot(1, "key", "value"));
            assertEquals(StalenessTracker.State.CURRENT, client.staleness());

            clock.advance(600);
            assertEquals(StalenessTracker.State.STALE, client.staleness());

            clock.advance(5_000);
            assertEquals(StalenessTracker.State.DEGRADED, client.staleness());

            clock.advance(25_000);
            assertEquals(StalenessTracker.State.DISCONNECTED, client.staleness());
        }

        @Test
        void stalenessMsReflectsElapsedTime() {
            client.loadSnapshot(buildSnapshot(1, "key", "value"));
            clock.advance(250);
            assertEquals(250, client.stalenessMs());
        }
    }

    // -----------------------------------------------------------------------
    // Subscriptions
    // -----------------------------------------------------------------------

    @Nested
    class Subscriptions {

        @Test
        void addAndRemoveSubscription() {
            client.addSubscription("app.");
            assertEquals(Set.of("app."), client.subscriptions());

            client.removeSubscription("app.");
            assertTrue(client.subscriptions().isEmpty());
        }

        @Test
        void multipleSubscriptions() {
            client.addSubscription("app.");
            client.addSubscription("db.");
            client.addSubscription("cache.");

            assertEquals(3, client.subscriptions().size());
            assertTrue(client.subscriptions().containsAll(Set.of("app.", "db.", "cache.")));
        }

        @Test
        void subscriptionsReturnsUnmodifiableSet() {
            client.addSubscription("app.");
            Set<String> subs = client.subscriptions();
            assertThrows(UnsupportedOperationException.class, () -> subs.add("hack."));
        }
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    @Nested
    class MetricsSnapshot {

        @Test
        void metricsReflectsCurrentState() {
            client.loadSnapshot(buildSnapshot(5, "a", "1", "b", "2"));
            client.addSubscription("app.");
            client.addSubscription("db.");

            EdgeMetrics m = client.metrics();
            assertEquals(5, m.currentVersion());
            assertEquals(StalenessTracker.State.CURRENT, m.stalenessState());
            assertEquals(2, m.subscriptionCount());
            assertEquals(2, m.snapshotSize());
        }

        @Test
        void metricsOnEmptyClient() {
            EdgeMetrics m = client.metrics();
            assertEquals(0, m.currentVersion());
            assertEquals(StalenessTracker.State.DISCONNECTED, m.stalenessState());
            assertEquals(0, m.subscriptionCount());
            assertEquals(0, m.snapshotSize());
        }
    }

    // -----------------------------------------------------------------------
    // Null safety
    // -----------------------------------------------------------------------

    @Nested
    class NullSafety {

        @Test
        void nullClockThrows() {
            assertThrows(NullPointerException.class, () -> new EdgeConfigClient(null));
        }

        @Test
        void nullKeyInGetThrows() {
            assertThrows(NullPointerException.class, () -> client.get(null));
        }

        @Test
        void nullDeltaThrows() {
            assertThrows(NullPointerException.class, () -> client.applyDelta(null));
        }

        @Test
        void nullSnapshotThrows() {
            assertThrows(NullPointerException.class, () -> client.loadSnapshot(null));
        }
    }
}

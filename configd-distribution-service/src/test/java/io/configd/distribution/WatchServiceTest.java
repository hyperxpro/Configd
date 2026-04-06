package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class WatchServiceTest {

    private static final byte[] VALUE = "val".getBytes();
    private TestClock clock;
    private WatchService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        // Use short window (5ms) for testing
        WatchCoalescer coalescer = new WatchCoalescer(clock, 5_000_000L, 64);
        service = new WatchService(coalescer);
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    @Nested
    class Registration {

        @Test
        void registerReturnsUniqueIds() {
            long id1 = service.register("a.", event -> {});
            long id2 = service.register("b.", event -> {});

            assertTrue(id1 > 0);
            assertTrue(id2 > 0);
            assertNotEquals(id1, id2);
        }

        @Test
        void registerWithStartVersion() {
            long id = service.register("a.", 10, event -> {});
            assertEquals(10, service.cursor(id));
        }

        @Test
        void registerDefaultStartsAtZero() {
            long id = service.register("a.", event -> {});
            assertEquals(0, service.cursor(id));
        }

        @Test
        void cancelRemovesWatch() {
            long id = service.register("a.", event -> {});
            assertEquals(1, service.watchCount());

            assertTrue(service.cancel(id));
            assertEquals(0, service.watchCount());
        }

        @Test
        void cancelNonExistentReturnsFalse() {
            assertFalse(service.cancel(999));
        }

        @Test
        void watchReturnsRegistration() {
            long id = service.register("prefix.", 5, event -> {});
            WatchService.Watch watch = service.watch(id);

            assertNotNull(watch);
            assertEquals(id, watch.id());
            assertEquals("prefix.", watch.prefix());
            assertEquals(5, watch.cursor());
        }

        @Test
        void watchReturnsNullForUnknownId() {
            assertNull(service.watch(999));
        }

        @Test
        void nullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> service.register(null, event -> {}));
        }

        @Test
        void nullListenerThrows() {
            assertThrows(NullPointerException.class,
                    () -> service.register("a.", null));
        }

        @Test
        void negativeStartVersionThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.register("a.", -1, event -> {}));
        }
    }

    // -----------------------------------------------------------------------
    // Event dispatch — basic
    // -----------------------------------------------------------------------

    @Nested
    class BasicDispatch {

        @Test
        void singleWatcherReceivesSingleEvent() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);
            clock.advanceNanos(5_000_000L);
            int dispatched = service.tick();

            assertEquals(1, dispatched);
            assertEquals(1, received.size());
            assertEquals(1, received.getFirst().version());
        }

        @Test
        void noDispatchBeforeCoalescingWindow() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);

            // Don't advance clock — window hasn't expired
            int dispatched = service.tick();
            assertEquals(0, dispatched);
            assertTrue(received.isEmpty());
        }

        @Test
        void flushAndDispatchBypassesWindow() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);

            int dispatched = service.flushAndDispatch();
            assertEquals(1, dispatched);
            assertEquals(1, received.size());
        }

        @Test
        void noDispatchWithNoWatchers() {
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);
            clock.advanceNanos(5_000_000L);
            assertEquals(0, service.tick());
        }

        @Test
        void noDispatchWithNoPendingEvents() {
            service.register("", event -> fail("should not be called"));
            clock.advanceNanos(5_000_000L);
            assertEquals(0, service.tick());
        }
    }

    // -----------------------------------------------------------------------
    // Prefix filtering
    // -----------------------------------------------------------------------

    @Nested
    class PrefixFiltering {

        @Test
        void emptyPrefixMatchesAllKeys() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("any.key", VALUE)), 1);
            assertEquals(1, service.flushAndDispatch());
            assertEquals(1, received.size());
        }

        @Test
        void prefixFilterMatchingKey() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("db.", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("db.host", VALUE)), 1);
            assertEquals(1, service.flushAndDispatch());
            assertEquals(1, received.size());
            assertEquals("db.host",
                    ((ConfigMutation.Put) received.getFirst().mutations().getFirst()).key());
        }

        @Test
        void prefixFilterNonMatchingKey() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("db.", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("cache.ttl", VALUE)), 1);
            assertEquals(0, service.flushAndDispatch());
            assertTrue(received.isEmpty());
        }

        @Test
        void mixedMatchingAndNonMatchingMutations() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("db.", received::add);

            service.onConfigChange(List.of(
                    new ConfigMutation.Put("db.host", VALUE),
                    new ConfigMutation.Put("cache.ttl", VALUE),
                    new ConfigMutation.Put("db.port", "5432".getBytes())
            ), 1);

            assertEquals(1, service.flushAndDispatch());
            assertEquals(1, received.size());

            WatchEvent event = received.getFirst();
            assertEquals(2, event.size()); // Only db.host and db.port
            assertEquals(1, event.version());
        }

        @Test
        void multipleWatchersWithDifferentPrefixes() {
            List<WatchEvent> dbEvents = new CopyOnWriteArrayList<>();
            List<WatchEvent> cacheEvents = new CopyOnWriteArrayList<>();

            service.register("db.", dbEvents::add);
            service.register("cache.", cacheEvents::add);

            service.onConfigChange(List.of(
                    new ConfigMutation.Put("db.host", VALUE),
                    new ConfigMutation.Put("cache.ttl", VALUE)
            ), 1);

            assertEquals(2, service.flushAndDispatch());
            assertEquals(1, dbEvents.size());
            assertEquals(1, cacheEvents.size());

            // db watcher gets only db.host
            assertEquals(1, dbEvents.getFirst().size());
            // cache watcher gets only cache.ttl
            assertEquals(1, cacheEvents.getFirst().size());
        }
    }

    // -----------------------------------------------------------------------
    // Version cursor tracking
    // -----------------------------------------------------------------------

    @Nested
    class VersionCursorTracking {

        @Test
        void cursorAdvancesAfterDispatch() {
            long id = service.register("", event -> {});

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 5);
            service.flushAndDispatch();

            assertEquals(5, service.cursor(id));
        }

        @Test
        void cursorAdvancesProgressively() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            long id = service.register("", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("a", VALUE)), 1);
            service.flushAndDispatch();
            assertEquals(1, service.cursor(id));

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("b", VALUE)), 2);
            service.flushAndDispatch();
            assertEquals(2, service.cursor(id));

            assertEquals(2, received.size());
        }

        @Test
        void watcherWithHighStartVersionSkipsOldEvents() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            // Watcher starts at version 10 — won't get events <= 10
            service.register("", 10, received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("old", VALUE)), 5);
            assertEquals(0, service.flushAndDispatch());
            assertTrue(received.isEmpty());

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("new", VALUE)), 11);
            assertEquals(1, service.flushAndDispatch());
            assertEquals(1, received.size());
            assertEquals(11, received.getFirst().version());
        }

        @Test
        void cursorForUnknownWatchReturnsMinusOne() {
            assertEquals(-1, service.cursor(999));
        }
    }

    // -----------------------------------------------------------------------
    // Coalescing behavior
    // -----------------------------------------------------------------------

    @Nested
    class Coalescing {

        @Test
        void rapidMutationsCoalesceIntoSingleEvent() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            // Multiple rapid changes within the coalescing window
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("a", VALUE)), 1);
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("b", VALUE)), 2);
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("c", VALUE)), 3);

            // Flush after window expires
            clock.advanceNanos(5_000_000L);
            service.tick();

            assertEquals(1, received.size()); // Single coalesced event
            WatchEvent event = received.getFirst();
            assertEquals(3, event.size()); // Contains all 3 mutations
            assertEquals(3, event.version()); // Latest version
        }

        @Test
        void separateBatchesProduceSeparateEvents() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("", received::add);

            // First batch
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("a", VALUE)), 1);
            clock.advanceNanos(5_000_000L);
            service.tick();

            // Second batch
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("b", VALUE)), 2);
            clock.advanceNanos(5_000_000L);
            service.tick();

            assertEquals(2, received.size());
            assertEquals(1, received.get(0).version());
            assertEquals(2, received.get(1).version());
        }
    }

    // -----------------------------------------------------------------------
    // Fan-out to multiple watchers
    // -----------------------------------------------------------------------

    @Nested
    class FanOut {

        @Test
        void sameEventDeliveredToMultipleWatchers() {
            List<WatchEvent> w1 = new CopyOnWriteArrayList<>();
            List<WatchEvent> w2 = new CopyOnWriteArrayList<>();
            List<WatchEvent> w3 = new CopyOnWriteArrayList<>();

            service.register("", w1::add);
            service.register("", w2::add);
            service.register("", w3::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);
            int dispatched = service.flushAndDispatch();

            assertEquals(3, dispatched);
            assertEquals(1, w1.size());
            assertEquals(1, w2.size());
            assertEquals(1, w3.size());
        }

        @Test
        void onlyMatchingWatchersReceiveEvents() {
            List<WatchEvent> dbWatcher = new CopyOnWriteArrayList<>();
            List<WatchEvent> cacheWatcher = new CopyOnWriteArrayList<>();
            List<WatchEvent> allWatcher = new CopyOnWriteArrayList<>();

            service.register("db.", dbWatcher::add);
            service.register("cache.", cacheWatcher::add);
            service.register("", allWatcher::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("db.host", VALUE)), 1);
            int dispatched = service.flushAndDispatch();

            assertEquals(2, dispatched); // db and all
            assertEquals(1, dbWatcher.size());
            assertTrue(cacheWatcher.isEmpty());
            assertEquals(1, allWatcher.size());
        }
    }

    // -----------------------------------------------------------------------
    // Cancel during dispatch
    // -----------------------------------------------------------------------

    @Nested
    class CancelBehavior {

        @Test
        void cancelledWatchDoesNotReceiveEvents() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            long id = service.register("", received::add);

            service.cancel(id);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);
            assertEquals(0, service.flushAndDispatch());
            assertTrue(received.isEmpty());
        }

        @Test
        void cancelOneWatcherDoesNotAffectOthers() {
            List<WatchEvent> w1 = new CopyOnWriteArrayList<>();
            List<WatchEvent> w2 = new CopyOnWriteArrayList<>();
            long id1 = service.register("", w1::add);
            service.register("", w2::add);

            service.cancel(id1);

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("key", VALUE)), 1);
            assertEquals(1, service.flushAndDispatch());
            assertTrue(w1.isEmpty());
            assertEquals(1, w2.size());
        }
    }

    // -----------------------------------------------------------------------
    // Pending count
    // -----------------------------------------------------------------------

    @Nested
    class PendingState {

        @Test
        void pendingCountReflectsCoalescerState() {
            assertEquals(0, service.pendingCount());

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("a", VALUE)), 1);
            assertEquals(1, service.pendingCount());

            service.onConfigChange(
                    List.of(new ConfigMutation.Put("b", VALUE)), 2);
            assertEquals(2, service.pendingCount());

            service.flushAndDispatch();
            assertEquals(0, service.pendingCount());
        }
    }

    // -----------------------------------------------------------------------
    // Delete mutations through watch
    // -----------------------------------------------------------------------

    @Nested
    class DeleteMutations {

        @Test
        void deleteEventsDeliveredToWatchers() {
            List<WatchEvent> received = new CopyOnWriteArrayList<>();
            service.register("config.", received::add);

            service.onConfigChange(
                    List.of(new ConfigMutation.Delete("config.old")), 1);
            assertEquals(1, service.flushAndDispatch());

            WatchEvent event = received.getFirst();
            assertEquals(1, event.size());
            assertInstanceOf(ConfigMutation.Delete.class, event.mutations().getFirst());
        }
    }

    // -----------------------------------------------------------------------
    // Watch record
    // -----------------------------------------------------------------------

    @Nested
    class WatchClass {

        @Test
        void invalidIdThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WatchService.Watch(0, "", e -> {}, 0));
        }

        @Test
        void nullPrefixThrows() {
            assertThrows(NullPointerException.class,
                    () -> new WatchService.Watch(1, null, e -> {}, 0));
        }

        @Test
        void nullListenerThrows() {
            assertThrows(NullPointerException.class,
                    () -> new WatchService.Watch(1, "", null, 0));
        }

        @Test
        void negativeCursorThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> new WatchService.Watch(1, "", e -> {}, -1));
        }

        @Test
        void advanceCursorMutatesInPlace() {
            WatchService.Watch watch = new WatchService.Watch(1, "prefix.", e -> {}, 0);
            watch.advanceCursor(10);

            assertEquals(10, watch.cursor());
            assertEquals(1, watch.id());
            assertEquals("prefix.", watch.prefix());
        }
    }

    // -----------------------------------------------------------------------
    // Test clock
    // -----------------------------------------------------------------------

    private static final class TestClock implements Clock {
        private long nanos = 1_000_000_000L;

        @Override
        public long currentTimeMillis() {
            return nanos / 1_000_000L;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advanceNanos(long deltaNanos) {
            nanos += deltaNanos;
        }
    }
}

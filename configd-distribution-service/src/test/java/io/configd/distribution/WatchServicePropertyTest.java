package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for the watch/notification system.
 * Verifies invariants that must hold regardless of mutation sequences,
 * prefix filters, and watcher registration patterns.
 */
class WatchServicePropertyTest {

    private static final byte[] VALUE = "val".getBytes();

    private static WatchService createService() {
        TestClock clock = new TestClock();
        // 0-delay coalescing for property tests — immediate flush
        WatchCoalescer coalescer = new WatchCoalescer(clock, 1L, 10_000);
        return new WatchService(coalescer);
    }

    /**
     * INV-W1: Every committed mutation generates exactly one notification
     * per matching watcher (prefix filter). No duplicates, no missed events.
     */
    @Property(tries = 200)
    void everyMutationNotifiesMatchingWatchersExactlyOnce(
            @ForAll @IntRange(min = 1, max = 20) int numKeys) {

        WatchService service = createService();
        List<WatchEvent> allKeyWatcher = new CopyOnWriteArrayList<>();
        service.register("", allKeyWatcher::add);

        int totalMutations = 0;
        for (int i = 1; i <= numKeys; i++) {
            ConfigMutation put = new ConfigMutation.Put("key-" + i, VALUE);
            service.onConfigChange(List.of(put), i);
            service.flushAndDispatch();
            totalMutations++;
        }

        // Each mutation should have produced exactly one event for the all-key watcher
        assertEquals(totalMutations, allKeyWatcher.size(),
                "INV-W1: watcher must receive exactly one event per mutation");

        // Total mutations across all events must match
        int totalReceived = allKeyWatcher.stream().mapToInt(WatchEvent::size).sum();
        assertEquals(totalMutations, totalReceived,
                "INV-W1: total mutations received must match total committed");
    }

    /**
     * INV-W2: Version cursors are strictly monotonically increasing
     * across consecutive events delivered to the same watcher.
     */
    @Property(tries = 200)
    void versionCursorsAreMonotonicallyIncreasing(
            @ForAll @IntRange(min = 2, max = 30) int numMutations) {

        WatchService service = createService();
        List<Long> versions = new CopyOnWriteArrayList<>();
        service.register("", event -> versions.add(event.version()));

        for (int i = 1; i <= numMutations; i++) {
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("k", VALUE)), i);
            service.flushAndDispatch();
        }

        assertFalse(versions.isEmpty(), "Must have received events");
        for (int i = 1; i < versions.size(); i++) {
            assertTrue(versions.get(i) > versions.get(i - 1),
                    "INV-W2 violated: version[" + i + "]=" + versions.get(i)
                            + " not > version[" + (i - 1) + "]=" + versions.get(i - 1));
        }
    }

    /**
     * INV-W3: A watcher with prefix P only receives mutations for keys
     * starting with P. Never receives non-matching keys.
     */
    @Property(tries = 200)
    void prefixFilterNeverDeliversNonMatchingKeys(
            @ForAll @StringLength(min = 1, max = 10) String prefix) {

        WatchService service = createService();
        List<WatchEvent> received = new CopyOnWriteArrayList<>();
        service.register(prefix, received::add);

        // Send matching and non-matching mutations
        List<ConfigMutation> mutations = List.of(
                new ConfigMutation.Put(prefix + "match-a", VALUE),
                new ConfigMutation.Put("nomatch.key", VALUE),
                new ConfigMutation.Put(prefix + "match-b", VALUE),
                new ConfigMutation.Delete("other.key")
        );
        service.onConfigChange(mutations, 1);
        service.flushAndDispatch();

        // Every delivered mutation must match the prefix
        for (WatchEvent event : received) {
            for (ConfigMutation m : event.mutations()) {
                String key = switch (m) {
                    case ConfigMutation.Put put -> put.key();
                    case ConfigMutation.Delete delete -> delete.key();
                };
                assertTrue(key.startsWith(prefix),
                        "INV-W3 violated: key '" + key
                                + "' delivered to watcher with prefix '" + prefix + "'");
            }
        }
    }

    /**
     * INV-W4: Coalescing preserves all mutations — no mutations are lost.
     * The union of mutations across all events for a watcher equals the
     * set of matching committed mutations.
     */
    @Property(tries = 100)
    void coalescingPreservesAllMutations(
            @ForAll @IntRange(min = 1, max = 50) int numMutations) {

        TestClock clock = new TestClock();
        // 100ms window — forces coalescing
        WatchCoalescer coalescer = new WatchCoalescer(clock, 100_000_000L, 10_000);
        WatchService service = new WatchService(coalescer);

        List<WatchEvent> received = new CopyOnWriteArrayList<>();
        service.register("", received::add);

        Set<String> committed = new LinkedHashSet<>();
        for (int i = 1; i <= numMutations; i++) {
            String key = "key-" + i;
            committed.add(key);
            service.onConfigChange(
                    List.of(new ConfigMutation.Put(key, VALUE)), i);
        }

        // Flush the coalesced batch
        clock.advanceNanos(100_000_000L);
        service.tick();

        // Collect all delivered keys
        Set<String> delivered = new LinkedHashSet<>();
        for (WatchEvent event : received) {
            for (ConfigMutation m : event.mutations()) {
                String key = switch (m) {
                    case ConfigMutation.Put put -> put.key();
                    case ConfigMutation.Delete delete -> delete.key();
                };
                delivered.add(key);
            }
        }

        assertEquals(committed, delivered,
                "INV-W4 violated: coalesced events must contain all committed mutations");
    }

    /**
     * INV-W5: Cancelled watches never receive further events.
     */
    @Property(tries = 100)
    void cancelledWatchNeverReceivesEvents(
            @ForAll @IntRange(min = 1, max = 10) int cancelAfter,
            @ForAll @IntRange(min = 1, max = 20) int totalMutations) {

        Assume.that(cancelAfter <= totalMutations);
        WatchService service = createService();

        List<WatchEvent> received = new CopyOnWriteArrayList<>();
        long watchId = service.register("", received::add);

        int deliveredBeforeCancel = 0;
        for (int i = 1; i <= totalMutations; i++) {
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("k" + i, VALUE)), i);
            service.flushAndDispatch();

            if (i == cancelAfter) {
                deliveredBeforeCancel = received.size();
                service.cancel(watchId);
            }
        }

        assertEquals(deliveredBeforeCancel, received.size(),
                "INV-W5 violated: cancelled watch received "
                        + (received.size() - deliveredBeforeCancel) + " extra events");
    }

    /**
     * INV-W6: Watch cursor after processing matches the last event version.
     */
    @Property(tries = 200)
    void cursorMatchesLastEventVersion(
            @ForAll @IntRange(min = 1, max = 20) int numMutations) {

        WatchService service = createService();
        List<Long> eventVersions = new CopyOnWriteArrayList<>();
        long watchId = service.register("", event -> eventVersions.add(event.version()));

        for (int i = 1; i <= numMutations; i++) {
            service.onConfigChange(
                    List.of(new ConfigMutation.Put("k", VALUE)), i);
            service.flushAndDispatch();
        }

        assertFalse(eventVersions.isEmpty());
        assertEquals(eventVersions.getLast(), service.cursor(watchId),
                "INV-W6: cursor must equal the version of the last delivered event");
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

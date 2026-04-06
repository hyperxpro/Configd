package io.configd.distribution;

import io.configd.common.Clock;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Event-driven, version-cursor based config watch/notification system.
 * <p>
 * Clients register watches on key prefixes and receive push notifications
 * when matching config entries change. The system is designed for:
 * <ul>
 *   <li><b>Event-driven delivery</b> — push-based via {@link WatchListener}
 *       callbacks. Never polling. Watchers are invoked when mutations
 *       are applied, not on a timer.</li>
 *   <li><b>Version-cursor tracking</b> — each watcher maintains a cursor
 *       (the version of the last event it processed). If a watcher falls
 *       behind, it can be caught up from the cursor position.</li>
 *   <li><b>Coalescing</b> — rapid mutations are batched via
 *       {@link WatchCoalescer} before fan-out. Prevents thundering herd
 *       on burst writes.</li>
 *   <li><b>Fan-out aware</b> — efficiently dispatches events to multiple
 *       watchers via prefix matching. Only watchers whose prefixes match
 *       the mutated keys are notified.</li>
 * </ul>
 * <p>
 * <b>Integration:</b> The WatchService is registered as a
 * {@code ConfigStateMachine.ConfigChangeListener}. When the Raft state machine
 * applies a committed entry, it calls {@link #onConfigChange(List, long)},
 * which feeds the coalescer. On each I/O tick, call {@link #tick()} to
 * flush coalesced events to matching watchers.
 * <p>
 * <b>Thread safety:</b> designed for single-threaded access from the
 * distribution service I/O thread. No synchronization is used.
 * <p>
 * <b>Design reference:</b> ADR-0006 (event-driven notifications),
 * ADR-0020 (prefix subscription model).
 *
 * @see WatchEvent
 * @see WatchCoalescer
 * @see WatchListener
 */
public final class WatchService {

    /**
     * Callback interface for receiving watch notifications.
     * <p>
     * Implementations are invoked on the distribution service I/O thread.
     * They must be fast and non-blocking — heavy processing should be
     * dispatched to a worker thread or buffered for async delivery.
     */
    @FunctionalInterface
    public interface WatchListener {

        /**
         * Called when config mutations matching the watch's prefix filter
         * are committed and ready for delivery.
         *
         * @param event the coalesced watch event
         */
        void onEvent(WatchEvent event);
    }

    /**
     * Represents a registered watch. Tracks the watcher's prefix filter,
     * listener callback, and version cursor for monotonic delivery.
     * <p>
     * The cursor is mutable — updated in-place during dispatch to avoid
     * per-watcher-per-event object allocation.
     */
    public static final class Watch {
        private final long id;
        private final String prefix;
        private final WatchListener listener;
        private long cursor;  // mutable — updated in-place during dispatch

        public Watch(long id, String prefix, WatchListener listener, long cursor) {
            if (id <= 0) throw new IllegalArgumentException("id must be positive: " + id);
            Objects.requireNonNull(prefix, "prefix must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            if (cursor < 0) throw new IllegalArgumentException("cursor must be non-negative: " + cursor);
            this.id = id;
            this.prefix = prefix;
            this.listener = listener;
            this.cursor = cursor;
        }

        public long id() { return id; }
        public String prefix() { return prefix; }
        public WatchListener listener() { return listener; }
        public long cursor() { return cursor; }
        void advanceCursor(long newCursor) { this.cursor = newCursor; }
    }

    private final WatchCoalescer coalescer;
    private long nextWatchId;
    private final Map<Long, Watch> watches;

    /**
     * Creates a WatchService with the given clock for coalescing.
     *
     * @param clock time source for the coalescer
     */
    public WatchService(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.coalescer = new WatchCoalescer(clock);
        this.nextWatchId = 1;
        this.watches = new HashMap<>();
    }

    /**
     * Creates a WatchService with a custom coalescer.
     *
     * @param coalescer the coalescer to use
     */
    public WatchService(WatchCoalescer coalescer) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer must not be null");
        this.nextWatchId = 1;
        this.watches = new HashMap<>();
    }

    /**
     * Registers a watch on the given key prefix.
     * <p>
     * The watcher will receive events for all keys that start with the
     * given prefix. An empty prefix matches all keys.
     * <p>
     * The optional {@code startVersion} parameter sets the watcher's
     * initial cursor. Events with version <= startVersion will not be
     * delivered. Use 0 to receive all future events.
     *
     * @param prefix       the key prefix to watch (empty = all keys)
     * @param startVersion the initial version cursor (events after this version)
     * @param listener     the callback for notifications
     * @return the watch ID for later cancellation
     */
    public long register(String prefix, long startVersion, WatchListener listener) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (startVersion < 0) {
            throw new IllegalArgumentException("startVersion must be non-negative: " + startVersion);
        }

        long id = nextWatchId++;
        Watch watch = new Watch(id, prefix, listener, startVersion);
        watches.put(id, watch);
        return id;
    }

    /**
     * Registers a watch starting from version 0 (receives all future events).
     */
    public long register(String prefix, WatchListener listener) {
        return register(prefix, 0, listener);
    }

    /**
     * Cancels a watch by ID.
     *
     * @param watchId the ID returned by {@link #register}
     * @return true if the watch existed and was removed
     */
    public boolean cancel(long watchId) {
        return watches.remove(watchId) != null;
    }

    /**
     * Returns the watch registration for the given ID, or null if not found.
     */
    public Watch watch(long watchId) {
        return watches.get(watchId);
    }

    /**
     * Receives config mutations from the Raft state machine apply path.
     * This method feeds the coalescer; actual dispatch happens on
     * {@link #tick()}.
     * <p>
     * Intended to be registered as a
     * {@code ConfigStateMachine.ConfigChangeListener}.
     *
     * @param mutations the mutations applied
     * @param version   the store version after applying
     */
    public void onConfigChange(List<ConfigMutation> mutations, long version) {
        coalescer.add(mutations, version);
    }

    /**
     * Called on each I/O tick to flush coalesced events and dispatch them
     * to matching watchers.
     * <p>
     * This is the core fan-out loop. For each flushed event:
     * <ol>
     *   <li>Determine which mutations match each watcher's prefix filter.</li>
     *   <li>Skip watchers whose cursor is >= the event version (already seen).</li>
     *   <li>Deliver a filtered event containing only matching mutations.</li>
     *   <li>Advance the watcher's cursor to the event version.</li>
     * </ol>
     *
     * @return the number of watcher notifications dispatched
     */
    public int tick() {
        if (!coalescer.shouldFlush()) {
            return 0;
        }

        WatchEvent event = coalescer.flush();
        if (event == null) {
            return 0;
        }

        return dispatchEvent(event);
    }

    /**
     * Forces an immediate flush and dispatch, regardless of the coalescing
     * window. Useful for tests and shutdown draining.
     *
     * @return the number of watcher notifications dispatched
     */
    public int flushAndDispatch() {
        WatchEvent event = coalescer.flush();
        if (event == null) {
            return 0;
        }
        return dispatchEvent(event);
    }

    /**
     * Returns the number of active watches.
     */
    public int watchCount() {
        return watches.size();
    }

    /**
     * Returns the number of mutations pending in the coalescer.
     */
    public int pendingCount() {
        return coalescer.pendingCount();
    }

    /**
     * Returns the version cursor for the given watch, or -1 if not found.
     */
    public long cursor(long watchId) {
        Watch w = watches.get(watchId);
        return (w != null) ? w.cursor() : -1;
    }

    // -----------------------------------------------------------------------
    // Internal dispatch
    // -----------------------------------------------------------------------

    private int dispatchEvent(WatchEvent event) {
        int dispatched = 0;
        Set<String> affectedKeys = event.affectedKeys();

        for (Watch watch : watches.values()) {
            if (watch.cursor() >= event.version()) {
                continue;
            }

            List<ConfigMutation> matching = filterByPrefix(event.mutations(), watch.prefix(), affectedKeys);
            watch.advanceCursor(event.version());

            if (matching.isEmpty()) {
                continue;
            }

            WatchEvent filtered;
            if (matching.size() == event.mutations().size()) {
                filtered = event;
            } else {
                filtered = new WatchEvent(matching, event.version());
            }

            watch.listener().onEvent(filtered);
            dispatched++;
        }
        return dispatched;
    }

    /**
     * Filters mutations to those whose key starts with the given prefix.
     * Optimization: if no affected key matches, returns empty list without
     * iterating the full mutation list.
     */
    private static List<ConfigMutation> filterByPrefix(
            List<ConfigMutation> mutations, String prefix, Set<String> affectedKeys) {

        // Empty prefix matches all keys
        if (prefix.isEmpty()) {
            return mutations;
        }

        // Quick check: does any affected key match?
        boolean anyMatch = false;
        for (String key : affectedKeys) {
            if (key.startsWith(prefix)) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) {
            return List.of();
        }

        // Filter individual mutations
        List<ConfigMutation> result = new ArrayList<>();
        for (ConfigMutation m : mutations) {
            String key = switch (m) {
                case ConfigMutation.Put put -> put.key();
                case ConfigMutation.Delete delete -> delete.key();
            };
            if (key.startsWith(prefix)) {
                result.add(m);
            }
        }
        return result;
    }
}

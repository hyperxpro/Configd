package com.aayushatharva.configd.replication;

import com.aayushatharva.configd.store.KVEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding window for proxy nodes.
 *
 * Configd proxies can advance ahead of their upstream replicas in certain
 * edge cases. The sliding window preserves all recent updates locally within a
 * rolling time window. Items in the window cannot be evicted until they move
 * outside of the window.
 *
 * This ensures that even if a proxy is slightly ahead of a replica, it can serve
 * consistent reads from its local window.
 */
public class SlidingWindow {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindow.class);

    private final long windowDurationMs;
    private final ConcurrentLinkedDeque<WindowEntry> entries = new ConcurrentLinkedDeque<>();

    public record WindowEntry(byte[] key, byte[] value, long version, long timestamp) {}

    public SlidingWindow(long windowDurationMs) {
        this.windowDurationMs = windowDurationMs;
    }

    /** Add an entry to the sliding window. */
    public void add(byte[] key, byte[] value, long version) {
        entries.addLast(new WindowEntry(key, value, version, System.currentTimeMillis()));
        evictExpired();
    }

    /** Look up a key in the window. Returns the latest version within the window. */
    public byte[] get(byte[] key) {
        // Iterate from newest to oldest
        var iter = entries.descendingIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (java.util.Arrays.equals(entry.key(), key)) {
                return entry.value();
            }
        }
        return null;
    }

    /** Get all entries in the window newer than the given version. */
    public List<KVEntry> getEntriesAfter(long version) {
        var result = new ArrayList<KVEntry>();
        for (var entry : entries) {
            if (entry.version() > version) {
                result.add(KVEntry.of(entry.key(), entry.value(), entry.version()));
            }
        }
        return result;
    }

    /** Remove entries that have fallen outside the time window. */
    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - windowDurationMs;
        while (!entries.isEmpty()) {
            var oldest = entries.peekFirst();
            if (oldest != null && oldest.timestamp() < cutoff) {
                entries.pollFirst();
            } else {
                break;
            }
        }
    }

    /** Check if a key is protected by the window (cannot be evicted). */
    public boolean isProtected(byte[] key) {
        for (var entry : entries) {
            if (java.util.Arrays.equals(entry.key(), key)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return entries.size();
    }

    public long getWindowDurationMs() {
        return windowDurationMs;
    }
}

package io.configd.distribution;

import io.configd.store.ConfigDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe, lock-free event buffer for efficient multi-subscriber fan-out.
 * Uses a ring buffer backed by {@link AtomicReferenceArray} for safe publication
 * to concurrent readers without locks.
 * <p>
 * Thread safety: single-writer (apply thread) appends via {@link #append};
 * multiple readers call {@link #deltasSince} concurrently. All reads are
 * lock-free. The volatile {@code head} and {@code tail} fields fence the
 * ring buffer slots.
 */
public final class FanOutBuffer {
    private final AtomicReferenceArray<ConfigDelta> ring;
    private final int capacity;
    private volatile long head; // next write position (monotonically increasing)
    private volatile long tail; // oldest valid position

    public FanOutBuffer(int maxEntries) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        this.capacity = maxEntries;
        this.ring = new AtomicReferenceArray<>(maxEntries);
        this.head = 0;
        this.tail = 0;
    }

    public void append(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        int slot = (int)(head % capacity);
        ring.set(slot, delta);  // AtomicReferenceArray.set provides volatile write semantics
        head = head + 1;        // volatile write — publishes the slot content
        if (head - tail > capacity) {
            tail = head - capacity;  // evict oldest
        }
    }

    public List<ConfigDelta> deltasSince(long fromVersion) {
        long currentTail = tail;   // volatile read
        long currentHead = head;   // volatile read
        List<ConfigDelta> result = new ArrayList<>();
        for (long i = currentTail; i < currentHead; i++) {
            ConfigDelta delta = ring.get((int)(i % capacity));  // volatile read
            if (delta != null && delta.fromVersion() >= fromVersion) {
                result.add(delta);
            }
        }
        return result;
    }

    public ConfigDelta latest() {
        long currentHead = head;
        if (currentHead == tail) return null;
        return ring.get((int)((currentHead - 1) % capacity));
    }

    public long latestVersion() {
        ConfigDelta latest = latest();
        return (latest != null) ? latest.toVersion() : -1;
    }

    public long oldestVersion() {
        long currentTail = tail;
        long currentHead = head;
        if (currentHead == currentTail) return -1;
        ConfigDelta oldest = ring.get((int)(currentTail % capacity));
        return (oldest != null) ? oldest.fromVersion() : -1;
    }

    public int size() {
        return (int)(head - tail);
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public boolean canReplayFrom(long fromVersion) {
        if (isEmpty()) return false;
        long currentTail = tail;
        ConfigDelta oldest = ring.get((int)(currentTail % capacity));
        return oldest != null && oldest.fromVersion() <= fromVersion;
    }
}

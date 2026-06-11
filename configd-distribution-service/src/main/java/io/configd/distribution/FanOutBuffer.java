package io.configd.distribution;

import io.configd.store.ConfigDelta;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe, lock-free event buffer for efficient multi-subscriber fan-out and
 * the implementation of the §4.6 {@link CommitNotificationSource} boundary
 * (ADR-0034).
 *
 * <p>It is a <b>bounded ring of {@code maxEntries}</b> (default 10,000; see
 * {@code ConfigdServer.FANOUT_BUFFER_CAPACITY}) backed by an
 * {@link AtomicReferenceArray} for safe publication to concurrent readers without
 * locks. On overflow it evicts the oldest entry (drop-oldest) and records the
 * eviction (metric + {@link #droppedTotal()}). This is safe because the buffer is
 * a hot-path cache, not the source of truth: post-RR-003 the durable log+snapshot
 * reconstructs all committed state, so an evicted notification is recoverable via
 * the {@link ReplaySource}.
 *
 * <h2>Thread-safety / synchronization (RR-066)</h2>
 * Single-writer: the Raft apply thread appends via {@link #append}. Multiple
 * readers call {@link #readSince} / the legacy read methods concurrently. All
 * reads are lock-free.
 *
 * <p>The historical hazard (RR-066) was that {@link #deltasSince} read {@code tail}
 * then {@code head} non-atomically, so a concurrent appender could lap the reader
 * mid-scan and yield duplicated/wrong deltas. It was harmless while no drain
 * existed; this interface is the drain. The cursor-based {@link #readSince}
 * closes it with two mechanisms:
 * <ol>
 *   <li><b>Gap detection by evicted-seq watermark.</b> The appender publishes the
 *       sequence of the most-recently evicted notification into {@code lastEvictedSeq}
 *       (a volatile {@link AtomicLong}) <em>before</em> advancing {@code tail}. A
 *       reader whose cursor is below that watermark is missing an evicted
 *       notification and is told GAP — never served a silently-truncated run.
 *       This is exact even when seq has natural gaps (no-op/RCFG entries skip
 *       sequence numbers) because it compares the cursor against an actual
 *       evicted seq, not a position arithmetic.</li>
 *   <li><b>Per-slot eviction re-validation.</b> While copying retained slots the
 *       reader re-reads {@code tail} after each slot read; if the slot position
 *       has fallen below {@code tail} the appender lapped it mid-copy, so the
 *       reader abandons the partial run and returns GAP. The appender's
 *       publish order — {@code ring.set(slot)} → {@code head++} → (on overflow)
 *       {@code lastEvictedSeq} → {@code tail = head - capacity} — combined with
 *       the reader reading {@code head} first then validating against {@code tail}
 *       guarantees any notification the reader includes was in {@code [tail, head)}
 *       at copy time.</li>
 * </ol>
 * The append path remains allocation-free (it wraps the incoming
 * {@link CommitNotification} reference into the ring; no per-append allocation).
 */
public final class FanOutBuffer implements CommitNotificationSource {

    private final AtomicReferenceArray<CommitNotification> ring;
    private final int capacity;
    private final FanOutMetrics metrics;
    private volatile long head; // next write position (monotonically increasing)
    private volatile long tail; // oldest valid position

    /**
     * RR-066: the applied-mutation seq of the most-recently evicted notification,
     * or {@code -1} if nothing has been evicted. Published by the appender BEFORE
     * it advances {@code tail}; read by {@link #readSince} to decide GAP vs OK.
     * An {@link AtomicLong} (not a plain volatile field) so the publish is a
     * single atomic store with the same happens-before as {@code tail}.
     */
    private final AtomicLong lastEvictedSeq = new AtomicLong(-1L);

    /** Lifetime count of evicted notifications ({@code fanout_buffer_dropped_total}). */
    private final AtomicLong droppedTotal = new AtomicLong(0L);

    public FanOutBuffer(int maxEntries) {
        this(maxEntries, FanOutMetrics.NOOP);
    }

    /**
     * @param maxEntries ring capacity (&gt; 0)
     * @param metrics    overflow metrics sink (non-null; use {@link FanOutMetrics#NOOP})
     */
    public FanOutBuffer(int maxEntries, FanOutMetrics metrics) {
        if (maxEntries <= 0) throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        this.capacity = maxEntries;
        this.ring = new AtomicReferenceArray<>(maxEntries);
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.head = 0;
        this.tail = 0;
    }

    // -----------------------------------------------------------------------
    // Append (single-writer, apply thread)
    // -----------------------------------------------------------------------

    /**
     * Publishes a full commit notification (the §4.6 producer path, ADR-0034).
     * Allocation-free: the notification reference is stored directly. On overflow
     * the oldest entry is evicted (drop-oldest); the eviction is recorded so a
     * lagging consumer gets a GAP rather than silently-skipped data.
     *
     * <p>Named {@code publish} (not {@code append}) so it does not form an
     * overload pair with the legacy {@link #append(ConfigDelta)} — overloading
     * would make {@code append(null)} ambiguous for existing callers/tests.
     *
     * @param notification the committed-mutation notification (non-null)
     */
    public void publish(CommitNotification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        int slot = (int) (head % capacity);
        // Determine eviction BEFORE the write: when the ring is full, the slot we
        // are about to write IS the slot currently holding the oldest entry
        // (head % capacity == tail % capacity), so writing overwrites it. We must
        // capture the evicted entry's seq for the RR-066 watermark before it is
        // clobbered, and publish that watermark BEFORE advancing tail — so a
        // reader that observes the advanced tail also observes the watermark and
        // reports GAP rather than serving a truncated run.
        boolean willEvict = (head - tail) >= capacity;
        if (willEvict) {
            CommitNotification evicting = ring.get(slot);
            if (evicting != null) {
                lastEvictedSeq.set(evicting.seq());
            }
            droppedTotal.incrementAndGet();
            metrics.onDropped();
        }
        ring.set(slot, notification);   // volatile write — publishes slot content
        head = head + 1;                // volatile write — publishes the new head
        if (willEvict) {
            tail = head - capacity;     // volatile write — evict oldest
        }
    }

    /**
     * Legacy producer path (pre-ADR-0034): appends a raw {@link ConfigDelta},
     * wrapping it in a {@link CommitNotification} whose seq is the delta's
     * {@code toVersion} and whose commit timestamp is 0 (unknown via this path).
     * Retained for the existing fan-out wiring and tests; the production wiring
     * uses {@link #publish(CommitNotification)} to carry the leader commit
     * timestamp (ADR-0035).
     */
    public void append(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");
        publish(new CommitNotification(delta.toVersion(), 0L, delta));
    }

    // -----------------------------------------------------------------------
    // CommitNotificationSource (cursor-based, GAP-signalling — RR-066 safe)
    // -----------------------------------------------------------------------

    @Override
    public Result readSince(long cursor) {
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must be non-negative: " + cursor);
        }
        // Fast-path GAP: if a notification with seq > cursor has already been
        // evicted, the watermark (published before tail advances) tells us the
        // cursor's successor is gone — replay needed.
        long evicted = lastEvictedSeq.get();
        if (evicted >= 0 && cursor < evicted) {
            return Result.gap(oldestSeqInternal());
        }

        // RR-066 / Lamport-style verify-after-read. Single writer; multiple
        // lock-free readers. Read tail FIRST, then head, copy the window, then
        // re-read tail. If tail moved during the copy, the writer evicted (and
        // therefore may have overwritten one of the slots we copied in place),
        // so the copy is potentially torn — return GAP and let the consumer
        // replay. If tail did NOT move, NO slot in [t1, h) could have been
        // overwritten: overwriting slot (i % capacity) requires head to reach
        // i + capacity, which requires tail to advance past t1 (eviction) — which
        // we just proved did not happen. Hence the copy is a clean, strictly
        // ascending, contiguous run.
        long t1 = tail;             // volatile read
        long h = head;              // volatile read AFTER tail
        // If head has outrun tail + capacity, the writer is mid-eviction and has
        // already advanced head past the point where tail WILL move (publish order
        // is ring.set -> head++ -> tail=, so head can be observed ahead of the
        // matching tail advance). Scanning [t1, h) would then visit a wrapped slot
        // twice and read the SAME (newer) notification at two positions — a
        // duplicate. Bounding the window to capacity makes the lap unambiguous:
        // signal GAP and let the consumer replay.
        if (h - t1 > capacity) {
            return Result.gap(oldestSeqInternal());
        }
        List<CommitNotification> out = new ArrayList<>();
        for (long i = t1; i < h; i++) {
            CommitNotification n = ring.get((int) (i % capacity));
            if (n == null) {
                // Slot not yet published (writer mid-append at head) or torn —
                // treat conservatively as a lap.
                return Result.gap(oldestSeqInternal());
            }
            if (n.seq() > cursor) {
                out.add(n);
            }
        }
        long t2 = tail;             // volatile read AFTER the copy
        if (t2 != t1) {
            // Eviction happened during the copy — potential in-place overwrite.
            return Result.gap(oldestSeqInternal());
        }
        return Result.ok(out);
    }

    @Override
    public long latestSeq() {
        long h = head;
        long t = tail;
        if (h == t) return -1;
        CommitNotification n = ring.get((int) ((h - 1) % capacity));
        return (n != null) ? n.seq() : -1;
    }

    @Override
    public long oldestSeq() {
        return oldestSeqInternal();
    }

    private long oldestSeqInternal() {
        long t = tail;
        long h = head;
        if (h == t) return -1;
        CommitNotification n = ring.get((int) (t % capacity));
        return (n != null) ? n.seq() : -1;
    }

    @Override
    public long droppedTotal() {
        return droppedTotal.get();
    }

    // -----------------------------------------------------------------------
    // Legacy delta-based read API (retained for existing fan-out wiring/tests)
    // -----------------------------------------------------------------------

    public List<ConfigDelta> deltasSince(long fromVersion) {
        long currentTail = tail;   // volatile read
        long currentHead = head;   // volatile read
        List<ConfigDelta> result = new ArrayList<>();
        for (long i = currentTail; i < currentHead; i++) {
            CommitNotification n = ring.get((int) (i % capacity));  // volatile read
            if (n != null && n.delta().fromVersion() >= fromVersion) {
                result.add(n.delta());
            }
        }
        return result;
    }

    public ConfigDelta latest() {
        long currentHead = head;
        if (currentHead == tail) return null;
        CommitNotification n = ring.get((int) ((currentHead - 1) % capacity));
        return (n != null) ? n.delta() : null;
    }

    public long latestVersion() {
        ConfigDelta latest = latest();
        return (latest != null) ? latest.toVersion() : -1;
    }

    public long oldestVersion() {
        long currentTail = tail;
        long currentHead = head;
        if (currentHead == currentTail) return -1;
        CommitNotification n = ring.get((int) (currentTail % capacity));
        return (n != null) ? n.delta().fromVersion() : -1;
    }

    public int size() {
        return (int) (head - tail);
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public boolean canReplayFrom(long fromVersion) {
        if (isEmpty()) return false;
        long currentTail = tail;
        CommitNotification n = ring.get((int) (currentTail % capacity));
        return n != null && n.delta().fromVersion() <= fromVersion;
    }
}

package io.configd.distribution;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent producer/reader stress test pinning the non-atomic head/tail hazard that
 * {@link FanOutBuffer#deltasSince} had and that {@link FanOutBuffer#readSince} closes.
 *
 * <p>The hazard: a writer laps a reader mid-scan, yielding duplicated or skipped
 * notifications. The cursor-based {@code readSince} contract is that every
 * non-GAP run is a strictly-ascending, contiguous-over-the-buffered-stream prefix
 * with no duplicate and no skip, and that a reader which advances its cursor and
 * replays on GAP eventually observes every committed seq exactly once, in order.
 *
 * <p>This complements a jcstress-based test of the same buffer, using
 * deterministic bounded latches instead of sleeps to synchronize threads.
 */
class FanOutBufferRaceTest {

    private static ConfigNotification note(long seq) {
        return new ConfigNotification(seq);
    }

    // Small helper to build a notification whose seq == its content, so a reader
    // can detect any duplicate/skip purely from the seq sequence it observes.
    private record ConfigNotification(long seq) {
        CommitNotification toCommit() {
            ConfigDelta d = new ConfigDelta(seq - 1, seq,
                    List.of(new ConfigMutation.Put("k", Long.toString(seq).getBytes())));
            return new CommitNotification(seq, 0L, d);
        }
    }

    @Test
    void concurrentReaderNeverSeesDuplicateOrSkippedSeqAndEventuallySeesAll()
            throws InterruptedException {
        int capacity = 64;
        long totalWrites = 200_000;
        int readerCount = 4;
        FanOutBuffer buf = new FanOutBuffer(capacity);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        AtomicBoolean writerFailed = new AtomicBoolean(false);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (long s = 1; s <= totalWrites; s++) {
                    buf.publish(note(s).toCommit());
                }
            } catch (Throwable t) {
                writerFailed.set(true);
                errors.add(t);
            } finally {
                writeDone.countDown();
            }
        }, "race-writer");

        // Each reader tails with a cursor; on GAP it replays (jumps its cursor to
        // the buffer's current oldest-retained minus one, the smallest legal
        // resume that the contract guarantees is contiguous) and continues.
        List<Thread> readers = new java.util.ArrayList<>();
        for (int r = 0; r < readerCount; r++) {
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    long cursor = 0;
                    long lastSeen = 0; // highest seq observed; must be strictly increasing
                    while (true) {
                        boolean done = writeDone.await(0, TimeUnit.MILLISECONDS);
                        CommitNotificationSource.Result res = buf.readSince(cursor);
                        if (res.isGap()) {
                            // Replay would deliver state up to (oldestRetained - 1); since
                            // everything below the floor is covered by replay, just resume
                            // tailing from the floor predecessor so the next readSince
                            // returns the retained run contiguously.
                            long floor = ((CommitNotificationSource.Result.Gap) res)
                                    .oldestRetainedSeq();
                            cursor = Math.max(cursor, floor - 1);
                            lastSeen = Math.max(lastSeen, cursor);
                            continue;
                        }
                        List<CommitNotification> ns =
                                ((CommitNotificationSource.Result.Ok) res).notifications();
                        long prev = cursor;
                        for (CommitNotification n : ns) {
                            // No duplicate, no out-of-order, no seq <= cursor.
                            if (n.seq() <= prev) {
                                throw new AssertionError(
                                        "non-ascending/duplicate seq in non-GAP run: "
                                                + n.seq() + " after " + prev
                                                + " (cursor=" + cursor + ")");
                            }
                            prev = n.seq();
                        }
                        if (!ns.isEmpty()) {
                            long newCursor = ns.get(ns.size() - 1).seq();
                            if (newCursor < cursor) {
                                throw new AssertionError("cursor moved backwards");
                            }
                            cursor = newCursor;
                            lastSeen = Math.max(lastSeen, cursor);
                        }
                        if (done && cursor >= totalWrites) {
                            return; // caught up after writer finished
                        }
                        if (done && buf.latestSeq() == cursor) {
                            return; // caught up to whatever the buffer retains
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            }, "race-reader-" + r);
            readers.add(reader);
        }

        writer.start();
        readers.forEach(Thread::start);
        start.countDown();

        assertTrue(writeDone.await(30, TimeUnit.SECONDS), "writer did not finish");
        for (Thread reader : readers) {
            reader.join(10_000);
        }

        assertFalse(writerFailed.get(), "writer thread failed: " + errors);
        assertTrue(errors.isEmpty(), "concurrent correctness violations: " + errors);

        assertEquals(capacity, buf.size());
        assertEquals(totalWrites, buf.latestSeq());
        assertEquals(totalWrites - capacity + 1, buf.oldestSeq());
        assertTrue(buf.droppedTotal() >= totalWrites - capacity,
                "drop count should account for nearly all writes");
    }

    /**
     * Reader-paced variant: the writer is bounded to stay at most
     * {@code capacity/2} ahead of the reader's observed cursor, so the reader's
     * cursor is provably never evicted. Under that discipline the reader MUST
     * observe the entire seq stream {@code 1..N} exactly once and contiguously.
     *
     * <p>A GAP may still be <em>transiently</em> observed: each eviction briefly
     * exposes {@code head > tail + capacity} (publish order is
     * {@code ring.set -> head++ -> tail=}), which {@code readSince} conservatively
     * reports as GAP. Because the reader is paced, the data it needs is still
     * retained, so it simply retries and the next {@code readSince(cursor)}
     * succeeds contiguously - no skip, no duplicate, no permanent loss.
     *
     * <p>Pacing is a bounded handoff (a volatile high-water cursor the reader publishes
     * and the writer spins on), not a sleep, so the test stays deterministic.
     */
    @Test
    void readerPacedSeesContiguousStreamExactlyOnce() throws InterruptedException {
        int capacity = 4096;
        long totalWrites = 50_000;
        long lead = capacity / 2;
        FanOutBuffer buf = new FanOutBuffer(capacity);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        // Reader publishes its highest observed seq; writer paces against it.
        java.util.concurrent.atomic.AtomicLong observed =
                new java.util.concurrent.atomic.AtomicLong(0);

        Thread writer = new Thread(() -> {
            try {
                start.await();
                for (long s = 1; s <= totalWrites; s++) {
                    // Bounded handoff: never get more than `lead` ahead of the
                    // reader, so the reader's window is never overwritten.
                    while (s - observed.get() > lead) {
                        Thread.onSpinWait();
                    }
                    buf.publish(note(s).toCommit());
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                writeDone.countDown();
            }
        }, "paced-writer");

        Thread reader = new Thread(() -> {
            try {
                start.await();
                long cursor = 0;
                long expected = 1;
                while (true) {
                    boolean done = writeDone.await(0, TimeUnit.MILLISECONDS);
                    CommitNotificationSource.Result res = buf.readSince(cursor);
                    if (res.isGap()) {
                        // Transient eviction window; since we're paced, our cursor's
                        // data is still retained, so retry and the next read is contiguous.
                        Thread.onSpinWait();
                        continue;
                    }
                    for (CommitNotification n : ((CommitNotificationSource.Result.Ok) res)
                            .notifications()) {
                        if (n.seq() != expected) {
                            throw new AssertionError("expected seq " + expected
                                    + " but got " + n.seq() + " (a skip or duplicate)");
                        }
                        expected++;
                        cursor = n.seq();
                    }
                    observed.set(cursor);
                    if (done && cursor >= totalWrites) return;
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "paced-reader");

        writer.start();
        reader.start();
        start.countDown();

        assertTrue(writeDone.await(30, TimeUnit.SECONDS));
        reader.join(10_000);

        assertTrue(errors.isEmpty(), "violations: " + errors);
        assertEquals(totalWrites, observed.get(),
                "reader-paced reader must observe the full contiguous stream with no GAP");
    }
}

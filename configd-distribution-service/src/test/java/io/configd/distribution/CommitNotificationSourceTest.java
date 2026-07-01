package io.configd.distribution;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the core claims for {@link CommitNotificationSource} as implemented by
 * {@link FanOutBuffer}:
 * <ul>
 *   <li>(a) bound - sustained appends never grow beyond the ring;</li>
 *   <li>(b) overflow policy - eviction increments the drop count and a stale cursor gets a
 *       GAP signal (never silent wrong/duplicated data);</li>
 *   <li>(c) replayability - after overflow, a consumer that replays from the
 *       {@link ReplaySource} and resumes tailing observes EVERY committed mutation's
 *       effect exactly, across a seeded randomized interleaving.</li>
 * </ul>
 */
class CommitNotificationSourceTest {

    // ------------------------------------------------------------------
    // A tiny authoritative model that mirrors the cumulative committed
    // state, and a ReplaySource over it (the source of truth the buffer
    // is a cache for). seq == applied-mutation sequence S.
    // ------------------------------------------------------------------
    private static final class Authoritative implements ReplaySource {
        private final TreeMap<String, byte[]> state = new TreeMap<>();
        private long seq = 0;

        /** Applies a mutation, returns the notification with the assigned seq. */
        CommitNotification applyAndNotify(ConfigMutation m, long commitTs) {
            seq++;
            switch (m) {
                case ConfigMutation.Put p -> state.put(p.key(), p.value());
                case ConfigMutation.Delete d -> state.remove(d.key());
            }
            ConfigDelta delta = new ConfigDelta(seq - 1, seq, List.of(m));
            return new CommitNotification(seq, commitTs, delta);
        }

        Map<String, byte[]> snapshotMap() {
            // deep copy for assertion comparison
            Map<String, byte[]> copy = new HashMap<>();
            state.forEach((k, v) -> copy.put(k, v.clone()));
            return copy;
        }

        @Override
        public Replay replayFromSnapshot() {
            HamtMap<String, VersionedValue> data = HamtMap.empty();
            for (Map.Entry<String, byte[]> e : state.entrySet()) {
                data = data.put(e.getKey(), new VersionedValue(e.getValue(), seq, 0L));
            }
            return new Replay(new ConfigSnapshot(data, seq, 0L), seq);
        }
    }

    private static ConfigMutation put(String k, String v) {
        return new ConfigMutation.Put(k, v.getBytes());
    }

    private static boolean mapsEqual(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) {
            if (!java.util.Arrays.equals(a.get(k), b.get(k))) return false;
        }
        return true;
    }

    // ==================================================================
    // (a) BOUND
    // ==================================================================
    @Test
    void boundSustainedAppendsNeverGrowBeyondRing() {
        int cap = 16;
        FanOutBuffer buf = new FanOutBuffer(cap);
        Authoritative auth = new Authoritative();
        for (int i = 0; i < cap * 100; i++) {
            buf.publish(auth.applyAndNotify(put("k" + i, "v" + i), 1000 + i));
            assertTrue(buf.size() <= cap,
                    "size must never exceed capacity, was " + buf.size());
        }
        assertEquals(cap, buf.size());
        // latest/oldest seq reflect exactly the retained window
        assertEquals(cap * 100, buf.latestSeq());
        assertEquals(cap * 100 - cap + 1, buf.oldestSeq());
    }

    // ==================================================================
    // (b) OVERFLOW POLICY - drop count + GAP on stale cursor
    // ==================================================================
    @Test
    void overflowIncrementsDropCountAndStaleCursorGetsGap() {
        int cap = 8;
        FanOutBuffer buf = new FanOutBuffer(cap);
        Authoritative auth = new Authoritative();

        // Fill exactly to capacity - no eviction yet.
        for (int i = 0; i < cap; i++) {
            buf.publish(auth.applyAndNotify(put("k" + i, "v" + i), 1000 + i));
        }
        assertEquals(0, buf.droppedTotal());
        // A cursor of 0 can still be served contiguously (nothing evicted).
        CommitNotificationSource.Result r0 = buf.readSince(0);
        assertFalse(r0.isGap());
        assertEquals(cap, ((CommitNotificationSource.Result.Ok) r0).notifications().size());

        // Overflow by 3 - evicts seq 1,2,3.
        for (int i = cap; i < cap + 3; i++) {
            buf.publish(auth.applyAndNotify(put("k" + i, "v" + i), 1000 + i));
        }
        assertEquals(3, buf.droppedTotal(), "drop count must equal evictions");

        // A cursor of 0 (needs seq 1..) was overtaken -> GAP, carrying the floor.
        CommitNotificationSource.Result gap = buf.readSince(0);
        assertTrue(gap.isGap(), "stale cursor must get a GAP, never silent partial data");
        assertEquals(buf.oldestSeq(),
                ((CommitNotificationSource.Result.Gap) gap).oldestRetainedSeq());

        // A cursor AT the eviction floor's predecessor is fine: oldest retained
        // is seq 4, so cursor 3 (already saw 1,2,3) is served contiguously.
        CommitNotificationSource.Result ok = buf.readSince(3);
        assertFalse(ok.isGap());
        List<CommitNotification> ns = ((CommitNotificationSource.Result.Ok) ok).notifications();
        assertEquals(cap, ns.size());
        assertEquals(4, ns.get(0).seq());
        assertEquals(cap + 3, ns.get(ns.size() - 1).seq());
    }

    @Test
    void caughtUpCursorReturnsEmptyOkNotGap() {
        FanOutBuffer buf = new FanOutBuffer(8);
        Authoritative auth = new Authoritative();
        for (int i = 0; i < 5; i++) {
            buf.publish(auth.applyAndNotify(put("k" + i, "v" + i), 1000 + i));
        }
        CommitNotificationSource.Result r = buf.readSince(buf.latestSeq());
        assertFalse(r.isGap());
        assertTrue(((CommitNotificationSource.Result.Ok) r).notifications().isEmpty());
    }

    @Test
    void commitTimestampIsCarriedThrough() {
        FanOutBuffer buf = new FanOutBuffer(4);
        Authoritative auth = new Authoritative();
        buf.publish(auth.applyAndNotify(put("k", "v"), 1234567L));
        CommitNotificationSource.Result r = buf.readSince(0);
        CommitNotification n = ((CommitNotificationSource.Result.Ok) r).notifications().get(0);
        assertEquals(1234567L, n.commitTimestampMillis(),
                "leader commit timestamp (ADR-0035 staleness clock) must survive the boundary");
        assertEquals(1, n.seq());
    }

    @Test
    void negativeCursorRejected() {
        FanOutBuffer buf = new FanOutBuffer(4);
        assertThrows(IllegalArgumentException.class, () -> buf.readSince(-1));
    }

    @Test
    void emptyBufferSeqsAreMinusOne() {
        FanOutBuffer buf = new FanOutBuffer(4);
        assertEquals(-1, buf.latestSeq());
        assertEquals(-1, buf.oldestSeq());
        CommitNotificationSource.Result r = buf.readSince(0);
        assertFalse(r.isGap());
        assertTrue(((CommitNotificationSource.Result.Ok) r).notifications().isEmpty());
    }

    // ==================================================================
    // (c) REPLAYABILITY - exactly-once over effect across overflow,
    //     seeded randomized interleaving of appends / overflows / reads.
    // ==================================================================
    @Test
    void replayThenTailObservesEveryMutationEffectExactly() {
        for (long seed = 0; seed < 25; seed++) {
            runReplayInterleaving(seed);
        }
    }

    private void runReplayInterleaving(long seed) {
        Random rnd = new Random(seed);
        int cap = 1 + rnd.nextInt(16);
        FanOutBuffer buf = new FanOutBuffer(cap);
        Authoritative auth = new Authoritative();

        // The consumer maintains its own materialized view by applying the
        // notifications it tails; on GAP it adopts the replay snapshot wholesale
        // then resumes tailing. At the end it MUST equal the authoritative state.
        Map<String, byte[]> consumerView = new HashMap<>();
        long cursor = 0;

        int rounds = 50 + rnd.nextInt(200);
        for (int round = 0; round < rounds; round++) {
            // Produce a random burst of mutations (may overflow the cache).
            int burst = rnd.nextInt(cap * 2 + 2);
            for (int i = 0; i < burst; i++) {
                String key = "k" + rnd.nextInt(8);
                ConfigMutation m = rnd.nextInt(5) == 0
                        ? new ConfigMutation.Delete(key)
                        : put(key, "v" + round + "_" + i);
                buf.publish(auth.applyAndNotify(m, 1000L + round));
            }

            // Consumer attempts to advance.
            cursor = drainConsumer(buf, auth, consumerView, cursor);
        }

        // Final catch-up: drain until caught up to latest.
        while (cursor < buf.latestSeq()) {
            long before = cursor;
            cursor = drainConsumer(buf, auth, consumerView, cursor);
            if (cursor == before) break; // no progress possible without more appends
        }

        assertTrue(mapsEqual(consumerView, auth.snapshotMap()),
                "seed=" + seed + ": consumer view diverged from authoritative state "
                        + "after replay+tail. consumer=" + consumerView.keySet()
                        + " auth=" + auth.snapshotMap().keySet());
        assertEquals(auth.replayFromSnapshot().seq(), cursor,
                "seed=" + seed + ": consumer cursor must reach the authoritative seq");
    }

    /** One consumer step: tail if possible, else replay-then-tail. Returns new cursor. */
    private long drainConsumer(FanOutBuffer buf, ReplaySource replay,
                               Map<String, byte[]> view, long cursor) {
        CommitNotificationSource.Result r = buf.readSince(cursor);
        if (r.isGap()) {
            // Replay from the source of truth, adopt its state, resume from its seq.
            ReplaySource.Replay rp = replay.replayFromSnapshot();
            view.clear();
            rp.snapshot().data().forEach((k, vv) -> view.put(k, vv.valueUnsafe().clone()));
            cursor = rp.seq();
            // After replay we are at the snapshot seq; tail anything newer.
            CommitNotificationSource.Result tail = buf.readSince(cursor);
            if (tail.isGap()) {
                // The buffer raced ahead again; the replay snapshot already covers
                // everything up to its seq, so just return - next round re-drains.
                return cursor;
            }
            applyNotifications(((CommitNotificationSource.Result.Ok) tail).notifications(), view);
            return latestCursor(((CommitNotificationSource.Result.Ok) tail).notifications(), cursor);
        }
        List<CommitNotification> ns = ((CommitNotificationSource.Result.Ok) r).notifications();
        applyNotifications(ns, view);
        return latestCursor(ns, cursor);
    }

    private void applyNotifications(List<CommitNotification> ns, Map<String, byte[]> view) {
        long prev = Long.MIN_VALUE;
        for (CommitNotification n : ns) {
            // Contiguity / monotonicity assertion: a non-GAP run must be strictly
            // ascending in seq with no duplicates (the non-atomic read hazard this test pins).
            assertTrue(n.seq() > prev, "non-GAP run must be strictly ascending in seq");
            prev = n.seq();
            for (ConfigMutation m : n.delta().mutations()) {
                switch (m) {
                    case ConfigMutation.Put p -> view.put(p.key(), p.value());
                    case ConfigMutation.Delete d -> view.remove(d.key());
                }
            }
        }
    }

    private long latestCursor(List<CommitNotification> ns, long cursor) {
        return ns.isEmpty() ? cursor : ns.get(ns.size() - 1).seq();
    }

    @Test
    void legacyDeltaAppendStillWorks() {
        // The legacy append(ConfigDelta) path must still feed the buffer.
        FanOutBuffer buf = new FanOutBuffer(4);
        buf.append(new ConfigDelta(0, 1, List.of(put("k", "v"))));
        assertEquals(1, buf.size());
        assertEquals(1, buf.latestSeq());
        CommitNotificationSource.Result r = buf.readSince(0);
        assertFalse(r.isGap());
        assertEquals(1, ((CommitNotificationSource.Result.Ok) r).notifications().size());
    }
}

package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge filtered-stream engine proofs (ADR-0045 tests (d) convergence + covered-S dual
 * cursor). Drives {@link EdgeClientCore} with a SUBSCRIBE_OK confirming filtering, a
 * non-contiguous (server-filtered) NOTIFY stream, and cursor-advance HEARTBEATs, and asserts:
 * the applied store converges to the full-then-local-filter result, the transport cursor
 * (covered-S) tracks the heartbeat while the store version tracks matched toVersions, and the
 * edge acks the covered-S so filtering never trips the server's ack-lag.
 */
class EdgeClientCoreFilteredModeTest {

    private static final class TestClock implements Clock {
        long timeMs = 10_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    private static final class RecordingSink implements EdgeClientCore.FrameSink {
        final List<EdgeFrame> sent = new ArrayList<>();
        @Override public boolean offer(EdgeFrame frame) {
            return sent.add(frame);
        }
        long lastAck() {
            long ack = -1;
            for (EdgeFrame f : sent) {
                if (f instanceof EdgeFrame.CursorAck a) {
                    ack = a.seq();
                }
            }
            return ack;
        }
    }

    private TestClock clock;
    private RecordingSink sink;
    private EdgeClientCore core;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        sink = new RecordingSink();
        core = new EdgeClientCore(clock, null, null, StrongReadKeyClass.DEFAULT, sink,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        core.addSubscription("svc/");
    }

    private static CommitNotification notif(long seq, long from, long to, String key) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(from, to,
                        List.of(new ConfigMutation.Put(key, "v".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void filteredStreamConvergesWithDualCursor() {
        // The server confirms filtering: the edge selects the filtered apply mode.
        core.onFrame(new EdgeFrame.SubscribeOk(0, EdgeFrame.Mode.TAIL, true));

        // A server-filtered stream: matching deltas at global seq 1 and 5 (2..4 dropped server-
        // side and their versions skipped). At N=1, notification.seq == delta.toVersion.
        core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, "svc/a"))));
        core.onFrame(new EdgeFrame.Notify(List.of(notif(5, 4, 5, "svc/b")))); // forward jump accepted

        assertEquals(5L, core.cursor(), "cursor tracks the last delivered covered seq");
        assertEquals(5L, core.currentVersion(), "the store applied version is the last matched toVersion");
        // Both matching keys are present (converged to the full-then-local-filter state).
        assertEquals("v", new String(core.get("svc/a").value(), StandardCharsets.UTF_8));
        assertEquals("v", new String(core.get("svc/b").value(), StandardCharsets.UTF_8));

        // A cursor-advance HEARTBEAT carries the drained-through covered-S (8): global 6..8 were
        // all filtered out for this edge. The covered cursor advances past the store version.
        core.onFrame(new EdgeFrame.Heartbeat(8, clock.timeMs));
        assertEquals(8L, core.cursor(), "the transport cursor advances to the covered-S");
        assertEquals(5L, core.currentVersion(), "the applied store version does NOT jump on a heartbeat");
        assertEquals(0L, core.cursorLag(), "a filtered edge caught up to the covered-S has zero lag");
        assertEquals(8L, sink.lastAck(), "the edge acks the covered-S (so ack-lag never trips on filtering)");
        assertTrue(core.frontierAdvances() > 0, "the covered frontier advanced");
    }

    @Test
    void classicHeartbeatDoesNotAdvanceCursorPastApplied() {
        // A non-filtered SUBSCRIBE_OK: the heartbeat is the staleness clock, NOT a covered-S
        // advance. latestSeq > cursor must NOT move the cursor (the classic invariant).
        core.onFrame(new EdgeFrame.SubscribeOk(0, EdgeFrame.Mode.TAIL, false));
        core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, "svc/a"))));
        assertEquals(1L, core.cursor());
        core.onFrame(new EdgeFrame.Heartbeat(9, clock.timeMs));
        assertEquals(1L, core.cursor(), "a classic heartbeat's latestSeq never advances the cursor");
        assertEquals(8L, core.cursorLag(), "classic mode reports the cursor lag (latestSeq - cursor = 9 - 1)");
    }

    @Test
    void filteredEdgeMatchesAFullSubscriberRestrictedToPrefix() {
        // (d) The strongest convergence check: a filtered edge's final store equals a full-chain
        // edge's store restricted to the prefix. Build both over the same underlying commit chain.
        EdgeClientCore full = new EdgeClientCore(clock, null, null, StrongReadKeyClass.DEFAULT,
                new RecordingSink(), EdgeClientCore.DEFAULT_HEARTBEAT_MS,
                EdgeClientCore.DEFAULT_SILENCE_FACTOR);
        full.addSubscription("svc/");
        full.onFrame(new EdgeFrame.SubscribeOk(0, EdgeFrame.Mode.TAIL, false));

        core.onFrame(new EdgeFrame.SubscribeOk(0, EdgeFrame.Mode.TAIL, true));

        // The global chain: svc/ and other/ commits interleaved. The full edge sees every delta
        // (storage-filters other/ locally); the filtered edge is served only the svc/ deltas plus
        // a covered-S heartbeat for the skipped tail.
        List<CommitNotification> global = List.of(
                notif(1, 0, 1, "svc/a"), notif(2, 1, 2, "other/x"),
                notif(3, 2, 3, "svc/b"), notif(4, 3, 4, "other/y"),
                notif(5, 4, 5, "svc/c"));
        for (CommitNotification cn : global) {
            full.onFrame(new EdgeFrame.Notify(List.of(cn)));
        }
        // The filtered edge receives only the svc/ deltas (server-side dropped the other/ ones),
        // with their fromVersion jumped over the skipped commits.
        core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, "svc/a"))));
        core.onFrame(new EdgeFrame.Notify(List.of(notif(3, 2, 3, "svc/b"))));
        core.onFrame(new EdgeFrame.Notify(List.of(notif(5, 4, 5, "svc/c"))));

        for (String k : List.of("svc/a", "svc/b", "svc/c")) {
            assertEquals(new String(full.get(k).value(), StandardCharsets.UTF_8),
                    new String(core.get(k).value(), StandardCharsets.UTF_8),
                    "filtered and full-then-local-filter agree on " + k);
        }
        // Neither stores the other/ keys (the full edge storage-filtered them; the filtered edge
        // was never served them).
        assertTrue(!core.get("other/x").found());
        assertTrue(!full.get("other/x").found());
    }
}

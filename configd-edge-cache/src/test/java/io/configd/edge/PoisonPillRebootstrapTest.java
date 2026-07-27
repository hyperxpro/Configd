package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The poison-pill policy, end to end through the PRODUCTION {@link EdgeClientCore} apply
 * path, including the terminal fail-loud case.
 *
 * <p>The apply-throw is manufactured with the TEST-ONLY
 * {@link EdgeClientCore.ApplyFaultInjector} seam: Configd stores opaque bytes, so no
 * delta that decodes and verifies can be made to throw through the real codec/applier -
 * that is the whole point of a poison pill. Everything downstream of the injected throw
 * (the catch, the {@link PoisonPillPolicy} ladder, the directives, the counters, the
 * terminal latch) is production code.
 *
 * <p>The simulated shell behavior between failures mirrors {@code EdgeStreamClient}:
 * drain the directive, {@code onReconnected()}, redeliver from the directive's resume
 * cursor (the server's TAIL path redelivers the failing seq; SNAPSHOT_FIRST sends a
 * snapshot).
 */
class PoisonPillRebootstrapTest {

    static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    static final class PoisonInjector implements EdgeClientCore.ApplyFaultInjector {
        long poisonSeq = -1;
        boolean poisonSnapshots;
        int applyThrows;
        int snapshotThrows;

        @Override public void beforeApply(long seq) {
            if (seq == poisonSeq) {
                applyThrows++;
                throw new IllegalStateException("injected poison apply defect at seq " + seq);
            }
        }

        @Override public void beforeSnapshotLoad(long snapshotSeq) {
            if (poisonSnapshots) {
                snapshotThrows++;
                throw new IllegalStateException(
                        "injected snapshot apply defect at seq " + snapshotSeq);
            }
        }
    }

    private TestClock clock;
    private MetricsRegistry metrics;
    private EdgeClientCore core;
    private PoisonInjector injector;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        metrics = new MetricsRegistry();
        InvariantMonitor monitor = new InvariantMonitor(metrics, true);
        PoisonPillPolicy policy = new PoisonPillPolicy(PoisonPillPolicy.DEFAULT_MAX_RETRIES,
                metrics.counter(PoisonPillPolicy.RETRIES_METRIC),
                metrics.counter(PoisonPillPolicy.POISON_PILL_METRIC),
                metrics.counter(PoisonPillPolicy.TERMINAL_METRIC));
        core = new EdgeClientCore(clock, monitor,
                metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR,
                null, null, policy);
        injector = new PoisonInjector();
        core.setApplyFaultInjectorForTest(injector);
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private CommitNotification notif(long seq, long from, long to, String key, String value) {
        return new CommitNotification(seq, clock.currentTimeMillis(),
                new ConfigDelta(from, to, List.of(new ConfigMutation.Put(key, bytes(value)))));
    }

    private static ConfigSnapshot snapshot(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(bytes(kv[i + 1]), version, version));
        }
        return new ConfigSnapshot(data, version, version);
    }

    private void feedSnapshot(ConfigSnapshot snap, long seq) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 4096);
        core.onFrame(new EdgeFrame.SnapshotBegin(seq, chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk c : chunks) {
            core.onFrame(c);
        }
        core.onFrame(new EdgeFrame.SnapshotEnd(seq));
    }

    private <T extends EdgeClientCore.ConnectionDirective> T drainOne(Class<T> type) {
        EdgeClientCore.ConnectionDirective d = core.pollDirective();
        assertNotNull(d, "expected a pending directive");
        assertNull(core.pollDirective(), "expected exactly one directive");
        return assertInstanceOf(type, d);
    }

    /** Applies seq 1 so the poison case starts from a non-zero cursor. */
    private void applySeqOne() {
        core.onFrame(new EdgeFrame.Notify(List.of(notif(1, 0, 1, "k", "v1"))));
        assertEquals(1, core.cursor());
    }

    @Nested
    class BoundedRetryLadder {

        @Test
        void firstFailuresResubscribeAtCurrentCursorThenQuarantineForcesCursorZero() {
            applySeqOne();
            injector.poisonSeq = 2;

            for (int attempt = 1; attempt <= 2; attempt++) {
                core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
                var r = drainOne(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class);
                assertEquals(1, r.resumeCursor(), "retry resubscribes at the current cursor");
                assertTrue(r.reason().startsWith("poison-retry:"), r.reason());
                core.onReconnected(); // the shell reconnects; the server redelivers seq 2
            }
            assertEquals(2, core.poisonPolicy().retries());
            assertEquals(0, core.poisonPolicy().quarantines());

            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
            var rb = drainOne(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class);
            assertEquals(0, rb.resumeCursor(), "ADR-0040: forced re-bootstrap is cursor 0");
            assertTrue(rb.reason().startsWith("poison-rebootstrap:"), rb.reason());
            assertEquals(1, core.poisonPolicy().quarantines());
            assertEquals(2, core.poisonPolicy().quarantinedSeq());
            assertEquals(1, metrics.counter(PoisonPillPolicy.POISON_PILL_METRIC).get(),
                    "configd.edge.poison_pill emitted on quarantine");
            assertEquals(3, metrics.counter(PoisonPillPolicy.RETRIES_METRIC).get());

            assertEquals(1, core.cursor());
            assertEquals(1, core.currentVersion());
            assertFalse(core.isTerminal());
        }

        @Test
        void transientFailureHealsViaRedeliveryAndClearsTheCount() {
            applySeqOne();
            injector.poisonSeq = 2;

            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
            drainOne(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class);
            core.onReconnected();

            injector.poisonSeq = -1;
            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
            assertEquals(2, core.cursor());
            assertEquals(0, core.poisonPolicy().quarantines());

            // The consecutive count was cleared by the progress: three FRESH failures on a
            // later seq are needed again before quarantine (not one).
            injector.poisonSeq = 3;
            core.onFrame(new EdgeFrame.Notify(List.of(notif(3, 2, 3, "k", "v3"))));
            drainOne(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class);
            core.onReconnected();
            assertEquals(0, core.poisonPolicy().quarantines(),
                    "one failure on a fresh seq must not quarantine");
        }

        @Test
        void batchAbortsAfterApplyFailureWithoutGapNoise() {
            applySeqOne();
            injector.poisonSeq = 2;

            // One NOTIFY batch [2, 3]: seq 2 throws; seq 3 must NOT be offered (it would
            // only GAP against the unadvanced cursor and pollute the honest gap series).
            core.onFrame(new EdgeFrame.Notify(List.of(
                    notif(2, 1, 2, "k", "v2"), notif(3, 2, 3, "k", "v3"))));

            assertEquals(1, core.cursor());
            assertEquals(0, core.gapsDetected(), "aborted batch must not count gaps");
            assertEquals(1, core.poisonPolicy().retries());
        }
    }

    @Nested
    class SnapshotRebootstrapRecovery {

        @Test
        void snapshotPastThePoisonSeqRecoversAndTheChainResumes() {
            applySeqOne();
            injector.poisonSeq = 2;
            for (int attempt = 1; attempt <= 3; attempt++) {
                core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
                core.pollDirective();
                core.onReconnected();
            }
            assertEquals(2, core.poisonPolicy().quarantinedSeq());

            // The forced re-bootstrap (cursor 0) yields SNAPSHOT_FIRST: a snapshot at the
            // server's latest seq (>= the poison - the CP applied it fine) cuts over.
            feedSnapshot(snapshot(5, "k", "v5", "other", "x"), 5);

            assertEquals(5, core.cursor());
            assertEquals(-1, core.poisonPolicy().quarantinedSeq(), "recovered");
            assertFalse(core.isTerminal());
            assertEquals(0, core.poisonPolicy().terminals());

            core.onFrame(new EdgeFrame.Notify(List.of(notif(6, 5, 6, "k", "v6"))));
            assertEquals(6, core.cursor());
            assertArrayEquals(bytes("v6"), core.get("k").value());
        }
    }

    @Nested
    class TerminalFailLoud {

        private void quarantineSeqTwo() {
            applySeqOne();
            injector.poisonSeq = 2;
            for (int attempt = 1; attempt <= 3; attempt++) {
                core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
                core.pollDirective();
                core.onReconnected();
            }
            assertEquals(2, core.poisonPolicy().quarantinedSeq());
        }

        @Test
        void snapshotFailingToApplyDuringForcedRebootstrapIsTerminal() {
            quarantineSeqTwo();

            injector.poisonSnapshots = true;
            feedSnapshot(snapshot(5, "k", "v5"), 5);

            var t = drainOne(EdgeClientCore.ConnectionDirective.TerminalFailure.class);
            assertTrue(t.reason().startsWith("poison-pill-terminal:"), t.reason());
            assertTrue(core.isTerminal());
            assertEquals(1, core.poisonPolicy().terminals());
            assertEquals(1, metrics.counter(PoisonPillPolicy.TERMINAL_METRIC).get(),
                    "configd.edge.poison_pill_terminal emitted BEFORE the process exit");
        }

        @Test
        void quarantinedSeqRedeliveredAsDeltaAfterRebootstrapIsTerminal() {
            quarantineSeqTwo();

            // The server chose TAIL for cursor 0 (young ring, nothing evicted): the poison
            // seq comes back as a DELTA and throws again - no snapshot can be obtained
            // without new wire surface, so the edge must die visibly, not hot-loop.
            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));

            drainOne(EdgeClientCore.ConnectionDirective.TerminalFailure.class);
            assertTrue(core.isTerminal());
            assertEquals(1, metrics.counter(PoisonPillPolicy.TERMINAL_METRIC).get());
        }

        @Test
        void terminalCoreStopsApplyingButStillServesUntilTheProcessExits() {
            quarantineSeqTwo();
            injector.poisonSnapshots = true;
            feedSnapshot(snapshot(5, "k", "v5"), 5);
            assertTrue(core.isTerminal());

            // Frames after terminal are ignored (the shell is about to exit non-zero);
            // applying more could mask the wedge the terminal directive reports.
            injector.poisonSnapshots = false;
            injector.poisonSeq = -1;
            core.onFrame(new EdgeFrame.Notify(List.of(notif(2, 1, 2, "k", "v2"))));
            assertEquals(1, core.cursor(), "terminal core must not advance");
            core.tick(clock.currentTimeMillis());

            assertArrayEquals(bytes("v1"), core.get("k").value());
        }

        @Test
        void terminalIsLatchedAndNeverRepeats() {
            quarantineSeqTwo();
            injector.poisonSnapshots = true;
            feedSnapshot(snapshot(5, "k", "v5"), 5);
            assertEquals(1, core.poisonPolicy().terminals());

            assertEquals(PoisonPillPolicy.Action.TERMINAL,
                    core.poisonPolicy().onApplyFailure(9, new IllegalStateException("x")));
            assertEquals(1, core.poisonPolicy().terminals(), "terminal is latched, not re-counted");
        }
    }

    @Nested
    class ScopeBoundaries {

        @Test
        void invalidSignatureIsNotAPoisonPill() {
            // Fail-closed: a SIGNED delta with no verifier is rejected; the chain
            // halts, staleness surfaces it - the poison policy never sees it.
            ConfigDelta signed = new ConfigDelta(0, 1,
                    List.of(new ConfigMutation.Put("k", bytes("v"))),
                    new byte[64], 1L, new byte[8]);
            core.onFrame(new EdgeFrame.Notify(List.of(
                    new CommitNotification(1, clock.currentTimeMillis(), signed))));

            assertEquals(1, core.verifyRejections());
            assertEquals(0, core.poisonPolicy().retries());
            assertEquals(0, core.poisonPolicy().quarantines());
            assertNull(core.pollDirective(), "a verify rejection queues no recovery directive");
        }

        @Test
        void snapshotFailureOutsideAForcedRebootstrapGetsTheBoundedRetryLadder() {
            // A corrupt snapshot OUTSIDE a poison quarantine is self-healing transfer
            // territory: bounded retry (resubscribe), not instant death.
            applySeqOne();
            injector.poisonSnapshots = true;
            feedSnapshot(snapshot(5, "k", "v5"), 5);

            var r = drainOne(EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint.class);
            assertTrue(r.reason().startsWith("poison-retry:"), r.reason());
            assertEquals(1, r.resumeCursor());
            assertFalse(core.isTerminal());

            core.onReconnected();
            injector.poisonSnapshots = false;
            feedSnapshot(snapshot(5, "k", "v5"), 5);
            assertEquals(5, core.cursor());
            assertEquals(0, core.poisonPolicy().quarantines());
        }

        @Test
        void policyRejectsNonPositiveMaxRetries() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new PoisonPillPolicy(0, null, null, null));
            // The POLICY's own named-config validation, not the inner detector's.
            assertTrue(e.getMessage().contains("edge.poisonpill.maxRetries"), e.getMessage());
        }
    }

    @Nested
    class PolicyLadderEdges {

        private final RuntimeException boom = new IllegalStateException("boom");

        private PoisonPillPolicy quarantined(PoisonPillPolicy p, long seq) {
            for (int i = 0; i < PoisonPillPolicy.DEFAULT_MAX_RETRIES - 1; i++) {
                assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(seq, boom));
            }
            assertEquals(PoisonPillPolicy.Action.REBOOTSTRAP, p.onApplyFailure(seq, boom));
            assertEquals(seq, p.quarantinedSeq());
            return p;
        }

        @Test
        void retryCountsAreIsolatedPerSeq() {
            PoisonPillPolicy p = new PoisonPillPolicy();
            // Two failures at seq 5 plus one at seq 6 must NOT quarantine anything -
            // the bounded count is per seq (a merged key would mis-quarantine at 3).
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(6, boom));
            assertEquals(0, p.quarantines());
            assertEquals(-1, p.quarantinedSeq());
        }

        @Test
        void progressPastTheFailingSeqResetsItsConsecutiveCount() {
            PoisonPillPolicy p = new PoisonPillPolicy();
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            p.onProgress(5); // the seq applied after a transient failure: count resets
            // Two MORE failures (would be 3rd and 4th without the reset) stay retries.
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(0, p.quarantines());
        }

        @Test
        void progressBelowTheFailingSeqDoesNotReset() {
            PoisonPillPolicy p = new PoisonPillPolicy();
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(5, boom));
            p.onProgress(4); // a snapshot/delta short of the wedge clears nothing
            assertEquals(PoisonPillPolicy.Action.REBOOTSTRAP, p.onApplyFailure(5, boom),
                    "the third consecutive failure must still quarantine");
        }

        @Test
        void quarantineReleasesAtExactlyTheQuarantinedSeqNotBelow() {
            PoisonPillPolicy p = quarantined(new PoisonPillPolicy(), 7);
            p.onProgress(6);
            assertEquals(7, p.quarantinedSeq(), "a snapshot below the poison seq is no recovery");
            p.onProgress(7); // exactly covered
            assertEquals(-1, p.quarantinedSeq());
            assertFalse(p.isTerminal());
        }

        @Test
        void differentSeqFailingDuringRebootstrapExitsTheQuarantineAsAFreshFailure() {
            PoisonPillPolicy p = quarantined(new PoisonPillPolicy(), 7);
            // Seqs apply in order: a failure at 9 means the re-bootstrap got PAST 7 -
            // the old quarantine is moot; 9 starts its own bounded ladder (not terminal).
            assertEquals(PoisonPillPolicy.Action.RESUBSCRIBE, p.onApplyFailure(9, boom));
            assertEquals(-1, p.quarantinedSeq(), "the stale quarantine is released");
            assertEquals(1, p.quarantines(), "no new quarantine from one fresh failure");
            assertFalse(p.isTerminal());
        }

        @Test
        void everythingAfterTerminalStaysTerminalWithoutRecounting() {
            PoisonPillPolicy p = quarantined(new PoisonPillPolicy(), 7);
            assertEquals(PoisonPillPolicy.Action.TERMINAL, p.onSnapshotApplyFailure(9, boom));
            assertTrue(p.isTerminal());
            assertEquals(1, p.terminals());
            long retries = p.retries();
            // Latched: every subsequent report answers TERMINAL with no further counting,
            // and progress can no longer un-decide death.
            assertEquals(PoisonPillPolicy.Action.TERMINAL, p.onApplyFailure(10, boom));
            assertEquals(PoisonPillPolicy.Action.TERMINAL, p.onSnapshotApplyFailure(11, boom));
            p.onProgress(99);
            assertTrue(p.isTerminal());
            assertEquals(1, p.terminals());
            assertEquals(retries, p.retries(), "post-terminal reports are not retries");
            assertEquals(7, p.quarantinedSeq(), "terminal state is frozen for the post-mortem");
        }

        @Test
        void maxRetriesAccessorReportsTheNamedConfig() {
            assertEquals(PoisonPillPolicy.DEFAULT_MAX_RETRIES,
                    new PoisonPillPolicy().maxRetries());
            assertEquals(5, new PoisonPillPolicy(5, null, null, null).maxRetries());
        }
    }
}

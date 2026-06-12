package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CT-28/CT-29 at the wire (PROCESS legs; the clock-driven state-machine unit legs live in
 * {@code SlowConsumerQuarantineTransitionTest} / {@code QuarantineReBootstrapTest} in
 * configd-distribution-service): a live {@link FanOutServer} over plaintext loopback with
 * test-scaled thresholds proves that
 * <ul>
 *   <li>repeated distress demotions DISCONNECT the subscriber — the wire sees
 *       {@code ERROR_CLOSE} with {@link ErrorCode#QUARANTINED} (code 8) and the socket
 *       closes (CT-28's "disconnect from tree, mark as quarantined");</li>
 *   <li>a reconnect during the quarantine cooldown is REFUSED at SUBSCRIBE with the same
 *       wire code (admission keyed on the subscriber identity, not the connection);</li>
 *   <li>after the cooldown the SUBSCRIBE is readmitted with the re-bootstrap FORCED:
 *       {@code SUBSCRIBE_OK} carries {@code SNAPSHOT_FIRST} even though the client asked
 *       to resume at a high cursor — the C3 {@code decideMode} cursor-0 rule, reused
 *       (CT-29's "must re-bootstrap via catch-up protocol").</li>
 * </ul>
 * Time is an injected {@link MutableClock} — the cooldown elapses by advancing it, never
 * by sleeping. Deadline-polling on socket reads only (RR-094 discipline).
 */
@Timeout(120)
class FanOutServerQuarantineTest {

    private static final String EDGE_ID = "edge-q";
    private static final long T0 = 1_700_000_000_000L;

    private FanOutServer server;
    private MutableClock clock;
    private SlowConsumerGovernor governor;
    private RecordingGovernorMetrics governorMetrics;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);
    private long seq;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * queueFrames=2 / 1-notification batches: every 3rd unacked publish is a
     * deterministic queue_overflow demotion. Ack-lag is effectively disabled so the
     * distress reason under test is exactly the bounded-queue overflow.
     */
    private static FanOutConfig tinyQueueConfig() {
        return new FanOutConfig(2, 50, 1, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
    }

    /** demoteLimit=2 → the second distress demotion quarantines; 60 s cooldown. */
    private static SlowConsumerPolicyConfig policyConfig() {
        return new SlowConsumerPolicyConfig(
                10_000L, 2, 10, 60_000L, 60_000L, 3, 3_600_000L, 3_600_000L, 4_096);
    }

    private int startServer() throws IOException {
        clock = new MutableClock(T0);
        governorMetrics = new RecordingGovernorMetrics();
        governor = new SlowConsumerGovernor(policyConfig(), governorMetrics);
        buffer = new FanOutBuffer(10_000);
        MetricsRegistry registry = new MetricsRegistry();
        server = new FanOutServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, buffer,
                new SnapshotReplaySource(replayState::get),
                tinyQueueConfig(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS, governor,
                new RegistryFanOutSessionMetrics(registry), clock);
        server.start();
        return server.localPort();
    }

    /** Publishes one committed mutation: the buffer notification + the replay snapshot. */
    private void publish(String key, String value) {
        long s = ++seq;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(s - 1, s,
                List.of(new ConfigMutation.Put(key, bytes)));
        // Keep the replay source authoritative at the published seq (as the real store is).
        ConfigSnapshot current = replayState.get();
        HamtMap<String, VersionedValue> data =
                current.data().put(key, new VersionedValue(bytes, s, T0));
        replayState.set(new ConfigSnapshot(data, s, T0));
        buffer.publish(new CommitNotification(s, T0, delta));
    }

    @Test
    void repeatDistressDemotionsDisconnectWithWireCode8ThenRefuseThenForceRebootstrap()
            throws Exception {
        int port = startServer();

        // --- Phase 1: quarantine. Subscribe, never ack; every 3 publishes overflow the
        // 2-frame queue → demotion. The 2nd demotion trips demoteLimit → QUARANTINED →
        // ERROR_CLOSE code 8 + socket close.
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode(), "empty buffer at subscribe → TAIL");

            publish("k/1", "a");
            publish("k/2", "b");
            publish("k/3", "c"); // 3rd unacked frame → queue_overflow demotion #1
            awaitGovernorState(ConsumerState.CATCHUP, "first demotion feeds the governor");
            // Wire-level sync: wait for the demotion snapshot to COMPLETE before the next
            // burst — the governor flips to CATCHUP at demote time, but the snapshot is
            // taken on the next session tick; publishing earlier would fold the second
            // burst into the first snapshot and no second demotion could ever occur.
            readUntil(edge, EdgeFrame.SnapshotEnd.class);

            publish("k/4", "d");
            publish("k/5", "e");
            publish("k/6", "f"); // → demotion #2 → demoteLimit(2) → QUARANTINED

            // The wire evidence: ERROR_CLOSE QUARANTINED (code 8), then the socket closes.
            // (The best-effort bye can race the writer thread mid-frame — the pre-existing
            // teardown pattern — so a torn final read is tolerated; the governor state and
            // the refusal leg below pin the policy authoritatively either way.)
            boolean sawQuarantineClose = drainUntilQuarantinedOrClosed(edge);
            assertTrue(sawQuarantineClose,
                    "the connection must end after the quarantine verdict");
        }
        awaitGovernorState(ConsumerState.QUARANTINED, "the 2nd distress demotion quarantines");
        assertEquals(1, governorMetrics.quarantines.get(),
                "edge_fanout_quarantines_total must move exactly once");

        // --- Phase 2: reconnect during the cooldown → REFUSED at SUBSCRIBE with code 8.
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 6L);
            EdgeFrame.ErrorClose refusal =
                    (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.QUARANTINED, refusal.code(),
                    "the refusal must carry wire code 8 (QUARANTINED)");
            assertTrue(refusal.message().contains("refused"),
                    "diagnostic message names the refusal: " + refusal.message());
            assertTrue(drainUntilClosed(edge), "the refused connection must close");
        }
        assertTrue(governorMetrics.reconnectsRefused.get() >= 1,
                "edge_fanout_reconnects_refused_total must count the refusal (C4-3)");
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE_ID),
                "a refusal must not mutate the state");

        // --- Phase 3: the cooldown elapses (clock advance, no sleep) → readmitted with
        // the re-bootstrap FORCED: SNAPSHOT_FIRST despite the high resume cursor.
        clock.advance(60_001);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 999_999L); // bogus-high cursor: must be ignored
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, ok.mode(),
                    "readmission rebinds the cursor to 0 → the C3 decideMode forces the "
                            + "snapshot re-bootstrap (§7 'must re-bootstrap')");
            assertEquals(1, governorMetrics.readmissions.get(),
                    "edge_fanout_readmissions_total must move on the cooldown exit");
            assertEquals(ConsumerState.CATCHUP, governor.state(EDGE_ID));

            // The snapshot lands at the published head (seq 6); acking it resolves
            // CATCHUP → HEALTHY (the snapshot+resume-ok exit).
            EdgeFrame.SnapshotEnd end =
                    (EdgeFrame.SnapshotEnd) readUntil(edge, EdgeFrame.SnapshotEnd.class);
            assertEquals(6L, end.snapshotSeq(), "the re-bootstrap snapshot is the head");
            edge.cursorAck(end.snapshotSeq());
            awaitGovernorState(ConsumerState.HEALTHY,
                    "ack progress past the snapshot resolves the catch-up");
        }
    }

    @Test
    void aDifferentIdentityIsUnaffectedByAnotherIdentitysQuarantine() throws Exception {
        int port = startServer();
        try (EdgeProtocolClient bad = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            bad.subscribeFullStore(EDGE_ID, 0L);
            readUntil(bad, EdgeFrame.SubscribeOk.class);
            // Two distinct overflow cycles (a single burst collapses into one demotion:
            // the post-demotion snapshot covers the whole burst).
            for (int i = 1; i <= 3; i++) {
                publish("k/" + i, "v" + i);
            }
            awaitGovernorState(ConsumerState.CATCHUP, "first demotion");
            readUntil(bad, EdgeFrame.SnapshotEnd.class); // first snapshot completed
            for (int i = 4; i <= 6; i++) {
                publish("k/" + i, "v" + i);
            }
            drainUntilQuarantinedOrClosed(bad);
        }
        awaitGovernorState(ConsumerState.QUARANTINED, "fixture: edge-q quarantined");

        // The policy keys on the subscriber identity: another edge subscribes fine.
        try (EdgeProtocolClient good = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            good.subscribeFullStore("edge-ok", 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(good, EdgeFrame.SubscribeOk.class);
            assertNotNull(ok, "an unrelated identity must be admitted normally");
            assertEquals(ConsumerState.HEALTHY, governor.state("edge-ok"));
        }
    }

    // -----------------------------------------------------------------------
    // helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    private void awaitGovernorState(ConsumerState expected, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (governor.state(EDGE_ID) == expected) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L); // 1 ms poll
        }
        fail(what + " — governor state is " + governor.state(EDGE_ID)
                + ", expected " + expected);
    }

    private static EdgeFrame readUntil(EdgeProtocolClient edge,
                                       Class<? extends EdgeFrame> type) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for " + type.getSimpleName());
            }
            if (type.isInstance(f)) {
                return f;
            }
        }
        fail("did not receive a " + type.getSimpleName() + " within the deadline");
        return null;
    }

    /**
     * Drains frames until an {@code ERROR_CLOSE(QUARANTINED)} arrives or the stream ends
     * (EOF / reset / torn final frame — the best-effort bye may race the writer). Returns
     * true once the connection demonstrably ended in either form.
     */
    private static boolean drainUntilQuarantinedOrClosed(EdgeProtocolClient edge)
            throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException | io.configd.distribution.wire.EdgeFrameCodec.CodecException e) {
                return true; // reset or torn bye — the socket is gone
            }
            if (f == null) {
                return true; // EOF
            }
            if (f instanceof EdgeFrame.ErrorClose ec && ec.code() == ErrorCode.QUARANTINED) {
                return true; // the clean wire evidence: code 8
            }
        }
        return false;
    }

    private static boolean drainUntilClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (edge.readFrame() == null) {
                    return true;
                }
            } catch (java.net.SocketTimeoutException e) {
                // keep polling
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    /** A manually-advanced {@link Clock}: the cooldown elapses by {@link #advance}. */
    private static final class MutableClock implements Clock {
        private final AtomicLong nowMillis;

        MutableClock(long startMillis) {
            this.nowMillis = new AtomicLong(startMillis);
        }

        void advance(long deltaMillis) {
            nowMillis.addAndGet(deltaMillis);
        }

        @Override public long currentTimeMillis() {
            return nowMillis.get();
        }

        @Override public long nanoTime() {
            return nowMillis.get() * 1_000_000L;
        }
    }

    /** Counts the governor's policy series (thread-safe: session threads write them). */
    private static final class RecordingGovernorMetrics implements FanOutSessionMetrics {
        final AtomicInteger quarantines = new AtomicInteger();
        final AtomicInteger reconnectsRefused = new AtomicInteger();
        final AtomicInteger readmissions = new AtomicInteger();

        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
        @Override public void onQuarantine() {
            quarantines.incrementAndGet();
        }
        @Override public void onReconnectRefused() {
            reconnectsRefused.incrementAndGet();
        }
        @Override public void onReadmission() {
            readmissions.incrementAndGet();
        }
    }
}

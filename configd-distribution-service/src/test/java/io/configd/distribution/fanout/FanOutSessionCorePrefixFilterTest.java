package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server-side prefix-filtering drain proofs (ADR-0044, Track 1): a filtered session
 * delivers only the matching (plus strong-read) deltas, advances its cursor over the full
 * scanned range, and tells the edge the covered-through position with a cursor-advance
 * HEARTBEAT. Uses the same real {@link FanOutBuffer} / {@link RecordingTransportSink} /
 * {@link FakeClock} harness as {@link FanOutSessionCoreTest}.
 */
class FanOutSessionCorePrefixFilterTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink sink = new RecordingTransportSink();
    private final CountingMetrics metrics = new CountingMetrics();

    /** A counting metrics sink so the filtered/delivered/cursor-advance series are assertable. */
    private static final class CountingMetrics implements FanOutSessionMetrics {
        int filtered;
        int delivered;
        int cursorAdvances;
        int filterActiveTrue;
        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
        @Override public void onFilteredDeltas(int n) { filtered += n; }
        @Override public void onDeliveredDeltas(int n) { delivered += n; }
        @Override public void onCursorAdvance() { cursorAdvances++; }
        @Override public void onFilterActive(boolean active) { if (active) filterActiveTrue++; }
    }

    private FanOutSessionCore session(FanOutBuffer buffer, FanOutConfig cfg) {
        return new FanOutSessionCore(buffer, snapshotAt(0), sink, cfg, metrics, clock);
    }

    private static FanOutConfig filteringConfig() {
        return FanOutConfig.defaults().withServerSidePrefixFilter(true, Set.of("secure/"));
    }

    private static EdgeFrame.Subscribe filteredSubscribe(List<String> prefixes, long resume) {
        return new EdgeFrame.Subscribe(false, prefixes, resume, -1L, "edge-1", true);
    }

    private static ReplaySource snapshotAt(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(
                    kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
        }
        ConfigSnapshot snap = new ConfigSnapshot(data, version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq,
                new ConfigDelta(seq - 1, seq,
                        List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    /** Total notifications the session delivered across all NOTIFY frames. */
    private int deliveredNotifications() {
        int n = 0;
        for (EdgeFrame.Notify f : sink.sentOfType(EdgeFrame.Notify.class)) {
            n += f.notifications().size();
        }
        return n;
    }

    // (a) f-fraction egress: an edge subscribing to a fraction f receives ~f of the deltas.

    @Test
    void subscribingToFractionReceivesApproxFraction() {
        FanOutBuffer buffer = new FanOutBuffer(64);
        // Subscribe on the empty buffer (TAIL), then publish a known distribution: half the
        // deltas touch "svc/" (matching), half touch "other/" (dropped).
        FanOutSessionCore s = session(buffer, filteringConfig());
        s.onSubscribe(filteredSubscribe(List.of("svc/"), 0));
        assertTrue(sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).filtered(),
                "SUBSCRIBE_OK confirms server-side filtering");
        assertEquals(1, metrics.filterActiveTrue);

        int n = 20;
        for (long i = 1; i <= n; i++) {
            buffer.publish(put(i, (i % 2 == 0 ? "svc/k" : "other/k") + i, "v"));
        }
        s.tick(clock.currentTimeMillis());

        assertEquals(n / 2, deliveredNotifications(), "only the svc/ deltas are delivered");
        assertEquals(n / 2, metrics.delivered);
        assertEquals(n / 2, metrics.filtered, "the other/ deltas are dropped whole");
        assertEquals(n, s.cursor(), "the cursor advances over the FULL scanned range");
    }

    // (c) full-store subscription under flag ON is inert (no filtering).

    @Test
    void filterInertForFullStore() {
        FanOutBuffer buffer = new FanOutBuffer(32);
        FanOutSessionCore s = session(buffer, filteringConfig());
        // A full-store subscribe (acceptsFiltered must be false) never filters even at flag-ON.
        s.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1", false));
        assertFalse(sink.sentOfType(EdgeFrame.SubscribeOk.class).get(0).filtered());
        assertEquals(0, metrics.filterActiveTrue);

        for (long i = 1; i <= 6; i++) {
            buffer.publish(put(i, "other/k" + i, "v"));
        }
        s.tick(clock.currentTimeMillis());
        assertEquals(6, deliveredNotifications(), "a full-store session delivers every delta");
        assertEquals(0, metrics.filtered);
    }

    // Strong-read keys are always shipped, regardless of the edge's prefix set.

    @Test
    void strongReadKeyAlwaysShipped() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        FanOutSessionCore s = session(buffer, filteringConfig());
        s.onSubscribe(filteredSubscribe(List.of("svc/"), 0));
        buffer.publish(put(1, "other/k", "v"));    // dropped
        buffer.publish(put(2, "secure/kill", "1")); // strong-read: always shipped
        buffer.publish(put(3, "svc/k", "v"));       // matching
        s.tick(clock.currentTimeMillis());

        List<String> deliveredKeys = sink.sentOfType(EdgeFrame.Notify.class).stream()
                .flatMap(f -> f.notifications().stream())
                .flatMap(cn -> cn.delta().mutations().stream())
                .map(ConfigMutation::key)
                .toList();
        assertEquals(List.of("secure/kill", "svc/k"), deliveredKeys,
                "the strong-read key ships even though it is outside the prefix set");
        assertEquals(3, s.cursor());
    }

    // A trailing filtered range advances the cursor and is signalled by a cursor-advance HEARTBEAT.

    @Test
    void trailingFilteredRangeEmitsCursorAdvanceHeartbeat() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        FanOutSessionCore s = session(buffer, filteringConfig());
        s.onSubscribe(filteredSubscribe(List.of("svc/"), 0));
        sink.clear();
        buffer.publish(put(1, "svc/k1", "v"));   // matching -> delivered
        buffer.publish(put(2, "other/k2", "v")); // dropped
        buffer.publish(put(3, "other/k3", "v")); // dropped (trailing)
        s.tick(clock.currentTimeMillis());

        assertEquals(1, deliveredNotifications(), "one matching delta delivered");
        List<EdgeFrame.Heartbeat> hbs = sink.sentOfType(EdgeFrame.Heartbeat.class);
        assertEquals(1, hbs.size(), "one coalesced cursor-advance heartbeat for the trailing skips");
        assertEquals(3L, hbs.get(0).latestSeq(),
                "the heartbeat carries the drained-through cursor (3), not the last delivered seq (1)");
        assertEquals(1, metrics.cursorAdvances);
        assertEquals(3L, s.cursor());
    }

    // A drain pass that matches nothing still advances the cursor and heartbeats once.

    @Test
    void allFilteredOutStillAdvancesCursorWithOneHeartbeat() {
        FanOutBuffer buffer = new FanOutBuffer(16);
        FanOutSessionCore s = session(buffer, filteringConfig());
        s.onSubscribe(filteredSubscribe(List.of("svc/"), 0));
        sink.clear();
        for (long i = 1; i <= 5; i++) {
            buffer.publish(put(i, "other/k" + i, "v"));
        }
        s.tick(clock.currentTimeMillis());

        assertEquals(0, deliveredNotifications(), "no NOTIFY frame is emitted for an all-drop pass");
        assertEquals(1, sink.sentOfType(EdgeFrame.Heartbeat.class).size());
        assertEquals(5L, sink.sentOfType(EdgeFrame.Heartbeat.class).get(0).latestSeq());
        assertEquals(5L, s.cursor());
        assertEquals(5, metrics.filtered);
    }
}

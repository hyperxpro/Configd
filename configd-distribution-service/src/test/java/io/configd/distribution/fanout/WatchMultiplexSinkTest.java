package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit matrix for {@link WatchMultiplexSink}: the per-watch filter, the core-frame to
 * {@code WATCH_*} translation table, the {@code WATCH_PROGRESS} W5-7 upper-bound clamp, and
 * the legacy-passthrough byte-identity guarantee. Drives a real {@link FanOutSessionCore}
 * through the decorator (registering watches directly in the {@link WatchRegistry}, the same
 * state the driver would post on the session thread) and records the translated frames at a
 * {@link RecordingTransportSink} delegate.
 */
class WatchMultiplexSinkTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();

    private WatchRegistry registry;
    private FanOutBuffer buffer;
    private FanOutSessionCore core;

    /** Builds a single-watch translating session over an empty buffer (TAIL => live streaming). */
    private void singleWatch(WatchTarget target) {
        registry = new WatchRegistry();
        buffer = new FanOutBuffer(64);
        FanOutSessionCore[] holder = new FanOutSessionCore[1];
        WatchMultiplexSink sink = new WatchMultiplexSink(out, registry, () -> holder[0].cursor());
        core = new FanOutSessionCore(buffer, snapshotAt(0), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock);
        holder[0] = core;
        sink.setWatchConnection(true);
        registry.register(new WatchRegistry.WatchEntry(1L, "edge", Set.of(), target, 0L, 0));
        sink.expectWatchCreated(1L);
        core.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge"));
    }

    // ---- per-watch filter (W5-6) -------------------------------------------

    @Test
    void keyTargetDeliversOnlyTheExactKey() {
        singleWatch(keyTarget("/app/db/host"));
        buffer.publish(threeKeys(1));
        core.tick(clock.now());
        assertEquals(List.of("/app/db/host"), changedKeys(onlyEvent()));
    }

    @Test
    void prefixTargetDeliversOnlyStartsWithMatches() {
        singleWatch(prefixTarget("/app/db/"));
        buffer.publish(threeKeys(1));
        core.tick(clock.now());
        assertEquals(List.of("/app/db/host", "/app/db/port"), changedKeys(onlyEvent()));
    }

    @Test
    void fullTargetDeliversAllKeys() {
        singleWatch(fullTarget(false));
        buffer.publish(threeKeys(1));
        core.tick(clock.now());
        assertEquals(List.of("/app/db/host", "/app/db/port", "/app/web/port"), changedKeys(onlyEvent()));
    }

    @Test
    void fullChainVerifyTargetMatchesAllKeysLikeFull() {
        // A root-gated full_chain_verify watch is served as the FULL key stream (W8-4):
        // matches() short-circuits to all keys regardless of the nominal targetKind.
        singleWatch(new WatchTarget(0, EdgeFrame.WATCH_TARGET_KEY, "/app/db/host", true));
        buffer.publish(threeKeys(1));
        core.tick(clock.now());
        assertEquals(List.of("/app/db/host", "/app/db/port", "/app/web/port"), changedKeys(onlyEvent()));
    }

    @Test
    void commitWithNoMatchingKeyProducesNoEventButCursorStillAdvances() {
        singleWatch(keyTarget("/never/matches"));
        buffer.publish(threeKeys(7));
        core.tick(clock.now());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty(), "no event for a non-matching commit");
        assertEquals(7L, core.cursor(), "the shared drain still advances over non-matching commits (W4-4)");
    }

    @Test
    void deleteMutationBecomesADeleteChange() {
        singleWatch(fullTarget(false));
        buffer.publish(delete(3, "/app/db/host"));
        core.tick(clock.now());
        EdgeFrame.WatchChange change = onlyEvent().changes().get(0);
        assertTrue(change.isDelete());
        assertEquals("/app/db/host", change.key());
    }

    // ---- translation table (W5-3..W5-7) ------------------------------------

    @Test
    void subscribeOkTranslatesToWatchCreatedWithShardModeVector() {
        singleWatch(fullTarget(false)); // empty buffer => TAIL
        EdgeFrame.WatchCreated created = out.sentOfType(EdgeFrame.WatchCreated.class).get(0);
        assertEquals(1L, created.watchId());
        assertEquals(1, created.shards().size(), "one shard at N=1");
        assertEquals(0, created.shards().get(0).gid());
        assertEquals(EdgeFrame.Mode.TAIL, created.shards().get(0).mode());
        assertEquals(0L, created.shards().get(0).latestSeq(), "empty buffer latestSeq (-1) clamps to 0");
    }

    @Test
    void notifyTranslatesToWatchEventTaggedGid0WithCommitMetadata() {
        singleWatch(fullTarget(false));
        buffer.publish(put(7, "/k/x", "vx"));
        core.tick(clock.now());
        EdgeFrame.WatchEvent event = onlyEvent();
        assertEquals(1L, event.watchId());
        assertEquals(0, event.gid());
        assertEquals(7L, event.s());
        assertEquals(1_007L, event.commitTs(), "commit timestamp passes through (1000 + seq)");
        assertEquals(EdgeFrame.CHANGE_KIND_PUT, event.changes().get(0).kind());
    }

    @Test
    void heartbeatProgressClampsToDrainedCursorNotRawLatestSeq() {
        // The W5-7 upper-bound clamp: a source whose latestSeq() runs AHEAD of what readSince
        // delivers. The bookmark MUST carry the drained cursor (5), never the raw HEARTBEAT
        // latestSeq (10) - advancing past unexamined commits would be a silent gap (W6-1).
        registry = new WatchRegistry();
        CommitNotificationSource ahead = new AheadOfDrainSource(/* latest */ 10, /* drainTo */ 5);
        FanOutSessionCore[] holder = new FanOutSessionCore[1];
        WatchMultiplexSink sink = new WatchMultiplexSink(out, registry, () -> holder[0].cursor());
        core = new FanOutSessionCore(ahead, snapshotAt(0), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock);
        holder[0] = core;
        sink.setWatchConnection(true);
        registry.register(new WatchRegistry.WatchEntry(1L, "edge", Set.of(), fullTarget(false), 1L, 0));
        sink.expectWatchCreated(1L);
        core.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 1L, -1L, "edge")); // cursor 1 => TAIL

        core.tick(2_000L); // drains seqs 2..5 => cursor 5 (latestSeq stays 10)
        assertEquals(5L, core.cursor());
        out.clear();
        core.tick(2_000L + FanOutConfig.defaults().heartbeatMs()); // idle => heartbeat => WATCH_PROGRESS

        EdgeFrame.WatchProgress progress = out.sentOfType(EdgeFrame.WatchProgress.class).get(0);
        assertEquals(1L, progress.watchId());
        assertEquals(1, progress.cursor().components().size());
        assertEquals(0, progress.cursor().components().get(0).gid());
        assertEquals(5L, progress.cursor().components().get(0).s(),
                "WATCH_PROGRESS S is the drained cursor (5), NOT the raw HEARTBEAT latestSeq (10)");
        assertEquals(2_000L + FanOutConfig.defaults().heartbeatMs(), progress.serverNowMillis());
    }

    // ---- legacy byte-identity ----------------------------------------------

    @Test
    void legacyPassthroughIsFrameIdenticalToTheBareCore() {
        // The 0x01 byte-identity guarantee: a connection whose flag is never flipped drives the
        // core through the decorator producing EXACTLY the frames a bare sink would record. The
        // SAME CommitNotification instances are published to both buffers so frame equality holds.
        CommitNotification n1 = put(1, "/k/1", "v1");
        CommitNotification n2 = put(2, "/k/2", "v2");
        List<EdgeFrame> bare = runLegacy(false, n1, n2);
        List<EdgeFrame> decorated = runLegacy(true, n1, n2);
        assertEquals(bare, decorated, "passthrough decorator emits frame-identical output to the bare core");
    }

    private List<EdgeFrame> runLegacy(boolean decorate, CommitNotification... notifications) {
        RecordingTransportSink rec = new RecordingTransportSink();
        // watchConnection stays false (the default) => pure passthrough.
        TransportSink sink = decorate
                ? new WatchMultiplexSink(rec, new WatchRegistry(), () -> 0L)
                : rec;
        FanOutBuffer buf = new FanOutBuffer(64);
        FanOutSessionCore session = new FanOutSessionCore(buf, snapshotAt(0), sink,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, new FakeClock(1_000L));
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge")); // empty => TAIL
        for (CommitNotification n : notifications) {
            buf.publish(n);
        }
        session.tick(1_000L);  // NOTIFY
        session.tick(1_300L);  // idle past heartbeatMs => HEARTBEAT
        return rec.sent();
    }

    // ---- helpers ------------------------------------------------------------

    private EdgeFrame.WatchEvent onlyEvent() {
        List<EdgeFrame.WatchEvent> events = out.sentOfType(EdgeFrame.WatchEvent.class);
        assertEquals(1, events.size(), "exactly one WATCH_EVENT");
        return events.get(0);
    }

    private static List<String> changedKeys(EdgeFrame.WatchEvent event) {
        List<String> keys = new ArrayList<>();
        for (EdgeFrame.WatchChange c : event.changes()) {
            keys.add(c.key());
        }
        return keys;
    }

    private static WatchTarget keyTarget(String path) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_KEY, path, false);
    }

    private static WatchTarget prefixTarget(String path) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, path, false);
    }

    private static WatchTarget fullTarget(boolean fullChainVerify) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_FULL, "", fullChainVerify);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static CommitNotification delete(long seq, String key) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Delete(key))));
    }

    private static CommitNotification threeKeys(long seq) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq, List.of(
                new ConfigMutation.Put("/app/db/host", "h".getBytes(StandardCharsets.UTF_8)),
                new ConfigMutation.Put("/app/db/port", "p".getBytes(StandardCharsets.UTF_8)),
                new ConfigMutation.Put("/app/web/port", "w".getBytes(StandardCharsets.UTF_8)))));
    }

    private static ReplaySource snapshotAt(long version) {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }

    /**
     * A source whose {@code latestSeq()} deliberately runs ahead of what {@code readSince}
     * delivers - to prove the {@code WATCH_PROGRESS} clamp uses the drained cursor, not the raw
     * latest. {@code readSince(c)} returns the contiguous run {@code (c, drainTo]}.
     */
    private static final class AheadOfDrainSource implements CommitNotificationSource {
        private final long latest;
        private final long drainTo;

        AheadOfDrainSource(long latest, long drainTo) {
            this.latest = latest;
            this.drainTo = drainTo;
        }

        @Override
        public Result readSince(long cursor) {
            List<CommitNotification> run = new ArrayList<>();
            for (long s = cursor + 1; s <= drainTo; s++) {
                run.add(put(s, "/k/" + s, "v"));
            }
            return Result.ok(run);
        }

        @Override
        public long latestSeq() {
            return latest;
        }

        @Override
        public long oldestSeq() {
            return 1L;
        }

        @Override
        public long droppedTotal() {
            return 0L;
        }
    }
}

package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit matrix for {@link WatchMultiplexSink}: the per-watch filter, the gid stamp, the
 * {@code SUBSCRIBE_OK} and {@code HEARTBEAT} forwarding to the cross-shard {@link
 * WatchMultiplexSink.Coordinator} (the two frames that carry an N-shard vector are
 * coalesced by the driver, not built here), and the legacy-passthrough byte-identity
 * guarantee. Drives a real {@link FanOutSessionCore} through the decorator, registering
 * watches directly in the {@link WatchRegistry}, the same state the driver would post on
 * the session thread, and records the translated frames at a {@link
 * RecordingTransportSink} delegate. The coalesced {@code WATCH_CREATED} and {@code
 * WATCH_PROGRESS} vectors and the drained-cursor clamp are proven at the driver level
 * ({@link MultiShardCoordinatorTest}).
 */
class WatchMultiplexSinkTest {

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final RecordingCoordinator coord = new RecordingCoordinator();

    private WatchRegistry registry;
    private FanOutBuffer buffer;
    private FanOutSessionCore core;

    private void singleWatch(WatchTarget target) {
        singleWatch(target, 0);
    }

    private void singleWatch(WatchTarget target, int gid) {
        registry = new WatchRegistry();
        buffer = new FanOutBuffer(64);
        WatchMultiplexSink sink = new WatchMultiplexSink(out, registry, gid, coord);
        core = new FanOutSessionCore(buffer, snapshotAt(0), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock);
        sink.setWatchConnection(true);
        registry.register(new WatchRegistry.WatchEntry(1L, "edge", Set.of(), target,
                new int[]{gid}, 0L, 0));
        sink.setSnapshotOwner(1L);
        core.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge"));
    }

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
        // A root-gated full_chain_verify watch is served as the full key stream: matches()
        // short-circuits to all keys regardless of the nominal targetKind.
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
        assertEquals(7L, core.cursor(), "the shard drain still advances over non-matching commits (W4-4)");
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

    @Test
    void notifyTranslatesToWatchEventTaggedWithThisShardsGid() {
        singleWatch(fullTarget(false), 3);
        buffer.publish(put(7, "/k/x", "vx"));
        core.tick(clock.now());
        EdgeFrame.WatchEvent event = onlyEvent();
        assertEquals(1L, event.watchId());
        assertEquals(3, event.gid(), "the WATCH_EVENT carries this decorator's real shard gid, not a constant 0");
        assertEquals(7L, event.s());
        assertEquals(1_007L, event.commitTs(), "commit timestamp passes through (1000 + seq)");
        assertEquals(EdgeFrame.CHANGE_KIND_PUT, event.changes().get(0).kind());
    }

    @Test
    void snapshotFramesCarryThisShardsGidAndOwner() {
        // A behind-buffer resume drives SNAPSHOT_FIRST; the per (watchId, gid) snapshot
        // frames carry the real gid of this decorator and the drain owner set via
        // setSnapshotOwner.
        FanOutBuffer tiny = new FanOutBuffer(4);
        for (long i = 1; i <= 10; i++) {
            tiny.publish(put(i, "/k/" + i, "v"));
        }
        registry = new WatchRegistry();
        WatchMultiplexSink sink = new WatchMultiplexSink(out, registry, 2, coord);
        core = new FanOutSessionCore(tiny,
                snapshotAt(10, "/k/9", "v9", "/k/10", "v10"), sink, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock);
        sink.setWatchConnection(true);
        registry.register(new WatchRegistry.WatchEntry(1L, "edge", Set.of(), fullTarget(false),
                new int[]{2}, 0L, 0));
        sink.setSnapshotOwner(1L);
        core.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 2L, -1L, "edge")); // readSince(2) GAPs
        core.tick(clock.now());

        EdgeFrame.WatchSnapshotBegin begin = out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).get(0);
        assertEquals(1L, begin.watchId());
        assertEquals(2, begin.gid());
        EdgeFrame.WatchSnapshotEnd end = out.sentOfType(EdgeFrame.WatchSnapshotEnd.class).get(0);
        assertEquals(2, end.gid());
    }

    @Test
    void subscribeOkForwardsThisShardsModeToTheCoordinator() {
        singleWatch(fullTarget(false), 4); // empty buffer, so TAIL; this decorator is shard 4
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty(),
                "the per-shard sink emits no WATCH_CREATED; the driver coalesces it");
        assertEquals(1, coord.shardCreated.size());
        RecordingCoordinator.ShardCreated sc = coord.shardCreated.get(0);
        assertEquals(4, sc.gid());
        assertEquals(0L, sc.latestSeq(), "empty buffer latestSeq (-1) clamps to 0");
        assertEquals(EdgeFrame.Mode.TAIL, sc.mode());
    }

    @Test
    void idleHeartbeatIsForwardedToTheCoordinatorNotEmittedDirectly() {
        singleWatch(fullTarget(false));
        core.tick(clock.now()); // anchors the heartbeat cadence
        long hbAt = clock.now() + FanOutConfig.defaults().heartbeatMs();
        core.tick(hbAt);
        assertTrue(out.sentOfType(EdgeFrame.WatchProgress.class).isEmpty(),
                "the per-shard sink emits no WATCH_PROGRESS; the driver coalesces it");
        assertEquals(1, coord.idleProgressServerNow.size());
        assertEquals(hbAt, coord.idleProgressServerNow.get(0).longValue());
    }

    @Test
    void legacyPassthroughIsFrameIdenticalToTheBareCore() {
        CommitNotification n1 = put(1, "/k/1", "v1");
        CommitNotification n2 = put(2, "/k/2", "v2");
        List<EdgeFrame> bare = runLegacy(false, n1, n2);
        List<EdgeFrame> decorated = runLegacy(true, n1, n2);
        assertEquals(bare, decorated, "passthrough decorator emits frame-identical output to the bare core");
    }

    private List<EdgeFrame> runLegacy(boolean decorate, CommitNotification... notifications) {
        RecordingTransportSink rec = new RecordingTransportSink();
        TransportSink sink = decorate
                ? new WatchMultiplexSink(rec, new WatchRegistry(), 0, coord)
                : rec;
        FanOutBuffer buf = new FanOutBuffer(64);
        FanOutSessionCore session = new FanOutSessionCore(buf, snapshotAt(0), sink,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, new FakeClock(1_000L));
        session.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge"));
        for (CommitNotification n : notifications) {
            buf.publish(n);
        }
        session.tick(1_000L);
        session.tick(1_300L);
        assertTrue(coord.shardCreated.isEmpty() && coord.idleProgressServerNow.isEmpty(),
                "a legacy passthrough connection never touches the coordinator");
        return rec.sent();
    }

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
        return new CommitNotification(seq, 1_000L + seq, new io.configd.store.ConfigDelta(seq - 1, seq,
                List.of(new io.configd.store.ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static CommitNotification delete(long seq, String key) {
        return new CommitNotification(seq, 1_000L + seq, new io.configd.store.ConfigDelta(seq - 1, seq,
                List.of(new io.configd.store.ConfigMutation.Delete(key))));
    }

    private static CommitNotification threeKeys(long seq) {
        return new CommitNotification(seq, 1_000L + seq, new io.configd.store.ConfigDelta(seq - 1, seq, List.of(
                new io.configd.store.ConfigMutation.Put("/app/db/host", "h".getBytes(StandardCharsets.UTF_8)),
                new io.configd.store.ConfigMutation.Put("/app/db/port", "p".getBytes(StandardCharsets.UTF_8)),
                new io.configd.store.ConfigMutation.Put("/app/web/port", "w".getBytes(StandardCharsets.UTF_8)))));
    }

    private static ReplaySource snapshotAt(long version, String... kv) {
        io.configd.store.HamtMap<String, io.configd.store.VersionedValue> data = io.configd.store.HamtMap.empty();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data = data.put(kv[i],
                    new io.configd.store.VersionedValue(kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
        }
        io.configd.store.ConfigSnapshot snap = new io.configd.store.ConfigSnapshot(data, version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }

    private static final class RecordingCoordinator implements WatchMultiplexSink.Coordinator {
        final List<ShardCreated> shardCreated = new ArrayList<>();
        final List<Long> idleProgressServerNow = new ArrayList<>();

        @Override
        public void onShardCreated(int gid, long latestSeq, EdgeFrame.Mode mode) {
            shardCreated.add(new ShardCreated(gid, latestSeq, mode));
        }

        @Override
        public boolean onIdleProgress(long serverNowMillis) {
            idleProgressServerNow.add(serverNowMillis);
            return true; // accepted (a real driver would emit the coalesced WATCH_PROGRESS)
        }

        record ShardCreated(int gid, long latestSeq, EdgeFrame.Mode mode) {
        }
    }
}

package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team re-audit PoC for the F1 fix on the <b>demotion</b> snapshot path (the slow-consumer
 * ladder's re-bootstrap), which is a <b>separate trigger</b> from the initial-subscribe snapshot
 * the sibling {@link WatchSnapshotAuthzRegressionTest} covers. The watch veneer drives the core
 * with a full-store subscribe, so a connection-level DEMOTION re-snapshot is, like the first
 * snapshot, the whole store unless filtered. Both triggers funnel through the single
 * {@link FanOutSessionCore#performSnapshotTransfer} to {@link ReplaySource#replayFromSnapshot}
 * chokepoint, which is the {@link FilteringReplaySource} bound to the drain-owner's target - so a
 * narrow watch that DEMOTES must receive a target-filtered re-snapshot, never the whole store
 * (W5-10 / W7-4). This test forces a {@code TRANSPORT_BLOCK} demotion on a narrow KEY owner and
 * asserts the re-snapshot carries only the authorized key.
 */
class WatchDemotionSnapshotAuthzTest {

    private static final WatchAuthorizer ALLOW_ALL = (p, r, t) -> true;

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();
    private final FanOutBuffer buffer = new FanOutBuffer(64);
    private FanOutConnectionDriver driver;

    private FanOutConnectionDriver newDriver(ReplaySource replay) {
        buffer.publish(commit(1L, "/k/a")); // non-empty source => a from-now watch positions at seq 1
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        return new FanOutConnectionDriver(buffer, replay, out, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, gov, "edge-authorized-for-/k/a-only",
                (c, m) -> teardowns.add(c), ALLOW_ALL);
    }

    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    @Test
    void demotionResnapshotIsFilteredToTheNarrowOwnerNotWholeStore() {
        driver = newDriver(snapshot(1L, "/k/a", "public", "/secret/x", "TOPSECRET-cross-tenant"));

        // A from-now KEY watch on /k/a TAILs (no initial snapshot) - but setTarget(KEY /k/a) is still
        // armed on the FilteringReplaySource for any LATER connection-level snapshot.
        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/k/a".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0));
        assertEquals(EdgeFrame.Mode.TAIL,
                out.sentOfType(EdgeFrame.WatchCreated.class).get(0).shards().get(0).mode(),
                "from-now ⇒ TAIL (no initial snapshot)");

        // Publish a new commit, then refuse its WATCH_EVENT offer so the core demotes to CATCHUP.
        buffer.publish(commit(2L, "/k/a"));
        out.blockNextOffers(1);                 // block the WATCH_EVENT once -> TRANSPORT_BLOCK demote
        driver.session().tick(clock.now());
        assertEquals(FanOutSessionCore.SessionState.CATCHUP, driver.session().state(),
                "the refused WATCH_EVENT demoted the session (slow-consumer ladder)");

        out.clear();                            // isolate the re-snapshot frames
        driver.session().tick(clock.now());     // performSnapshotTransfer -> FILTERED re-snapshot

        List<EdgeFrame.WatchSnapshotChunk> chunks = out.sentOfType(EdgeFrame.WatchSnapshotChunk.class);
        assertFalse(chunks.isEmpty(), "the demotion produced a re-snapshot substream");
        assertTrue(chunks.stream().allMatch(c -> c.watchId() == 1L), "tagged to the narrow owner (id 1)");

        ConfigSnapshot delivered = reassemble(chunks);
        assertTrue(delivered.containsKey("/k/a"), "the AUTHORIZED key /k/a is present");
        assertFalse(delivered.containsKey("/secret/x"),
                "the DEMOTION re-snapshot must NOT leak the unauthorized key (W5-10 / W7-4)");
    }

    // ---- helpers (mirrors WatchSnapshotAuthzRegressionTest) ----

    private static CommitNotification commit(long seq, String key) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, "v".getBytes(StandardCharsets.UTF_8)))));
    }

    private static ConfigSnapshot reassemble(List<EdgeFrame.WatchSnapshotChunk> chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        chunks.stream().sorted((a, b) -> Integer.compare(a.index(), b.index()))
                .forEach(c -> body.write(c.bytes(), 0, c.bytes().length));
        return EdgeSnapshotCodec.deserialize(body.toByteArray());
    }

    private static ReplaySource snapshot(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            data = data.put(kv[i], new VersionedValue(kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
        }
        ConfigSnapshot snap = new ConfigSnapshot(data, version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }
}

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
 * Regression tests for the watch catch-up <b>snapshot</b> authorization fixes (redteam F1/F2):
 * the snapshot path is the one server→client path the per-{@code NOTIFY} filter does not cover, so
 * left unfixed a narrow watch received the whole store on its first snapshot — a read-authz bypass
 * around the subscription gate (W7-4 / W5-10). The fixes:
 * <ul>
 *   <li><b>W3-4 (F1):</b> a from-now watch ({@code cursor==0}, no {@code with_initial_snapshot})
 *       TAILs from the frontier — it is NOT put in {@code SNAPSHOT_FIRST}, so it receives <b>no</b>
 *       catch-up snapshot at all (the common-path leak is eliminated outright).</li>
 *   <li><b>Source filtering (F1):</b> when a snapshot IS delivered ({@code with_initial_snapshot},
 *       or a resume behind the buffer), {@link FilteringReplaySource} filters it to the watch's
 *       target, so a narrow watch never receives a key it could not read; FULL /
 *       {@code full_chain_verify} (root-authorized) pass through whole.</li>
 *   <li><b>Fixed owner tag (F2):</b> a connection-level snapshot is tagged to the FIXED drain-owner
 *       captured when the drain starts, not {@code firstLiveWatchId()} per-frame — so an owner that
 *       cancels mid-transfer cannot mis-attribute its snapshot to a {@code TAIL}-acked sibling.</li>
 * </ul>
 */
class WatchSnapshotAuthzRegressionTest {

    private static final WatchAuthorizer ALLOW_ALL = (p, r, t) -> true;

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();
    private FanOutConnectionDriver driver;

    private FanOutConnectionDriver newDriver(ReplaySource replay) {
        FanOutBuffer buffer = new FanOutBuffer(64);
        // One published commit ⇒ the source is non-empty (latestSeq >= 0).
        buffer.publish(new CommitNotification(1, 1_001L, new ConfigDelta(0, 1,
                List.of(new ConfigMutation.Put("/k/a", "v".getBytes(StandardCharsets.UTF_8))))));
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
    void fromNowNarrowWatchTailsWithNoSnapshot() {
        // W3-4 (F1): a fresh from-now KEY watch on a non-empty store TAILs — no catch-up snapshot,
        // so there is no whole-store exposure path at all.
        driver = newDriver(snapshot(1L, "/k/a", "public", "/secret/x", "TOPSECRET-cross-tenant"));
        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/k/a".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0));

        EdgeFrame.WatchCreated created = out.sentOfType(EdgeFrame.WatchCreated.class).get(0);
        assertEquals(EdgeFrame.Mode.TAIL, created.shards().get(0).mode(),
                "W3-4: a from-now watch TAILs from the frontier, NOT SNAPSHOT_FIRST");

        driver.session().tick(clock.now());
        assertTrue(out.sentOfType(EdgeFrame.WatchSnapshotChunk.class).isEmpty(),
                "no catch-up snapshot for a from-now watch ⇒ no whole-store exposure");
    }

    @Test
    void withInitialSnapshotNarrowWatchGetsTargetFilteredSnapshotNotWholeStore() {
        // with_initial_snapshot IS the snapshot-then-tail path (W5-4a); the snapshot MUST be filtered
        // to the watch's target (W5-10 / W7-4). A /k/a-only watch must NOT receive /secret/x.
        driver = newDriver(snapshot(1L, "/k/a", "public-value", "/secret/x", "TOPSECRET-cross-tenant"));
        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/k/a".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(),
                EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT));

        EdgeFrame.WatchCreated created = out.sentOfType(EdgeFrame.WatchCreated.class).get(0);
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, created.shards().get(0).mode(),
                "with_initial_snapshot ⇒ SNAPSHOT_FIRST (the snapshot-then-tail path)");

        driver.session().tick(clock.now()); // performs the (filtered) catch-up snapshot transfer
        List<EdgeFrame.WatchSnapshotChunk> chunks = out.sentOfType(EdgeFrame.WatchSnapshotChunk.class);
        assertFalse(chunks.isEmpty(), "the watch received its catch-up snapshot substream");
        assertTrue(chunks.stream().allMatch(c -> c.watchId() == 1L), "tagged to the KEY watch (id 1)");

        ConfigSnapshot delivered = reassemble(chunks);
        assertTrue(delivered.containsKey("/k/a"), "the AUTHORIZED key /k/a is present");
        assertFalse(delivered.containsKey("/secret/x"),
                "FIXED (W5-10 / W7-4): the /k/a-only watch's snapshot does NOT contain /secret/x");
    }

    @Test
    void fullWatchWithInitialSnapshotReceivesWholeStore() {
        // FULL is root-authorized (W7-3) ⇒ NO filtering; it legitimately receives every key.
        driver = newDriver(snapshot(1L, "/k/a", "public", "/secret/x", "secret"));
        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_FULL,
                new byte[0], WatchCursor.fromNow(), EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT));
        driver.session().tick(clock.now());

        ConfigSnapshot delivered = reassemble(out.sentOfType(EdgeFrame.WatchSnapshotChunk.class));
        assertTrue(delivered.containsKey("/k/a") && delivered.containsKey("/secret/x"),
                "a root-authorized FULL watch receives the whole store (no narrowing)");
    }

    @Test
    void snapshotStaysTaggedToTheDrainOwnerWhenItCancelsMidTransfer() {
        // F2: A (with_initial_snapshot) owns the drain + SNAPSHOT_FIRST; B (KEY) rides TAIL. If the
        // owner A cancels while its snapshot is paused on backpressure, the resumed WATCH_SNAPSHOT_*
        // stays tagged to A (the FIXED captured owner), never mis-attributed to TAIL-acked sibling B.
        driver = newDriver(snapshot(1L, "/k/a", "pub", "/secret/x", "TOPSECRET"));

        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_FULL,
                new byte[0], WatchCursor.fromNow(), EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT)); // A: owner
        feed(new EdgeFrame.WatchCreate(2L, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/k/a".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(), 0));               // B: TAIL

        EdgeFrame.WatchCreated bCreated = out.sentOfType(EdgeFrame.WatchCreated.class).stream()
                .filter(c -> c.watchId() == 2L).findFirst().orElseThrow();
        assertEquals(EdgeFrame.Mode.TAIL, bCreated.shards().get(0).mode(),
                "B is acked TAIL — the protocol promises B no snapshot substream");

        out.clear();
        out.blockNextOffers(1);               // block A's SNAPSHOT_BEGIN once → transfer pauses
        driver.session().tick(clock.now());
        assertTrue(out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).isEmpty(), "BEGIN paused");

        feed(new EdgeFrame.WatchCancel(1L));   // cancel the drain owner A mid-transfer
        driver.session().tick(clock.now());    // snapshot resumes

        List<EdgeFrame.WatchSnapshotBegin> begins = out.sentOfType(EdgeFrame.WatchSnapshotBegin.class);
        assertEquals(1, begins.size());
        assertEquals(1L, begins.get(0).watchId(),
                "FIXED (F2): the snapshot stays tagged to drain-owner A (1), not sibling B (2)");
    }

    // ---- helpers ----

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

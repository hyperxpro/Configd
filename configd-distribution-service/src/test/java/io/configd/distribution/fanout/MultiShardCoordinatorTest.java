package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-shard fan-out/fan-in coordinator proofs. Drives {@link FanOutConnectionDriver}
 * over N in-memory {@link FanOutBuffer}s + a frame-capturing {@link RecordingTransportSink}, sweeping
 * ticks (the same deterministic, thread-free harness as {@code FanOutSessionCoreGapClassificationTest}
 * generalized to N shards). These tests exercise the coordinator directly, never booting an edge server.
 *
 * <p>Coverage rule for the test {@link ShardResolver}: a KEY target {@code /sX/...} covers shard X;
 * a PREFIX / FULL target scatters across every shard (the target sets coverage, never the cursor).
 * The real hash-routed completeness proof (a key that {@code shardFor}s to a non-zero shard) lives in
 * the server module, over the real {@code StaticShardMap}.
 */
class MultiShardCoordinatorTest {

    private static final WatchAuthorizer ALLOW = (p, r, t) -> true;
    private static final WatchAuthorizer DENY = (p, r, t) -> false;

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();

    private int n;
    private FanOutBuffer[] buffers;
    private MutableSnapshot[] snaps;
    private FanOutConnectionDriver driver;

    // ---- harness ------------------------------------------------------------

    private void setup(int shards, WatchAuthorizer auth) {
        setup(shards, auth, "edge-1", 64);
    }

    private void setup(int shards, WatchAuthorizer auth, String identity, int capacity) {
        this.n = shards;
        this.buffers = new FanOutBuffer[shards];
        this.snaps = new MutableSnapshot[shards];
        Map<Integer, CommitNotificationSource> sources = new LinkedHashMap<>();
        Map<Integer, ReplaySource> replays = new LinkedHashMap<>();
        int[] gids = new int[shards];
        for (int g = 0; g < shards; g++) {
            buffers[g] = new FanOutBuffer(capacity);
            snaps[g] = new MutableSnapshot();
            sources.put(g, buffers[g]);
            replays.put(g, new SnapshotReplaySource(snaps[g]));
            gids[g] = g;
        }
        int[] allGids = gids;
        // Mirror the production ShardMapResolver: a match-all target (FULL, or any kind carrying
        // full_chain_verify) scatters to every shard - checked BEFORE the KEY branch so a
        // KEY+full_chain_verify target covers all shards, not just the one its path names.
        ShardResolver resolver = t -> t.isMatchAll() ? allGids.clone()
                : (t.targetKind() == EdgeFrame.WATCH_TARGET_KEY
                        ? new int[]{shardOf(t.path())}
                        : allGids.clone());
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(sources, replays, gids, resolver, out,
                FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, clock, gov, identity,
                (c, m) -> teardowns.add(c), auth);
    }

    /** "/s2/host" -> shard 2 (the test coverage convention for KEY targets). */
    private static int shardOf(String path) {
        int slash = path.indexOf('/', 1);
        String seg = path.substring(1, slash < 0 ? path.length() : slash);
        return Integer.parseInt(seg.substring(1));
    }

    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    private void sweep() {
        driver.sweep(clock.now());
    }

    private void sweepAt(long now) {
        clock.set(now);
        driver.sweep(now);
    }

    // =====================================================================
    // N=1 differential oracle (the byte-identity regression floor)
    // =====================================================================

    @Test
    void nEquals1IsFrameIdenticalToTheReferenceTranslator() {
        // Drive the SAME subscribe + publishes + ticks through (A) the N=1 coordinator and
        // (B) an independent re-implementation of the single-shard translation. If the
        // frame lists match, the N=1 coalescer is byte-identical to the incumbent veneer.
        WatchTarget full = new WatchTarget(0, EdgeFrame.WATCH_TARGET_FULL, "", false);

        // (A) the coordinator
        setup(1, ALLOW);
        feed(fullCreate(1, WatchCursor.fromNow()));
        buffers[0].publish(put(1, "/k/a", "va"));
        buffers[0].publish(put(2, "/k/b", "vb"));
        sweepAt(1_000L);                 // NOTIFY -> WATCH_EVENT x2
        sweepAt(1_000L);                 // anchor the heartbeat cadence
        sweepAt(1_000L + hb());          // idle -> HEARTBEAT -> coalesced WATCH_PROGRESS
        feed(new EdgeFrame.WatchCancel(1L));
        List<EdgeFrame> coordinatorFrames = List.copyOf(out.sent());

        // (B) the independent reference
        RecordingTransportSink refOut = new RecordingTransportSink();
        FanOutBuffer refBuf = new FanOutBuffer(64);
        FanOutSessionCore[] refHolder = new FanOutSessionCore[1];
        ReferenceSink refSink = new ReferenceSink(refOut, 1L, full, () -> refHolder[0].cursor());
        FanOutSessionCore refCore = new FanOutSessionCore(refBuf, new SnapshotReplaySource(new MutableSnapshot()),
                refSink, FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, new FakeClock(1_000L));
        refHolder[0] = refCore;
        refSink.snapshotOwner = 1L;
        refCore.onSubscribe(new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1")); // FULL from-now, empty
        refBuf.publish(put(1, "/k/a", "va"));
        refBuf.publish(put(2, "/k/b", "vb"));
        refCore.tick(1_000L);
        refCore.tick(1_000L);
        refCore.tick(1_000L + hb());
        refSink.cancel(); // the reference driver's WATCH_CANCEL ack
        List<EdgeFrame> referenceFrames = List.copyOf(refOut.sent());

        assertEquals(referenceFrames, coordinatorFrames,
                "the N=1 coordinator emits frames identical to the independent single-shard translator");
        // And concretely: WATCH_CREATED[ShardMode(0,...)], two WATCH_EVENT(gid=0), one WATCH_PROGRESS.
        assertEquals(1, out.sentOfType(EdgeFrame.WatchCreated.class).size());
        assertEquals(1, out.sentOfType(EdgeFrame.WatchCreated.class).get(0).shards().size());
        assertEquals(0, out.sentOfType(EdgeFrame.WatchCreated.class).get(0).shards().get(0).gid());
        assertEquals(2, out.sentOfType(EdgeFrame.WatchEvent.class).size());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).stream().allMatch(e -> e.gid() == 0));
    }

    // =====================================================================
    // completeness under coverage (a change on shard != 0 IS delivered)
    // =====================================================================

    @Test
    void aChangeOnANonZeroShardIsDeliveredTaggedWithThatGid() {
        setup(3, ALLOW);
        feed(fullCreate(1, WatchCursor.fromNow())); // FULL scatters to all 3 shards
        buffers[2].publish(put(1, "/anything", "v")); // a commit ONLY on shard 2
        sweep();

        List<EdgeFrame.WatchEvent> events = eventsFor(1L);
        assertEquals(1, events.size(), "the shard-2 change is delivered (completeness under coverage)");
        assertEquals(2, events.get(0).gid(), "tagged with the real shard gid=2, not a constant 0");
        assertEquals(1L, events.get(0).s());
    }

    // =====================================================================
    // a not-ready-at-subscribe leg is materialized, never omitted
    // =====================================================================

    @Test
    void emptyShardLegIsMaterializedAndDeliversWhenDataArrives() {
        setup(3, ALLOW);
        buffers[0].publish(put(1, "/x0", "v")); // shards 0 non-empty; shard 1 EMPTY; shard 2 non-empty
        buffers[2].publish(put(1, "/x2", "v"));
        feed(fullCreate(1, WatchCursor.fromNow()));

        EdgeFrame.WatchCreated created = created(1L);
        assertEquals(3, created.shards().size(), "every covered shard has a leg, incl. the empty shard 1");
        assertTrue(created.shards().stream().anyMatch(s -> s.gid() == 1),
                "the not-ready shard leg is present, never omitted");

        // A late write to the previously-empty shard 1 is delivered (the leg was live all along).
        buffers[1].publish(put(1, "/x1", "v"));
        sweep();
        assertTrue(eventsFor(1L).stream().anyMatch(e -> e.gid() == 1),
                "the late write on the initially-empty shard is delivered");
    }

    // =====================================================================
    // no silent partial: a lagging shard freezes its component, never truncates
    // =====================================================================

    @Test
    void laggingShardComponentFreezesWhileServerNowAdvancesAndSiblingsDeliver() {
        setup(2, ALLOW);
        feed(fullCreate(1, WatchCursor.fromNow()));
        sweepAt(1_000L); // anchor cadence (both idle)

        // Shard 0 advances; shard 1 is frozen (no commits ever). Drive two heartbeat windows.
        buffers[0].publish(put(1, "/a", "v1"));
        sweepAt(1_000L + hb());
        WatchCursor first = progressFor(1L).get(progressFor(1L).size() - 1).cursor();

        buffers[0].publish(put(2, "/a", "v2"));
        sweepAt(1_000L + 3 * hb());
        List<EdgeFrame.WatchProgress> progress = progressFor(1L);
        WatchCursor last = progress.get(progress.size() - 1).cursor();

        long s0First = component(first, 0);
        long s0Last = component(last, 0);
        long s1First = component(first, 1);
        long s1Last = component(last, 1);
        assertTrue(s0Last > s0First, "the healthy shard-0 component advances (" + s0First + " -> " + s0Last + ")");
        assertEquals(s1First, s1Last, "the lagging shard-1 component FREEZES (never truncated, never faked)");
        assertEquals(0L, s1Last, "shard 1 never committed, so its frontier stays at 0");
        // server_now advanced across the frozen window => quiet distinguishable from lagging.
        assertTrue(progress.get(progress.size() - 1).serverNowMillis()
                > progress.get(0).serverNowMillis(), "server_now advances while shard 1 is frozen");
        // Shard-0 events delivered; shard 1 delivered nothing but was never dropped/truncated.
        assertTrue(eventsFor(1L).stream().allMatch(e -> e.gid() == 0));
        assertFalse(teardowns.contains(ErrorCode.GAP_UNRECOVERABLE), "a frozen shard is never a truncation");
    }

    // =====================================================================
    // per-shard order preserved through the merge
    // =====================================================================

    @Test
    void eachPerShardSubstreamIsContiguousAscendingInS() {
        setup(2, ALLOW);
        feed(prefixCreate(1, "/app/", WatchCursor.fromNow())); // PREFIX scatters to all shards
        // Interleave publishes across two shards; per-shard S sequences are independent.
        buffers[0].publish(put(1, "/app/a", "a1"));
        buffers[1].publish(put(1, "/app/b", "b1"));
        buffers[0].publish(put(2, "/app/a", "a2"));
        buffers[1].publish(put(2, "/app/b", "b2"));
        buffers[0].publish(put(3, "/app/a", "a3"));
        sweep();

        List<Long> shard0 = eventsFor(1L).stream().filter(e -> e.gid() == 0).map(EdgeFrame.WatchEvent::s).toList();
        List<Long> shard1 = eventsFor(1L).stream().filter(e -> e.gid() == 1).map(EdgeFrame.WatchEvent::s).toList();
        assertEquals(List.of(1L, 2L, 3L), shard0, "shard-0 substream contiguous ascending in S");
        assertEquals(List.of(1L, 2L), shard1, "shard-1 substream contiguous ascending in S");
    }

    // =====================================================================
    // no cross-shard order (the tags separate the substreams; S is per-shard)
    // =====================================================================

    @Test
    void theTagsSeparateSubstreamsAndSIsIncomparableAcrossShards() {
        setup(2, ALLOW);
        feed(prefixCreate(1, "/app/", WatchCursor.fromNow()));
        buffers[0].publish(put(1, "/app/a", "a1"));
        buffers[1].publish(put(1, "/app/b", "b1")); // SAME S=1 on a different shard = unrelated commit
        sweep();

        List<EdgeFrame.WatchEvent> events = eventsFor(1L);
        // Both shards produced an S=1 event: the (gid, S) tag is the ONLY discriminator - S alone is
        // NOT a cross-shard order (shard-0 S=1 and shard-1 S=1 name unrelated commits, W6-2a).
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(e -> e.gid() == 0 && e.s() == 1L));
        assertTrue(events.stream().anyMatch(e -> e.gid() == 1 && e.s() == 1L));
        long distinctGids = events.stream().map(EdgeFrame.WatchEvent::gid).distinct().count();
        assertEquals(2, distinctGids, "the two same-S events are told apart ONLY by their shard gid");
    }

    // =====================================================================
    // resume across a multi-shard reconnect (mixed TAIL / SNAPSHOT_FIRST, max-merge)
    // =====================================================================

    @Test
    void resumeWithPartialVectorMixesTailAndSnapshotPerShard() {
        setup(2, ALLOW, "edge-1", 4); // tiny buffers so shard 0 can evict
        // Shard 0: 1..10 published into a cap-4 buffer => early seqs evicted; resume (0,2) will GAP.
        for (long i = 1; i <= 10; i++) {
            buffers[0].publish(put(i, "/app/a", "v" + i));
        }
        snaps[0].set(10, "/app/a", "v10"); // shard-0 replay floor at seq 10
        // Shard 1: 1..3 retained (cap 4); resume (1,1) is in-window => TAIL from 1.
        for (long i = 1; i <= 3; i++) {
            buffers[1].publish(put(i, "/app/b", "w" + i));
        }
        // Resume vector names both shards: (0,2) behind-buffer, (1,1) in-window.
        WatchCursor resume = new WatchCursor(List.of(
                new WatchCursor.Component(0, 2), new WatchCursor.Component(1, 1)));
        feed(prefixCreate(1, "/app/", resume));

        EdgeFrame.WatchCreated created = created(1L);
        assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, shardMode(created, 0).mode(), "shard 0 behind-buffer => SNAPSHOT_FIRST");
        assertEquals(EdgeFrame.Mode.TAIL, shardMode(created, 1).mode(), "shard 1 in-window => TAIL");

        sweep(); // shard 0 performs its per-(watch,gid) snapshot; shard 1 tails
        assertEquals(0, out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).get(0).gid(),
                "only shard 0 emits a catch-up snapshot substream");
        List<Long> shard1 = eventsFor(1L).stream().filter(e -> e.gid() == 1).map(EdgeFrame.WatchEvent::s).toList();
        assertEquals(List.of(2L, 3L), shard1, "shard 1 tails from its resume component (max-merge never regresses)");
    }

    // =====================================================================
    // one whole-target decision; deny => zero leaks from any shard, whole-watch reject
    // =====================================================================

    @Test
    void deniedWatchLeaksZeroFramesFromAnyShard() {
        setup(3, DENY);
        // Data exists on EVERY shard - a leaking leg would emit a WATCH_EVENT / WATCH_SNAPSHOT.
        buffers[0].publish(put(1, "/x0", "v"));
        buffers[1].publish(put(1, "/x1", "v"));
        buffers[2].publish(put(1, "/x2", "v"));
        feed(fullCreate(1, WatchCursor.fromNow()));
        sweep();
        sweep();

        assertEquals(1, out.sent().size(), "exactly one frame - the whole-watch reject");
        EdgeFrame.WatchCanceled cancel = (EdgeFrame.WatchCanceled) out.sent().get(0);
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancel.code());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty(), "ZERO data frames from ANY shard");
        assertTrue(out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).isEmpty());
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty(), "no leg was even created");
    }

    // =====================================================================
    // a gid-spoofed cursor component fails closed; an in-range-irrelevant one is ignored
    // =====================================================================

    @Test
    void cursorNamingAnOutOfRangeShardIsRejectedBadSubscribe() {
        setup(2, ALLOW);
        // A cursor component naming gid 5 on a 2-shard cluster is unroutable (a foreign deployment).
        WatchCursor spoof = new WatchCursor(List.of(new WatchCursor.Component(5, 1)));
        feed(fullCreate(1, spoof));
        assertEquals(1, out.sent().size());
        assertEquals(ErrorCode.BAD_SUBSCRIBE, ((EdgeFrame.WatchCanceled) out.sent().get(0)).code());
        assertTrue(out.sentOfType(EdgeFrame.WatchCreated.class).isEmpty());
    }

    @Test
    void inRangeButIrrelevantCursorComponentIsIgnoredNotMaterialized() {
        setup(3, ALLOW);
        // A KEY watch on shard 1, but the cursor carries an extra (in-range) component for shard 2.
        // The TARGET sets coverage: the watch covers ONLY shard 1; shard 2's component is ignored.
        WatchCursor extra = new WatchCursor(List.of(
                new WatchCursor.Component(1, 0), new WatchCursor.Component(2, 5)));
        feed(keyCreate(1, "/s1/key", extra));
        EdgeFrame.WatchCreated created = created(1L);
        assertEquals(1, created.shards().size(), "coverage is target-driven: only shard 1, not the spoofed shard 2");
        assertEquals(1, created.shards().get(0).gid());
    }

    @Test
    void keyTargetCarryingFullChainVerifyCoversAllShardsAndDeliversFromEach() {
        setup(3, ALLOW);
        // A KEY target with the full_chain_verify flag matches every key and is root-authorized, so
        // it must cover ALL shards - the coverage vector agrees with matches(), not with the literal
        // path (the coverage/completeness inconsistency this gate prevents).
        feed(new EdgeFrame.WatchCreate(1, 0, EdgeFrame.WATCH_TARGET_KEY,
                "/s1/key".getBytes(StandardCharsets.UTF_8), WatchCursor.fromNow(),
                EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY));
        EdgeFrame.WatchCreated created = created(1L);
        assertArrayEquals(new int[]{0, 1, 2},
                created.shards().stream().mapToInt(EdgeFrame.ShardMode::gid).toArray(),
                "KEY+full_chain_verify seeds and advertises every shard, not just the one its path names");
        // A change on a shard the literal KEY path does not name is still delivered (match-all).
        buffers[2].publish(put(1, "/unrelated/on/shard2", "v"));
        sweep();
        assertTrue(eventsFor(1L).stream().anyMatch(e -> e.gid() == 2),
                "a change on a shard the literal KEY path does not name is delivered (match-all fcv)");
    }

    // =====================================================================
    // resume is a create for authz; revoke-then-resume => NOT_AUTHORIZED, zero frames
    // =====================================================================

    @Test
    void resumeWithAValidCursorStillPassesTheGateAndIsDeniedIfRevoked() {
        setup(2, DENY); // the "revoked" state: the authorizer now denies
        buffers[0].publish(put(1, "/app/a", "v"));
        buffers[1].publish(put(1, "/app/b", "v"));
        // A resume with a perfectly valid old cursor is STILL a create for authz (the cursor is data,
        // never an authz token) - it is denied and leaks nothing.
        WatchCursor resume = new WatchCursor(List.of(
                new WatchCursor.Component(0, 0), new WatchCursor.Component(1, 0)));
        feed(prefixCreate(1, "/app/", resume));
        sweep();
        assertEquals(ErrorCode.NOT_AUTHORIZED, ((EdgeFrame.WatchCanceled) out.sent().get(0)).code());
        assertTrue(out.sentOfType(EdgeFrame.WatchEvent.class).isEmpty(), "a resume cannot skip the gate");
    }

    // =====================================================================
    // revoke mid-stream cuts ALL covered legs atomically (W7-7 generalized to N shards)
    // =====================================================================

    @Test
    void revocationCutsAllShardLegsAtomically() {
        RevocableAuthorizer auth = new RevocableAuthorizer();
        setup(3, auth);
        feed(fullCreate(1, WatchCursor.fromNow())); // covers all 3 shards
        buffers[0].publish(put(1, "/x0", "v"));
        buffers[2].publish(put(1, "/x2", "v"));
        sweep();
        assertFalse(eventsFor(1L).isEmpty(), "the watch streams before revocation");
        out.clear();

        auth.revoke(); // policy version advances AND the verdict flips to deny
        driver.maybeReauthorizeWatches();

        // ONE logical re-check cut the whole watch: a single WATCH_CANCELED(NOT_AUTHORIZED), not N.
        List<EdgeFrame.WatchCanceled> cancels = out.sentOfType(EdgeFrame.WatchCanceled.class);
        assertEquals(1, cancels.size(), "one whole-watch cancel (all N legs share one registry entry)");
        assertEquals(ErrorCode.NOT_AUTHORIZED, cancels.get(0).code());

        // And no shard leg keeps delivering after the cut.
        out.clear();
        buffers[1].publish(put(1, "/x1", "v"));
        sweep();
        assertTrue(eventsFor(1L).isEmpty(), "every shard leg stopped fanning out to the revoked watch");
    }

    // =====================================================================
    // per-shard Gap isolation: one shard SNAPSHOT_FIRSTs, siblings uninterrupted
    // =====================================================================

    @Test
    void midStreamGapOnOneShardResnapshotsOnlyThatShard() {
        setup(2, ALLOW, "edge-1", 4); // tiny buffers
        feed(fullCreate(1, WatchCursor.fromNow())); // both TAIL from now (empty)
        sweep(); // caught up, nothing to deliver

        // Shard 0: publish 10 into the cap-4 buffer WITHOUT a sweep in between => the watch's core-0
        // cursor (0) falls behind eviction => the next sweep GAPs and re-snapshots shard 0 only.
        for (long i = 1; i <= 10; i++) {
            buffers[0].publish(put(i, "/a", "v" + i));
        }
        snaps[0].set(10, "/a", "v10");
        // Shard 1: a couple of in-window commits that must keep tailing uninterrupted.
        buffers[1].publish(put(1, "/b", "w1"));
        buffers[1].publish(put(2, "/b", "w2"));
        sweep();
        sweep(); // let the paced snapshot transfer complete

        assertFalse(out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).isEmpty(), "shard 0 re-snapshots");
        assertTrue(out.sentOfType(EdgeFrame.WatchSnapshotBegin.class).stream().allMatch(b -> b.gid() == 0),
                "ONLY shard 0 emits a catch-up snapshot");
        List<Long> shard1 = eventsFor(1L).stream().filter(e -> e.gid() == 1).map(EdgeFrame.WatchEvent::s).toList();
        assertEquals(List.of(1L, 2L), shard1, "shard 1 tails uninterrupted through shard 0's gap");
    }

    // =====================================================================
    // Re-homed - the W5-7 drained-cursor clamp on the coalesced WATCH_PROGRESS
    // =====================================================================

    @Test
    void watchProgressComponentIsTheDrainedCursorNotTheRawTip() {
        // Shard 0's source runs its latestSeq (10) ahead of what readSince delivers (5). The progress
        // component MUST carry the drained cursor (5), never the raw tip (10) - the W5-7 no-silent-gap
        // clamp, now enforced by the coordinator reading core.cursor().
        this.n = 1;
        this.buffers = new FanOutBuffer[1];
        this.snaps = new MutableSnapshot[]{new MutableSnapshot()};
        Map<Integer, CommitNotificationSource> sources = Map.of(0, new AheadOfDrainSource(10, 5));
        Map<Integer, ReplaySource> replays = Map.of(0, new SnapshotReplaySource(snaps[0]));
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(sources, replays, new int[]{0},
                t -> new int[]{0}, out, FanOutConfig.defaults(), FanOutSessionMetrics.NOOP, clock, gov,
                "edge-1", (c, m) -> teardowns.add(c), ALLOW);
        feed(fullCreate(1, WatchCursor.of(0, 1))); // resume at 1 => TAIL
        sweepAt(2_000L);                            // drains 2..5 => cursor 5 (latestSeq stays 10)
        sweepAt(2_000L + hb());                     // idle => heartbeat => coalesced WATCH_PROGRESS

        EdgeFrame.WatchProgress p = progressFor(1L).get(0);
        assertEquals(1, p.cursor().components().size());
        assertEquals(0, p.cursor().components().get(0).gid());
        assertEquals(5L, p.cursor().components().get(0).s(),
                "WATCH_PROGRESS carries the drained cursor (5), not the raw latestSeq (10)");
    }

    // ---- frame helpers ------------------------------------------------------

    private List<EdgeFrame.WatchEvent> eventsFor(long watchId) {
        return out.sentOfType(EdgeFrame.WatchEvent.class).stream().filter(e -> e.watchId() == watchId).toList();
    }

    private List<EdgeFrame.WatchProgress> progressFor(long watchId) {
        return out.sentOfType(EdgeFrame.WatchProgress.class).stream().filter(p -> p.watchId() == watchId).toList();
    }

    private EdgeFrame.WatchCreated created(long watchId) {
        return out.sentOfType(EdgeFrame.WatchCreated.class).stream()
                .filter(c -> c.watchId() == watchId).findFirst().orElseThrow();
    }

    private static EdgeFrame.ShardMode shardMode(EdgeFrame.WatchCreated created, int gid) {
        return created.shards().stream().filter(s -> s.gid() == gid).findFirst().orElseThrow();
    }

    private static long component(WatchCursor cursor, int gid) {
        return cursor.components().stream().filter(c -> c.gid() == gid).mapToLong(WatchCursor.Component::s)
                .findFirst().orElse(-1L);
    }

    private static long hb() {
        return FanOutConfig.defaults().heartbeatMs();
    }

    // ---- frame builders -----------------------------------------------------

    private static EdgeFrame.WatchCreate fullCreate(long id, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_FULL, new byte[0], cursor, 0);
    }

    private static EdgeFrame.WatchCreate prefixCreate(long id, String path, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_PREFIX,
                path.getBytes(StandardCharsets.UTF_8), cursor, 0);
    }

    private static EdgeFrame.WatchCreate keyCreate(long id, String path, WatchCursor cursor) {
        return new EdgeFrame.WatchCreate(id, 0, EdgeFrame.WATCH_TARGET_KEY,
                path.getBytes(StandardCharsets.UTF_8), cursor, 0);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    // ---- test doubles -------------------------------------------------------

    /** A mutable per-shard snapshot supplier (the replay floor for a shard's catch-up). */
    private static final class MutableSnapshot implements Supplier<ConfigSnapshot> {
        private ConfigSnapshot snap = new ConfigSnapshot(HamtMap.empty(), 0L, 0L);

        void set(long version, String... kv) {
            HamtMap<String, VersionedValue> data = HamtMap.empty();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                data = data.put(kv[i],
                        new VersionedValue(kv[i + 1].getBytes(StandardCharsets.UTF_8), version, 0L));
            }
            this.snap = new ConfigSnapshot(data, version, 0L);
        }

        @Override
        public ConfigSnapshot get() {
            return snap;
        }
    }

    /** An authorizer whose verdict flips to deny and whose policy version advances on {@link #revoke}. */
    private static final class RevocableAuthorizer implements WatchAuthorizer {
        private volatile boolean allowed = true;
        private volatile long version = 1L;

        void revoke() {
            allowed = false;
            version++;
        }

        @Override
        public boolean authorizeWatch(String principal, java.util.Set<String> roles, WatchTarget target) {
            return allowed;
        }

        @Override
        public long policyVersion() {
            return version;
        }
    }

    /** A source whose {@code latestSeq()} deliberately runs ahead of what {@code readSince} delivers. */
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

    /**
     * An independent re-implementation of the single-shard {@link WatchMultiplexSink}
     * translation for ONE drain-owner watch - the differential-oracle reference. It codes the old
     * contract from scratch (SUBSCRIBE_OK -> WATCH_CREATED[ShardMode(0,..)], NOTIFY -> filtered
     * WATCH_EVENT(gid 0), HEARTBEAT -> WATCH_PROGRESS[(0, drainedCursor)], SNAPSHOT_* -> gid 0), so a
     * frame-equality against the N=1 coordinator proves byte-identity against a distinct codepath.
     */
    private static final class ReferenceSink implements TransportSink {
        private final RecordingTransportSink out;
        private final long watchId;
        private final WatchTarget target;
        private final LongSupplier drainedCursor;
        long snapshotOwner;

        ReferenceSink(RecordingTransportSink out, long watchId, WatchTarget target, LongSupplier drainedCursor) {
            this.out = out;
            this.watchId = watchId;
            this.target = target;
            this.drainedCursor = drainedCursor;
        }

        @Override
        public boolean offer(EdgeFrame frame) {
            return switch (frame) {
                case EdgeFrame.SubscribeOk ok -> out.offer(new EdgeFrame.WatchCreated(watchId,
                        List.of(new EdgeFrame.ShardMode(0, Math.max(0L, ok.latestSeq()), ok.mode()))));
                case EdgeFrame.Notify n -> {
                    for (CommitNotification cn : n.notifications()) {
                        List<EdgeFrame.WatchChange> changes = filter(cn);
                        if (!changes.isEmpty()) {
                            out.offer(new EdgeFrame.WatchEvent(watchId, 0, cn.seq(),
                                    cn.commitTimestampMillis(), changes));
                        }
                    }
                    yield true;
                }
                case EdgeFrame.Heartbeat hb -> out.offer(new EdgeFrame.WatchProgress(watchId,
                        WatchCursor.of(0, Math.max(0L, drainedCursor.getAsLong())), hb.serverNowMillis()));
                case EdgeFrame.SnapshotBegin sb -> out.offer(new EdgeFrame.WatchSnapshotBegin(
                        snapshotOwner, 0, sb.snapshotSeq(), sb.chunkCount(), sb.totalBytes()));
                case EdgeFrame.SnapshotChunk sc -> out.offer(new EdgeFrame.WatchSnapshotChunk(
                        snapshotOwner, 0, sc.index(), sc.bytes()));
                case EdgeFrame.SnapshotEnd se -> out.offer(new EdgeFrame.WatchSnapshotEnd(
                        snapshotOwner, 0, se.snapshotSeq()));
                default -> out.offer(frame);
            };
        }

        @Override
        public void close(ErrorCode code, String message) {
            out.offer(new EdgeFrame.WatchCanceled(watchId, code, null, message));
            out.close(code, message);
        }

        void cancel() {
            out.offer(new EdgeFrame.WatchCanceled(watchId, ErrorCode.SERVER_SHUTDOWN, null, "canceled"));
        }

        private List<EdgeFrame.WatchChange> filter(CommitNotification cn) {
            List<EdgeFrame.WatchChange> changes = new ArrayList<>();
            for (ConfigMutation m : cn.delta().mutations()) {
                if (!target.matches(m.key())) {
                    continue;
                }
                if (m instanceof ConfigMutation.Put put) {
                    changes.add(EdgeFrame.WatchChange.put(put.key(), put.valueUnsafe()));
                } else {
                    changes.add(EdgeFrame.WatchChange.delete(m.key()));
                }
            }
            return changes;
        }
    }
}

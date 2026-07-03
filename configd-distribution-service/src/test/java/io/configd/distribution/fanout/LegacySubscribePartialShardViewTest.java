package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
import io.configd.distribution.wire.WatchCursor;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy whole-store SUBSCRIBE refusal at N&gt;1, proven at the driver. The legacy SUBSCRIBE
 * plane is <b>primary-shard-only</b> - it drains {@code cores.get(primaryGid)} alone, so at N&gt;1
 * it is a partial keyspace view. The driver therefore refuses a legacy SUBSCRIBE <b>per
 * connection</b> at N&gt;1 with {@code BAD_SUBSCRIBE} and zero data frames, unless the operator sets
 * {@code allowPartialShardView}. The multi-shard {@code WATCH} plane is complete and is NEVER
 * refused here. At N=1 the refusal never fires (one shard is the whole keyspace) - the flag is
 * never consulted, so the legacy plane is byte-identical to a non-sharded build.
 *
 * <p>Drives {@link FanOutConnectionDriver} over N in-memory {@link FanOutBuffer}s + a recording
 * {@link RecordingTransportSink} - no threads, no I/O - the same harness family as
 * {@link MultiShardCoordinatorTest} and {@link LegacySubscribeAuthzTest}.
 */
class LegacySubscribePartialShardViewTest {

    /** Authorizes WATCH over any target - the SUBSCRIBE half stays default-closed. */
    private static final WatchAuthorizer ALLOW_WATCH = (p, r, t) -> true;

    /** Grants SUBSCRIBE (and WATCH) - used to prove the partial-view refusal fires BEFORE the authz gate. */
    private static WatchAuthorizer grantSubscribe(String... allowed) {
        Set<String> ok = Set.of(allowed);
        return new WatchAuthorizer() {
            @Override
            public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
                return true;
            }

            @Override
            public boolean authorizeSubscribe(String principal, Set<String> roles) {
                return ok.contains(principal);
            }
        };
    }

    private final FakeClock clock = new FakeClock(1_000L);
    private RecordingTransportSink out = new RecordingTransportSink();
    private List<ErrorCode> teardowns = new java.util.ArrayList<>();

    private FanOutBuffer[] buffers;
    private FanOutConnectionDriver driver;

    // ---- harness ------------------------------------------------------------

    private void setup(int shards, boolean allowPartialShardView, WatchAuthorizer auth) {
        this.out = new RecordingTransportSink();       // fresh sink per driver
        this.teardowns = new java.util.ArrayList<>();
        this.buffers = new FanOutBuffer[shards];
        Map<Integer, CommitNotificationSource> sources = new LinkedHashMap<>();
        Map<Integer, ReplaySource> replays = new LinkedHashMap<>();
        int[] gids = new int[shards];
        for (int g = 0; g < shards; g++) {
            buffers[g] = new FanOutBuffer(64);
            sources.put(g, buffers[g]);
            replays.put(g, emptyReplay());
            gids[g] = g;
        }
        int[] allGids = gids;
        // Mirror the production ShardMapResolver: FULL / full_chain_verify scatters to every shard.
        ShardResolver resolver = t -> t.isMatchAll() ? allGids.clone() : new int[]{0};
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        FanOutConfig config = FanOutConfig.defaults().withAllowPartialShardView(allowPartialShardView);
        this.driver = new FanOutConnectionDriver(sources, replays, gids, resolver, out, config,
                FanOutSessionMetrics.NOOP, clock, gov, "edge-1", (c, m) -> teardowns.add(c), auth);
    }

    /** Posts a frame and runs any resulting session command on the test thread. */
    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    // ---- (a) N>1 legacy SUBSCRIBE without the opt-in is refused, zero frames -

    @Test
    void nGreaterThanOneLegacySubscribeWithoutOptInIsRefusedBadSubscribeWithZeroFrames() {
        // The authorizer WOULD grant this subscribe, proving the SPLIT guard - not the authz gate -
        // is what refuses: the teardown is BAD_SUBSCRIBE (the partial-view guard), never NOT_AUTHORIZED.
        setup(3, false, grantSubscribe("edge-1"));
        feed(subscribe());

        assertEquals(List.of(ErrorCode.BAD_SUBSCRIBE), teardowns,
                "a legacy SUBSCRIBE at N>1 without the opt-in is refused BAD_SUBSCRIBE (the partial-view "
                        + "refusal, not the authz gate)");
        assertTrue(out.sent().isEmpty(),
                "zero data frames precede the refusal - not even SUBSCRIBE_OK or a snapshot");
    }

    // ---- (b) N>1 legacy SUBSCRIBE WITH the opt-in is admitted (escape hatch) -

    @Test
    void nGreaterThanOneLegacySubscribeWithOptInIsAdmitted() {
        setup(3, true, grantSubscribe("edge-1"));
        feed(subscribe());

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "with allowPartialShardView the legacy SUBSCRIBE is admitted (primary-only escape hatch)");
        assertFalse(teardowns.contains(ErrorCode.BAD_SUBSCRIBE), "the partial-view guard does not fire");
        assertTrue(teardowns.isEmpty(), "no teardown on an admitted feed");
    }

    // ---- (c) N>1 WATCH is admitted regardless of the flag -------------------

    @Test
    void nGreaterThanOneWatchIsAdmittedAndCoversAllShardsRegardlessOfTheFlag() {
        // The multi-shard WATCH plane is complete; the partial-view flag (OFF here) never gates it.
        setup(3, false, ALLOW_WATCH);
        feed(new EdgeFrame.WatchCreate(1L, 0, EdgeFrame.WATCH_TARGET_FULL, new byte[0],
                WatchCursor.fromNow(), 0));

        List<EdgeFrame.WatchCreated> created = out.sentOfType(EdgeFrame.WatchCreated.class);
        assertEquals(1, created.size(), "the multi-shard WATCH is admitted at N>1 with the flag OFF");
        assertEquals(3, created.get(0).shards().size(),
                "a FULL watch covers all 3 shards (never gated by allowPartialShardView)");
        assertFalse(teardowns.contains(ErrorCode.BAD_SUBSCRIBE),
                "a WATCH is never refused by the partial-view gate");
    }

    // ---- (d) N=1 legacy SUBSCRIBE is byte-identical; the flag is never read --

    @Test
    void nEquals1LegacySubscribeIsByteIdenticalWithTheFlagOffOrOn() {
        // At N=1 (allGids.length==1) the guard's `allGids.length > 1` is false, so the branch is never
        // taken and the flag is never consulted: flag OFF and flag ON emit the identical frame list.
        setup(1, false, grantSubscribe("edge-1"));
        feed(subscribe());
        List<EdgeFrame> flagOff = List.copyOf(out.sent());
        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(), "N=1 flag OFF: admitted");
        assertTrue(teardowns.isEmpty(), "N=1 flag OFF: no teardown");

        // Fresh driver (setup installs a fresh sink), flag ON, everything else identical.
        setup(1, true, grantSubscribe("edge-1"));
        feed(subscribe());
        List<EdgeFrame> flagOn = List.copyOf(out.sent());
        assertTrue(teardowns.isEmpty(), "N=1 flag ON: no teardown");

        assertEquals(flagOff, flagOn,
                "at N=1 the legacy SUBSCRIBE is byte-identical whether allowPartialShardView is off or on");
    }

    // ---- helpers ------------------------------------------------------------

    private static EdgeFrame.Subscribe subscribe() {
        return new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1"); // FULL from-now
    }

    private static ReplaySource emptyReplay() {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), 0L, 0L);
        return new SnapshotReplaySource(() -> snap);
    }
}

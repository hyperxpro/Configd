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

class LegacySubscribePartialShardViewTest {

    private static final WatchAuthorizer ALLOW_WATCH = (p, r, t) -> true;

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
        ShardResolver resolver = t -> t.isMatchAll() ? allGids.clone() : new int[]{0};
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        FanOutConfig config = FanOutConfig.defaults().withAllowPartialShardView(allowPartialShardView);
        this.driver = new FanOutConnectionDriver(sources, replays, gids, resolver,
                WatchCursor.INITIAL_TOPOLOGY_EPOCH, out, config,
                FanOutSessionMetrics.NOOP, clock, gov, "edge-1", (c, m) -> teardowns.add(c), auth);
    }

    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    @Test
    void nGreaterThanOneLegacySubscribeWithoutOptInIsRefusedBadSubscribeWithZeroFrames() {
        setup(3, false, grantSubscribe("edge-1"));
        feed(subscribe());

        assertEquals(List.of(ErrorCode.BAD_SUBSCRIBE), teardowns,
                "a legacy SUBSCRIBE at N>1 without the opt-in is refused BAD_SUBSCRIBE (the partial-view "
                        + "refusal, not the authz gate)");
        assertTrue(out.sent().isEmpty(),
                "zero data frames precede the refusal - not even SUBSCRIBE_OK or a snapshot");
    }

    @Test
    void staleEpochLegacySubscribeIsRefusedStaleTopologyBeforePartialViewGuard() {
        setup(3, false, grantSubscribe("edge-1"));
        feed(new EdgeFrame.Subscribe(true, List.of(), 2L, 0L, -1L, "edge-1", false));
        assertEquals(List.of(ErrorCode.STALE_TOPOLOGY), teardowns,
                "a SUBSCRIBE bound to a superseded epoch is refused STALE_TOPOLOGY, not the partial-view "
                        + "BAD_SUBSCRIBE");
        assertTrue(out.sent().isEmpty(), "zero data frames precede the refusal");
    }

    @Test
    void nGreaterThanOneLegacySubscribeWithOptInIsAdmitted() {
        setup(3, true, grantSubscribe("edge-1"));
        feed(subscribe());

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "with allowPartialShardView the legacy SUBSCRIBE is admitted (primary-only escape hatch)");
        assertFalse(teardowns.contains(ErrorCode.BAD_SUBSCRIBE), "the partial-view guard does not fire");
        assertTrue(teardowns.isEmpty(), "no teardown on an admitted feed");
    }

    @Test
    void nGreaterThanOneWatchIsAdmittedAndCoversAllShardsRegardlessOfTheFlag() {
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

    @Test
    void nEquals1LegacySubscribeIsByteIdenticalWithTheFlagOffOrOn() {
        setup(1, false, grantSubscribe("edge-1"));
        feed(subscribe());
        List<EdgeFrame> flagOff = List.copyOf(out.sent());
        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(), "N=1 flag OFF: admitted");
        assertTrue(teardowns.isEmpty(), "N=1 flag OFF: no teardown");

        setup(1, true, grantSubscribe("edge-1"));
        feed(subscribe());
        List<EdgeFrame> flagOn = List.copyOf(out.sent());
        assertTrue(teardowns.isEmpty(), "N=1 flag ON: no teardown");

        assertEquals(flagOff, flagOn,
                "at N=1 the legacy SUBSCRIBE is byte-identical whether allowPartialShardView is off or on");
    }

    private static EdgeFrame.Subscribe subscribe() {
        return new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-1"); // FULL from-now
    }

    private static ReplaySource emptyReplay() {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), 0L, 0L);
        return new SnapshotReplaySource(() -> snap);
    }
}

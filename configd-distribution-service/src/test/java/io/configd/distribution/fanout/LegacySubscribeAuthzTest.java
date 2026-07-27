package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;
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

class LegacySubscribeAuthzTest {

    private static WatchAuthorizer subscribeGrant(String... allowed) {
        Set<String> ok = Set.of(allowed);
        return new WatchAuthorizer() {
            @Override
            public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
                return false;
            }

            @Override
            public boolean authorizeSubscribe(String principal, Set<String> roles) {
                return ok.contains(principal);
            }
        };
    }

    private static final WatchAuthorizer SUBSCRIBE_BOOM = new WatchAuthorizer() {
        @Override
        public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
            return false;
        }

        @Override
        public boolean authorizeSubscribe(String principal, Set<String> roles) {
            throw new RuntimeException("authorizer blew up");
        }
    };

    private final FakeClock clock = new FakeClock(1_000L);
    private final RecordingTransportSink out = new RecordingTransportSink();
    private final List<ErrorCode> teardowns = new ArrayList<>();

    private FanOutBuffer buffer;
    private FanOutConnectionDriver driver;

    private void setup(WatchAuthorizer auth, String identity) {
        this.buffer = new FanOutBuffer(64);
        ReplaySource replay = snapshotAt(0);
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(buffer, replay, out, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, gov, identity, (c, m) -> teardowns.add(c), auth);
    }

    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    private void tick() {
        driver.session().tick(clock.now());
    }

    @Test
    void denyingAuthorizerTearsDownNotAuthorizedWithZeroDataFrames() {
        setup(subscribeGrant(), "edge-1");
        feed(subscribe("edge-1"));
        tick();

        assertEquals(List.of(ErrorCode.NOT_AUTHORIZED), teardowns,
                "a denied SUBSCRIBE tears down NOT_AUTHORIZED");
        assertTrue(out.sent().isEmpty(),
                "zero outbound frames precede the reject - not even SUBSCRIBE_OK or a snapshot");
    }

    @Test
    void grantingAuthorizerAdmitsAndHydratesExactlyAsBefore() {
        setup(subscribeGrant("edge-1"), "edge-1");
        feed(subscribe("edge-1"));

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "a granted SUBSCRIBE is acknowledged with SUBSCRIBE_OK");
        assertTrue(teardowns.isEmpty(), "no teardown on a granted feed");

        buffer.publish(put(1, "/k/a", "v"));
        tick();
        assertEquals(1, out.sentOfType(EdgeFrame.Notify.class).size(),
                "committed deltas stream as NOTIFY exactly as before the gate");
    }

    @Test
    void throwingAuthorizerFailsClosed() {
        setup(SUBSCRIBE_BOOM, "edge-1");
        feed(subscribe("edge-1"));
        tick();

        assertEquals(List.of(ErrorCode.NOT_AUTHORIZED), teardowns, "any throwable denies (fail-closed)");
        assertTrue(out.sent().isEmpty(), "zero outbound frames on a throwing authorizer");
    }

    @Test
    void nullAuthorizerAdmitsSubscribe() {
        setup(null, "edge-1"); // auth off: no principal model to evaluate
        feed(subscribe("edge-1"));

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "a null authorizer admits the feed (unchanged auth-off behavior)");
        assertTrue(teardowns.isEmpty());
    }

    @Test
    void wireEdgeIdCannotSelfAuthorizeOverMtls() {
        setup(subscribeGrant("admin"), "attacker");
        feed(subscribe("admin"));
        tick();

        assertEquals(List.of(ErrorCode.NOT_AUTHORIZED), teardowns,
                "the wire edgeId cannot self-authorize over mTLS");
        assertTrue(out.sent().isEmpty());
    }

    @Test
    void certPrincipalIsAuthorizedRegardlessOfWireEdgeId() {
        setup(subscribeGrant("admin"), "admin");
        feed(subscribe("ignored-wire-id"));

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "the granted cert principal is admitted; the wire edgeId is irrelevant over mTLS");
        assertTrue(teardowns.isEmpty());
    }

    private static EdgeFrame.Subscribe subscribe(String wireEdgeId) {
        return new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, wireEdgeId);
    }

    private static CommitNotification put(long seq, String key, String val) {
        return new CommitNotification(seq, 1_000L + seq, new ConfigDelta(seq - 1, seq,
                List.of(new ConfigMutation.Put(key, val.getBytes(StandardCharsets.UTF_8)))));
    }

    private static ReplaySource snapshotAt(long version) {
        ConfigSnapshot snap = new ConfigSnapshot(HamtMap.<String, VersionedValue>empty(), version, 0L);
        return new SnapshotReplaySource(() -> snap);
    }
}

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

/**
 * The legacy full-store SUBSCRIBE authorization matrix driven through {@link FanOutConnectionDriver}.
 * A full-store SUBSCRIBE is a streaming read of the whole store, so it is gated at admission on a
 * whole-store READ cover via the {@link WatchAuthorizer} SPI. The gate is fail-closed with one
 * asymmetry from the watch path: a {@code null} authorizer admits the feed (an auth-off deployment
 * has no principal model to evaluate). Uses a real {@link FanOutBuffer} + {@link SnapshotReplaySource},
 * a recording {@link RecordingTransportSink}, a {@link FakeClock}, and lambda / small named
 * authorizers - no threads, no I/O.
 */
class LegacySubscribeAuthzTest {

    /** An authorizer that grants SUBSCRIBE iff the principal is listed; every watch is denied. */
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

    // ---- harness ------------------------------------------------------------

    private void setup(WatchAuthorizer auth, String identity) {
        this.buffer = new FanOutBuffer(64);
        ReplaySource replay = snapshotAt(0);
        SlowConsumerGovernor gov =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), FanOutSessionMetrics.NOOP);
        this.driver = new FanOutConnectionDriver(buffer, replay, out, FanOutConfig.defaults(),
                FanOutSessionMetrics.NOOP, clock, gov, identity, (c, m) -> teardowns.add(c), auth);
    }

    /** Posts a frame and runs the resulting session command on the test (session) thread. */
    private void feed(EdgeFrame frame) {
        driver.onInboundFrame(frame);
        driver.drainInboundCommands();
    }

    private void tick() {
        driver.session().tick(clock.now());
    }

    // ---- the gate -----------------------------------------------------------

    @Test
    void denyingAuthorizerTearsDownNotAuthorizedWithZeroDataFrames() {
        setup(subscribeGrant(), "edge-1"); // grants no one => "edge-1" is denied
        feed(subscribe("edge-1"));
        tick(); // prove no data frame ever materializes, even after a tick

        assertEquals(List.of(ErrorCode.NOT_AUTHORIZED), teardowns,
                "a denied SUBSCRIBE tears down NOT_AUTHORIZED");
        assertTrue(out.sent().isEmpty(),
                "zero outbound frames precede the reject - not even SUBSCRIBE_OK or a snapshot");
    }

    @Test
    void grantingAuthorizerAdmitsAndHydratesExactlyAsBefore() {
        setup(subscribeGrant("edge-1"), "edge-1");
        feed(subscribe("edge-1"));

        // onSubscribe emits SUBSCRIBE_OK synchronously; the feed then streams commits verbatim.
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

    // ---- the check runs on the bound cert identity, not the wire edgeId -----

    @Test
    void wireEdgeIdCannotSelfAuthorizeOverMtls() {
        // The cert principal is "attacker"; the authorizer grants only "admin". The attacker puts the
        // authorized id "admin" in the wire frame, but bindIdentity overrides it with the cert
        // principal, so the gate evaluates "attacker" and denies. The wire-asserted edgeId is ignored.
        setup(subscribeGrant("admin"), "attacker");
        feed(subscribe("admin"));
        tick();

        assertEquals(List.of(ErrorCode.NOT_AUTHORIZED), teardowns,
                "the wire edgeId cannot self-authorize over mTLS");
        assertTrue(out.sent().isEmpty());
    }

    @Test
    void certPrincipalIsAuthorizedRegardlessOfWireEdgeId() {
        // The dual of the negative case: the cert principal "admin" is granted, so the feed is admitted
        // even though the wire frame carries a different, irrelevant edgeId.
        setup(subscribeGrant("admin"), "admin");
        feed(subscribe("ignored-wire-id"));

        assertEquals(1, out.sentOfType(EdgeFrame.SubscribeOk.class).size(),
                "the granted cert principal is admitted; the wire edgeId is irrelevant over mTLS");
        assertTrue(teardowns.isEmpty());
    }

    // ---- helpers ------------------------------------------------------------

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

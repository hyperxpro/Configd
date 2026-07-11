package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialSource;
import io.configd.client.ForbiddenException;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.Watch;
import io.configd.client.edge.WatchEvent;
import io.configd.client.edge.WatchOptions;
import io.configd.client.edge.WatchTarget;
import io.configd.common.Clock;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.EdgeAuthConfig;
import io.configd.server.fanout.EdgeCertGate;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SERVER-OBEYS, the watch-authorization contract (§02 W7): drives the reference {@link Watch} against a live
 * {@link FanOutServer} whose {@link WatchAuthorizer} SPI is scripted, and asserts the veneer obeys the gate --
 * authorize-at-subscription BEFORE any data frame (W5-4 / W7-1), reject an over-broad target rather than
 * silently narrowing it (W7-2), a {@code full_chain_verify} target without root scope is rejected with ZERO
 * {@code NOTIFY} leaked (W7-3, the mandatory negative test), the {@code NOT_AUTHORIZED} 403-class per-watch
 * terminal (W7-5/W7-5a), and bounded revocation on an ACL policy-version advance (W7-7). The
 * authn-then-authz-then-stream order (W8-2) is exercised by the authenticated positive path.
 */
@Timeout(60)
class ServerObeysWatchLifecycleTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final String TOKEN = "s3cr3t-watch-token";
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private FanOutServer server;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState = new AtomicReference<>(ConfigSnapshot.EMPTY);

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @Tag("clause:W5-4")
    @Tag("clause:W7-1..W7-4")
    @Tag("clause:W8-2")
    void authorizedNarrowWatchIsAdmittedThenStreams() throws Exception {
        // The gate authorizes a KEY /allowed watch (READ and WATCH over the whole, narrow target).
        int port = startServer(new ScriptedAuthorizer(t -> "/allowed".equals(t.path())));
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(bearerConfig(port))) {
            Watch watch = client.watch(WatchTarget.key("/allowed"), WatchOptions.defaults());
            List<WatchEvent> events = subscribe(watch);
            // authn (mTLS/bearer handshake), then authz (subscription), then stream: WATCH_CREATED arrives only
            // after the gate authorized the subscription (W8-2 / W5-4).
            watch.awaitCreated(Duration.ofSeconds(20));
            publish(1, "/allowed", "v");
            await("the authorized watch tailed its key from the real server", () -> countChanges(events) >= 1);
            await("per-shard cursor advanced to (0,1)", () -> componentS(watch.cursor(), 0) == 1L);
        }
    }

    @Test
    @Tag("clause:W7-1..W7-4")
    @Tag("clause:W7-5_W7-5a")
    void overBroadTargetIsRejectedNotSilentlyNarrowedWithZeroDataFrames() throws Exception {
        // The gate authorizes only the exact key /data/pub; a FULL watch extends far beyond that grant.
        int port = startServer(new ScriptedAuthorizer(
                t -> t.targetKind() == EdgeFrame.WATCH_TARGET_KEY && "/data/pub".equals(t.path())));
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(bearerConfig(port))) {
            Watch watch = client.watch(WatchTarget.full(), WatchOptions.defaults());
            List<WatchEvent> events = subscribe(watch);
            // No WATCH_CREATED / data frame precedes the reject -- awaitCreated throws the 403-class terminal
            // (W7-1: authorize before any payload-bearing frame; NOT_AUTHORIZED (11) is the 403-class code).
            assertThrows(ForbiddenException.class, () -> watch.awaitCreated(Duration.ofSeconds(20)));
            // A key the principal COULD read (/data/pub) is published; a server that SILENTLY NARROWED the FULL
            // target to the authorized subset would deliver it. Zero events proves reject-not-filter (W7-2).
            publish(1, "/data/pub", "v");
            assertNoEvents(events, "an over-broad watch is rejected wholesale, never narrowed to a filtered subset");
        }
    }

    @Test
    @Tag("clause:W7-1..W7-4")
    @Tag("clause:W7-5_W7-5a")
    void fullChainVerifyWithoutRootScopeIsRejectedWithNoChainLeaked() throws Exception {
        // The gate denies any full_chain_verify target lacking a root grant (a non-root fcv watch).
        int port = startServer(new ScriptedAuthorizer(t -> !t.fullChainVerify()));
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(fullChainVerifyConfig(port))) {
            Watch watch = client.watch(WatchTarget.key("/k").with(WatchTarget.Flag.FULL_CHAIN_VERIFY),
                    WatchOptions.defaults());
            List<WatchEvent> events = subscribe(watch);
            assertThrows(ForbiddenException.class, () -> watch.awaitCreated(Duration.ofSeconds(20)));
            // Publish under the requested target; the fcv carrier is the connection-level NOTIFY firehose. Because
            // the reject precedes any data frame, ZERO events are delivered, so ZERO NOTIFY leaks to a non-root
            // principal (W7-3 + the W7-5 mandatory "assert zero NOTIFY" negative case).
            publish(1, "/k", "v");
            assertNoEvents(events, "the verbatim signed chain MUST NOT stream to a principal lacking root scope");
        }
    }

    @Test
    @Tag("clause:W7-7")
    void liveWatchIsForceClosedWithinBoundedLatencyOnPolicyVersionAdvance() throws Exception {
        // Initially authorized; the authorizer is then flipped to deny and its monotonic policy version advanced.
        ScriptedAuthorizer authorizer = new ScriptedAuthorizer(t -> "/allowed".equals(t.path()));
        int port = startServer(authorizer);
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(bearerConfig(port))) {
            Watch watch = client.watch(WatchTarget.key("/allowed"), WatchOptions.defaults());
            List<WatchEvent> events = subscribe(watch);
            watch.awaitCreated(Duration.ofSeconds(20));
            publish(1, "/allowed", "v");
            await("the watch is live before revocation", () -> countChanges(events) >= 1);

            // Revoke the grant and advance the policy version -- the veneer polls the version each session tick.
            authorizer.allow = t -> false;
            authorizer.version.incrementAndGet();
            publish(2, "/allowed", "v2"); // drive a session-loop iteration so the reauthorization tick runs

            // The server re-authorizes every live watch on the version advance and force-closes the now-revoked
            // one with NOT_AUTHORIZED within a bounded latency (W7-7).
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> watch.terminalFuture().get(20, TimeUnit.SECONDS));
            assertInstanceOf(ForbiddenException.class, ee.getCause(),
                    "a revoked live watch is force-closed with the 403-class NOT_AUTHORIZED");
        }
    }

    /** A {@link WatchAuthorizer} whose per-target verdict and monotonic policy version are scripted per test. */
    private static final class ScriptedAuthorizer implements WatchAuthorizer {
        volatile Predicate<io.configd.distribution.fanout.WatchTarget> allow;
        final AtomicLong version = new AtomicLong(0);

        ScriptedAuthorizer(Predicate<io.configd.distribution.fanout.WatchTarget> allow) {
            this.allow = allow;
        }

        @Override
        public boolean authorizeWatch(String principal, Set<String> roles,
                                      io.configd.distribution.fanout.WatchTarget target) {
            return allow.test(target);
        }

        @Override
        public long policyVersion() {
            return version.get();
        }
    }

    private int startServer(WatchAuthorizer authorizer) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        this.buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor = new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        server = new FanOutServer(
                Map.of(0, buffer),
                Map.of(0, new SnapshotReplaySource(replayState::get)),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, FanOutConfig.defaults(),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                governor, metrics, Clock.system(), authorizer,
                new EdgeAuthConfig(bearerChain(), 16_384, 8_192, Duration.ofHours(1).toMillis()),
                EdgeCertGate.OFF);
        server.start();
        return server.localPort();
    }

    private ConfigdClientConfig bearerConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .credentialSource(CredentialSource.staticBearer(TOKEN))
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
    }

    /** As {@link #bearerConfig} but with a chain verifier, required to arm a full_chain_verify watch. */
    private ConfigdClientConfig fullChainVerifyConfig(int port) throws Exception {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic())
                .credentialSource(CredentialSource.staticBearer(TOKEN))
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
    }

    private static AuthenticatorChain bearerChain() {
        return AuthenticatorChain.build(List.of("bearer"), mapConfig(Map.of(
                "configd.auth.bearer.token", TOKEN,
                "configd.auth.bearer.principal", "edge-watch-svc")));
    }

    private static ConfigSource mapConfig(Map<String, String> m) {
        return new ConfigSource() {
            @Override
            public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }

            @Override
            public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
    }

    private void publish(long to, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(to - 1, to, List.of(new ConfigMutation.Put(key, bytes)));
        HamtMap<String, VersionedValue> data = replayState.get().data().put(key, new VersionedValue(bytes, to, T0));
        replayState.set(new ConfigSnapshot(data, to, T0));
        buffer.publish(new CommitNotification(to, T0, delta));
    }

    private static List<WatchEvent> subscribe(Watch watch) {
        List<WatchEvent> events = new CopyOnWriteArrayList<>();
        watch.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(WatchEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        return events;
    }

    /** Asserts no event is delivered over a short settle window (the reject/leak would show up here). */
    private static void assertNoEvents(List<WatchEvent> events, String why) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (!events.isEmpty()) {
                fail(why + " — but " + events.size() + " event(s) leaked");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(0, countChanges(events), why);
    }

    private static int countChanges(List<WatchEvent> events) {
        return events.stream().mapToInt(e -> e.changes().size()).sum();
    }

    private static long componentS(WatchCursor cursor, int gid) {
        return cursor.components().stream().filter(c -> c.gid() == gid)
                .mapToLong(WatchCursor.Component::s).findFirst().orElse(-1L);
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out awaiting: " + description);
    }
}

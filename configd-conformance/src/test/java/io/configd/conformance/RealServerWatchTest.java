package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialSource;
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
import io.configd.server.fanout.EdgeAuthConfig;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-server conformance for the watch plane: drives the thin reference client's {@link Watch} against a
 * live {@link FanOutServer} (the actual {@code 0x02} WATCH_CREATE->CREATED->EVENT path, per-shard cursor
 * advance, and CURSOR_ACK), not a mock. With static sharding this is one shard (gid 0), so it exercises the
 * vector-native cursor machinery at its degenerate single-component width.
 */
// Server-obeys + client-conforms on the 0x02 watch plane: WATCH_CREATE->CREATED->EVENT with a per-shard cursor
// vector plus CURSOR_ACK (from-now tail), the shared-connection multiplex where a sibling survives, and the
// loud refuse of a cursored share, all against a live FanOutServer.
@Timeout(60)
@Tag("clause:W1-2")
@Tag("clause:W3-4")
@Tag("clause:W3-7")
@Tag("clause:W6-4")
@Tag("clause:W8-6")
class RealServerWatchTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final String TOKEN = "s3cr3t-watch-token";
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};
    /** Admits every watch (the conformance harness authorizes; the client-side authz is out of scope here). */
    private static final WatchAuthorizer PERMISSIVE = (principal, roles, target) -> true;

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
    void clientWatchTailsRealFanOutServerAndAdvancesCursor() throws Exception {
        int port = startServer();
        ConfigdClientConfig config = ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .credentialSource(CredentialSource.staticBearer(TOKEN)) // watches require a real principal
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();

        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
            Watch watch = client.watch(WatchTarget.full(), WatchOptions.defaults());
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
            watch.awaitCreated(Duration.ofSeconds(20));

            // From-now: publish two fresh commits; the server fans them to the live watch as WATCH_EVENTs.
            publish(1, "/app/color", "green");
            publish(2, "/app/size", "large");

            await("two watch events tailed from the real server", () -> countChanges(events) >= 2);
            await("per-shard cursor advanced to (0,2)", () -> componentS(watch.cursor(), 0) == 2L);
            // Per-key/per-shard ordering: both events are gid 0, so they are ordered by ascending S.
            assertTrue(events.size() >= 2);
            assertTrue(WatchEvent.ordered(events.get(0), events.get(events.size() - 1)));
            assertEquals(0, events.get(0).gid());
        }
    }

    @Test
    void twoFromNowWatchesShareOneRealConnection() throws Exception {
        int port = startServer();
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(port))) {
            Watch host = client.watch(WatchTarget.full(), WatchOptions.defaults());
            List<WatchEvent> hostEvents = subscribe(host);
            host.awaitCreated(Duration.ofSeconds(20));

            Watch shared = client.watch(WatchTarget.full(), WatchOptions.defaults().shareConnectionOf(host));
            List<WatchEvent> sharedEvents = subscribe(shared);
            shared.awaitCreated(Duration.ofSeconds(20));

            publish(1, "/multi/key", "v");
            // The real server fans the commit to BOTH watch_ids over the ONE shared connection.
            await("host watch received the commit", () -> countChanges(hostEvents) >= 1);
            await("shared watch received the same commit on the same connection", () -> countChanges(sharedEvents) >= 1);
        }
    }

    @Test
    void cursoredShareIsRefusedW8_6a() throws Exception {
        int port = startServer();
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(clientConfig(port))) {
            Watch host = client.watch(WatchTarget.full(), WatchOptions.defaults());
            host.awaitCreated(Duration.ofSeconds(20));
            // An independently-cursored watch is refused a shared drain (never silently frontier-resumed).
            assertThrows(IllegalStateException.class, () -> client.watch(WatchTarget.full(),
                    WatchOptions.defaults().resume(WatchCursor.of(0, 3)).shareConnectionOf(host)));
        }
    }

    // -----------------------------------------------------------------------

    private ConfigdClientConfig clientConfig(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .credentialSource(CredentialSource.staticBearer(TOKEN))
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();
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

    private int startServer() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        this.buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor = new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        server = new FanOutServer(
                java.util.Map.of(0, buffer),
                java.util.Map.of(0, new SnapshotReplaySource(replayState::get)),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, FanOutConfig.defaults(),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                governor, metrics, Clock.system(), PERMISSIVE,
                new EdgeAuthConfig(bearerChain(), 16_384, 8_192, Duration.ofHours(1).toMillis()),
                EdgeCertGate.OFF);
        server.start();
        return server.localPort();
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

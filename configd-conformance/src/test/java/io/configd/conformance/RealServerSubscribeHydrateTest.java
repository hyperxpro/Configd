package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.Subscription;
import io.configd.client.edge.SubscribeOptions;
import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.observability.MetricsRegistry;
import io.configd.server.fanout.FanOutServer;
import io.configd.server.fanout.RegistryFanOutSessionMetrics;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-server conformance: drives the thin reference client against a live {@link FanOutServer} (the edge data
 * plane), proving the Gate-1 connect path and the Gate-2 subscribe / hydrate / signed-chain-verify / apply /
 * {@code CURSOR_ACK} interoperate with the actual server wire — not just a mock. This is the seed of the
 * Gate-5 conformance suite; the mTLS / token auth-mode interop grows here next.
 */
@Timeout(60)
class RealServerSubscribeHydrateTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final SecureRandom RNG = new SecureRandom();

    private FanOutServer server;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void clientHydratesAndVerifiesAgainstRealFanOutServer() throws Exception {
        KeyPair leader = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        int port = startServer(buffer);

        // The leader publishes a signed, contiguous, monotonically-epoched chain into the fan-out plane.
        publish(buffer, leader, 0, 1, 1, "app/name", "configd");
        publish(buffer, leader, 1, 2, 2, "app/region", "us-east");
        publish(buffer, leader, 2, 3, 3, "app/tier", "gold");

        ConfigdClientConfig config = ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .verifyWith(leader.getPublic())
                .retryPolicy(new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5))
                .limits(longIdle())
                .build();

        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
            Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
            sub.awaitHydrated(Duration.ofSeconds(20));

            await("verified chain applied to version 3", () -> sub.view().currentVersion() == 3);
            assertArrayEquals("configd".getBytes(StandardCharsets.UTF_8), sub.view().get("app/name").orElseThrow());
            assertArrayEquals("us-east".getBytes(StandardCharsets.UTF_8), sub.view().get("app/region").orElseThrow());
            assertArrayEquals("gold".getBytes(StandardCharsets.UTF_8), sub.view().get("app/tier").orElseThrow());
            assertEquals(3, sub.view().size());
        }
    }

    // -----------------------------------------------------------------------

    private int startServer(FanOutBuffer buffer) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        server = new FanOutServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null, // plaintext (test-only)
                buffer,
                new SnapshotReplaySource(replayState::get),
                FanOutConfig.defaults(),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                new RegistryFanOutSessionMetrics(registry),
                Clock.system());
        server.start();
        return server.localPort();
    }

    /** Publishes one signed committed mutation: the buffer notification + the cumulative replay snapshot. */
    private void publish(FanOutBuffer buffer, KeyPair leader, long from, long to, long epoch,
                         String key, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = signedPut(leader, from, to, epoch, key, bytes);
        ConfigSnapshot current = replayState.get();
        HamtMap<String, VersionedValue> data = current.data().put(key, new VersionedValue(bytes, to, T0));
        replayState.set(new ConfigSnapshot(data, to, T0));
        buffer.publish(new CommitNotification(to, T0, delta));
    }

    private static ConfigDelta signedPut(KeyPair leader, long from, long to, long epoch, String key, byte[] value)
            throws Exception {
        byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
        RNG.nextBytes(nonce);
        List<ConfigMutation> mutations = List.of(new ConfigMutation.Put(key, value));
        ConfigDelta unsigned = new ConfigDelta(from, to, mutations, null, epoch, nonce);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(leader.getPrivate());
        sig.update(unsigned.signingPayload());
        return new ConfigDelta(from, to, mutations, sig.sign(), epoch, nonce);
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

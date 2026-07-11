package io.configd.conformance;

import io.configd.client.ConfigdClientConfig;
import io.configd.client.CredentialSource;
import io.configd.client.HostileServerLimits;
import io.configd.client.RetryPolicy;
import io.configd.client.edge.ConfigdEdgeClient;
import io.configd.client.edge.Subscription;
import io.configd.client.edge.SubscribeOptions;
import io.configd.client.tls.ClientTls;
import io.configd.common.Clock;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.auth.BasicAuthPasswords;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
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
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;
import io.configd.distribution.wire.WatchCursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real-server coverage of the four auth modes: connect and present the credential against the actual
 * {@code EdgeAuthGateHandler}, then subscribe and hydrate. Bearer and Basic ride the {@code AUTH} frame
 * (pipelined behind which the {@code SUBSCRIBE} is buffered by the real server, §06 F6A-6); mTLS authenticates
 * at the handshake with no frame. (No-auth is proven by {@link RealServerSubscribeHydrateTest}.) Deltas are
 * unsigned here in {@code trustUnverified} mode to isolate the auth path; the signed-chain verify is proven
 * separately.
 */
@Timeout(120)
@Tag("clause:AU2-1")
@Tag("clause:AU2-3")
@Tag("clause:AU2-4")
@Tag("clause:AU3-1")
@Tag("clause:AU3-2")
@Tag("clause:OV6-2")
class RealServerAuthModesTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};
    private static final char[] PASS = "changeit".toCharArray();
    private static final String TOKEN = "s3cr3t-edge-token";

    @TempDir
    static Path certDir;
    private static Path serverKeystore;
    private static Path serverTruststore;
    private static Path clientKeystore;
    private static Path clientTruststore;

    private FanOutServer server;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);

    @BeforeAll
    static void generateCerts() throws Exception {
        serverKeystore = certDir.resolve("server-ks.p12");
        serverTruststore = certDir.resolve("server-ts.p12");
        clientKeystore = certDir.resolve("client-ks.p12");
        clientTruststore = certDir.resolve("client-ts.p12");
        Path serverPem = certDir.resolve("server.pem");
        Path clientPem = certDir.resolve("client.pem");
        genKeyPair(serverKeystore, "server", "CN=localhost,O=configd-conformance", "san=dns:localhost,ip:127.0.0.1");
        genKeyPair(clientKeystore, "client", "CN=edge-client,O=configd-conformance", null);
        exportCert(serverKeystore, "server", serverPem);
        exportCert(clientKeystore, "client", clientPem);
        importCert(clientTruststore, "server", serverPem);  // the client verifies the server
        importCert(serverTruststore, "client", clientPem);  // the server verifies the client cert
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void tokenAuthAgainstRealServer() throws Exception {
        int port = startServer(bearerChain(), null);
        publish(0, 1, "app/token", "ok");
        ConfigdClientConfig config = plaintextClient(port)
                .credentialSource(CredentialSource.staticBearer(TOKEN))
                .build();
        assertHydrates(config, "app/token", "ok");
    }

    @Test
    void basicAuthAgainstRealServer() throws Exception {
        int port = startServer(basicChain("alice", "s3cret".toCharArray()), null);
        publish(0, 1, "app/basic", "ok");
        ConfigdClientConfig config = plaintextClient(port)
                .credentialSource(CredentialSource.basic("alice", "s3cret".toCharArray()))
                .build();
        assertHydrates(config, "app/basic", "ok");
    }

    @Test
    void mtlsAuthAgainstRealServer() throws Exception {
        TlsManager serverTls = new TlsManager(new TlsConfig(serverKeystore, serverKeystore, serverTruststore,
                true, List.of("TLS_AES_256_GCM_SHA384", "TLS_AES_128_GCM_SHA256"), List.of("TLSv1.3"), PASS));
        int port = startServer(mtlsChain(), serverTls);
        publish(0, 1, "app/mtls", "ok");
        ClientTls clientTls = ClientTls.mutualTls(clientKeystore, PASS, clientTruststore, PASS);
        ConfigdClientConfig config = ConfigdClientConfig.builder()
                .endpoint("localhost", port)
                .tls(clientTls)
                .trustUnverified()
                .retryPolicy(fastRetry())
                .limits(longIdle())
                .build();
        assertHydrates(config, "app/mtls", "ok");
    }

    private void assertHydrates(ConfigdClientConfig config, String key, String value) throws Exception {
        try (ConfigdEdgeClient client = ConfigdEdgeClient.open(config)) {
            Subscription sub = client.subscribeFullStore(SubscribeOptions.defaults());
            sub.awaitHydrated(Duration.ofSeconds(30));
            await("authenticated + hydrated key " + key,
                    () -> sub.view().get(key).map(v -> new String(v, StandardCharsets.UTF_8).equals(value))
                            .orElse(false));
            assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), sub.view().get(key).orElseThrow());
        }
    }

    private ConfigdClientConfig.Builder plaintextClient(int port) {
        return ConfigdClientConfig.builder()
                .endpoint("127.0.0.1", port)
                .allowPlaintext(true)
                .trustUnverified()
                .retryPolicy(fastRetry())
                .limits(longIdle());
    }

    private int startServer(AuthenticatorChain chain, TlsManager tls) throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        this.buffer = buffer;
        SlowConsumerGovernor governor = new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        EdgeAuthConfig edgeAuth = new EdgeAuthConfig(chain, 16_384, 8_192, Duration.ofHours(1).toMillis());
        server = new FanOutServer(
                Map.of(0, buffer),
                Map.of(0, new SnapshotReplaySource(replayState::get)),
                new int[]{0}, SINGLE_SHARD, WatchCursor.INITIAL_TOPOLOGY_EPOCH,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                tls, FanOutConfig.defaults(),
                FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                governor, metrics, Clock.system(), null /* authorizer: null admits the full-store SUBSCRIBE */,
                edgeAuth, EdgeCertGate.OFF);
        server.start();
        return server.localPort();
    }

    private FanOutBuffer buffer;

    private void publish(long from, long to, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(from, to, List.of(new ConfigMutation.Put(key, bytes)));
        HamtMap<String, VersionedValue> data = replayState.get().data().put(key, new VersionedValue(bytes, to, T0));
        replayState.set(new ConfigSnapshot(data, to, T0));
        buffer.publish(new CommitNotification(to, T0, delta));
    }

    private static AuthenticatorChain bearerChain() {
        return AuthenticatorChain.build(List.of("bearer"), mapConfig(Map.of(
                "configd.auth.bearer.token", TOKEN,
                "configd.auth.bearer.principal", "edge-token-svc")));
    }

    private static AuthenticatorChain basicChain(String user, char[] password) {
        String entry = user + ":" + BasicAuthPasswords.hash(password) + ":";
        return AuthenticatorChain.build(List.of("basic"), mapConfig(Map.of(
                "configd.auth.basic.users", entry)));
    }

    private static AuthenticatorChain mtlsChain() {
        return AuthenticatorChain.build(List.of("mtls"), mapConfig(Map.of()));
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

    private static RetryPolicy fastRetry() {
        return new RetryPolicy(Duration.ofMillis(10), Duration.ofMillis(100), 5);
    }

    private static HostileServerLimits longIdle() {
        HostileServerLimits d = HostileServerLimits.defaults();
        return new HostileServerLimits(d.maxFrameBytes(), d.connectTimeoutMs(), d.handshakeTimeoutMs(),
                30_000, d.maxSnapshotTotalBytes(), d.maxSnapshotChunks());
    }

    private static void genKeyPair(Path keystore, String alias, String dname, String san) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of(
                "keytool", "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", dname, "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit",
                "-keypass", "changeit"));
        if (san != null) {
            cmd.add("-ext");
            cmd.add(san);
        }
        run(cmd.toArray(new String[0]));
    }

    private static void exportCert(Path keystore, String alias, Path pem) throws Exception {
        run("keytool", "-exportcert", "-alias", alias, "-keystore", keystore.toString(),
                "-storepass", "changeit", "-rfc", "-file", pem.toString());
    }

    private static void importCert(Path truststore, String alias, Path pem) throws Exception {
        run("keytool", "-importcert", "-alias", alias, "-file", pem.toString(),
                "-keystore", truststore.toString(), "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + command[1] + "\n" + out);
        }
    }

    private static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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

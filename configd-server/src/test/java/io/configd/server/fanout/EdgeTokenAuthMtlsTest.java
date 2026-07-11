package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.common.auth.AuthenticatorChain;
import io.configd.common.config.ConfigSource;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.ShardResolver;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.WatchCursor;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * mTLS-on-a-token-edge byte-identity: with token auth CONFIGURED (so the edge is
 * {@code wantClientAuth}), a client that presents a trusted certificate still authenticates at the TLS
 * handshake - it sends NO {@code AUTH} frame, its identity is the cert Subject DN, and NO active token
 * expiry is armed for it. The token frame is purely additive: certificate clients behave exactly as
 * they did on an mTLS-required edge with no token support at all. Proven on both transports.
 */
@Timeout(180)
class EdgeTokenAuthMtlsTest {

    private static final char[] PASS = "changeit".toCharArray();
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path clientKeyStore;
    private static Path trustStore;

    private FanOutEndpoint server;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);
    private long seq;

    @BeforeAll
    static void generateTls() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-edge-token-mtls-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        trustStore = fixtureDir.resolve("ts.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test");
        genKeyPair(clientKeyStore, "client", "CN=edge-cert-client,O=configd-test");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);
        // The server trusts itself (the client validates the server cert against this same store) and
        // the client leaf (so mTLS accepts it).
        importCert(trustStore, "server", serverCert);
        importCert(trustStore, "client", clientCert);
    }

    @AfterAll
    static void deleteTls() throws Exception {
        if (fixtureDir == null) {
            return;
        }
        try (var paths = Files.walk(fixtureDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private void certPathIsHandshakeAuthedWithNoExpiry(boolean netty) throws Exception {
        // A deliberately short token TTL: if the cert path (wrongly) armed the token expiry, the
        // connection would be closed CREDENTIAL_EXPIRED well before the post-TTL publish below.
        int port = startTlsTokenServer(netty, 400L);

        SSLContext clientCtx = clientContext(clientKeyStore, trustStore);
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(10_000);
        sock.startHandshake();

        try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
            // No AUTH frame: the verified client cert authenticated the connection at the handshake.
            edge.subscribeFullStore("wire-claimed-id", 0L);
            EdgeFrame.SubscribeOk ok = (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode(),
                    "a trusted cert client on a token edge subscribes with no AUTH frame");

            // Well past the token TTL - the cert path arms NO active expiry, so a post-TTL publish is
            // still delivered (a wrongly-armed expiry would have closed the connection by now).
            Thread.sleep(3L * 400L);
            publish("after/ttl", "still-alive");
            assertTrue(receivedNotify(edge, "after/ttl"),
                    "the cert connection must survive past the token TTL (no active expiry armed)");
        }
    }

    @Test
    void jdkCertPathIsHandshakeAuthedWithNoExpiry() throws Exception {
        certPathIsHandshakeAuthedWithNoExpiry(false);
    }

    @Test
    void nettyCertPathIsHandshakeAuthedWithNoExpiry() throws Exception {
        certPathIsHandshakeAuthedWithNoExpiry(true);
    }

    private static AuthenticatorChain mtlsAndBearerChain() {
        // A MIXED edge chain: it accepts BOTH a handshake client certificate (mtls) AND a token (bearer).
        // The cert path is authenticated only when 'mtls' is in the chain (a token-only edge must not
        // auto-authenticate a trust-store cert), so this test - which exercises the cert path - configures
        // the mixed chain an operator running a cert-or-token edge would use.
        Map<String, String> m = Map.of(
                "configd.auth.bearer.token", "unused-on-the-cert-path",
                "configd.auth.bearer.principal", "token-principal");
        ConfigSource cfg = new ConfigSource() {
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(m.get(key));
            }
            @Override public Set<String> keysWithPrefix(String prefix) {
                return m.keySet().stream().filter(k -> k.startsWith(prefix)).collect(Collectors.toSet());
            }
        };
        return AuthenticatorChain.build(List.of("mtls", "bearer"), cfg);
    }

    private int startTlsTokenServer(boolean netty, long ttlMs) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), serverKeyStore, trustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TlsManager tlsManager = new TlsManager(serverTls);
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        EdgeAuthConfig edgeAuth = new EdgeAuthConfig(mtlsAndBearerChain(), 16_384, 8_192, ttlMs);
        InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 0);
        Map<Integer, CommitNotificationSource> sources = Map.of(0, buffer);
        Map<Integer, ReplaySource> replays = Map.of(0, new SnapshotReplaySource(replayState::get));
        Clock clock = Clock.system();
        // authorizer = null: authorization is off on this plane, so the cert SUBSCRIBE is admitted - this
        // test isolates the mTLS handshake-auth path (the ACL integration is proven by EdgeTokenAuthTest).
        server = netty
                ? new NettyFanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, tlsManager, FanOutConfig.defaults(),
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, null, edgeAuth, EdgeCertGate.OFF)
                : new FanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, tlsManager, FanOutConfig.defaults(),
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, null, edgeAuth, EdgeCertGate.OFF);
        server.start();
        return server.localPort();
    }

    private void publish(String key, String value) {
        long s = ++seq;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(s - 1, s, List.of(new ConfigMutation.Put(key, bytes)));
        ConfigSnapshot current = replayState.get();
        HamtMap<String, VersionedValue> data = current.data().put(key, new VersionedValue(bytes, s, 0L));
        replayState.set(new ConfigSnapshot(data, s, 0L));
        buffer.publish(new CommitNotification(s, 0L, delta));
    }

    private static EdgeFrame readUntil(EdgeProtocolClient edge, Class<? extends EdgeFrame> type)
            throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for " + type.getSimpleName());
            }
            if (type.isInstance(f)) {
                return f;
            }
        }
        fail("did not receive a " + type.getSimpleName() + " within the deadline");
        return null;
    }

    private static boolean receivedNotify(EdgeProtocolClient edge, String key) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                return false; // closed before the NOTIFY -> the expiry wrongly fired
            }
            if (f instanceof EdgeFrame.Notify n) {
                for (CommitNotification cn : n.notifications()) {
                    for (ConfigMutation m : cn.delta().mutations()) {
                        if (m instanceof ConfigMutation.Put put && put.key().equals(key)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static SSLContext clientContext(Path clientKs, Path trustStorePath) throws Exception {
        KeyStore ks = loadStore(clientKs);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        KeyStore ts = loadStore(trustStorePath);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadStore(Path p) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, PASS);
        }
        return ks;
    }

    private static void genKeyPair(Path keyStore, String alias, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
    }

    private static void exportCert(Path keyStore, String alias, Path certOut) throws Exception {
        runKeytool("keytool", "-exportcert", "-alias", alias,
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-rfc", "-file", certOut.toString());
    }

    private static void importCert(Path trustStorePath, String alias, Path certIn) throws Exception {
        runKeytool("keytool", "-importcert", "-alias", alias, "-file", certIn.toString(),
                "-keystore", trustStorePath.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12", "-noprompt");
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        List<String> cmd = new ArrayList<>(List.of(command));
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", cmd));
    }
}

package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.RevocationPolicy;
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
import io.configd.distribution.wire.ErrorCode;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Gate 5 mid-connection certificate {@code notAfter} enforcement on the pure-mTLS edge, proven on both
 * transports. A SHORT-lived client certificate connects; with {@code enforceCertNotAfter} ON the
 * {@link EdgeCertGate} arms a close at {@code notAfter + leeway} and the connection is torn down
 * {@code CREDENTIAL_EXPIRED} (a reconnect signal - a cert cannot refresh in-band). With enforcement OFF
 * (the default) the connection survives past {@code notAfter} - byte-identical to Gate 3, where the
 * handshake validated {@code notAfter} once, at connect, and never re-checked it.
 *
 * <p>The client leaf is imported into the server truststore as its own trust anchor, so the handshake is
 * not gated on the leaf's own validity window (RFC 5280 does not check an anchor's validity) - this
 * isolates the MID-connection enforcement from the handshake-time check.
 */
@Timeout(150)
class EdgeCertExpiryTest {

    private static final char[] PASS = "changeit".toCharArray();
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path clientKeyStore;
    private static Path trustStore;
    private static String priorFirstFrameDeadline;

    private FanOutEndpoint server;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);
    private long seq;

    @BeforeAll
    static void generateTls() throws Exception {
        // A large first-frame deadline so the enforced test's un-SUBSCRIBEd cert connection is reaped by
        // the cert-expiry tick (the behavior under test), not by the pre-SUBSCRIBE slow-loris deadline.
        priorFirstFrameDeadline = System.setProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP, "120000");

        fixtureDir = Files.createTempDirectory("configd-edge-cert-expiry-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        trustStore = fixtureDir.resolve("ts.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        genServerKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test");
        // A client cert whose notAfter is ~8s from now: startdate = now - (1 day - 8s), validity = 1 day.
        genShortClientKeyPair(clientKeyStore, "client", "CN=edge-cert-client,O=configd-test");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);
        importCert(trustStore, "server", serverCert);
        importCert(trustStore, "client", clientCert);
    }

    @AfterAll
    static void deleteTls() throws Exception {
        if (priorFirstFrameDeadline == null) {
            System.clearProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP);
        } else {
            System.setProperty(FanOutServer.FIRST_FRAME_DEADLINE_PROP, priorFirstFrameDeadline);
        }
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

    // ---- enforcement ON: the connection is closed CREDENTIAL_EXPIRED at notAfter + leeway ----

    private void enforcedCertNotAfterClosesCredentialExpired(boolean netty) throws Exception {
        // enforceCertNotAfter ON, leeway 0 so the close fires at notAfter (a few seconds out, or already
        // past on a later fixture-shared run - either way the terminal close is CREDENTIAL_EXPIRED).
        EdgeCertGate gate = new EdgeCertGate(RevocationPolicy.OFF, null, leewayZero(), true);
        int port = startMtlsServer(netty, gate);
        try (EdgeProtocolClient edge = connectMtls(port)) {
            // Deliberately NO SUBSCRIBE: an un-subscribed session emits nothing, so the cert-expiry
            // terminal close cannot race a heartbeat, and the enlarged first-frame deadline does not reap.
            EdgeFrame.ErrorClose close = (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.CREDENTIAL_EXPIRED, close.code(),
                    "an mTLS cert past notAfter (enforcement ON) is closed CREDENTIAL_EXPIRED");
            assertTrue(close.message() != null && close.message().contains("reconnect"),
                    "the cert-expiry close reason is reconnect-oriented");
        }
    }

    @Test
    void jdkEnforcedCertNotAfterClosesCredentialExpired() throws Exception {
        enforcedCertNotAfterClosesCredentialExpired(false);
    }

    @Test
    void nettyEnforcedCertNotAfterClosesCredentialExpired() throws Exception {
        enforcedCertNotAfterClosesCredentialExpired(true);
    }

    // ---- enforcement OFF (default): the connection survives past notAfter (byte-identical to Gate 3) ----

    private void unenforcedCertSurvivesPastNotAfter(boolean netty) throws Exception {
        int port = startMtlsServer(netty, EdgeCertGate.OFF); // default posture
        try (EdgeProtocolClient edge = connectMtls(port)) {
            edge.subscribeFullStore("edge-cert", 0L);
            readUntil(edge, EdgeFrame.SubscribeOk.class);
            Thread.sleep(9_000L); // past the ~8s client notAfter
            publish("after/notafter", "still-alive");
            assertTrue(receivedNotify(edge, "after/notafter"),
                    "with enforcement OFF the cert connection survives past notAfter (byte-identical)");
        }
    }

    @Test
    void jdkUnenforcedCertSurvivesPastNotAfter() throws Exception {
        unenforcedCertSurvivesPastNotAfter(false);
    }

    @Test
    void nettyUnenforcedCertSurvivesPastNotAfter() throws Exception {
        unenforcedCertSurvivesPastNotAfter(true);
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------

    private static CredentialExpiryPolicy leewayZero() {
        return new CredentialExpiryPolicy(0.20, 30_000L, 300_000L, 0.10, 300_000L, 3_600_000L, 0L);
    }

    private int startMtlsServer(boolean netty, EdgeCertGate certGate) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), serverKeyStore, trustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TlsManager tlsManager = new TlsManager(serverTls);
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 0);
        Map<Integer, CommitNotificationSource> sources = Map.of(0, buffer);
        Map<Integer, ReplaySource> replays = Map.of(0, new SnapshotReplaySource(replayState::get));
        Clock clock = Clock.system();
        // Pure mTLS edge (edgeAuth = null): the cert gate is the only Gate-5 element in play. authorizer =
        // null so the SUBSCRIBE in the OFF test is admitted (auth off on this plane).
        server = netty
                ? new NettyFanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, tlsManager, FanOutConfig.defaults(),
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, null, null, certGate)
                : new FanOutServer(sources, replays, new int[]{0}, SINGLE_SHARD,
                        WatchCursor.INITIAL_TOPOLOGY_EPOCH, bind, tlsManager, FanOutConfig.defaults(),
                        FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES, FanOutServer.DEFAULT_MAX_SESSIONS,
                        governor, metrics, clock, null, null, certGate);
        server.start();
        return server.localPort();
    }

    private EdgeProtocolClient connectMtls(int port) throws Exception {
        SSLContext clientCtx = clientContext(clientKeyStore, trustStore);
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(20_000);
        sock.startHandshake();
        return new EdgeProtocolClient(sock);
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
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
                return false; // closed before the NOTIFY -> enforcement wrongly fired
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

    // ---- TLS material helpers (mirror EdgeTokenAuthMtlsTest) ----

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

    private static void genServerKeyPair(Path keyStore, String alias, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
    }

    /** A client keypair whose {@code notAfter} is ~8s from now: startdate 1 day ago + 8s, validity 1 day. */
    private static void genShortClientKeyPair(Path keyStore, String alias, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-startdate", "-23H-59M-52S", "-validity", "1",
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
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", command));
    }
}

package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.common.auth.CredentialExpiryPolicy;
import io.configd.common.auth.CrlFileRevocationChecker;
import io.configd.common.auth.RevocationMode;
import io.configd.common.auth.RevocationPolicy;
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
import io.configd.store.ConfigSnapshot;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end online revocation of a REAL revoked edge client certificate via the functional CRL-file
 * {@link CrlFileRevocationChecker}. A CA issues a client cert; a CRL revokes its serial. On a pure-mTLS
 * edge:
 * <ul>
 *   <li><b>strict + revoked-in-CRL</b> {@code ->} the connection is REJECTED at admission (no SUBSCRIBE_OK);</li>
 *   <li><b>strict + good (empty CRL)</b> {@code ->} admitted;</li>
 *   <li><b>lax + missing CRL</b> (responder-down analogue) {@code ->} fail-OPEN (admitted) + the
 *       responder-unreachable alarm is logged.</li>
 * </ul>
 * A structural test confirms the Raft inter-node transport carries NO revocation hook, so the interior is
 * exempt by construction (a down responder can never brick consensus).
 */
@Timeout(150)
class EdgeCrlRevocationTest {

    private static final char[] PASS = "changeit".toCharArray();
    private static final ShardResolver SINGLE_SHARD = t -> new int[]{0};

    private static Path dir;
    private static Path serverKs;
    private static Path clientKs;    // CA-signed client cert (the revoked one)
    private static Path trustStore;  // trusts {CA, server}
    private static Path revokedCrl;
    private static Path emptyCrl;

    private FanOutEndpoint server;

    @BeforeAll
    static void generate() throws Exception {
        dir = Files.createTempDirectory("configd-edge-crl-");
        serverKs = dir.resolve("server.p12");
        clientKs = dir.resolve("client.p12");
        trustStore = dir.resolve("ts.p12");
        revokedCrl = dir.resolve("revoked.crl");
        emptyCrl = dir.resolve("empty.crl");
        Path caKs = dir.resolve("ca.p12");
        Path caPem = dir.resolve("ca.pem");
        Path serverPem = dir.resolve("server.pem");
        Path clientCsr = dir.resolve("client.csr");
        Path clientPem = dir.resolve("client.pem");

        // Self-signed server; a CA that issues the client cert.
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost,O=configd-test",
                "-ext", "san=dns:localhost,ip:127.0.0.1", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-validity", "3", "-storetype", "PKCS12",
                "-keystore", serverKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=configd-test-ca,O=configd-test", "-ext", "bc:c",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3",
                "-storetype", "PKCS12", "-keystore", caKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        keytool("-genkeypair", "-alias", "client", "-dname", "CN=edge-crl-client,O=configd-test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-validity", "3",
                "-storetype", "PKCS12", "-keystore", clientKs.toString(), "-storepass", "changeit", "-keypass", "changeit");
        keytool("-certreq", "-alias", "client", "-keystore", clientKs.toString(), "-storepass", "changeit",
                "-file", clientCsr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-infile", clientCsr.toString(), "-outfile", clientPem.toString(), "-rfc", "-validity", "3");
        keytool("-exportcert", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-rfc", "-file", caPem.toString());
        keytool("-exportcert", "-alias", "server", "-keystore", serverKs.toString(), "-storepass", "changeit",
                "-rfc", "-file", serverPem.toString());
        // The client keystore holds the client leaf + its CA issuer (the presented chain).
        keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-keystore", clientKs.toString(),
                "-storepass", "changeit", "-noprompt");
        keytool("-importcert", "-alias", "client", "-file", clientPem.toString(), "-keystore", clientKs.toString(),
                "-storepass", "changeit", "-noprompt");
        // One truststore trusting BOTH the CA (server accepts CA-signed clients) and the server (client trusts it).
        keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-keystore", trustStore.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");
        keytool("-importcert", "-alias", "server", "-file", serverPem.toString(), "-keystore", trustStore.toString(),
                "-storepass", "changeit", "-storetype", "PKCS12", "-noprompt");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(clientKs)) {
            ks.load(in, PASS);
        }
        X509Certificate leaf = (X509Certificate) ks.getCertificate("client");
        keytool("-gencrl", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-id", leaf.getSerialNumber().toString() + ":1", "-file", revokedCrl.toString(), "-validity", "3");
        keytool("-gencrl", "-alias", "ca", "-keystore", caKs.toString(), "-storepass", "changeit",
                "-file", emptyCrl.toString(), "-validity", "3");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
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

    // ---- strict + a cert on the CRL -> rejected (both transports) ----

    private void strictRevokedCertRejected(boolean netty) throws Exception {
        EdgeCertGate gate = new EdgeCertGate(
                new RevocationPolicy(RevocationMode.STRICT, true, 3_000L),
                new CrlFileRevocationChecker(revokedCrl), CredentialExpiryPolicy.DEFAULTS, false);
        int port = startMtlsServer(netty, gate);
        try (EdgeProtocolClient edge = connectMtls(port)) {
            edge.subscribeFullStore("edge-crl", 0L);
            assertTrue(closedWithoutSubscribeOk(edge),
                    "a revoked edge client cert under strict must be rejected (no SUBSCRIBE_OK, connection closed)");
        }
    }

    @Test
    void jdkStrictRevokedCertRejected() throws Exception {
        strictRevokedCertRejected(false);
    }

    @Test
    void nettyStrictRevokedCertRejected() throws Exception {
        strictRevokedCertRejected(true);
    }

    // ---- strict + a good cert (empty CRL) -> admitted ----

    @Test
    void nettyStrictGoodCertAdmitted() throws Exception {
        EdgeCertGate gate = new EdgeCertGate(
                new RevocationPolicy(RevocationMode.STRICT, true, 3_000L),
                new CrlFileRevocationChecker(emptyCrl), CredentialExpiryPolicy.DEFAULTS, false);
        int port = startMtlsServer(true, gate);
        try (EdgeProtocolClient edge = connectMtls(port)) {
            edge.subscribeFullStore("edge-crl", 0L);
            assertNotNull(readUntil(edge, EdgeFrame.SubscribeOk.class),
                    "a good cert (not on the CRL) under strict is admitted");
        }
    }

    // ---- lax + a missing CRL (responder-down analogue) -> fail-open + alarm ----

    @Test
    void nettyLaxMissingCrlFailsOpenAndAlarms() throws Exception {
        Logger gateLog = Logger.getLogger(EdgeCertGate.class.getName());
        AtomicBoolean alarmed = new AtomicBoolean(false);
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()
                        && String.valueOf(r.getMessage()).contains("UNREACHABLE")) {
                    alarmed.set(true);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        gateLog.addHandler(capture);
        try {
            EdgeCertGate gate = new EdgeCertGate(
                    new RevocationPolicy(RevocationMode.LAX, true, 3_000L),
                    new CrlFileRevocationChecker(dir.resolve("nope.crl")), CredentialExpiryPolicy.DEFAULTS, false);
            int port = startMtlsServer(true, gate);
            try (EdgeProtocolClient edge = connectMtls(port)) {
                edge.subscribeFullStore("edge-crl", 0L);
                assertNotNull(readUntil(edge, EdgeFrame.SubscribeOk.class),
                        "lax + an unreachable responder fails OPEN (admitted)");
            }
            assertTrue(alarmed.get(), "lax + unreachable responder must raise the responder-unreachable alarm");
        } finally {
            gateLog.removeHandler(capture);
        }
    }

    // ---- the interior is exempt BY CONSTRUCTION: the Raft transport carries no revocation hook ----

    @Test
    void raftInteriorTransportHasNoRevocationHook() throws Exception {
        Class<?> raft = Class.forName("io.configd.netty.NettyRaftTransport");
        for (Constructor<?> c : raft.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertFalse(mentionsRevocationOrCertGate(p),
                        "the Raft transport constructor must take no revocation/cert-gate parameter: " + p.getName());
            }
        }
        for (Field f : raft.getDeclaredFields()) {
            assertFalse(mentionsRevocationOrCertGate(f.getType()),
                    "the Raft transport must hold no revocation/cert-gate field: " + f.getType().getName());
        }
    }

    private static boolean mentionsRevocationOrCertGate(Class<?> type) {
        String n = type.getName();
        return n.contains("Revocation") || n.contains("EdgeCertGate");
    }

    // -----------------------------------------------------------------------
    // fixtures
    // -----------------------------------------------------------------------

    private int startMtlsServer(boolean netty, EdgeCertGate certGate) throws Exception {
        TlsConfig serverTls = new TlsConfig(
                dir.resolve("server.pem"), serverKs, trustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TlsManager tlsManager = new TlsManager(serverTls);
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buffer = new FanOutBuffer(10_000);
        SlowConsumerGovernor governor =
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics);
        InetSocketAddress bind = new InetSocketAddress("127.0.0.1", 0);
        Map<Integer, CommitNotificationSource> sources = Map.of(0, buffer);
        Map<Integer, ReplaySource> replays = Map.of(0, new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY));
        Clock clock = Clock.system();
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
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(clientKs)) {
            ks.load(in, PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) {
            ts.load(in, PASS);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(15_000);
        sock.startHandshake();
        return new EdgeProtocolClient(sock);
    }

    /** True if the connection closes (EOF / ErrorClose then EOF) without ever delivering a SUBSCRIBE_OK. */
    private static boolean closedWithoutSubscribeOk(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException | RuntimeException e) {
                return true; // reset / decode end -> closed
            }
            if (f == null) {
                return true; // EOF -> closed
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return false; // admitted -> NOT rejected
            }
        }
        return false;
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

    private static void keytool(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "keytool";
        System.arraycopy(args, 0, cmd, 1, args.length);
        int rc = new ProcessBuilder(cmd).redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", cmd));
    }
}

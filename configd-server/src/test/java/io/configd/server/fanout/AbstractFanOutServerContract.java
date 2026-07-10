package io.configd.server.fanout;

import io.configd.common.Clock;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.distribution.fanout.FanOutConfig;
import io.configd.distribution.fanout.FanOutSessionMetrics;
import io.configd.distribution.fanout.SlowConsumerGovernor;
import io.configd.distribution.fanout.SlowConsumerGovernor.ConsumerState;
import io.configd.distribution.fanout.SlowConsumerPolicyConfig;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeFrameCodec;
import io.configd.distribution.wire.ErrorCode;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Transport-equivalence contract for the edge fan-out endpoint. The SAME mTLS admission,
 * mTLS-attack rejection, slow-consumer / quarantine policy, admission-bound, S2-S4 propagation, and
 * protocol-violation behaviour is proven against EVERY {@link FanOutEndpoint} implementation by
 * varying ONLY server construction ({@link #newServer}); the assertions, deadlines, injected clocks,
 * and keytool fixtures are transcribed verbatim from the per-transport JDK tests this folds in
 * ({@code FanOutServerMtlsTest}, {@code FanOutServerMtlsAttackTest}, {@code FanOutServerQuarantineTest},
 * {@code FanOutServerAdmissionBoundTest}, and the {@code FanOutServerIntegrationTest} corruption legs).
 *
 * <h2>Test fixture discipline</h2>
 * The expensive keytool keystore/cert generation (many subprocesses, merged here from the mTLS +
 * mTLS-attack fixtures) is hoisted into one {@code @BeforeAll static} fixture (cached temp dir,
 * {@code @AfterAll} cleanup), which JUnit runs once per concrete subclass and does NOT subject to the
 * class {@link Timeout}. Each test carries a generous method-level {@code @Timeout(120)} for pure hang
 * detection on the throttled 2-vCPU box, never a perf assertion; deadline-polling on socket reads
 * only (no {@code sleep} as synchronization).
 */
@Timeout(120)
abstract class AbstractFanOutServerContract {

    /**
     * Builds the transport under test with the full (governor-bearing) constructor. Concrete
     * subclasses return {@code new FanOutServer(...)} / {@code new NettyFanOutServer(...)}; this is
     * the ONLY construction difference across transports.
     */
    protected abstract FanOutEndpoint newServer(InetSocketAddress bind,
                                                TlsManager tls,
                                                CommitNotificationSource source,
                                                ReplaySource replay,
                                                FanOutConfig config,
                                                int queueFrames,
                                                int maxSessions,
                                                SlowConsumerGovernor governor,
                                                RegistryFanOutSessionMetrics metrics,
                                                Clock clock);

    private static final char[] PASS = "changeit".toCharArray();

    // ---- merged TLS fixture (mTLS + mTLS-attack) ----
    private static Path fixtureDir;
    private static Path serverKeyStore;
    private static Path serverTrustStore;
    private static Path clientKeyStore;    // legit, trusted by the server
    private static Path rogueKeyStore;     // self-signed, NOT trusted by the server
    private static Path expiredKeyStore;   // CA-signed end-entity, validity window already past

    // ---- per-test mutable state (the quarantine legs) ----
    private FanOutEndpoint server;
    private MutableClock clock;
    private SlowConsumerGovernor governor;
    private RecordingGovernorMetrics governorMetrics;
    private FanOutBuffer buffer;
    private final AtomicReference<ConfigSnapshot> replayState =
            new AtomicReference<>(ConfigSnapshot.EMPTY);
    private long seq;
    private final List<Socket> clients = new ArrayList<>();

    @BeforeAll
    static void generateTlsFixture() throws Exception {
        fixtureDir = Files.createTempDirectory("configd-fanout-contract-");
        serverKeyStore = fixtureDir.resolve("server-ks.p12");
        serverTrustStore = fixtureDir.resolve("server-ts.p12");
        clientKeyStore = fixtureDir.resolve("client-ks.p12");
        rogueKeyStore = fixtureDir.resolve("rogue-ks.p12");
        expiredKeyStore = fixtureDir.resolve("expired-ks.p12");
        Path caKeyStore = fixtureDir.resolve("ca-ks.p12");
        Path serverCert = fixtureDir.resolve("server.pem");
        Path clientCert = fixtureDir.resolve("client.pem");
        Path caCert = fixtureDir.resolve("ca.pem");

        // Server cert (SAN localhost so HTTPS-style identity is satisfiable; the edge server does not
        // enable endpoint identification, but matching keeps the fixture clean).
        genKeyPair(serverKeyStore, "server", "CN=localhost,O=configd-test", "-validity", "1");
        // Legit client cert with a distinctive Subject so we can assert the identity binding.
        genKeyPair(clientKeyStore, "client", "CN=edge-client-1,O=configd-test", "-validity", "1");
        exportCert(serverKeyStore, "server", serverCert);
        exportCert(clientKeyStore, "client", clientCert);

        // Rogue client cert - self-signed, NOT imported into the server trust store.
        genKeyPair(rogueKeyStore, "rogue", "CN=rogue-edge,O=attacker", "-validity", "1");

        // CA + CA-signed expired END-ENTITY: a self-signed expired LEAF would be accepted as a trust
        // anchor (RFC 5280 section 6.1 does not check an anchor's own validity), so the CA layer is what
        // makes notAfter enforceable on the leaf.
        genCa(caKeyStore, "CN=configd-test-ca,O=configd-test");
        exportCert(caKeyStore, "ca", caCert);
        genCaSignedExpiredEndEntity(expiredKeyStore, caKeyStore, caCert,
                "CN=edge-expired,O=configd-test");

        // Server trusts itself, the legit client leaf, and the CA (so the expired end-entity's chain
        // validates UP TO the anchor and fails only on validity). The client uses the server trust
        // store for the handshake, so the server cert must be in it too.
        importCert(serverTrustStore, "server", serverCert);
        importCert(serverTrustStore, "client", clientCert);
        importCert(serverTrustStore, "ca", caCert);
    }

    @AfterAll
    static void deleteTlsFixture() throws Exception {
        if (fixtureDir == null) {
            return;
        }
        try (var paths = Files.walk(fixtureDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }

    @AfterEach
    void stopServer() {
        for (Socket s : clients) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        if (server != null) {
            server.close();
        }
    }

    // =======================================================================
    // mTLS admission (folded from FanOutServerMtlsTest)
    // =======================================================================

    @Test
    @Timeout(120)
    void rejectsClientWithNoCertificate() throws Exception {
        int port = startMtlsServer();
        // A client that trusts the server but presents NO client cert -> mTLS must reject.
        // In TLS 1.3 the client's startHandshake() may return before the server's
        // need-client-auth rejection lands, so we assert the connection is UNUSABLE: a SUBSCRIBE
        // never elicits a SUBSCRIBE_OK (the only secure observation), and using it fails.
        assertConnectionRejected(clientContext(null, serverTrustStore), port, null);
    }

    @Test
    @Timeout(120)
    void rejectsClientWithUntrustedCertificate() throws Exception {
        int port = startMtlsServer();
        // The rogue client presents a cert the server does not trust -> connection unusable.
        assertConnectionRejected(clientContext(rogueKeyStore, serverTrustStore), port, null);
    }

    @Test
    @Timeout(120)
    void acceptsTrustedClientAndCompletesSubscribe() throws Exception {
        int port = startMtlsServer();
        // The legit client presents a trusted cert -> handshake succeeds, SUBSCRIBE works.
        SSLContext clientCtx = clientContext(clientKeyStore, serverTrustStore);
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(10_000);
        sock.startHandshake(); // must NOT throw

        try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
            // The wire edgeId is advisory; the server binds the cert Subject DN. We send a
            // deliberately different wire edgeId to confirm acceptance regardless.
            edge.subscribeFullStore("wire-claimed-id", 0L);
            EdgeFrame f = readUntilSubscribeOk(edge);
            assertNotNull(f, "a trusted mTLS client must receive SUBSCRIBE_OK");
            assertTrue(f instanceof EdgeFrame.SubscribeOk);
        }
    }

    // =======================================================================
    // mTLS NEGATIVE / attacks (folded from FanOutServerMtlsAttackTest)
    // =======================================================================

    @Test
    @Timeout(120)
    void plaintextSubscribeIsNeverAcknowledged() throws Exception {
        int port = startMtlsServer();
        // A plain (non-TLS) socket speaking the edge protocol. Against the TLS-only edge port the
        // SUBSCRIBE bytes are a malformed TLS record; the server tears the connection down and never
        // serves SUBSCRIBE_OK. We assert exactly that, with bounded timeouts so a silent drop cannot
        // hang the test.
        boolean rejected = false;
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 3_000)) {
            edge.subscribeFullStore("plaintext-attacker", 0L);
            EdgeFrame f = readUntilSubscribeOk(edge, 3);
            rejected = (f == null); // no SUBSCRIBE_OK -> rejected
        } catch (IOException e) {
            rejected = true; // connection reset by the TLS server -> rejected
        }
        assertTrue(rejected,
                "a plaintext SUBSCRIBE must never receive SUBSCRIBE_OK from the TLS-only edge server");
    }

    @Test
    @Timeout(120)
    void expiredClientCertificateIsRejected() throws Exception {
        int port = startMtlsServer();
        // The expired client is a CA-signed end-entity; the CA is trusted, so the ONLY reason to
        // reject is the dead validity window. Reuses the unusable-connection discipline.
        assertConnectionRejected(clientContext(expiredKeyStore, serverTrustStore), port, null);
    }

    @Test
    @Timeout(120)
    void tlsV12OnlyClientIsRejectedByTheTlsV13OnlyServer() throws Exception {
        int port = startMtlsServer();
        // A fully trusted client credential, but the socket offers ONLY TLSv1.2 against the
        // TLSv1.3-only server -> no common protocol -> handshake fails; nothing downgrades.
        assertConnectionRejected(clientContext(clientKeyStore, serverTrustStore), port,
                new String[]{"TLSv1.2"});
    }

    // =======================================================================
    // Slow-consumer / quarantine policy (folded from FanOutServerQuarantineTest)
    // =======================================================================

    private static final String EDGE_ID = "edge-q";
    private static final long T0 = 1_700_000_000_000L;

    /**
     * queueFrames=2 / 1-notification batches: every 3rd unacked publish is a deterministic
     * queue_overflow demotion. Ack-lag is effectively disabled so the distress reason under test is
     * exactly the bounded-queue overflow.
     */
    private static FanOutConfig tinyQueueConfig() {
        return new FanOutConfig(2, 50, 1, 262_144, 1_000_000L, 250L, 5L, 1_048_576);
    }

    /** demoteLimit=2 -> the second distress demotion quarantines; 60 s cooldown. */
    private static SlowConsumerPolicyConfig policyConfig() {
        return new SlowConsumerPolicyConfig(
                10_000L, 2, 10, 60_000L, 60_000L, 3, 3_600_000L, 3_600_000L, 4_096);
    }

    /**
     * Permissive policy for the propagation test: demote/gap/quarantine limits are effectively
     * unbounded so the no-ack flood demotes -> snapshots -> resumes WITHOUT ever quarantining.
     * With the production-faithful {@code policyConfig()} (demoteLimit=2) the {@code tinyQueueConfig}
     * (queueFrames=2) flood would trip a SECOND overflow demotion -> QUARANTINED -> connection close,
     * racing the snapshot read (a fast runner loses the race: "stream closed while waiting for
     * SnapshotBegin"). Quarantine-on-repeat-demotion is proven by the dedicated quarantine tests; the
     * propagation test isolates the demotion -> snapshot -> resume RECOVERY path.
     */
    private static SlowConsumerPolicyConfig propagationPolicyConfig() {
        return new SlowConsumerPolicyConfig(
                10_000L, 1_000_000, 1_000_000, 60_000L, 60_000L, 1_000_000, 3_600_000L, 3_600_000L, 4_096);
    }

    private int startServer() throws IOException {
        return startServer(policyConfig());
    }

    private int startServer(SlowConsumerPolicyConfig policy) throws IOException {
        clock = new MutableClock(T0);
        governorMetrics = new RecordingGovernorMetrics();
        governor = new SlowConsumerGovernor(policy, governorMetrics);
        buffer = new FanOutBuffer(10_000);
        MetricsRegistry registry = new MetricsRegistry();
        server = newServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, buffer,
                new SnapshotReplaySource(replayState::get),
                tinyQueueConfig(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS, governor,
                new RegistryFanOutSessionMetrics(registry), clock);
        server.start();
        return server.localPort();
    }

    /** Publishes one committed mutation: the buffer notification + the replay snapshot. */
    private void publish(String key, String value) {
        long s = ++seq;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ConfigDelta delta = new ConfigDelta(s - 1, s,
                List.of(new ConfigMutation.Put(key, bytes)));
        // Keep the replay source authoritative at the published seq (as the real store is).
        ConfigSnapshot current = replayState.get();
        HamtMap<String, VersionedValue> data =
                current.data().put(key, new VersionedValue(bytes, s, T0));
        replayState.set(new ConfigSnapshot(data, s, T0));
        buffer.publish(new CommitNotification(s, T0, delta));
    }

    @Test
    void repeatDistressDemotionsDisconnectWithWireCode8ThenRefuseThenForceRebootstrap()
            throws Exception {
        int port = startServer();

        // --- Phase 1: quarantine. Subscribe, never ack; every 3 publishes overflow the
        // 2-frame queue -> demotion. The 2nd demotion trips demoteLimit -> QUARANTINED ->
        // ERROR_CLOSE code 8 + socket close.
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode(), "empty buffer at subscribe → TAIL");

            publish("k/1", "a");
            publish("k/2", "b");
            publish("k/3", "c"); // 3rd unacked frame -> queue_overflow demotion #1
            awaitGovernorState(ConsumerState.CATCHUP, "first demotion feeds the governor");
            // Wire-level sync: wait for the demotion snapshot to COMPLETE before the next
            // burst - the governor flips to CATCHUP at demote time, but the snapshot is
            // taken on the next session tick; publishing earlier would fold the second
            // burst into the first snapshot and no second demotion could ever occur.
            readUntil(edge, EdgeFrame.SnapshotEnd.class);

            publish("k/4", "d");
            publish("k/5", "e");
            publish("k/6", "f"); // -> demotion #2 -> demoteLimit(2) -> QUARANTINED

            // The wire evidence: ERROR_CLOSE QUARANTINED (code 8), then the socket closes.
            // (The best-effort bye can race the writer thread mid-frame - the pre-existing
            // teardown pattern - so a torn final read is tolerated; the governor state and
            // the refusal leg below pin the policy authoritatively either way.)
            boolean sawQuarantineClose = drainUntilQuarantinedOrClosed(edge);
            assertTrue(sawQuarantineClose,
                    "the connection must end after the quarantine verdict");
        }
        awaitGovernorState(ConsumerState.QUARANTINED, "the 2nd distress demotion quarantines");
        assertEquals(1, governorMetrics.quarantines.get(),
                "edge_fanout_quarantines_total must move exactly once");

        // --- Phase 2: reconnect during the cooldown -> REFUSED at SUBSCRIBE with code 8.
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 6L);
            EdgeFrame.ErrorClose refusal =
                    (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.QUARANTINED, refusal.code(),
                    "the refusal must carry wire code 8 (QUARANTINED)");
            assertTrue(refusal.message().contains("refused"),
                    "diagnostic message names the refusal: " + refusal.message());
            assertTrue(drainUntilClosed(edge), "the refused connection must close");
        }
        assertTrue(governorMetrics.reconnectsRefused.get() >= 1,
                "edge_fanout_reconnects_refused_total must count the refusal (C4-3)");
        assertEquals(ConsumerState.QUARANTINED, governor.state(EDGE_ID),
                "a refusal must not mutate the state");

        // --- Phase 3: the cooldown elapses (clock advance, no sleep) -> readmitted with
        // the re-bootstrap FORCED: SNAPSHOT_FIRST despite the high resume cursor.
        clock.advance(60_001);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 999_999L); // bogus-high cursor: must be ignored
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, ok.mode(),
                    "readmission rebinds the cursor to 0 → the C3 decideMode forces the "
                            + "snapshot re-bootstrap (§7 'must re-bootstrap')");
            assertEquals(1, governorMetrics.readmissions.get(),
                    "edge_fanout_readmissions_total must move on the cooldown exit");
            assertEquals(ConsumerState.CATCHUP, governor.state(EDGE_ID));

            // The snapshot lands at the published head (seq 6); acking it resolves
            // CATCHUP -> HEALTHY (the snapshot+resume-ok exit).
            EdgeFrame.SnapshotEnd end =
                    (EdgeFrame.SnapshotEnd) readUntil(edge, EdgeFrame.SnapshotEnd.class);
            assertEquals(6L, end.snapshotSeq(), "the re-bootstrap snapshot is the head");
            edge.cursorAck(end.snapshotSeq());
            awaitGovernorState(ConsumerState.HEALTHY,
                    "ack progress past the snapshot resolves the catch-up");
        }
    }

    /**
     * The C4 sign-off P1 (C4-A) regression leg: the LIVE session loop's time-driven
     * evaluation must actually fire. Before the fix, the eval-cadence sentinel
     * ({@code Long.MIN_VALUE}) was compared by SUBTRACTION - which overflows negative for
     * any real clock value - so {@code governor.evaluate()} never ran on this loop and
     * HEALTHY->SLOW (the section 7 warn tier, CT-27's transition) was unreachable at runtime.
     * This test drives the promotion end-to-end at the server: a subscriber holds its
     * queue at/above warn, the injected clock advances past
     * {@code edge.fanout.policy.queueWarnWindowMs}, and the SESSION LOOP (no direct
     * governor calls from the test) must promote the identity to SLOW and move
     * {@code edge_fanout_slow_transitions_total}.
     */
    @Test
    void sustainedQueueWarnPromotesToSlowOnTheLiveSessionLoop() throws Exception {
        int port = startServer(); // queueWarnWindowMs=10_000; warn threshold = 1 frame

        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 0L);
            readUntil(edge, EdgeFrame.SubscribeOk.class);

            // One unacked frame puts the queue at/above warn (threshold 1 of 2) without
            // ever reaching overflow - pure sustained pressure, no demotion.
            publish("k/warn", "w");
            readUntil(edge, EdgeFrame.Notify.class);
            assertEquals(ConsumerState.HEALTHY, governor.state(EDGE_ID),
                    "the warn window has not elapsed on the frozen clock — not yet SLOW");

            // The window elapses by CLOCK ADVANCE only (no sleeps): the live session
            // loop's next evaluation must promote.
            clock.advance(10_000);
            awaitGovernorState(ConsumerState.SLOW,
                    "the live session loop must run the time-driven evaluation (C4-A)");
            assertEquals(1, governorMetrics.slowTransitions.get(),
                    "edge_fanout_slow_transitions_total must move exactly once");

            // And the SLOW exit also rides the live loop: acking drains the queue below
            // warn -> the pressure edge resolves the identity back to HEALTHY.
            edge.cursorAck(seq);
            awaitGovernorState(ConsumerState.HEALTHY,
                    "ack progress at the live server resolves SLOW");
        }
    }

    /**
     * The CT-30 closing condition at the wire: the {@code quarantineLimit}-th quarantine
     * escalates to UNHEALTHY through a LIVE server - exercising the
     * {@code onDemotionEvent} UNHEALTHY teardown arm (previously only the
     * QUARANTINED half was process-proven), the unhealthy-cooldown refusal at the wire,
     * and the C4-3 automatic readmission after {@code unhealthyCooldownMs}.
     */
    @Test
    void secondQuarantineWithinTheWindowEscalatesToUnhealthyAtTheWire() throws Exception {
        // demoteLimit=1: each overflow demotion quarantines; quarantineLimit=2: the 2nd
        // quarantine inside the hour escalates; unhealthy cooldown 120 s (clock-advanced).
        int port = startServer(new SlowConsumerPolicyConfig(
                10_000L, 1, 10, 60_000L, 60_000L, 2, 3_600_000L, 120_000L, 4_096));

        // --- Quarantine #1: one overflow demotion suffices (demoteLimit=1).
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 0L);
            readUntil(edge, EdgeFrame.SubscribeOk.class);
            publish("u/1", "a");
            publish("u/2", "b");
            publish("u/3", "c"); // 3rd unacked frame -> overflow -> quarantine #1
            drainUntilQuarantinedOrClosed(edge);
        }
        awaitGovernorState(ConsumerState.QUARANTINED, "first quarantine");
        assertEquals(1, governorMetrics.quarantines.get());

        // --- Readmission, then quarantine #2 -> UNHEALTHY through the live teardown arm.
        clock.advance(60_001);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, ok.mode(), "forced re-bootstrap");
            readUntil(edge, EdgeFrame.SnapshotEnd.class); // re-bootstrap completed (sync)

            publish("u/4", "d");
            publish("u/5", "e");
            publish("u/6", "f"); // overflow -> quarantine #2 -> quarantineLimit(2) -> UNHEALTHY
            assertTrue(drainUntilQuarantinedOrClosed(edge),
                    "the UNHEALTHY escalation must disconnect at the wire (code 8 + close)");
        }
        awaitGovernorState(ConsumerState.UNHEALTHY,
                "the 2nd quarantine within the window escalates");
        assertEquals(2, governorMetrics.quarantines.get(),
                "the escalating trip still counts as a quarantine");
        assertEquals(1, governorMetrics.unhealthy.get(),
                "edge_fanout_unhealthy_total must move exactly once (alert-grade)");

        // --- Refused throughout the unhealthy cooldown, with the state named.
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 6L);
            EdgeFrame.ErrorClose refusal =
                    (EdgeFrame.ErrorClose) readUntil(edge, EdgeFrame.ErrorClose.class);
            assertEquals(ErrorCode.QUARANTINED, refusal.code(),
                    "UNHEALTHY shares wire code 8 (closed taxonomy — note deviation 5)");
            assertTrue(refusal.message().contains("UNHEALTHY"),
                    "the diagnostic names the escalated state: " + refusal.message());
            assertTrue(drainUntilClosed(edge));
        }
        assertTrue(governorMetrics.reconnectsRefused.get() >= 1);
        assertEquals(ConsumerState.UNHEALTHY, governor.state(EDGE_ID));

        // --- The unhealthy cooldown ALONE readmits (C4-3), snapshot-first forced.
        clock.advance(120_001);
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge.subscribeFullStore(EDGE_ID, 999_999L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.SNAPSHOT_FIRST, ok.mode(),
                    "post-unhealthy readmission forces the re-bootstrap");
            assertEquals(ConsumerState.CATCHUP, governor.state(EDGE_ID));
            EdgeFrame.SnapshotEnd end =
                    (EdgeFrame.SnapshotEnd) readUntil(edge, EdgeFrame.SnapshotEnd.class);
            edge.cursorAck(end.snapshotSeq());
            awaitGovernorState(ConsumerState.HEALTHY,
                    "the re-bootstrapped edge resolves after the UNHEALTHY episode");
        }
    }

    @Test
    void aDifferentIdentityIsUnaffectedByAnotherIdentitysQuarantine() throws Exception {
        int port = startServer();
        try (EdgeProtocolClient bad = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            bad.subscribeFullStore(EDGE_ID, 0L);
            readUntil(bad, EdgeFrame.SubscribeOk.class);
            // Two distinct overflow cycles (a single burst collapses into one demotion:
            // the post-demotion snapshot covers the whole burst).
            for (int i = 1; i <= 3; i++) {
                publish("k/" + i, "v" + i);
            }
            awaitGovernorState(ConsumerState.CATCHUP, "first demotion");
            readUntil(bad, EdgeFrame.SnapshotEnd.class); // first snapshot completed
            for (int i = 4; i <= 6; i++) {
                publish("k/" + i, "v" + i);
            }
            drainUntilQuarantinedOrClosed(bad);
        }
        awaitGovernorState(ConsumerState.QUARANTINED, "fixture: edge-q quarantined");

        // The policy keys on the subscriber identity: another edge subscribes fine.
        try (EdgeProtocolClient good = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            good.subscribeFullStore("edge-ok", 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(good, EdgeFrame.SubscribeOk.class);
            assertNotNull(ok, "an unrelated identity must be admitted normally");
            assertEquals(ConsumerState.HEALTHY, governor.state("edge-ok"));
        }
    }

    // =======================================================================
    // Admission bound (folded from FanOutServerAdmissionBoundTest)
    // =======================================================================

    @Test
    @Timeout(60)
    void connectionsBeyondMaxSessionsAreRefusedAndCounted() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        FanOutBuffer buf = new FanOutBuffer(16);
        server = newServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, buf,
                new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                2 /* maxSessions */,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics),
                metrics, Clock.system());
        server.start();
        int port = server.localPort();

        // Two half-open connections (never write a frame) occupy the bound.
        clients.add(new Socket(InetAddress.getLoopbackAddress(), port));
        clients.add(new Socket(InetAddress.getLoopbackAddress(), port));

        // The third is admitted at TCP level (backlog) but must be closed by the
        // accept loop without ever being served: its read sees EOF promptly.
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        boolean refusedObserved = false;
        while (System.nanoTime() < deadline && !refusedObserved) {
            try (Socket third = new Socket(InetAddress.getLoopbackAddress(), port)) {
                third.setSoTimeout(5_000);
                try (InputStream in = third.getInputStream()) {
                    refusedObserved = (in.read() == -1); // EOF = closed by the server, nothing served
                }
            } catch (IOException e) {
                refusedObserved = true; // reset-on-close is an equally valid refusal observation
            }
        }
        assertTrue(refusedObserved, "third connection must be refused (EOF/reset), never served");

        long refused = registry.snapshot().metrics()
                .get("edge.fanout.sessions_refused").value();
        assertTrue(refused >= 1, "edge.fanout.sessions_refused must count the refusal, was " + refused);
    }

    @Test
    void nonPositiveMaxSessionsRejectedAtConstruction() {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> newServer(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        null, new FanOutBuffer(16),
                        new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                        FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                        0 /* maxSessions */,
                        new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics),
                        metrics, Clock.system()));
        assertEquals("maxSessions must be positive: 0", e.getMessage());
    }

    // =======================================================================
    // S2-S4 propagation (NEW; the C1 server path end-to-end without ConfigdServer)
    // =======================================================================

    /**
     * The full C1/C2/C3/C4 server path over a direct plaintext {@link FanOutEndpoint} (no
     * {@code ConfigdServer}, no HTTP API): SUBSCRIBE->SUBSCRIBE_OK(TAIL) on an empty buffer; verbatim,
     * strictly-increasing-seq NOTIFY of two committed deltas (version monotonicity / no stale
     * overwrite), each with byte-identical key+value; CURSOR_ACK flow-control; demotion->chunked
     * SNAPSHOT recovery once the edge stops acking under a flood; and resumed TAIL past the snapshot
     * seq. Publishes go through the SAME {@code publish(key,value)} helper the quarantine legs use, so
     * the replay snapshot stays authoritative at the published head and seqs stay monotonic.
     */
    @Test
    void propagationDeliversVerbatimOrderedNotifiesAndRecovers() throws Exception {
        clock = new MutableClock(T0); // unused by a system-Clock server, but keeps state coherent
        governorMetrics = new RecordingGovernorMetrics();
        governor = new SlowConsumerGovernor(propagationPolicyConfig(), governorMetrics);
        buffer = new FanOutBuffer(10_000);
        MetricsRegistry registry = new MetricsRegistry();
        // queueFrames=2 (tiny) so the no-ack flood overflows promptly into a demotion; the default
        // transport queue (64) would need a far larger flood to fill on this slow box.
        server = newServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, buffer,
                new SnapshotReplaySource(replayState::get),
                tinyQueueConfig(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS, governor,
                new RegistryFanOutSessionMetrics(registry), Clock.system());
        server.start();
        int port = server.localPort();

        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 15_000)) {
            // --- SUBSCRIBE -> SUBSCRIBE_OK(TAIL) on an empty buffer ---
            edge.subscribeFullStore("edge-prop", 0L);
            EdgeFrame.SubscribeOk ok =
                    (EdgeFrame.SubscribeOk) readUntil(edge, EdgeFrame.SubscribeOk.class);
            assertEquals(EdgeFrame.Mode.TAIL, ok.mode(), "empty buffer at subscribe → TAIL");

            // --- two committed mutations delivered verbatim, strictly increasing seq ---
            publish("svc/a", "v-a");
            long seqA = expectVerbatimNotify(edge, "svc/a", "v-a", 0L);
            edge.cursorAck(seqA);

            publish("svc/b", "v-b");
            long seqB = expectVerbatimNotify(edge, "svc/b", "v-b", seqA);
            assertTrue(seqB > seqA, "version monotonicity: the second NOTIFY seq must exceed the first");
            edge.cursorAck(seqB);

            // --- STOP acking + flood ~300 publishes -> DEMOTED_TO_CATCHUP then chunked SNAPSHOT ---
            long floodTarget = seqB;
            for (int i = 0; i < 300; i++) {
                publish("flood/" + i, "x" + i);
                floodTarget = seq;
            }
            EdgeFrame.ErrorClose demote = (EdgeFrame.ErrorClose) readUntilDemotionDraining(edge);
            assertEquals(ErrorCode.DEMOTED_TO_CATCHUP, demote.code());

            EdgeFrame.SnapshotBegin begin =
                    (EdgeFrame.SnapshotBegin) readUntil(edge, EdgeFrame.SnapshotBegin.class);
            assertNotNull(begin, "demotion must be followed by SNAPSHOT_BEGIN");
            List<EdgeFrame.SnapshotChunk> chunks = new ArrayList<>();
            EdgeFrame f;
            while (!((f = edge.readFrame()) instanceof EdgeFrame.SnapshotEnd)) {
                if (f instanceof EdgeFrame.SnapshotChunk c) {
                    chunks.add(c);
                }
                if (f == null) {
                    fail("stream ended before SNAPSHOT_END");
                }
            }
            EdgeFrame.SnapshotEnd end = (EdgeFrame.SnapshotEnd) f;
            assertEquals(begin.snapshotSeq(), end.snapshotSeq(), "BEGIN/END snapshot seq must match");
            assertEquals(begin.chunkCount(), chunks.size(), "all announced chunks must arrive");
            // The snapshot is taken at demotion time (when the bounded transport queue filled), a
            // valid committed seq <= the final flood target - NOT necessarily near the end, since the
            // flood continues after the early overflow-demotion.
            assertTrue(end.snapshotSeq() > 0 && end.snapshotSeq() <= floodTarget,
                    "snapshot seq must be a valid committed seq in (0, floodTarget]: " + end.snapshotSeq());

            // --- ack the snapshot point; tailing resumes with a NOTIFY seq > snapshotSeq ---
            edge.cursorAck(end.snapshotSeq());
            publish("after/snap", "post");
            long resumedSeq = collectNotifiedSeqAtLeast(edge, end.snapshotSeq() + 1);
            assertTrue(resumedSeq >= end.snapshotSeq() + 1, "tail resumes after the snapshot");
        }
    }

    // =======================================================================
    // Protocol violations (folded from FanOutServerIntegrationTest, against a direct server)
    // =======================================================================

    @Test
    void garbageFirstFrameClosesWithoutCrashing() throws Exception {
        int port = startPlaintextServer();
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            // A length prefix that declares a valid-size frame but garbage body -> CRC/decode
            // error -> server closes the connection (FRAME_CORRUPT), without dying.
            byte[] garbage = new byte[20];
            garbage[0] = 0x00;
            garbage[1] = 0x00;
            garbage[2] = 0x00;
            garbage[3] = 0x14; // length 20
            for (int i = 4; i < 20; i++) {
                garbage[i] = (byte) 0xEE;
            }
            edge.sendRaw(garbage);
            // The server should close the connection (we read EOF or an ERROR_CLOSE then EOF).
            boolean closed = drainUntilClosed(edge);
            assertTrue(closed, "server must close the connection on a corrupt first frame");
        }

        // The server is still alive - a fresh, well-behaved subscriber still works.
        try (EdgeProtocolClient edge2 = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge2.subscribeFullStore("edge-2", 0L);
            assertNotNull(readUntil(edge2, EdgeFrame.SubscribeOk.class),
                    "server must still serve new subscribers after a bad connection");
        }
    }

    @Test
    void nonSubscribeFirstFrameIsProtocolViolation() throws Exception {
        int port = startPlaintextServer();
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            // Sending CURSOR_ACK before SUBSCRIBE is a protocol violation -> close.
            edge.cursorAck(5);
            assertTrue(drainUntilClosed(edge),
                    "a non-SUBSCRIBE first frame must close the connection");
        }
    }

    @Test
    void unknownWireVersionFirstFrameIsRejectedAndTheServerSurvives() throws Exception {
        int port = startPlaintextServer();
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            // A WELL-FORMED SUBSCRIBE (valid CRC) whose version byte is patched to an UNKNOWN value.
            // Unlike garbageFirstFrame (a corrupt CRC -> FRAME_CORRUPT), this drives the version-
            // validation path: the CRC is recomputed over the patched bytes so the server reaches the
            // version check and rejects it as BAD_WIRE_VERSION, not the CRC check.
            byte[] frame = firstFrameWithVersionByte(
                    new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-badver"), (byte) 0x7F);
            edge.sendRaw(frame);
            // The server must close the connection. Where a transport emits a final ERROR_CLOSE before
            // closing (the JDK reader does even pre-session; the Netty transport only once a session
            // exists - see the cross-pin leg), it MUST carry BAD_WIRE_VERSION, never a misleading code.
            EdgeFrame bye = readErrorCloseOrClosed(edge);
            if (bye != null) {
                assertTrue(bye instanceof EdgeFrame.ErrorClose ec && ec.code() == ErrorCode.BAD_WIRE_VERSION,
                        "an unknown wire version must be rejected as BAD_WIRE_VERSION, got: " + bye);
            }
            assertTrue(drainUntilClosed(edge),
                    "the server must close a connection opened with an unknown wire version");
        }
        // The server survived the malformed-version connection: a fresh well-behaved subscriber works.
        try (EdgeProtocolClient edge2 = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            edge2.subscribeFullStore("edge-after-badver", 0L);
            assertNotNull(readUntil(edge2, EdgeFrame.SubscribeOk.class),
                    "server must still serve new subscribers after an unknown-version connection");
        }
    }

    @Test
    void crossVersionPinnedFrameIsRejectedWithBadWireVersionOverTheWire() throws Exception {
        int port = startPlaintextServer();
        try (EdgeProtocolClient edge = EdgeProtocolClient.connectPlaintext(port, 10_000)) {
            // Open a legitimate 0x01 connection: the SUBSCRIBE pins the connection to 0x01 and creates a
            // session server-side (its SUBSCRIBE_OK confirms the session exists).
            edge.subscribeFullStore("edge-crosspin", 0L);
            assertNotNull(readUntil(edge, EdgeFrame.SubscribeOk.class),
                    "the 0x01 SUBSCRIBE must be accepted (it pins the connection version)");
            // Now send a frame STAMPED 0x02 on the 0x01-pinned connection. The per-connection version pin
            // (W5-11) rejects it as BAD_WIRE_VERSION over the wire - a mixed-version relay cannot feed a
            // 0x02 frame onto a 0x01 connection. With a session established BOTH transports emit a final
            // ERROR_CLOSE before closing, so the documented first-frame-pin code is assertable at the wire.
            edge.sendRaw(EdgeFrameCodec.encode(new EdgeFrame.CursorAck(1L), EdgeFrameCodec.EDGE_WIRE_VERSION_V2));
            EdgeFrame bye = readErrorCloseOrClosed(edge);
            assertNotNull(bye, "a cross-version-pinned frame must draw a final ERROR_CLOSE over the wire");
            assertTrue(bye instanceof EdgeFrame.ErrorClose ec && ec.code() == ErrorCode.BAD_WIRE_VERSION,
                    "a frame stamped with a version other than the connection negotiated must close "
                            + "BAD_WIRE_VERSION, got: " + bye);
            assertTrue(drainUntilClosed(edge), "the server must close the cross-version-pinned connection");
        }
    }

    // -----------------------------------------------------------------------
    // server starters
    // -----------------------------------------------------------------------

    private int startMtlsServer() throws Exception {
        TlsConfig serverTls = new TlsConfig(
                fixtureDir.resolve("server.pem"), serverKeyStore, serverTrustStore,
                true, List.of("TLS_AES_256_GCM_SHA384"), List.of("TLSv1.3"), PASS);
        TlsManager tlsManager = new TlsManager(serverTls);

        FanOutBuffer buf = new FanOutBuffer(10_000);
        ReplaySource replay = new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY);
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);

        server = newServer(
                new InetSocketAddress("127.0.0.1", 0), tlsManager, buf, replay,
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics),
                metrics, Clock.system());
        server.start();
        return server.localPort();
    }

    /** A direct plaintext server with a small buffer + EMPTY snapshot (the protocol-violation legs). */
    private int startPlaintextServer() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        RegistryFanOutSessionMetrics metrics = new RegistryFanOutSessionMetrics(registry);
        server = newServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null /* plaintext */, new FanOutBuffer(16),
                new SnapshotReplaySource(() -> ConfigSnapshot.EMPTY),
                FanOutConfig.defaults(), FanOutServer.DEFAULT_TRANSPORT_QUEUE_FRAMES,
                FanOutServer.DEFAULT_MAX_SESSIONS,
                new SlowConsumerGovernor(SlowConsumerPolicyConfig.defaults(), metrics),
                metrics, Clock.system());
        server.start();
        return server.localPort();
    }

    // -----------------------------------------------------------------------
    // wire helpers (deadline-polling; no sleep-as-sync)
    // -----------------------------------------------------------------------

    /**
     * Asserts a connection from {@code clientCtx} is rejected: the handshake fails OR the connection
     * is unusable (no SUBSCRIBE_OK is ever served). If {@code enabledProtocols} is non-null the socket
     * is restricted to those protocols (the version-downgrade attack). Tolerant of the TLS-1.3 timing
     * where the server's need-client-auth / path-validation rejection surfaces on first I/O rather
     * than at {@code startHandshake()}.
     */
    private void assertConnectionRejected(SSLContext clientCtx, int port, String[] enabledProtocols)
            throws Exception {
        SSLSocket sock = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        if (enabledProtocols != null) {
            sock.setEnabledProtocols(enabledProtocols);
        }
        sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        sock.setSoTimeout(5_000);
        boolean rejected = false;
        try {
            sock.startHandshake();
            // Handshake "succeeded" on the client side - try to use the connection. A rejected
            // client must NOT receive a SUBSCRIBE_OK; the I/O fails or the stream EOFs.
            try (EdgeProtocolClient edge = new EdgeProtocolClient(sock)) {
                edge.subscribeFullStore("rejected", 0L);
                EdgeFrame f = readUntilSubscribeOk(edge, 4);
                rejected = (f == null); // no SUBSCRIBE_OK -> rejected
            }
        } catch (IOException e) {
            rejected = true; // handshake or first-I/O failure -> rejected
        } finally {
            closeQuietly(sock);
        }
        assertTrue(rejected, "mTLS must reject this client (no SUBSCRIBE_OK served)");
    }

    /** Deadline-polling read for SUBSCRIBE_OK (the trusted-accept path); null if none within 20 s. */
    private static EdgeFrame readUntilSubscribeOk(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                return null;
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return f;
            }
        }
        return null;
    }

    /**
     * Reads up to {@code maxFrames} server frames looking for SUBSCRIBE_OK; null if none. Returns null
     * on EOF, SO_TIMEOUT, OR a codec exception: when the server rejects (e.g. responds with a TLS
     * alert record to a plaintext or downgraded client) the bytes the client reads are NOT a valid
     * edge frame, so {@code EdgeFrameCodec} throws - which is, definitionally, "no SUBSCRIBE_OK was
     * served". Catching it here keeps the assertion the secure observation: a SUBSCRIBE_OK is the only
     * thing that proves acceptance.
     */
    private static EdgeFrame readUntilSubscribeOk(EdgeProtocolClient edge, int maxFrames)
            throws IOException {
        for (int i = 0; i < maxFrames; i++) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                return null; // no reply within the SO_TIMEOUT -> not acknowledged
            } catch (EdgeFrameCodec.CodecException e) {
                return null; // server bytes (e.g. a TLS alert) did not decode -> not acknowledged
            }
            if (f == null) {
                return null; // EOF
            }
            if (f instanceof EdgeFrame.SubscribeOk) {
                return f;
            }
        }
        return null;
    }

    /**
     * Reads forward until a NOTIFY carries a Put for {@code key} with byte-identical {@code value} at a
     * seq strictly greater than {@code afterSeq}; returns that seq. Asserts the verbatim key+value and
     * monotonic ordering at the wire (no stale overwrite).
     */
    private static long expectVerbatimNotify(EdgeProtocolClient edge, String key, String value,
                                             long afterSeq) throws IOException {
        byte[] expected = value.getBytes(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed while waiting for a verbatim NOTIFY of " + key);
            }
            if (f instanceof EdgeFrame.Notify n) {
                for (CommitNotification cn : n.notifications()) {
                    for (ConfigMutation m : cn.delta().mutations()) {
                        if (m instanceof ConfigMutation.Put put && put.key().equals(key)) {
                            assertTrue(cn.seq() > afterSeq,
                                    "NOTIFY seq must strictly increase: " + cn.seq() + " <= " + afterSeq);
                            assertArrayEquals(expected, put.value(),
                                    "NOTIFY must carry the verbatim value bytes for " + key);
                            return cn.seq();
                        }
                    }
                }
            }
        }
        fail("did not receive a verbatim NOTIFY for " + key + " within the deadline");
        return -1;
    }

    private void awaitGovernorState(ConsumerState expected, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (governor.state(EDGE_ID) == expected) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L); // 1 ms poll
        }
        fail(what + " — governor state is " + governor.state(EDGE_ID)
                + ", expected " + expected);
    }

    private static EdgeFrame readUntil(EdgeProtocolClient edge,
                                       Class<? extends EdgeFrame> type) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
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

    /** Reads NOTIFY frames, returning the highest seq seen once it reaches {@code target}. */
    private static long collectNotifiedSeqAtLeast(EdgeProtocolClient edge, long target)
            throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        long max = -1;
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed before reaching seq " + target);
            }
            if (f instanceof EdgeFrame.Notify n) {
                for (var cn : n.notifications()) {
                    max = Math.max(max, cn.seq());
                }
                if (max >= target) {
                    return max;
                }
            }
        }
        fail("did not receive NOTIFY up to seq " + target + " (max=" + max + ")");
        return max;
    }

    /** Drains NOTIFY/HEARTBEAT/SNAPSHOT frames (without acking) until a DEMOTED_TO_CATCHUP arrives. */
    private static EdgeFrame readUntilDemotionDraining(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (f == null) {
                fail("stream closed before demotion");
            }
            if (f instanceof EdgeFrame.ErrorClose ec
                    && ec.code() == ErrorCode.DEMOTED_TO_CATCHUP) {
                return ec;
            }
        }
        fail("no DEMOTED_TO_CATCHUP within the deadline");
        return null;
    }

    /**
     * Drains frames until an {@code ERROR_CLOSE(QUARANTINED)} arrives or the stream ends (EOF / reset /
     * torn final frame - the best-effort bye may race the writer). Returns true once the connection
     * demonstrably ended in either form.
     */
    private static boolean drainUntilQuarantinedOrClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException | EdgeFrameCodec.CodecException e) {
                return true; // reset or torn bye - the socket is gone
            }
            if (f == null) {
                return true; // EOF
            }
            if (f instanceof EdgeFrame.ErrorClose ec && ec.code() == ErrorCode.QUARANTINED) {
                return true; // the clean wire evidence: code 8
            }
        }
        return false;
    }

    private static boolean drainUntilClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (edge.readFrame() == null) {
                    return true;
                }
            } catch (java.net.SocketTimeoutException e) {
                // keep polling
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads forward, skipping benign frames (e.g. a HEARTBEAT on an idle subscription), until an
     * {@link EdgeFrame.ErrorClose} arrives (returned) or the stream closes (returns {@code null}).
     */
    private static EdgeFrame readErrorCloseOrClosed(EdgeProtocolClient edge) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            EdgeFrame f;
            try {
                f = edge.readFrame();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return null; // connection reset == closed, no ERROR_CLOSE observed
            }
            if (f == null) {
                return null; // EOF == closed
            }
            if (f instanceof EdgeFrame.ErrorClose) {
                return f;
            }
            // any other frame (a HEARTBEAT etc.) - keep reading toward the close
        }
        return null;
    }

    /**
     * Encodes {@code frame} as a valid {@code 0x01} wire frame, then overwrites the version byte with
     * {@code badVersion} and RECOMPUTES the CRC32C so the frame is well-formed save the version - the
     * server therefore reaches the version check ({@link ErrorCode#BAD_WIRE_VERSION}) rather than failing
     * at the CRC ({@link ErrorCode#FRAME_CORRUPT}). Wire layout:
     * {@code [length:4][version:1][type:1][payload][CRC32C:4]}; the CRC covers {@code [0 .. len-4)}.
     */
    private static byte[] firstFrameWithVersionByte(EdgeFrame frame, byte badVersion) {
        byte[] wire = EdgeFrameCodec.encode(frame);
        wire[4] = badVersion; // the version byte, immediately after the 4-byte length prefix
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(wire, 0, wire.length - EdgeFrameCodec.TRAILER_SIZE);
        int value = (int) crc.getValue();
        int t = wire.length - EdgeFrameCodec.TRAILER_SIZE;
        wire[t] = (byte) (value >>> 24);
        wire[t + 1] = (byte) (value >>> 16);
        wire[t + 2] = (byte) (value >>> 8);
        wire[t + 3] = (byte) value;
        return wire;
    }

    // -----------------------------------------------------------------------
    // SSL context + keystore loading
    // -----------------------------------------------------------------------

    /**
     * Builds an SSLContext with an optional client key store and a trust store. Uses the generic
     * "TLS" protocol so the version-downgrade test can deliberately restrict the SOCKET to TLSv1.2;
     * the server's TLSv1.3-only policy is what must reject it.
     */
    private static SSLContext clientContext(Path clientKs, Path trustStore) throws Exception {
        KeyManagerFactory kmf = null;
        if (clientKs != null) {
            KeyStore ks = loadStore(clientKs);
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PASS);
        }
        KeyStore ts = loadStore(trustStore);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadStore(Path p) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p)) {
            ks.load(in, PASS);
        }
        return ks;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // -----------------------------------------------------------------------
    // keytool fixture builders (merged from the mTLS + mTLS-attack tests)
    // -----------------------------------------------------------------------

    private static void genKeyPair(Path keyStore, String alias, String dname, String... validity)
            throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "keytool", "-genkeypair", "-alias", alias,
                "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA",
                "-dname", dname, "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit"));
        cmd.addAll(List.of(validity));
        runKeytool(cmd.toArray(String[]::new));
    }

    private static void exportCert(Path keyStore, String alias, Path certOut) throws Exception {
        runKeytool("keytool", "-exportcert", "-alias", alias,
                "-keystore", keyStore.toString(), "-storepass", "changeit",
                "-rfc", "-file", certOut.toString());
    }

    /** Generates a long-lived CA keypair (alias {@code ca}, basicConstraints CA:true). */
    private static void genCa(Path caKeyStore, String dname) throws Exception {
        runKeytool("keytool", "-genkeypair", "-alias", "ca",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650", "-dname", dname, "-ext", "bc:c",
                "-storetype", "PKCS12", "-keystore", caKeyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
    }

    /**
     * Builds a keystore (alias {@code expired}) holding a CA-signed end-entity cert whose validity
     * window is already PAST ({@code -gencert -startdate -2d -validity 1}), plus the CA in its chain.
     */
    private static void genCaSignedExpiredEndEntity(Path keyStore, Path caKeyStore, Path caCert,
                                                    String dname) throws Exception {
        Path csr = fixtureDir.resolve("expired.csr");
        Path signed = fixtureDir.resolve("expired-signed.pem");
        runKeytool("keytool", "-genkeypair", "-alias", "expired",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "1", "-dname", dname,
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "changeit", "-keypass", "changeit");
        runKeytool("keytool", "-certreq", "-alias", "expired",
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-file", csr.toString());
        runKeytool("keytool", "-gencert", "-alias", "ca",
                "-keystore", caKeyStore.toString(), "-storepass", "changeit",
                "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc",
                "-startdate", "-2d", "-validity", "1", "-ext", "san=dns:localhost,ip:127.0.0.1");
        runKeytool("keytool", "-importcert", "-alias", "ca", "-file", caCert.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
        runKeytool("keytool", "-importcert", "-alias", "expired", "-file", signed.toString(),
                "-keystore", keyStore.toString(), "-storepass", "changeit", "-noprompt");
    }

    private static void importCert(Path trustStore, String alias, Path certIn) throws Exception {
        runKeytool("keytool", "-importcert", "-alias", alias, "-file", certIn.toString(),
                "-keystore", trustStore.toString(), "-storepass", "changeit",
                "-storetype", "PKCS12", "-noprompt");
    }

    private static void runKeytool(String... command) throws Exception {
        int rc = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start().waitFor();
        assertTrue(rc == 0, "keytool failed: " + String.join(" ", command));
    }

    // -----------------------------------------------------------------------
    // test fixtures (verbatim from FanOutServerQuarantineTest)
    // -----------------------------------------------------------------------

    /** A manually-advanced {@link Clock}: the cooldown elapses by {@link #advance}. */
    private static final class MutableClock implements Clock {
        private final AtomicLong nowMillis;

        MutableClock(long startMillis) {
            this.nowMillis = new AtomicLong(startMillis);
        }

        void advance(long deltaMillis) {
            nowMillis.addAndGet(deltaMillis);
        }

        @Override public long currentTimeMillis() {
            return nowMillis.get();
        }

        @Override public long nanoTime() {
            return nowMillis.get() * 1_000_000L;
        }
    }

    /** Counts the governor's policy series (thread-safe: session threads write them). */
    private static final class RecordingGovernorMetrics implements FanOutSessionMetrics {
        final AtomicInteger slowTransitions = new AtomicInteger();
        final AtomicInteger quarantines = new AtomicInteger();
        final AtomicInteger unhealthy = new AtomicInteger();
        final AtomicInteger reconnectsRefused = new AtomicInteger();
        final AtomicInteger readmissions = new AtomicInteger();

        @Override public void onNotifyBatch(int n, int bytes) { }
        @Override public void onQueueDepth(int depth) { }
        @Override public void onSlowConsumerWarning() { }
        @Override public void onDemotion(String reason) { }
        @Override public void onSnapshotTransfer() { }
        @Override public void onHeartbeat() { }
        @Override public void onSessionClosed(String reason) { }
        @Override public void onSlowTransition() {
            slowTransitions.incrementAndGet();
        }
        @Override public void onQuarantine() {
            quarantines.incrementAndGet();
        }
        @Override public void onUnhealthy() {
            unhealthy.incrementAndGet();
        }
        @Override public void onReconnectRefused() {
            reconnectsRefused.incrementAndGet();
        }
        @Override public void onReadmission() {
            readmissions.incrementAndGet();
        }
    }
}

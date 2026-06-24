package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.edge.EdgeClientCore;
import io.configd.edge.PoisonPillPolicy;
import io.configd.edge.StrongReadKeyClass;
import io.configd.observability.InvariantMonitor;
import io.configd.observability.MetricsRegistry;
import io.configd.observability.PrometheusExporter;
import io.configd.store.ConfigSigner;
import io.configd.transport.TlsConfig;
import io.configd.transport.TlsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * The separately runnable edge node process (C2; the artifact RR-001 indicts the absence
 * of). A thin orchestrator, mirroring {@code ConfigdServer}: all protocol logic lives in
 * {@link EdgeClientCore} (configd-edge-cache — the same engine the simulator drives), the
 * socket shell is {@link EdgeStreamClient}, the read surface is {@link EdgeHttpServer}.
 *
 * <h2>Wiring (ADR-0037/0038/0039)</h2>
 * <ul>
 *   <li>mTLS: the same {@link TlsConfig#mtls}/{@link TlsManager} classes as the control
 *       plane ("consistent with the control plane's" by construction); plaintext when the
 *       TLS triple is absent (test / single-node), matching the server's policy;</li>
 *   <li>signed chain: {@code --verify-key} (Ed25519 public key, X.509/SPKI DER — produced
 *       by {@code io.configd.store.VerifyKeyExporter} from the leader's
 *       {@code signing-key.bin}). With a verifier every delta must verify; without one,
 *       SIGNED deltas are rejected fail-closed (F-0052) — so against a real (signing)
 *       control plane the flag is effectively mandatory;</li>
 *   <li>{@code --data-dir} holds ONLY the SEC-017 {@code epoch.lock} sidecar. Values —
 *       including {@code secure/} — are never written to disk by the edge (RR-098: the
 *       store-everything topology's exfiltration residual is bounded to process memory);</li>
 *   <li>the DISCONNECTED re-bootstrap trigger (CT-06) is REAL as of C3: each transition
 *       INTO DISCONNECTED counts {@code edge_rebootstrap_triggered_total} and invokes
 *       {@link EdgeStreamClient#requestRebootstrap} — tear down the live connection (if
 *       any), cut the backoff short, re-SUBSCRIBE at the current cursor (the server's
 *       TAIL/SNAPSHOT_FIRST decision resolves replay vs re-bootstrap);</li>
 *   <li>ADR-0040 poison pill: bounded apply-failure retries
 *       ({@code --poison-max-retries}) → forced snapshot re-bootstrap → terminal
 *       fail-loud ({@code System.exit}({@value #EXIT_POISON_TERMINAL})).</li>
 * </ul>
 */
public final class EdgeNodeMain {

    /**
     * ADR-0040 terminal fail-loud exit code: the poison-pill policy decided the edge can
     * neither advance nor re-bootstrap. Distinct from the usage/config exit (1) so an
     * operator can tell a poison death from a misconfiguration at a glance.
     */
    static final int EXIT_POISON_TERMINAL = 3;

    private final EdgeNodeConfig config;
    private final MetricsRegistry metricsRegistry;
    private final EdgeNodeMetrics metrics;
    private final EdgeClientCore core;
    private final EdgeStreamClient streamClient;
    private final NettyEdgeHttpServer httpServer;

    private EdgeNodeMain(EdgeNodeConfig config, MetricsRegistry metricsRegistry,
                         EdgeNodeMetrics metrics, EdgeClientCore core,
                         EdgeStreamClient streamClient, NettyEdgeHttpServer httpServer) {
        this.config = config;
        this.metricsRegistry = metricsRegistry;
        this.metrics = metrics;
        this.core = core;
        this.streamClient = streamClient;
        this.httpServer = httpServer;
    }

    /**
     * Builds and starts an edge node from configuration. The TLS context is built from the
     * config's triple via {@link TlsConfig#mtls} (the control plane's empty-store-password
     * policy) when {@link EdgeNodeConfig#tlsEnabled()}.
     */
    public static EdgeNodeMain start(EdgeNodeConfig config) {
        TlsManager tlsManager = null;
        if (config.tlsEnabled()) {
            try {
                tlsManager = new TlsManager(TlsConfig.mtls(
                        config.tlsCertPath(), config.tlsKeyPath(), config.tlsTrustStorePath()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize edge mTLS", e);
            }
        }
        return start(config, tlsManager);
    }

    /**
     * Builds and starts an edge node with an explicit (possibly null = plaintext)
     * {@link TlsManager}. The injectable-TlsManager seam mirrors {@code FanOutServer}'s and
     * exists for test fixtures whose PKCS12 stores carry a non-empty password (keytool
     * cannot produce the empty-password stores {@link TlsConfig#mtls} expects).
     */
    public static EdgeNodeMain start(EdgeNodeConfig config, TlsManager tlsManager) {
        return start(config, tlsManager, null);
    }

    /**
     * Builds and starts an edge node with an explicit (possibly null) {@link TlsManager}
     * and an injectable ADR-0040 terminal action ({@code null} = the production
     * {@code System.exit}({@value #EXIT_POISON_TERMINAL})). The terminal-action seam
     * mirrors the injectable-TlsManager seam: process tests pin the terminal fail-loud
     * path with a recorder instead of killing the test JVM.
     */
    public static EdgeNodeMain start(EdgeNodeConfig config, TlsManager tlsManager,
                                     Runnable terminalAction) {
        try {
            Files.createDirectories(config.dataDir());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create data directory: " + config.dataDir(), e);
        }

        ConfigSigner verifier = null;
        if (config.verifyKeyPath() != null) {
            verifier = new ConfigSigner(loadVerifyKey(config.verifyKeyPath()));
        }

        Clock clock = Clock.system();
        MetricsRegistry registry = new MetricsRegistry();
        // S6/WS-A: JVM/process runtime gauges on the edge process too (runtime board + leak alerts).
        io.configd.observability.JvmMetrics.bind(registry);
        // Fail-open production monitor (the ConfigdServer policy): an invariant violation
        // increments invariant.violation.* and keeps serving — never throws in-process.
        InvariantMonitor invariantMonitor = new InvariantMonitor(registry, false);
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);

        // CT-06 (C3): the DISCONNECTED re-bootstrap orchestration is EdgeStreamClient's
        // requestRebootstrap, composed internally (null = no additional observer hook).
        // ADR-0040: TERMINAL exits the process non-zero — fail loud, never a hot loop.
        Runnable terminal = terminalAction != null ? terminalAction
                : () -> System.exit(EXIT_POISON_TERMINAL);
        EdgeStreamClient streamClient = new EdgeStreamClient(
                config.fanOutEndpoints(), config.edgeId(), config.subscribePrefixes(),
                tlsManager, config.reconnectBackoffMs(), config.heartbeatSilenceFactor(),
                clock, metrics, null, terminal);

        PoisonPillPolicy poisonPolicy = new PoisonPillPolicy(config.poisonMaxRetries(),
                metrics.poisonRetriesCounter(), metrics.poisonPillCounter(),
                metrics.poisonTerminalCounter());
        EdgeClientCore core = new EdgeClientCore(clock, invariantMonitor,
                metrics.implausibleCounter(), StrongReadKeyClass.DEFAULT, streamClient.sink(),
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, config.heartbeatSilenceFactor(),
                verifier, config.dataDir(), poisonPolicy);
        for (String prefix : config.subscribePrefixes()) {
            core.addSubscription(prefix);
        }
        metrics.bind(core);

        // ADR-0043 M1: the edge-read HTTP surface is served by Netty (io_uring→Epoll→NIO selected at
        // start), replacing the JDK HttpServer (EdgeHttpServer). Byte-identical responses (both adapters
        // delegate to EdgeReadHandler) at 8.7× less server-side allocation (docs/netty-migration/).
        // S6/WS-A: publish the edge-read histogram bucket schedule so configd_edge_read_seconds renders
        // the le buckets (0.001 / 0.005) the edge-read burn-rate alert queries.
        NettyEdgeHttpServer httpServer = new NettyEdgeHttpServer(config.apiPort(), core,
                StrongReadKeyClass.DEFAULT,
                new PrometheusExporter(registry,
                        io.configd.observability.ConfigdMetrics.edgeProcessHistogramSchedules()),
                metrics);
        try {
            httpServer.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Interrupted starting edge HTTP server on port " + config.apiPort(), e);
        }
        streamClient.start(core);

        return new EdgeNodeMain(config, registry, metrics, core, streamClient, httpServer);
    }

    /**
     * Loads an Ed25519 public key from an X.509/SubjectPublicKeyInfo DER file (the
     * {@code VerifyKeyExporter} output; equivalently
     * {@code openssl pkey -pubin -inform DER}-compatible bytes).
     */
    static PublicKey loadVerifyKey(Path path) {
        try {
            byte[] der = Files.readAllBytes(path);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load Ed25519 verify key from " + path, e);
        }
    }

    /** Stops the edge node: stream client first (clean socket end), then the HTTP surface. */
    public void shutdown() {
        streamClient.close();
        httpServer.stop();
    }

    /** The actual bound API port (resolves an ephemeral {@code --api-port 0}). */
    public int apiPort() {
        return httpServer.port();
    }

    /** The edge client core (tests / diagnostics; reads are thread-safe). */
    public EdgeClientCore core() {
        return core;
    }

    /** The stream client (tests / diagnostics — the CT-06 re-bootstrap orchestration seam). */
    EdgeStreamClient streamClient() {
        return streamClient;
    }

    /** The process metrics registry (tests / diagnostics). */
    public MetricsRegistry metricsRegistry() {
        return metricsRegistry;
    }

    /** The parsed configuration. */
    public EdgeNodeConfig config() {
        return config;
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: configd-edge-node --edge-id <id> "
                    + "--fanout-endpoints <h:p[,h:p]> --data-dir <path> "
                    + "[--api-port <port>] [--verify-key <path>] "
                    + "[--subscribe-prefix <prefix>]... "
                    + "[--tls-cert <path> --tls-key <path> --tls-trust-store <path>] "
                    + "[--reconnect-backoff-ms <ms>] [--heartbeat-silence-factor <n>]");
            System.exit(1);
        }

        EdgeNodeConfig config;
        try {
            config = EdgeNodeConfig.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        EdgeNodeMain node = start(config);
        System.out.println("configd-edge-node started"
                + ": edgeId=" + config.edgeId()
                + " apiPort=" + node.apiPort()
                + " endpoints=" + config.fanOutEndpoints()
                + " tls=" + (config.tlsEnabled() ? "mTLS" : "PLAINTEXT")
                + " verifyKey=" + (config.verifyKeyPath() != null ? "configured" : "ABSENT")
                + " prefixes=" + (config.subscribePrefixes().isEmpty()
                        ? "(full store)" : config.subscribePrefixes()));

        Runtime.getRuntime().addShutdownHook(new Thread(node::shutdown, "edge-shutdown"));
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            node.shutdown();
        }
    }
}

package io.configd.edge.node;

import io.configd.common.Clock;
import io.configd.edge.EdgeClientCore;
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
 *   <li>the DISCONNECTED re-bootstrap trigger is a NAMED STUB seam
 *       ({@link #rebootstrapHook}): C2 detects the transition, counts
 *       {@code edge_rebootstrap_triggered_total}, and invokes the hook; C3 (ADR-0040)
 *       supplies the real re-bootstrap orchestration.</li>
 * </ul>
 */
public final class EdgeNodeMain {

    private final EdgeNodeConfig config;
    private final MetricsRegistry metricsRegistry;
    private final EdgeNodeMetrics metrics;
    private final EdgeClientCore core;
    private final EdgeStreamClient streamClient;
    private final EdgeHttpServer httpServer;

    private EdgeNodeMain(EdgeNodeConfig config, MetricsRegistry metricsRegistry,
                         EdgeNodeMetrics metrics, EdgeClientCore core,
                         EdgeStreamClient streamClient, EdgeHttpServer httpServer) {
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
        // Fail-open production monitor (the ConfigdServer policy): an invariant violation
        // increments invariant.violation.* and keeps serving — never throws in-process.
        InvariantMonitor invariantMonitor = new InvariantMonitor(registry, false);
        EdgeNodeMetrics metrics = new EdgeNodeMetrics(registry);

        EdgeStreamClient streamClient = new EdgeStreamClient(
                config.fanOutEndpoints(), config.edgeId(), config.subscribePrefixes(),
                tlsManager, config.reconnectBackoffMs(), config.heartbeatSilenceFactor(),
                clock, metrics, rebootstrapHook());

        EdgeClientCore core = new EdgeClientCore(clock, invariantMonitor,
                metrics.implausibleCounter(), StrongReadKeyClass.DEFAULT, streamClient.sink(),
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, config.heartbeatSilenceFactor(),
                verifier, config.dataDir());
        for (String prefix : config.subscribePrefixes()) {
            core.addSubscription(prefix);
        }
        metrics.bind(core);

        EdgeHttpServer httpServer;
        try {
            httpServer = new EdgeHttpServer(config.apiPort(), core, StrongReadKeyClass.DEFAULT,
                    new PrometheusExporter(registry), metrics);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to start edge HTTP server on port " + config.apiPort(), e);
        }
        httpServer.start();
        streamClient.start(core);

        return new EdgeNodeMain(config, registry, metrics, core, streamClient, httpServer);
    }

    /**
     * The DISCONNECTED re-bootstrap trigger seam (CT-06 trigger half). C2 ships a stub —
     * the transition is already counted on {@code edge_rebootstrap_triggered_total} by
     * {@link EdgeNodeMetrics#syncFromCore} before this hook runs; C3 (ADR-0040) replaces
     * the body with the full re-subscribe-from-snapshot orchestration.
     */
    private static Runnable rebootstrapHook() {
        return () -> { /* C3 (ADR-0040): re-bootstrap orchestration plugs in here. */ };
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
        httpServer.stop(1);
    }

    /** The actual bound API port (resolves an ephemeral {@code --api-port 0}). */
    public int apiPort() {
        return httpServer.port();
    }

    /** The edge client core (tests / diagnostics; reads are thread-safe). */
    public EdgeClientCore core() {
        return core;
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

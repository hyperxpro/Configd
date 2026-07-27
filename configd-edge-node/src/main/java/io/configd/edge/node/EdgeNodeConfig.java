package io.configd.edge.node;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for one edge node process. Parsed from command-line arguments; immutable
 * after construction.
 *
 * <h2>Named policy configs (every threshold is a named config with a corresponding metric)</h2>
 * <ul>
 *   <li>{@code edge.reconnect.backoffMs} ({@code --reconnect-backoff-ms}, default
 *       {@value #DEFAULT_RECONNECT_BACKOFF_MS}): the BASE reconnect backoff. The realized
 *       delay is bounded and jittered by {@link EdgeStreamClient} (doubling per consecutive
 *       failure up to {@link EdgeStreamClient#MAX_BACKOFF_MS}, +/-50% full jitter). Metric:
 *       {@code edge_reconnects_total}.</li>
 *   <li>{@code edge.heartbeat.silenceFactor} ({@code --heartbeat-silence-factor}, default
 *       {@value #DEFAULT_HEARTBEAT_SILENCE_FACTOR}): reconnect after this many missed
 *       heartbeat intervals (silenceFactor * heartbeatMs, heartbeat cadence is the
 *       server's {@code edge.fanout.heartbeatMs} = 250 ms). Metric:
 *       {@code edge_reconnects_total} (reason visible in the structured log).</li>
 *   <li>{@code edge.poisonpill.maxRetries} ({@code --poison-max-retries}, default
 *       {@value #DEFAULT_POISON_MAX_RETRIES}): bounded apply-failure retries per seq before
 *       quarantine, forced snapshot re-bootstrap, then terminal fail-loud.
 *       Metrics: {@code edge_poison_retries_total}, {@code configd_edge_poison_pill_total},
 *       {@code configd_edge_poison_pill_terminal_total}.</li>
 * </ul>
 *
 * @param edgeId             the edge identity carried in SUBSCRIBE. Over mTLS the server
 *                           binds the session to the client-cert Subject DN (the wire field
 *                           is advisory), so this MUST match the cert identity - operators
 *                           pass the cert DN here so logs/metrics agree with the server's
 *                           authoritative view
 * @param fanOutEndpoints    ordered fan-out endpoints ({@code h:p[,h:p]}); the client
 *                           connects to the first and fails over round-robin
 * @param apiPort            the read-serving HTTP port ({@code 0} = ephemeral)
 * @param dataDir            directory for the {@code epoch.lock} sidecar - epoch METADATA
 *                           only, never values ({@code secure/} values stay in memory only)
 * @param verifyKeyPath      optional Ed25519 public key (X.509/SPKI DER, the
 *                           {@code VerifyKeyExporter} output). When present every delta
 *                           must verify (fail-closed); when absent, SIGNED deltas are
 *                           rejected fail-closed by {@code DeltaApplier}
 * @param subscribePrefixes  repeatable {@code --subscribe-prefix}; empty = full store
 *                           (this is the edge-side STORAGE filter - the server always
 *                           streams the full signed chain)
 * @param tlsCertPath        TLS certificate path (same triple as the server), or null
 * @param tlsKeyPath         TLS key store (PKCS12), or null
 * @param tlsTrustStorePath  TLS trust store (PKCS12), or null
 * @param reconnectBackoffMs base reconnect backoff in ms ({@code edge.reconnect.backoffMs})
 * @param heartbeatSilenceFactor missed-heartbeat reconnect factor
 *                           ({@code edge.heartbeat.silenceFactor})
 * @param poisonMaxRetries   bounded apply-failure retries before quarantine
 *                           ({@code edge.poisonpill.maxRetries})
 */
public record EdgeNodeConfig(
        String edgeId,
        List<InetSocketAddress> fanOutEndpoints,
        int apiPort,
        Path dataDir,
        Path verifyKeyPath,
        List<String> subscribePrefixes,
        Path tlsCertPath,
        Path tlsKeyPath,
        Path tlsTrustStorePath,
        long reconnectBackoffMs,
        int heartbeatSilenceFactor,
        int poisonMaxRetries
) {

    /** Default read-serving API port (the control plane's API default is 8080). */
    public static final int DEFAULT_API_PORT = 8081;

    public static final long DEFAULT_RECONNECT_BACKOFF_MS = 100L;

    public static final int DEFAULT_HEARTBEAT_SILENCE_FACTOR = 8;

    public static final int DEFAULT_POISON_MAX_RETRIES = 3;

    public EdgeNodeConfig {
        if (edgeId == null || edgeId.isBlank()) {
            throw new IllegalArgumentException("edgeId must not be blank");
        }
        if (fanOutEndpoints == null || fanOutEndpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one fan-out endpoint is required");
        }
        fanOutEndpoints = List.copyOf(fanOutEndpoints);
        if (apiPort < 0 || apiPort > 65_535) {
            throw new IllegalArgumentException("apiPort out of range: " + apiPort);
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir is required (SEC-017 epoch.lock home)");
        }
        subscribePrefixes = subscribePrefixes == null ? List.of() : List.copyOf(subscribePrefixes);
        for (String p : subscribePrefixes) {
            if (p == null || p.isBlank()) {
                throw new IllegalArgumentException("subscribe prefix must not be blank");
            }
        }
        int tlsFlags = (tlsCertPath != null ? 1 : 0) + (tlsKeyPath != null ? 1 : 0)
                + (tlsTrustStorePath != null ? 1 : 0);
        if (tlsFlags != 0 && tlsFlags != 3) {
            throw new IllegalArgumentException(
                    "TLS requires all of --tls-cert/--tls-key/--tls-trust-store or none "
                            + "(refusing a silent plaintext downgrade)");
        }
        if (reconnectBackoffMs <= 0) {
            throw new IllegalArgumentException(
                    "edge.reconnect.backoffMs must be > 0: " + reconnectBackoffMs);
        }
        if (heartbeatSilenceFactor <= 0) {
            throw new IllegalArgumentException(
                    "edge.heartbeat.silenceFactor must be > 0: " + heartbeatSilenceFactor);
        }
        if (poisonMaxRetries <= 0) {
            throw new IllegalArgumentException(
                    "edge.poisonpill.maxRetries must be > 0: " + poisonMaxRetries);
        }
    }

    public boolean tlsEnabled() {
        return tlsCertPath != null && tlsKeyPath != null && tlsTrustStorePath != null;
    }

    public static EdgeNodeConfig parse(String[] args) {
        String edgeId = null;
        String endpointsStr = null;
        int apiPort = DEFAULT_API_PORT;
        String dataDir = null;
        String verifyKey = null;
        List<String> prefixes = new ArrayList<>();
        String tlsCert = null;
        String tlsKey = null;
        String tlsTrustStore = null;
        long reconnectBackoffMs = DEFAULT_RECONNECT_BACKOFF_MS;
        int silenceFactor = DEFAULT_HEARTBEAT_SILENCE_FACTOR;
        int poisonMaxRetries = DEFAULT_POISON_MAX_RETRIES;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--edge-id" -> {
                    requireNextArg(args, i, "--edge-id");
                    edgeId = args[++i];
                }
                case "--fanout-endpoints" -> {
                    requireNextArg(args, i, "--fanout-endpoints");
                    endpointsStr = args[++i];
                }
                case "--api-port" -> {
                    requireNextArg(args, i, "--api-port");
                    apiPort = Integer.parseInt(args[++i]);
                }
                case "--data-dir" -> {
                    requireNextArg(args, i, "--data-dir");
                    dataDir = args[++i];
                }
                case "--verify-key" -> {
                    requireNextArg(args, i, "--verify-key");
                    verifyKey = args[++i];
                }
                case "--subscribe-prefix" -> {
                    requireNextArg(args, i, "--subscribe-prefix");
                    prefixes.add(args[++i]);
                }
                case "--tls-cert" -> {
                    requireNextArg(args, i, "--tls-cert");
                    tlsCert = args[++i];
                }
                case "--tls-key" -> {
                    requireNextArg(args, i, "--tls-key");
                    tlsKey = args[++i];
                }
                case "--tls-trust-store" -> {
                    requireNextArg(args, i, "--tls-trust-store");
                    tlsTrustStore = args[++i];
                }
                case "--reconnect-backoff-ms" -> {
                    requireNextArg(args, i, "--reconnect-backoff-ms");
                    reconnectBackoffMs = Long.parseLong(args[++i]);
                }
                case "--heartbeat-silence-factor" -> {
                    requireNextArg(args, i, "--heartbeat-silence-factor");
                    silenceFactor = Integer.parseInt(args[++i]);
                }
                case "--poison-max-retries" -> {
                    requireNextArg(args, i, "--poison-max-retries");
                    poisonMaxRetries = Integer.parseInt(args[++i]);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (edgeId == null) {
            throw new IllegalArgumentException("--edge-id is required");
        }
        if (endpointsStr == null) {
            throw new IllegalArgumentException("--fanout-endpoints is required");
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("--data-dir is required");
        }

        return new EdgeNodeConfig(
                edgeId,
                parseEndpoints(endpointsStr),
                apiPort,
                Path.of(dataDir),
                verifyKey != null ? Path.of(verifyKey) : null,
                prefixes,
                tlsCert != null ? Path.of(tlsCert) : null,
                tlsKey != null ? Path.of(tlsKey) : null,
                tlsTrustStore != null ? Path.of(tlsTrustStore) : null,
                reconnectBackoffMs,
                silenceFactor,
                poisonMaxRetries);
    }

    private static List<InetSocketAddress> parseEndpoints(String str) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String entry : str.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid endpoint (expected host:port): " + trimmed);
            }
            String host = trimmed.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid endpoint port (expected host:port): " + trimmed, e);
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Endpoint port out of range: " + trimmed);
            }
            // Unresolved so a DNS change between reconnects is honoured at connect time.
            out.add(InetSocketAddress.createUnresolved(host, port));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("--fanout-endpoints must name at least one h:p");
        }
        return out;
    }

    private static void requireNextArg(String[] args, int currentIndex, String flag) {
        if (currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
    }
}

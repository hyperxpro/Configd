package io.configd.server;

import io.configd.common.NodeId;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds all configuration for a Configd server instance.
 * <p>
 * Parsed from command-line arguments. Immutable after construction.
 *
 * @param nodeId          unique integer identifier for this node in the cluster
 * @param dataDir         directory for durable storage (Raft state, WAL, snapshots)
 * @param peers           set of peer node IDs (excluding this node)
 * @param bindAddress     network address to bind the server to
 * @param bindPort        network port to bind the server to
 * @param apiPort         HTTP API port (default 8080)
 * @param tlsCertPath     path to the TLS certificate (PKCS12), or null if TLS disabled
 * @param tlsKeyPath      path to the TLS key store (PKCS12), or null if TLS disabled
 * @param tlsTrustStorePath path to the TLS trust store (PKCS12), or null if TLS disabled
 * @param authToken       simple bearer token for API auth, or null if auth disabled
 * @param peerAddresses   map of peer NodeId to network address, or null if not configured
 * @param strongReadPrefixes key prefixes whose GETs MUST be served fail-closed
 *                        linearizable; defaults to
 *                        {@code secure/}. Empty disables strong-read enforcement.
 * @param edgePort        the C1 fan-out endpoint port (ADR-0037), or {@code null} to
 *                        disable the edge data-plane endpoint (the default - current
 *                        behavior is unchanged when {@code --edge-port} is absent). When
 *                        present, the endpoint reuses the same {@link io.configd.transport.TlsManager}
 *                        as Raft (REQUIRED mTLS when TLS is configured; plaintext otherwise).
 */
public record ServerConfig(
        NodeId nodeId,
        Path dataDir,
        Set<NodeId> peers,
        String bindAddress,
        int bindPort,
        int apiPort,
        Path tlsCertPath,
        Path tlsKeyPath,
        Path tlsTrustStorePath,
        String authToken,
        Map<NodeId, InetSocketAddress> peerAddresses,
        Path signingKeyFile,
        Set<String> strongReadPrefixes,
        Integer edgePort
) {

    /**
     * Parses command-line arguments into a {@code ServerConfig}.
     * <p>
     * Expected arguments:
     * <pre>
     *   --node-id         integer node ID (required)
     *   --data-dir        path to data directory (required)
     *   --peers           comma-separated peer node IDs, e.g. "2,3,4" (required)
     *   --bind-address    bind address (default: 0.0.0.0)
     *   --bind-port       bind port (default: 9090)
     *   --api-port        HTTP API port (default: 8080)
     *   --tls-cert        path to TLS certificate store (optional)
     *   --tls-key         path to TLS key store (optional)
     *   --tls-trust-store path to TLS trust store (optional)
     *   --auth-token      bearer token for API auth (optional)
     *   --strong-read-prefixes comma-separated key prefixes served fail-closed
     *                     linearizable; default "secure/"
     *   --edge-port       C1 fan-out edge endpoint port (ADR-0037); absent = endpoint
     *                     disabled (default). Reuses the Raft TlsManager (mTLS) when configured.
     * </pre>
     *
     * @param args command-line arguments
     * @return parsed server configuration
     * @throws IllegalArgumentException if required arguments are missing or invalid
     */
    public static ServerConfig parse(String[] args) {
        int nodeId = -1;
        String dataDir = null;
        String peersStr = null;
        String bindAddress = "0.0.0.0";
        int bindPort = 9090;
        int apiPort = 8080;
        String tlsCert = null;
        String tlsKey = null;
        String tlsTrustStore = null;
        String authToken = null;
        String peerAddressesStr = null;
        String signingKeyFile = null;
        // Defaults to 'secure/' to protect security keys even when the flag is omitted.
        String strongReadPrefixesStr = null;
        Integer edgePort = null; // fan-out endpoint; absent = disabled (default)

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--node-id" -> {
                    requireNextArg(args, i, "--node-id");
                    nodeId = Integer.parseInt(args[++i]);
                }
                case "--data-dir" -> {
                    requireNextArg(args, i, "--data-dir");
                    dataDir = args[++i];
                }
                case "--peers" -> {
                    requireNextArg(args, i, "--peers");
                    peersStr = args[++i];
                }
                case "--bind-address" -> {
                    requireNextArg(args, i, "--bind-address");
                    bindAddress = args[++i];
                }
                case "--bind-port" -> {
                    requireNextArg(args, i, "--bind-port");
                    bindPort = Integer.parseInt(args[++i]);
                }
                case "--api-port" -> {
                    requireNextArg(args, i, "--api-port");
                    apiPort = Integer.parseInt(args[++i]);
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
                case "--auth-token" -> {
                    requireNextArg(args, i, "--auth-token");
                    authToken = args[++i];
                }
                case "--peer-addresses" -> {
                    requireNextArg(args, i, "--peer-addresses");
                    peerAddressesStr = args[++i];
                }
                case "--signing-key-file" -> {
                    requireNextArg(args, i, "--signing-key-file");
                    signingKeyFile = args[++i];
                }
                case "--strong-read-prefixes" -> {
                    requireNextArg(args, i, "--strong-read-prefixes");
                    strongReadPrefixesStr = args[++i];
                }
                case "--edge-port" -> {
                    requireNextArg(args, i, "--edge-port");
                    edgePort = Integer.parseInt(args[++i]);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        if (nodeId < 0) {
            throw new IllegalArgumentException("--node-id is required");
        }
        if (dataDir == null) {
            throw new IllegalArgumentException("--data-dir is required");
        }
        if (peersStr == null) {
            throw new IllegalArgumentException("--peers is required");
        }

        Set<NodeId> peers = parsePeers(peersStr);
        Map<NodeId, InetSocketAddress> peerAddresses = peerAddressesStr != null
                ? parsePeerAddresses(peerAddressesStr) : null;
        Set<String> strongReadPrefixes = parseStrongReadPrefixes(strongReadPrefixesStr);

        return new ServerConfig(
                NodeId.of(nodeId),
                Path.of(dataDir),
                peers,
                bindAddress,
                bindPort,
                apiPort,
                tlsCert != null ? Path.of(tlsCert) : null,
                tlsKey != null ? Path.of(tlsKey) : null,
                tlsTrustStore != null ? Path.of(tlsTrustStore) : null,
                authToken,
                peerAddresses,
                signingKeyFile != null ? Path.of(signingKeyFile) : null,
                strongReadPrefixes,
                edgePort
        );
    }

    /** True if the C1 fan-out edge endpoint is configured ({@code --edge-port} present). */
    public boolean edgeEnabled() {
        return edgePort != null;
    }

    /**
     * Returns true if all three TLS paths are configured.
     */
    public boolean tlsEnabled() {
        return tlsCertPath != null && tlsKeyPath != null && tlsTrustStorePath != null;
    }

    /**
     * Returns true if bearer token auth is configured.
     */
    public boolean authEnabled() {
        return authToken != null && !authToken.isBlank();
    }

    private static Set<NodeId> parsePeers(String peersStr) {
        if (peersStr.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(peersStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .map(NodeId::of)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Parses peer addresses from a string of format "id=host:port,id=host:port,...".
     * Example: "1=192.168.1.10:9091,2=192.168.1.11:9092"
     */
    private static Map<NodeId, InetSocketAddress> parsePeerAddresses(String str) {
        if (str == null || str.isBlank()) {
            return Map.of();
        }
        Map<NodeId, InetSocketAddress> result = new HashMap<>();
        for (String entry : str.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid peer address format (expected id=host:port): " + trimmed);
            }
            int id = Integer.parseInt(parts[0].trim());
            String[] hostPort = parts[1].trim().split(":", 2);
            if (hostPort.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid address format (expected host:port): " + parts[1]);
            }
            result.put(NodeId.of(id), new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])));
        }
        return Map.copyOf(result);
    }

    /**
     * Parses the strong-read prefix list.
     * <ul>
     *   <li>flag omitted ({@code null}) &rarr; the safe default {@code secure/}
     *       (security keys stay protected even if the operator forgot the flag);</li>
     *   <li>explicit empty / blank value &rarr; empty set (enforcement disabled,
     *       a deliberate opt-out);</li>
     *   <li>otherwise &rarr; the comma-separated, trimmed, non-blank prefixes.</li>
     * </ul>
     */
    private static Set<String> parseStrongReadPrefixes(String str) {
        if (str == null) {
            return Set.of(StrongReadPolicy.DEFAULT_PREFIX);
        }
        if (str.isBlank()) {
            return Set.of(); // explicit opt-out
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireNextArg(String[] args, int currentIndex, String flag) {
        if (currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
    }
}

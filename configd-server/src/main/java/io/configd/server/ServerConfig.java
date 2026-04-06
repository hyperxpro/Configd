package io.configd.server;

import io.configd.common.NodeId;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
        Path signingKeyFile
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
                signingKeyFile != null ? Path.of(signingKeyFile) : null
        );
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
        return List.of(peersStr.split(",")).stream()
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

    private static void requireNextArg(String[] args, int currentIndex, String flag) {
        if (currentIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
    }
}

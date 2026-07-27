package io.configd.server;

import io.configd.common.NodeId;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


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

    
    public static ServerConfig parse(String[] args) {
        int nodeId = -1;
        String dataDir = null;
        String peersStr = null;
        // Default to loopback rather than the wildcard 0.0.0.0. Binding an unauthenticated store to a
        // public interface by default is the Redis/etcd "default-open" footgun; a genuine multi-host
        // cluster sets --bind-address to a routable address on purpose. When the effective bind is
        // non-loopback while auth is off, ConfigdServer.enforceBindNotSilentlyPublic refuses to start
        // unless the operator acknowledges it with configd.security.allowInsecurePublicBind.
        String bindAddress = "127.0.0.1";
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
                case "--config" -> {
                    // The YAML config file is loaded into the ConfigSource by ConfigdServer.loadBootConfig,
                    // not stored on this record. Accept and skip its value here so the argument is valid.
                    requireNextArg(args, i, "--config");
                    i++;
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

    
    public boolean edgeEnabled() {
        return edgePort != null;
    }

    
    public boolean tlsEnabled() {
        return tlsCertPath != null && tlsKeyPath != null && tlsTrustStorePath != null;
    }

    
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

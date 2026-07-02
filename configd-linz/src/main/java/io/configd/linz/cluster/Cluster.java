package io.configd.linz.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Brings up and tears down a cluster of {@code n} real separate-JVM nodes
 * (ids 1..n) on distinct loopback ports. Node ids are 1-based; node {@code k}
 * uses Raft port {@code raftBase + k} and API port {@code apiBase + k}.
 */
public final class Cluster implements AutoCloseable {

    private final List<ClusterNode> nodes;

    private Cluster(List<ClusterNode> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public static Cluster create(int n, int raftBase, int apiBase, Path baseDir, Path jar,
                                 ClusterNode.TlsFiles tls) throws IOException {
        String peerAddresses = IntStream.rangeClosed(1, n)
                .mapToObj(k -> k + "=127.0.0.1:" + (raftBase + k))
                .collect(Collectors.joining(","));
        // Each node's Ed25519 signing key lives OUTSIDE its data dir - in a sibling secrets/ dir
        // under the cluster baseDir - so the server's D-1 co-location guard (PA-2021) is SATISFIED
        // rather than disabled. SigningKeyStore.loadOrCreate auto-generates a missing key; the path is
        // per-node and stable, so a kill -9 + restart recovers the WAL against the same signing key
        // and the at-rest integrity chain stays valid.
        Path secretsDir = baseDir.resolve("secrets");
        Files.createDirectories(secretsDir);
        List<ClusterNode> ns = new ArrayList<>();
        for (int k = 1; k <= n; k++) {
            final int id = k;
            String peers = IntStream.rangeClosed(1, n)
                    .filter(j -> j != id)
                    .mapToObj(Integer::toString)
                    .collect(Collectors.joining(","));
            Path dataDir = baseDir.resolve("n" + id);
            Files.createDirectories(dataDir);
            Path signingKeyFile = secretsDir.resolve("n" + id + "-signing-key.bin");
            Path log = baseDir.resolve("n" + id + ".log");
            ns.add(new ClusterNode(id, raftBase + id, apiBase + id, dataDir, signingKeyFile, peers,
                    peerAddresses, jar, log, tls));
        }
        return new Cluster(ns);
    }

    public void startAll() throws IOException {
        for (ClusterNode node : nodes) {
            node.launch();
        }
    }

    public List<ClusterNode> nodes() {
        return nodes;
    }

    /** Node by 1-based id. */
    public ClusterNode node(int id) {
        return nodes.get(id - 1);
    }

    public int size() {
        return nodes.size();
    }

    /** Kills every node process (teardown). Safe to call repeatedly. */
    @Override
    public void close() {
        for (ClusterNode node : nodes) {
            node.kill9();
        }
    }
}

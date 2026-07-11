package io.configd.linz.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the signing-key co-location fix: every spawned node must be handed a
 * {@code --signing-key-file} that lives OUTSIDE its {@code --data-dir}, so the server's
 * co-location guard is SATISFIED (the server would otherwise default the key into the
 * data dir and refuse to start, leaving the faulted-linz cluster leaderless).
 *
 * <p>Asserts against the exact launch command ({@link ClusterNode} builds it deterministically)
 * rather than spawning a JVM, so this runs in the default {@code ./mvnw test} with no server jar.
 */
class ClusterSigningKeyTest {

    @TempDir
    Path baseDir;

    private static final Path DUMMY_JAR = Path.of("configd-server/target/configd-server.jar");

    @Test
    void everyNodeMountsSigningKeyOutsideItsDataDir() throws Exception {
        try (Cluster cluster = Cluster.create(3, 11000, 10000, baseDir, DUMMY_JAR, null)) {
            for (ClusterNode node : cluster.nodes()) {
                List<String> cmd = node.buildCommand();

                int flag = cmd.indexOf("--signing-key-file");
                assertTrue(flag >= 0, "launch command must pass --signing-key-file");
                assertTrue(flag + 1 < cmd.size(), "--signing-key-file must have a value");
                Path keyFile = Path.of(cmd.get(flag + 1)).toAbsolutePath().normalize();

                int dataFlag = cmd.indexOf("--data-dir");
                Path dataDir = Path.of(cmd.get(dataFlag + 1)).toAbsolutePath().normalize();

                // Mirror the server's co-location check (ConfigdServer.isInsideDataDir): the key
                // path must NOT be inside the data dir, otherwise the guard fails the node closed.
                assertFalse(keyFile.startsWith(dataDir),
                        "signing key " + keyFile + " must live OUTSIDE data dir " + dataDir);
                // The harness convention: a stable per-node file under a sibling secrets/ dir.
                assertEquals(node.signingKeyFile().toAbsolutePath().normalize(), keyFile);
                assertTrue(keyFile.startsWith(baseDir.resolve("secrets").toAbsolutePath().normalize()),
                        "signing key should live under the cluster secrets/ dir");
            }
        }
    }

    @Test
    void secretsDirIsCreatedAndKeysArePerNode() throws Exception {
        try (Cluster cluster = Cluster.create(5, 11000, 10000, baseDir, DUMMY_JAR, null)) {
            assertTrue(Files.isDirectory(baseDir.resolve("secrets")),
                    "Cluster.create must create the secrets/ dir");
            long distinctKeys = cluster.nodes().stream()
                    .map(ClusterNode::signingKeyFile)
                    .distinct()
                    .count();
            assertEquals(cluster.size(), distinctKeys, "each node must get its OWN signing key file");
        }
    }

    @Test
    void signingKeyPathIsStableAcrossRelaunch() throws Exception {
        try (Cluster cluster = Cluster.create(3, 11000, 10000, baseDir, DUMMY_JAR, null)) {
            ClusterNode node = cluster.node(1);
            // restart() re-invokes launch(), which rebuilds the command; the signing-key path must be
            // identical so a kill -9 + relaunch recovers the WAL against the same integrity chain.
            List<String> first = node.buildCommand();
            List<String> second = node.buildCommand();
            int i1 = first.indexOf("--signing-key-file");
            int i2 = second.indexOf("--signing-key-file");
            assertEquals(first.get(i1 + 1), second.get(i2 + 1));
        }
    }
}

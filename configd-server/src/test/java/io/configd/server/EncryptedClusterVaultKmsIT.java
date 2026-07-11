package io.configd.server;

import io.configd.common.kms.WrappedKey;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.store.CommandCodec;
import io.configd.store.SigningKeyStore;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live E2E for the production at-rest posture that no single suite exercised on its own: a real,
 * in-process, three-node {@link ConfigdServer} cluster running <b>encryption at rest ON with its
 * keyring-custody root sealed by a real external Vault Transit KMS</b> (a {@code hashicorp/vault}
 * container), booting, committing and replicating <b>encrypted</b> data, and surviving a node restart
 * that <b>re-unseals through Vault</b> with the previously-committed data intact.
 *
 * <p>The two existing proofs each cover only half of this. {@code VaultTransitKmsIT} drives the
 * {@code vault-transit} provider against a real Vault but as a single-process unit, with no cluster and no
 * encrypted Raft log. {@code EncryptedMultiShardClusterCompositionTest} runs a real encrypted 3-node
 * cluster but seals with the default {@code local} posture (custody derived from the signing key), so
 * the external KMS boot path never runs. This test is the join: the full cluster boot path
 * ({@code ConfigdServer.start}, {@code deriveRaftIntegrityEnvelope}, {@code unsealKeyringCustodySecret},
 * the {@code KmsProviderFactory} ServiceLoader, the real {@code VaultKmsProviderFactory}) selecting and
 * driving a real Vault, on every node, at boot and again on restart.
 *
 * <h2>What proves the Vault path actually ran (not a silent local fallback)</h2>
 * <ul>
 *   <li><b>Positive</b>: every node writes a {@code raft-kms-root} sealed-carrier file that the
 *       {@code local} posture never writes, and its stored {@link WrappedKey} is a genuine Vault
 *       carrier, with provider type {@code vault-transit} and a {@code vault:vN:} ciphertext that only a
 *       real Transit {@code encrypt} produces. A {@code local}-fallback boot would leave no such file.</li>
 *   <li><b>Negative</b> ({@link #bootFailsClosedWhenVaultUnreachable}): selecting {@code vault-transit}
 *       with an unreachable Vault fails the boot closed rather than silently downgrading to a local
 *       envelope, which is the property that makes the positive proof trustworthy.</li>
 * </ul>
 *
 * <p>Flag-guarded on {@code -Dconfigd.it.containers=true} (needs a Docker daemon and pulls the Vault
 * image), mirroring {@code VaultTransitKmsIT}; the {@code *IT} name keeps it out of the default reactor.
 * Single shard, since the multi-shard dimension is orthogonal to KMS custody and already proven by the
 * composition test; three nodes so a 2-of-3 commit quorum survives while the restart target is down,
 * letting the restarted node re-unseal through Vault and catch up a real gap. Deadlines are generous for
 * the throttled box and everything is deadline-polled, with no sleep-as-synchronisation; the per-method
 * {@link Timeout} is pure hang detection.
 */
@EnabledIfSystemProperty(named = "configd.it.containers", matches = "true")
@Timeout(600)
final class EncryptedClusterVaultKmsIT {

    // Vault, reused from VaultTransitKmsIT's provisioning.
    private static final String ROOT_TOKEN = "root-dev-token";
    private static final String MOUNT = "transit";
    private static final String KEY = "configd-root-kek";
    private static final String ROLE = "configd";

    private static VaultContainer<?> vault;
    private static String roleId;
    private static String secretId;

    private static final int NODES = 3;
    private static final int SHARDS = 1;
    private static final int PRIMARY = 0; // the single Raft group

    private static final long STABILIZE_MS = 120_000;
    private static final long REPLICATE_MS = 45_000;
    private static final long RESTART_MS = 90_000;
    private static final long POLL_MS = 50;

    // System properties this test drives; saved/restored so it never leaks posture into sibling tests.
    private static final String[] PROPS = {
            "configd.raft.encryption.enabled",
            "configd.raft.encryption.kms.provider",
            "configd.raft.shardCount",
            "configd.raft.ownerPoolSize",
            "configd.raft.electionTimeoutMinMs",
            "configd.raft.electionTimeoutMaxMs",
            "configd.raft.heartbeatIntervalMs",
            "configd.raft.netty.workerThreads",
            "configd.raft.autobalance.enabled",
            "configd.kms.vault.address",
            "configd.kms.vault.transitMount",
            "configd.kms.vault.transitKeyName",
            "configd.kms.vault.auth.approle.roleId",
            "configd.kms.vault.auth.approle.secretId",
            "configd.kms.vault.timeoutMs",
    };
    private final Map<String, String> saved = new HashMap<>();
    private final List<ConfigdServer> running = new ArrayList<>();

    @BeforeAll
    static void startVault() throws Exception {
        vault = new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.13").asCompatibleSubstituteFor("vault"))
                .withVaultToken(ROOT_TOKEN)
                .withStartupTimeout(Duration.ofMinutes(3))
                .withInitCommand(
                        "secrets enable transit",
                        "write -f " + MOUNT + "/keys/" + KEY + " type=aes256-gcm96",
                        "auth enable approle");
        vault.start();

        // A policy granting the AppRole the Transit custodian operations, then a role bound to it. The
        // secret_id is unlimited-use by default, so all three node boots plus the restart re-login share it.
        exec("sh", "-c", "echo 'path \"" + MOUNT + "/*\" { capabilities = [\"create\",\"read\",\"update\"] }' "
                + "| VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=" + ROOT_TOKEN + " vault policy write configd -");
        exec("vault", "write", "auth/approle/role/" + ROLE,
                "token_policies=configd", "secret_id_ttl=60m", "token_ttl=60m", "token_max_ttl=60m");
        roleId = exec("vault", "read", "-field=role_id", "auth/approle/role/" + ROLE + "/role-id").trim();
        secretId = exec("vault", "write", "-f", "-field=secret_id", "auth/approle/role/" + ROLE + "/secret-id").trim();
        assertTrue(!roleId.isEmpty() && !secretId.isEmpty(), "minted AppRole credentials");
    }

    @AfterAll
    static void stopVault() {
        if (vault != null) {
            vault.stop();
        }
    }

    private static String exec(String... cmd) throws Exception {
        Container.ExecResult r = vault.execInContainer(cmd);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("vault exec failed: " + String.join(" ", cmd)
                    + " -> " + r.getStderr() + r.getStdout());
        }
        return r.getStdout();
    }

    @BeforeEach
    void setPosture() {
        for (String p : PROPS) {
            saved.put(p, System.getProperty(p));
        }
        System.setProperty("configd.raft.encryption.enabled", "true");
        System.setProperty("configd.raft.encryption.kms.provider", "vault-transit");
        System.setProperty("configd.raft.shardCount", Integer.toString(SHARDS));
        System.setProperty("configd.raft.ownerPoolSize", Integer.toString(SHARDS));
        // Generous election budget (ratio ~15-20) so 2-vCPU jitter cannot trip a spurious election while
        // three full servers and a Vault container contend for two cores; matches the composition test.
        System.setProperty("configd.raft.electionTimeoutMinMs", "1500");
        System.setProperty("configd.raft.electionTimeoutMaxMs", "3000");
        System.setProperty("configd.raft.heartbeatIntervalMs", "100");
        System.setProperty("configd.raft.netty.workerThreads", "1");
        System.setProperty("configd.raft.autobalance.enabled", "false");
        // Point every node at the real Vault Transit custodian.
        System.setProperty("configd.kms.vault.address", vault.getHttpHostAddress());
        System.setProperty("configd.kms.vault.transitMount", MOUNT);
        System.setProperty("configd.kms.vault.transitKeyName", KEY);
        System.setProperty("configd.kms.vault.auth.approle.roleId", roleId);
        System.setProperty("configd.kms.vault.auth.approle.secretId", secretId);
        System.setProperty("configd.kms.vault.timeoutMs", "5000");
    }

    @AfterEach
    void tearDown() {
        for (ConfigdServer s : running) {
            try {
                s.shutdown();
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        }
        running.clear();
        for (String p : PROPS) {
            String v = saved.get(p);
            if (v == null) {
                System.clearProperty(p);
            } else {
                System.setProperty(p, v);
            }
        }
    }

    @Test
    void encryptedClusterUnsealsViaVaultCommitsReplicatesAndReUnsealsOnRestart(@TempDir Path root)
            throws Exception {
        // One shared cluster signing key kept outside every node's data dir (avoiding key/data co-location),
        // pre-created so the three boots never race to mint it. The signing key still exists under the
        // Vault posture: it is the auth and keyring-integrity IKM; what Vault custodies is the separate
        // keyring-custody secret.
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore.loadOrCreate(signingKey);

        int[] bindPorts = reserveDistinctPorts(NODES);
        ServerConfig[] configs = new ServerConfig[NODES];
        for (int i = 0; i < NODES; i++) {
            configs[i] = nodeConfig(i, bindPorts, root.resolve("node-" + i), signingKey);
        }

        // --- boot all three: each node UNSEALS its keyring-custody root through the real Vault at boot ---
        ConfigdServer[] servers = new ConfigdServer[NODES];
        for (int i = 0; i < NODES; i++) {
            servers[i] = ConfigdServer.start(configs[i]);
            running.add(servers[i]);
        }

        // Proof the Vault path ran (not a local fallback): every node persisted a raft-kms-root carrier -
        // the file the 'local' posture never writes - and it is a genuine Vault Transit carrier.
        byte[][] carriersBefore = new byte[NODES][];
        for (int i = 0; i < NODES; i++) {
            Path data = root.resolve("node-" + i);
            Path sealed = data.resolve(KmsSealedRootStore.FILE_NAME);
            assertTrue(KmsSealedRootStore.exists(sealed),
                    "node " + i + " must persist the Vault sealed-root carrier (local posture writes none)");
            WrappedKey wrapped = KmsSealedRootStore.read(sealed);
            assertEquals("vault-transit", wrapped.keyId().providerType(),
                    "node " + i + " sealed-root must be custodied by the vault-transit provider");
            assertTrue(new String(wrapped.ciphertext(), StandardCharsets.UTF_8).startsWith("vault:v"),
                    "node " + i + " carrier must be a real Vault Transit vault:vN: ciphertext, not a local seal");
            carriersBefore[i] = Files.readAllBytes(sealed);

            // Encryption is genuinely on: every node minted the frozen dual-slot keyring at the encrypted size.
            assertEquals(131080L, Files.size(data.resolve("raft-keyring")),
                    "node " + i + " must mint the frozen preallocated keyring under encryption");
        }

        // A stable leader forms (the armed strict-boot witness gate clears at quorum).
        int leader = awaitStableLeader(servers, PRIMARY, STABILIZE_MS);
        assertTrue(leader >= 0, "the single shard must elect one stable leader within " + STABILIZE_MS
                + "ms: " + leadershipSnapshot(servers, PRIMARY));

        // A write commits on the leader and replicates plus applies on all three Vault-sealed nodes. The
        // value carries a distinctive canary; encryption at rest must keep it off the disk in plaintext.
        String canary = "PLAINTEXT-CANARY-" + UUID.randomUUID();
        byte[] canaryBytes = canary.getBytes(StandardCharsets.UTF_8);
        long committed = commitAndAwaitReplication(servers, PRIMARY, "cfg/secret", canary);
        assertTrue(committed > 0, "the write must reach a committed index replicated to all three nodes");

        // Sanity that the search bytes are the ones that would be on disk unencrypted: the raw command the
        // Raft log would append plainly does contain the canary. It must nonetheless be absent from disk.
        byte[] plainCommand = CommandCodec.encodePut("cfg/secret", canaryBytes);
        assertTrue(indexOf(plainCommand, canaryBytes) >= 0, "the canary is present in the plaintext command");
        for (int i = 0; i < NODES; i++) {
            assertMarkerAbsentOnDisk(root.resolve("node-" + i), canaryBytes);
        }

        // Restart a follower: it must re-unseal through Vault (read the same carrier, unwrap live) and
        // recover its encrypted data, catching up a gap opened while it was down.
        // Re-confirm the leader right before choosing (leadership may have moved since the first election),
        // so the restart target is a genuine follower and dropping it forces no re-election.
        int curLeader = awaitStableLeader(servers, PRIMARY, STABILIZE_MS);
        assertTrue(curLeader >= 0, "leadership must be stable before the restart: "
                + leadershipSnapshot(servers, PRIMARY));
        int restartTarget = firstFollower(servers, PRIMARY, curLeader);
        assertTrue(restartTarget >= 0, "the shard must have a follower to restart: "
                + leadershipSnapshot(servers, PRIMARY));
        servers[restartTarget].shutdown();
        running.remove(servers[restartTarget]);

        // Advance the cluster (2-of-3 quorum) while the target is down, so recovery reconciles a real gap.
        long postDown = commitAndAwaitReplicationExcluding(servers, PRIMARY, restartTarget,
                "cfg/gamma", "v-gamma");

        ConfigdServer restarted = ConfigdServer.start(configs[restartTarget]);
        servers[restartTarget] = restarted;
        running.add(restarted);

        // A successful restart is itself a re-unseal proof (an unreachable Vault would fail it closed - see
        // the negative test), and the carrier was unwrapped, not re-provisioned: the file is byte-unchanged.
        assertArrayEquals(carriersBefore[restartTarget],
                Files.readAllBytes(root.resolve("node-" + restartTarget).resolve(KmsSealedRootStore.FILE_NAME)),
                "restart must re-unseal the EXISTING Vault carrier, never re-seal a new secret");

        boolean caughtUp = awaitUntil(RESTART_MS, () -> appliedIndex(restarted, PRIMARY) >= postDown);
        assertTrue(caughtUp, "restarted node must re-unseal via Vault and catch shard up to " + postDown
                + " (got " + appliedIndex(restarted, PRIMARY) + ") — decrypting its recovered log");

        // The previously-committed canary is still served post-restart and still encrypted on disk.
        assertMarkerAbsentOnDisk(root.resolve("node-" + restartTarget), canaryBytes);

        // A fresh write after recovery commits + replicates to all three (the cluster is whole again).
        long finalCommit = commitAndAwaitReplication(servers, PRIMARY, "cfg/epsilon", "v-epsilon");
        assertTrue(finalCommit > postDown, "a post-recovery write commits + replicates across the whole cluster");
    }

    /**
     * The trust anchor for the positive proof: selecting {@code vault-transit} while Vault is unreachable
     * fails the boot closed - it does not silently fall back to a local envelope (which would return
     * normally and leave a node claiming "encrypted at rest" whose custody chain never touched Vault).
     */
    @Test
    void bootFailsClosedWhenVaultUnreachable(@TempDir Path root) throws Exception {
        System.setProperty("configd.kms.vault.address", "http://127.0.0.1:1"); // nothing listening
        Path signingKey = root.resolve("secrets").resolve("signing-key.bin");
        Files.createDirectories(signingKey.getParent());
        SigningKeyStore keyStore = SigningKeyStore.loadOrCreate(signingKey);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigdServer.deriveRaftIntegrityEnvelope(keyStore, signingKey, root.resolve("data")),
                "an unreachable vault-transit custodian must fail the boot, not downgrade");
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains("vault-transit") && msg.toLowerCase().contains("fail closed"),
                "must fail closed on an unreachable Vault, never a silent local fallback: " + msg);
    }

    // cluster helpers (deadline-polled; no sleep-as-sync) - mirror the composition test's idiom

    private ServerConfig nodeConfig(int nodeId, int[] bindPorts, Path dataDir, Path signingKey) {
        StringBuilder peers = new StringBuilder();
        StringBuilder peerAddrs = new StringBuilder();
        for (int j = 0; j < NODES; j++) {
            if (j == nodeId) {
                continue;
            }
            if (peers.length() > 0) {
                peers.append(',');
                peerAddrs.append(',');
            }
            peers.append(j);
            peerAddrs.append(j).append("=127.0.0.1:").append(bindPorts[j]);
        }
        return ServerConfig.parse(new String[]{
                "--node-id", Integer.toString(nodeId),
                "--data-dir", dataDir.toString(),
                "--peers", peers.toString(),
                "--peer-addresses", peerAddrs.toString(),
                "--bind-port", Integer.toString(bindPorts[nodeId]),
                "--api-port", "0",
                "--signing-key-file", signingKey.toString(),
        });
    }

    private static long appliedIndex(ConfigdServer server, int gid) {
        RaftNode node = server.driver().getGroup(gid);
        return node == null ? -1 : node.monitorView().lastApplied();
    }

    /** The index of the single node reporting LEADER for {@code gid}, or -1 (none, or a split). */
    private static int leaderFor(ConfigdServer[] servers, int gid) {
        int leader = -1;
        for (int i = 0; i < servers.length; i++) {
            RaftNode node = servers[i].driver().getGroup(gid);
            if (node != null && node.monitorView().role() == RaftRole.LEADER) {
                if (leader >= 0) {
                    return -1; // two leaders observed (transient) - not stable
                }
                leader = i;
            }
        }
        return leader;
    }

    private static int firstFollower(ConfigdServer[] servers, int gid, int leader) {
        for (int i = 0; i < servers.length; i++) {
            if (i != leader && servers[i].driver().getGroup(gid) != null) {
                return i;
            }
        }
        return -1;
    }

    /** Polls until one node is the sole LEADER for {@code gid} across several consecutive observations. */
    private static int awaitStableLeader(ConfigdServer[] servers, int gid, long budgetMs)
            throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int candidate = -1;
        int stable = 0;
        while (System.nanoTime() < end) {
            int leader = leaderFor(servers, gid);
            if (leader >= 0 && leader == candidate) {
                if (++stable >= 10) { // ~0.5s of steady single-leadership
                    return leader;
                }
            } else {
                candidate = leader;
                stable = (leader >= 0) ? 1 : 0;
            }
            Thread.sleep(POLL_MS);
        }
        return -1;
    }

    private static String leadershipSnapshot(ConfigdServer[] servers, int gid) {
        StringBuilder sb = new StringBuilder("[gid=").append(gid).append(" roles=");
        for (int i = 0; i < servers.length; i++) {
            RaftNode node = servers[i].driver().getGroup(gid);
            sb.append(i).append(':').append(node == null ? "none" : node.monitorView().role()).append(' ');
        }
        return sb.append(']').toString();
    }

    /** Proposes a PUT on {@code gid}'s current leader and waits until ALL nodes apply it; returns index. */
    private long commitAndAwaitReplication(ConfigdServer[] servers, int gid, String key, String value)
            throws Exception {
        return commitAndAwaitReplicationExcluding(servers, gid, -1, key, value);
    }

    /** As {@link #commitAndAwaitReplication} but only requires replication on nodes other than
     *  {@code excluded} (used while one node is intentionally down). */
    private long commitAndAwaitReplicationExcluding(ConfigdServer[] servers, int gid, int excluded,
                                                    String key, String value) throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLICATE_MS);
        long committed = -1;
        while (System.nanoTime() < deadline && committed < 0) {
            int leader = leaderFor(servers, gid);
            if (leader < 0 || leader == excluded) {
                Thread.sleep(POLL_MS);
                continue;
            }
            MultiRaftDriver driver = servers[leader].driver();
            long before = appliedIndex(servers[leader], gid);
            ProposeOutcome outcome;
            try {
                outcome = driver.ownerExecutor(gid)
                        .submit(() -> driver.propose(gid, cmd)).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Thread.sleep(POLL_MS);
                continue;
            }
            if (outcome.result() != ProposalResult.ACCEPTED) {
                Thread.sleep(POLL_MS);
                continue;
            }
            while (System.nanoTime() < deadline) {
                long now = appliedIndex(servers[leader], gid);
                if (now > before) {
                    committed = now;
                    break;
                }
                Thread.sleep(POLL_MS);
            }
        }
        assertTrue(committed > 0, "shard " + gid + " leader did not commit '" + key + "' in time");

        final long target = committed;
        for (int i = 0; i < servers.length; i++) {
            if (i == excluded) {
                continue;
            }
            final ConfigdServer s = servers[i];
            boolean applied = awaitUntil(deadlineRemainingMs(deadline),
                    () -> appliedIndex(s, gid) >= target);
            assertTrue(applied, "node " + i + " did not replicate+apply shard " + gid + " up to index "
                    + target + " (got " + appliedIndex(s, gid) + ")");
        }
        return committed;
    }

    private static long deadlineRemainingMs(long deadlineNanos) {
        long remain = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return Math.max(remain, 1_000L); // floor so a late per-node check still gets a chance
    }

    /** Asserts the canary's plaintext bytes appear in NO file under {@code dataDir} (encrypted at rest). */
    private static void assertMarkerAbsentOnDisk(Path dataDir, byte[] marker) throws IOException {
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                byte[] bytes = Files.readAllBytes(p);
                assertFalse(indexOf(bytes, marker) >= 0,
                        "at-rest plaintext leak: the committed value appears unencrypted in "
                                + dataDir.relativize(p));
            }
        }
    }

    /** First index of {@code needle} in {@code haystack}, or -1. Small inputs - a naive scan is fine. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private interface Cond {
        boolean met();
    }

    private static boolean awaitUntil(long budgetMs, Cond cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            if (cond.met()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return cond.met();
    }

    /** Reserves {@code n} distinct free loopback ports by opening all n at once, then closing them. */
    private static int[] reserveDistinctPorts(int n) throws Exception {
        java.net.ServerSocket[] socks = new java.net.ServerSocket[n];
        int[] ports = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                socks[i] = new java.net.ServerSocket(0);
                ports[i] = socks[i].getLocalPort();
            }
        } finally {
            for (java.net.ServerSocket s : socks) {
                if (s != null) {
                    s.close();
                }
            }
        }
        return ports;
    }
}

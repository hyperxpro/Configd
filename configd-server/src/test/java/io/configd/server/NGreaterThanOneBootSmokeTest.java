package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.store.CommandCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The SWITCH-FLIP smoke. Proves that, with the boot guard removed, the REAL
 * {@link ConfigdServer#start} BOOTS at {@code N>1} and runs a sharded write end-to-end on the live server.
 * Before G4 this throw-on-boot was the project's deliberate safety scaffold; this test is the proof the
 * flip works at the server level (the per-shard write/read SEMANTICS are additionally proven on the real
 * bring-up path by {@code MultiShardIntegratedSweepTest} + {@code MultiGroupBringupTest}).
 *
 * <p>Single-node groups ({@code --peers ""}) so each shard self-elects without a quorum; the running tick
 * loop commits proposals. The smoke asserts: the server boots with N registered groups, each self-elects
 * LEADER, a propose to shard k COMMITS+APPLIES on shard k and advances ONLY shard k's applied index (live
 * cross-shard isolation on the booted server), and the per-shard observability series exist.
 */
class NGreaterThanOneBootSmokeTest {

    private static final String SHARD_PROP = "configd.raft.shardCount";
    private static final String POOL_PROP = "configd.raft.ownerPoolSize";
    private String savedShard;
    private String savedPool;

    @BeforeEach
    void save() {
        savedShard = System.getProperty(SHARD_PROP);
        savedPool = System.getProperty(POOL_PROP);
    }

    @AfterEach
    void restore() {
        restore(SHARD_PROP, savedShard);
        restore(POOL_PROP, savedPool);
    }

    private static void restore(String key, String val) {
        if (val == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, val);
        }
    }

    @Test
    @Timeout(90)
    void serverBootsAtNGreaterThanOneAndCommitsPerShard(@TempDir Path dataDir) throws Exception {
        System.setProperty(SHARD_PROP, "2");
        System.setProperty(POOL_PROP, "2"); // one owner thread per shard (the real multi-owner shape)
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "",      // single-node cluster -> each shard self-elects (no quorum needed)
                "--api-port", "0"
        });

        ConfigdServer server = ConfigdServer.start(config); // <-- BEFORE G4 this threw IllegalStateException
        try {
            MultiRaftDriver driver = server.driver();
            RaftNode g0 = driver.getGroup(0);
            RaftNode g1 = driver.getGroup(1);
            assertNotNull(g0, "shard 0 must be registered at N=2");
            assertNotNull(g1, "shard 1 must be registered at N=2 (the guard removal lets N>1 boot)");

            // Both single-node shards self-elect LEADER via the running tick loop.
            awaitLeader(g0, "shard 0");
            awaitLeader(g1, "shard 1");

            // A propose to shard 0 commits + applies on shard 0 ONLY (live cross-shard isolation).
            long g1AppliedBefore = g1.monitorView().lastApplied();
            commitTo(driver, 0, "alpha", "v0");
            assertEquals(g1AppliedBefore, g1.monitorView().lastApplied(),
                    "a write to shard 0 must NOT advance shard 1's applied index (cross-shard isolation)");

            // A propose to shard 1 commits + applies on shard 1.
            commitTo(driver, 1, "beta", "v1");

            // Per-shard observability is live at N>1: shard 1's series exist.
            String metrics = server.scrapeMetrics();
            assertTrue(metrics.contains("raft_shard_leader_1") || metrics.contains("raft.shard.leader.1")
                            || metrics.contains("raft_shard_current_term_1"),
                    "per-shard metrics for shard 1 must be present at N>1");
        } finally {
            server.shutdown();
        }
    }

    @Test
    @Timeout(90)
    void edgeEndpointAtNGreaterThanOneNowBootsServingMultiShardWatches(@TempDir Path dataDir)
            throws Exception {
        // The guard flip: N>1 + --edge-port now BOOTS. The fan-out coordinator serves the multi-shard
        // WATCH plane (one core per shard, (gid,S)-tagged cursor vector). The co-resident legacy whole-
        // store SUBSCRIBE plane is still primary-shard-only at N>1, so it is refused PER CONNECTION at
        // runtime (BAD_SUBSCRIBE) unless -Dconfigd.edge.allowPartialShardView - that runtime refusal is
        // proven at the driver by FanOutConnectionDriver's tests, not here. This smoke proves only that
        // boot no longer throws and the edge endpoint is bound at N>1.
        System.setProperty(SHARD_PROP, "2");
        System.setProperty(POOL_PROP, "2");
        ServerConfig config = ServerConfig.parse(new String[]{
                "--node-id", "0",
                "--data-dir", dataDir.toString(),
                "--peers", "",
                "--api-port", "0",
                "--edge-port", "0"   // edge enabled + N>1: BOOTS now (multi-shard WATCH supported)
        });
        ConfigdServer server = ConfigdServer.start(config); // <-- before the guard flip this threw
        try {
            assertNotNull(server.fanOutServer(),
                    "the edge endpoint must be bound at N>1 (the boot guard is gone)");
            assertNotNull(server.driver().getGroup(1),
                    "shard 1 must be registered at N=2 (N>1 + edge boots)");
        } finally {
            server.shutdown();
        }
        // A SUPPORTED N>1+edge boot now persists the fixed-at-deploy marker (it is a real deploy, not a
        // refused boot) - the inverse of the pre-flip assertion.
        assertTrue(java.nio.file.Files.exists(dataDir.resolve("raft-shard-count.meta")),
                "a booted N>1+edge deploy must persist the fixed-at-deploy marker");
    }

    /** Polls the group's owner-published monitor view until it is LEADER (single-node self-election). */
    private static void awaitLeader(RaftNode node, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (node.monitorView().role() == RaftRole.LEADER) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(label + " did not self-elect LEADER within 30s");
    }

    /** Proposes a PUT to shard {@code gid} on its owner and waits for the running tick loop to apply it. */
    private static void commitTo(MultiRaftDriver driver, int gid, String key, String value)
            throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
        long before = driver.getGroup(gid).monitorView().lastApplied();
        ProposeOutcome outcome = driver.ownerExecutor(gid)
                .submit(() -> driver.propose(gid, cmd)).get(5, TimeUnit.SECONDS);
        assertEquals(ProposalResult.ACCEPTED, outcome.result(), "shard " + gid + " leader must accept");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (driver.getGroup(gid).monitorView().lastApplied() > before) {
                return; // committed + applied by the running tick loop
            }
            Thread.sleep(20);
        }
        throw new AssertionError("shard " + gid + " did not apply the write within 10s");
    }
}

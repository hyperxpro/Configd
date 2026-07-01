package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.configd.common.Clock;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.transport.FrameCodec;
import io.configd.transport.RaftTransportEndpoint;
import io.configd.transport.TlsManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The N-group consensus bring-up proof. Drives the REAL production helper
 * {@link ConfigdServer#buildRaftGroup} (the single bring-up path the server loops over a shard) for
 * N in {1,3} on a real {@link MultiRaftDriver} + {@link OwnerExecutorPool}, so this is "N groups actually
 * running" with production wiring - not a sim.
 *
 * <p>What it discriminates:
 * <ul>
 *   <li><b>N=1 byte-identity foundation</b> - the single group reuses the node-level {@link Storage}
 *       instance (so its WAL/snapshot bytes + paths are unchanged), and in no-peer mode carries no
 *       outbound adapter/coalescer (the no-op transport, exactly as today).</li>
 *   <li><b>Independent bring-up</b> - N groups each self-elect LEADER on their OWN owner thread, each with
 *       its own durable storage directory ({@code dataDir/shard-<gid>}).</li>
 *   <li><b>Per-shard linearizability + isolation (S2/S4)</b> - a committed write to group k applies to
 *       group k's store ONLY; a sibling group's store never sees it.</li>
 *   <li><b>The outbound half</b> - each group's outbound adapter stamps ITS gid on the wire
 *       (a frame sent by group k carries {@code groupId == k}), not a captured constant 0.</li>
 * </ul>
 */
class MultiGroupBringupTest {

    private static final NodeId NODE = NodeId.of(1);

    private OwnerExecutorPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    // ---- N=1 byte-identity foundation -------------------------------------------------------

    @Test
    void n1ReusesNodeStorageInstanceAndHasNoOutboundAdapterInNoPeerMode() throws Exception {
        pool = new OwnerExecutorPool(1);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);

        Storage nodeStorage = Storage.inMemory();
        ConfigdServer.RaftGroupRuntime rt = ConfigdServer.buildRaftGroup(
                0, /*shardCount=*/1, /*dataDir=*/null, nodeStorage, integrity(), Clock.system(),
                /*signer=*/null, ConfigStateMachine.InvariantChecker.NOOP,
                io.configd.raft.RaftNode.InvariantChecker.NOOP, testMetrics(),
                RaftConfig.of(NODE, Set.of()), NODE, /*tcpTransport=*/null,
                /*groupCommit=*/false, 4096, 0L, driver);

        assertEquals(0, rt.groupId());
        // N=1: the group MUST reuse the node-level storage instance (byte-identical WAL/snapshot + paths;
        // the same instance the node-level AuditLog uses in production).
        assertSame(nodeStorage, rt.storage(),
                "at N=1 the single group must reuse the node-level Storage instance (byte-identity)");
        // No peers => no-op transport => no outbound adapter / coalescer (exactly as today).
        assertNull(rt.adapter(), "no-peer mode must have no outbound adapter");
        assertNull(rt.coalescingTransport(), "no-peer mode must have no coalescing transport");
        assertNotNull(rt.raftNode());
        assertNotNull(rt.configStore());
        assertNotNull(rt.stateMachine());
    }

    @Test
    void n1GroupSelfElectsAndAppliesAWrite() throws Exception {
        pool = new OwnerExecutorPool(1);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);

        ConfigdServer.RaftGroupRuntime rt = bringUpLeader(driver, 1, 0, null, Storage.inMemory());
        proposeAndAwaitApply(driver, rt, 0, "alpha", "v0".getBytes(StandardCharsets.UTF_8));
        assertEquals("v0", value(rt, "alpha"));
    }

    // ---- N>1 independent bring-up + per-shard isolation -------------------------------------

    @Test
    void nGroupsBringUpIndependentlyWithDistinctPerShardStorage(@TempDir Path dataDir) throws Exception {
        final int n = 3;
        pool = new OwnerExecutorPool(n); // owner i <- gid i (floorMod(gid, n))
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);

        ConfigdServer.RaftGroupRuntime[] rts = new ConfigdServer.RaftGroupRuntime[n];
        Storage nodeStorage = Storage.file(dataDir); // node-level (would back the AuditLog in prod)
        for (int gid = 0; gid < n; gid++) {
            rts[gid] = bringUpLeader(driver, n, gid, dataDir, nodeStorage);
        }

        // Each group is its own LEADER on its own owner thread.
        for (int gid = 0; gid < n; gid++) {
            assertEquals(RaftRole.LEADER, rts[gid].raftNode().role(), "group " + gid + " must self-elect");
        }
        // At N>1 each group has its OWN storage (a distinct dataDir/shard-<gid>), NOT the node-level one.
        for (int gid = 0; gid < n; gid++) {
            assertNotSame(nodeStorage, rts[gid].storage(),
                    "at N>1 group " + gid + " must NOT reuse the node-level storage");
        }
        assertNotSame(rts[0].storage(), rts[1].storage(), "shards must have distinct storage");
        assertNotSame(rts[1].storage(), rts[2].storage(), "shards must have distinct storage");
        assertNotSame(rts[0].configStore(), rts[1].configStore(), "shards must have distinct stores");
    }

    @Test
    void perShardWritesAreIsolated(@TempDir Path dataDir) throws Exception {
        final int n = 3;
        pool = new OwnerExecutorPool(n);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);

        ConfigdServer.RaftGroupRuntime[] rts = new ConfigdServer.RaftGroupRuntime[n];
        Storage nodeStorage = Storage.file(dataDir);
        for (int gid = 0; gid < n; gid++) {
            rts[gid] = bringUpLeader(driver, n, gid, dataDir, nodeStorage);
        }

        // Commit a DISTINCT key to each shard.
        for (int gid = 0; gid < n; gid++) {
            proposeAndAwaitApply(driver, rts[gid], gid, "k" + gid,
                    ("val" + gid).getBytes(StandardCharsets.UTF_8));
        }

        // Per-shard linearizability + ISOLATION: group k holds ONLY its own key; siblings never see it.
        for (int writer = 0; writer < n; writer++) {
            for (int observer = 0; observer < n; observer++) {
                boolean present = rts[observer].configStore().get("k" + writer).found();
                if (writer == observer) {
                    assertTrue(present, "group " + observer + " must hold its own committed key k" + writer);
                    assertEquals("val" + writer, value(rts[observer], "k" + writer));
                } else {
                    assertFalse(present, "group " + observer + " must NOT see group " + writer
                            + "'s key k" + writer + " (cross-shard isolation breach)");
                }
            }
        }
    }

    // ---- outbound half: per-group adapter stamps its gid ---------------------------

    @Test
    void perGroupOutboundAdapterStampsItsGid(@TempDir Path dataDir) {
        // Build groups with a recording node-level transport endpoint; assert each group's OUTBOUND adapter
        // stamps ITS gid on the wire - the latent N>1 correctness (pre-fix everything would carry gid 0).
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system()); // no pool needed (groupCommit off)
        for (int gid : new int[] {0, 1, 7}) {
            RecordingEndpoint endpoint = new RecordingEndpoint();
            ConfigdServer.RaftGroupRuntime rt = ConfigdServer.buildRaftGroup(
                    gid, /*shardCount=*/8, dataDir, /*nodeStorage=*/Storage.inMemory(),
                    integrity(), Clock.system(), null, ConfigStateMachine.InvariantChecker.NOOP,
                    io.configd.raft.RaftNode.InvariantChecker.NOOP, testMetrics(),
                    RaftConfig.of(NODE, Set.of()), NODE, endpoint,
                    /*groupCommit=*/false, 4096, 0L, driver);
            assertNotNull(rt.adapter(), "peer mode must build an outbound adapter for group " + gid);

            RaftMessage msg = new AppendEntriesRequest(0L, NODE, 0L, 0L, List.of(), 0L);
            rt.adapter().send(NodeId.of(2), msg);

            assertEquals(1, endpoint.sent.size(), "exactly one frame sent for group " + gid);
            assertEquals(gid, endpoint.sent.get(0).groupId(),
                    "group " + gid + "'s outbound adapter must stamp gid=" + gid + " (not the captured 0)");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Builds a group via the real {@link ConfigdServer#buildRaftGroup}, registers + owner-binds it, and
     *  drives its owner until it self-elects LEADER (single-node cluster, no peers). */
    private ConfigdServer.RaftGroupRuntime bringUpLeader(
            MultiRaftDriver driver, int shardCount, int gid, Path dataDir, Storage nodeStorage)
            throws Exception {
        ConfigdServer.RaftGroupRuntime rt = ConfigdServer.buildRaftGroup(
                gid, shardCount, dataDir, nodeStorage, integrity(), Clock.system(),
                /*signer=*/null, ConfigStateMachine.InvariantChecker.NOOP,
                io.configd.raft.RaftNode.InvariantChecker.NOOP, testMetrics(),
                RaftConfig.of(NODE, Set.of()), NODE, /*tcpTransport=*/null,
                /*groupCommit=*/false, 4096, 0L, driver);
        driver.addGroup(gid, rt.raftNode());
        ScheduledExecutorService owner = driver.ownerExecutor(gid);
        owner.submit(() -> {
            rt.raftNode().bindOwnerThread();
            for (int i = 0; i < 500; i++) {
                rt.raftNode().tick();
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, rt.raftNode().role(),
                "single-node group " + gid + " should self-elect to LEADER");
        return rt;
    }

    /** Proposes a PUT to {@code gid} (on its owner) and drives ticks until it commits + applies. */
    private void proposeAndAwaitApply(MultiRaftDriver driver, ConfigdServer.RaftGroupRuntime rt,
            int gid, String key, byte[] value) throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value);
        ScheduledExecutorService owner = driver.ownerExecutor(gid);
        ProposeOutcome outcome = owner.submit(() -> driver.propose(gid, cmd)).get(5, TimeUnit.SECONDS);
        assertTrue(outcome.accepted(), "leader for group " + gid + " must accept the propose");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            owner.submit(() -> rt.raftNode().tick()).get(5, TimeUnit.SECONDS);
            if (rt.configStore().get(key).found()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("group " + gid + " did not apply key '" + key + "' within the budget");
    }

    private static String value(ConfigdServer.RaftGroupRuntime rt, String key) {
        return new String(rt.configStore().get(key).value(), StandardCharsets.UTF_8);
    }

    private static IntegrityEnvelope integrity() {
        // A fixed test key - the at-rest MAC is real but the key material is irrelevant to bring-up.
        return new IntegrityEnvelope(new SecretKeySpec(new byte[32], "HmacSHA256"));
    }

    private static ConfigdMetrics testMetrics() {
        return new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
    }

    /** A minimal node-level transport endpoint that records every outbound frame. */
    private static final class RecordingEndpoint implements RaftTransportEndpoint {
        final List<FrameCodec.Frame> sent = new CopyOnWriteArrayList<>();

        @Override public void send(NodeId target, Object message) {
            sent.add((FrameCodec.Frame) message);
        }
        @Override public void registerHandler(MessageHandler handler) { }
        @Override public void start() { }
        @Override public int localPort() { return 0; }
        @Override public TlsManager tlsManager() { return null; }
        @Override public long framesDropped() { return 0; }
        @Override public long inboundConnectionsRefused() { return 0; }
        @Override public void close() { }
    }
}

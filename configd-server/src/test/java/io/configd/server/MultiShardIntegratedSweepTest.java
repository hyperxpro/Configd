package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.configd.common.Clock;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource.Result;
import io.configd.distribution.FanOutBuffer;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.ProposalResult;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigMutation;
import io.configd.store.ConfigStateMachine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class MultiShardIntegratedSweepTest {

    private static final NodeId NODE = NodeId.of(1);
    private OwnerExecutorPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    @Timeout(60)
    void realBringUpComposedWithShardedFanOut_perShardIsolationInBothStoreAndFanOut(@TempDir Path dataDir)
            throws Exception {
        final int n = 4;
        final int p = 2; // P<N: owner0={0,2}, owner1={1,3} - groups share owner threads (production shape)
        pool = new OwnerExecutorPool(p);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);

        // (1) Real production bring-up: N groups via buildRaftGroup, registered + owner-bound + self-elected.
        Storage nodeStorage = Storage.file(dataDir);
        List<ConfigdServer.RaftGroupRuntime> runtimes = new ArrayList<>(n);
        for (int gid = 0; gid < n; gid++) {
            runtimes.add(bringUpLeader(driver, n, gid, dataDir, nodeStorage));
        }
        // Distinct per-shard storage (N>1) - the bring-up really sharded.
        for (int gid = 0; gid < n; gid++) {
            assertEquals(RaftRole.LEADER, runtimes.get(gid).raftNode().role(), "group " + gid + " is LEADER");
        }

        // (2) Real sharded fan-out wired over the real runtimes.
        ConfigdServer.ShardedFanOut fan = ConfigdServer.registerShardedFanOut(
                runtimes, Clock.system(), new MetricsRegistry().counter("fanout.buffer.dropped"), 10_000);
        assertEquals(n, fan.buffers().size(), "one fan-out buffer per shard");

        // (3) Commit a distinct key to each shard (on its owner), driving real consensus to apply.
        for (int gid = 0; gid < n; gid++) {
            proposeAndAwaitApply(driver, runtimes.get(gid), gid, "k" + gid,
                    ("val" + gid).getBytes(StandardCharsets.UTF_8));
        }

        // (4a) Per-shard store isolation: group k holds only its own key.
        for (int writer = 0; writer < n; writer++) {
            for (int observer = 0; observer < n; observer++) {
                boolean present = runtimes.get(observer).configStore().get("k" + writer).found();
                if (writer == observer) {
                    assertTrue(present, "group " + observer + " must hold its own key k" + writer);
                } else {
                    assertFalse(present, "STORE cross-shard leak: group " + observer + " sees k" + writer);
                }
            }
        }

        // (4b) Per-shard fan-out isolation: each shard's buffer has exactly its own commit, at
        // per-shard seq 1, carrying its own key - and no other shard's key.
        for (int gid = 0; gid < n; gid++) {
            List<CommitNotification> out = drain(fan.buffers().get(gid));
            assertEquals(1, out.size(), "shard " + gid + " fan-out buffer holds exactly its own commit");
            assertEquals(1L, out.get(0).seq(), "shard " + gid + " per-shard fan-out seq starts at 1");
            assertEquals("k" + gid, onlyKey(out.get(0)),
                    "shard " + gid + " fan-out buffer must carry ONLY its own key (no cross-shard leak)");
        }
    }

    // These helpers mirror MultiGroupBringupTest's.
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

    private void proposeAndAwaitApply(MultiRaftDriver driver, ConfigdServer.RaftGroupRuntime rt,
            int gid, String key, byte[] value) throws Exception {
        byte[] cmd = CommandCodec.encodePut(key, value);
        ScheduledExecutorService owner = driver.ownerExecutor(gid);
        ProposeOutcome outcome = owner.submit(() -> driver.propose(gid, cmd)).get(5, TimeUnit.SECONDS);
        assertTrue(outcome.result() == ProposalResult.ACCEPTED, "leader for group " + gid + " accepts");
        for (int i = 0; i < 500; i++) {
            owner.submit(() -> rt.raftNode().tick()).get(5, TimeUnit.SECONDS);
            if (rt.configStore().get(key).found()) {
                return;
            }
        }
        fail("group " + gid + " did not apply key '" + key + "' within the budget");
    }

    private static List<CommitNotification> drain(FanOutBuffer buffer) {
        Result r = buffer.readSince(0);
        assertFalse(r.isGap(), "buffer should not GAP within capacity");
        return ((Result.Ok) r).notifications();
    }

    private static String onlyKey(CommitNotification n) {
        assertEquals(1, n.delta().mutations().size(), "one mutation per single-key commit");
        return switch (n.delta().mutations().get(0)) {
            case ConfigMutation.Put put -> put.key();
            case ConfigMutation.Delete delete -> delete.key();
        };
    }

    private static IntegrityEnvelope integrity() {
        return new IntegrityEnvelope(new SecretKeySpec(new byte[32], "HmacSHA256"));
    }

    private static ConfigdMetrics testMetrics() {
        return new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
    }
}

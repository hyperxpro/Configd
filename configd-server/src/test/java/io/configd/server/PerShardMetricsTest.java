package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.api.ConfigWriteService;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.IntegrityEnvelope;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftRole;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;
import io.configd.replication.StaticShardMap;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.transport.RaftTransport;

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
import org.junit.jupiter.api.io.TempDir;

class PerShardMetricsTest {

    private static final NodeId NODE = NodeId.of(1);

    private OwnerExecutorPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void registersPerShardHealthForEveryGroupPlusLeaderCount(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdServer.registerPerShardMetrics(registry, fx.driver, fx.runtimes);

        MetricsRegistry.MetricsSnapshot snap = registry.snapshot();
        for (int gid = 0; gid < n; gid++) {
            assertNotNull(snap.metrics().get("raft.shard.commit_index." + gid),
                    "commit_index series missing for shard " + gid);
            assertNotNull(snap.metrics().get("raft.shard.last_applied." + gid));
            assertNotNull(snap.metrics().get("raft.shard.apply_lag." + gid));
            assertNotNull(snap.metrics().get("raft.shard.current_term." + gid));
            // The wedge and saturation gauges render for every shard from the first scrape (each is 0 on a
            // healthy single-node leader: no replication lag, no codec rejects, no reassembly refusal).
            assertNotNull(snap.metrics().get("raft.shard.replication_lag_max." + gid),
                    "replication_lag_max series missing for shard " + gid);
            assertNotNull(snap.metrics().get("raft.shard.append_send_rejected." + gid));
            assertNotNull(snap.metrics().get("raft.shard.snapshot_chunk_send_rejected." + gid));
            assertNotNull(snap.metrics().get("raft.shard.snapshot_reassembly_refused." + gid));
            // Each group is its own single-node LEADER, so the leader gauge is 1 and term is greater than 0.
            assertEquals(1L, snap.metrics().get("raft.shard.leader." + gid).value(),
                    "shard " + gid + " must report itself LEADER");
            assertTrue(snap.metrics().get("raft.shard.current_term." + gid).value() > 0,
                    "shard " + gid + " must have an elected term");
        }
        assertEquals((long) n, snap.metrics().get("raft.node.leader_count").value(),
                "this node leads all " + n + " single-node shards");
    }

    @Test
    void commitIndexAdvancesPerShardWithACommit(@TempDir Path dataDir) throws Exception {
        final int n = 2;
        Fixture fx = bringUp(n, dataDir);
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdServer.registerPerShardMetrics(registry, fx.driver, fx.runtimes);

        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, 5_000, metrics());
        String key = keyForShard(fx.shardMap, 0);
        assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class,
                proposer.propose(ConfigScope.GLOBAL, List.of(key),
                        CommandCodec.encodePut(key, "v".getBytes(StandardCharsets.UTF_8))),
                "the write must commit on shard 0");

        // Gauges are pull-based - a fresh snapshot reflects the post-commit state.
        long commitIdx = registry.snapshot().metrics().get("raft.shard.commit_index.0").value();
        assertTrue(commitIdx >= 1, "shard 0 commit_index must advance after a commit; got " + commitIdx);
        assertTrue(registry.snapshot().metrics().get("raft.shard.apply_lag.0").value() >= 0,
                "apply_lag must be non-negative");
    }

    @Test
    void n1RegistersExactlyTheGroupZeroSeries(@TempDir Path dataDir) throws Exception {
        Fixture fx = bringUp(1, dataDir);
        MetricsRegistry registry = new MetricsRegistry();
        ConfigdServer.registerPerShardMetrics(registry, fx.driver, fx.runtimes);

        MetricsRegistry.MetricsSnapshot snap = registry.snapshot();
        assertNotNull(snap.metrics().get("raft.shard.leader.0"), "group-0 series present at N=1");
        assertEquals(1L, snap.metrics().get("raft.node.leader_count").value());
        assertFalse(snap.metrics().containsKey("raft.shard.leader.1"),
                "N=1 must register NO shard-1 series (purely the single group)");
    }

    @Test
    void transportSaturationGaugesRenderAtZeroAndMoveWithTheEndpointCounters() {
        // The outbound-drop and inbound-refuse counters live on the transport endpoint;
        // registerTransportSaturationGauges pull-gauges them, so they must render at 0 on the first scrape
        // and reflect the endpoint's counters as they advance.
        MetricsRegistry registry = new MetricsRegistry();
        MutableEndpoint endpoint = new MutableEndpoint();
        ConfigdServer.registerTransportSaturationGauges(registry, endpoint);

        assertEquals(0L, registry.snapshot().metrics().get("configd.raft.transport.frames_dropped").value(),
                "frames_dropped must render at 0 on the first scrape");
        assertEquals(0L, registry.snapshot().metrics()
                        .get("configd.raft.transport.inbound_connections_refused").value(),
                "inbound_connections_refused must render at 0 on the first scrape");

        endpoint.framesDropped = 7;
        endpoint.inboundConnectionsRefused = 3;
        assertEquals(7L, registry.snapshot().metrics().get("configd.raft.transport.frames_dropped").value(),
                "the frames_dropped gauge must track the endpoint's drop counter");
        assertEquals(3L, registry.snapshot().metrics()
                        .get("configd.raft.transport.inbound_connections_refused").value(),
                "the inbound_connections_refused gauge must track the endpoint's refusal counter");
    }

    /** A {@link io.configd.transport.RaftTransportEndpoint} whose saturation counters are settable, so the
     *  pull gauges can be driven off a known value without standing up a real socket transport. */
    private static final class MutableEndpoint implements io.configd.transport.RaftTransportEndpoint {
        volatile long framesDropped;
        volatile long inboundConnectionsRefused;

        @Override public void send(NodeId target, Object message) { }
        @Override public void registerHandler(RaftTransport.MessageHandler handler) { }
        @Override public void start() { }
        @Override public int localPort() { return 0; }
        @Override public io.configd.transport.TlsManager tlsManager() { return null; }
        @Override public long framesDropped() { return framesDropped; }
        @Override public long inboundConnectionsRefused() { return inboundConnectionsRefused; }
        @Override public void close() { }
    }

    private record Fixture(MultiRaftDriver driver, StaticShardMap shardMap,
                           List<ConfigdServer.RaftGroupRuntime> runtimes) {}

    private Fixture bringUp(int n, Path dataDir) throws Exception {
        pool = new OwnerExecutorPool(n);
        MultiRaftDriver driver = new MultiRaftDriver(NODE, Clock.system());
        driver.setOwnerPool(pool);
        StaticShardMap shardMap = new StaticShardMap(n);
        Storage nodeStorage = Storage.file(dataDir);
        List<ConfigdServer.RaftGroupRuntime> runtimes = new ArrayList<>(n);
        for (int gid = 0; gid < n; gid++) {
            ConfigdServer.RaftGroupRuntime rt = ConfigdServer.buildRaftGroup(
                    gid, n, dataDir, nodeStorage, integrity(), Clock.system(),
                    null, ConfigStateMachine.InvariantChecker.NOOP,
                    io.configd.raft.RaftNode.InvariantChecker.NOOP, metrics(),
                    RaftConfig.of(NODE, Set.of()), NODE, null, false, 4096, 0L, driver);
            driver.addGroup(gid, rt.raftNode());
            ScheduledExecutorService owner = driver.ownerExecutor(gid);
            owner.submit(() -> {
                rt.raftNode().bindOwnerThread();
                for (int i = 0; i < 500; i++) {
                    rt.raftNode().tick();
                }
            }).get(10, TimeUnit.SECONDS);
            assertEquals(RaftRole.LEADER, rt.raftNode().role(), "group " + gid + " must self-elect");
            runtimes.add(rt);
        }
        return new Fixture(driver, shardMap, runtimes);
    }

    private static String keyForShard(StaticShardMap map, int targetShard) {
        for (int i = 0; i < 100_000; i++) {
            String cand = "key" + i;
            if (map.shardFor(ConfigScope.GLOBAL, cand) == targetShard) {
                return cand;
            }
        }
        throw new AssertionError("no key for shard " + targetShard);
    }

    private static IntegrityEnvelope integrity() {
        return new IntegrityEnvelope(new SecretKeySpec(new byte[32], "HmacSHA256"));
    }

    private static ConfigdMetrics metrics() {
        return new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
    }
}

package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.api.ConfigReadService;
import io.configd.api.ConfigWriteService;
import io.configd.api.HealthService;
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
import io.configd.store.VersionedConfigStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The LIVE write/read routing proof. Drives the REAL production seams
 * ({@link ConfigdServer#raftProposer(MultiRaftDriver, StaticShardMap, long, ConfigdMetrics)} and
 * {@link ConfigdServer#shardedConfigReader}) over N real single-node groups (built via the Seam-C
 * {@code buildRaftGroup} + owner pool), so this is live routing - not a sim.
 *
 * <ul>
 *   <li><b>Write routing + isolation (S2/S4):</b> a write for key k routes to {@code shardFor(GLOBAL,k)}'s
 *       group and applies to THAT shard's store only; no sibling shard sees it.</li>
 *   <li><b>Read routing:</b> the sharded reader resolves the SAME shard the writer used, so a read of k
 *       returns the committed value; {@code getPrefix} scatter-gathers across shards.</li>
 *   <li><b>Cross-shard DISCLAIM guard:</b> a multi-key write whose keys span shards is rejected
 *       ({@code CrossShardRejected}) before any Raft work.</li>
 *   <li><b>Shard-aware redirect:</b> the leader hint resolves the OWNING shard's leader.</li>
 *   <li><b>N=1 byte-identity:</b> the sharded reader over one group is the single store.</li>
 * </ul>
 */
class ShardedRoutingTest {

    private static final NodeId NODE = NodeId.of(1);
    private static final ConfigScope SCOPE = ConfigScope.GLOBAL;
    private static final long TIMEOUT_MS = 5_000;

    private OwnerExecutorPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    // ---- write routing + per-shard isolation ------------------------------------------------

    @Test
    void writeRoutesToOwningShardAndIsolatesAcrossShards(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics());

        // Use keys that demonstrably spread across shards; commit each via the PRODUCTION proposer.
        List<String> keys = List.of("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf");
        boolean sawMultipleShards = false;
        int firstShard = fx.shardMap.shardFor(SCOPE, keys.get(0));
        for (String key : keys) {
            var result = proposer.propose(SCOPE, List.of(key), put(key, "v-" + key));
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class, result,
                    "single-node leader must commit-confirm the write for " + key);
            int shard = fx.shardMap.shardFor(SCOPE, key);
            if (shard != firstShard) {
                sawMultipleShards = true;
            }
            // The key lands in its OWNING shard's store...
            assertTrue(fx.runtimes.get(shard).configStore().get(key).found(),
                    "key '" + key + "' must be applied to its owning shard " + shard);
            // ...and in NO other shard's store (cross-shard isolation).
            for (int g = 0; g < n; g++) {
                if (g != shard) {
                    assertFalse(fx.runtimes.get(g).configStore().get(key).found(),
                            "key '" + key + "' must NOT appear in non-owning shard " + g);
                }
            }
        }
        assertTrue(sawMultipleShards, "vacuity: the test keys must span more than one shard at N=" + n);
    }

    // ---- read routing: the sharded reader resolves the same shard the writer used -----------

    @Test
    void shardedReaderReadsTheOwningShardAndScatterGathersPrefix(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics());
        ConfigReadService.ConfigReader reader =
                ConfigdServer.shardedConfigReader(fx.shardMap, fx.runtimesByGid, fx.runtimes, SCOPE);

        List<String> keys = List.of("cfg/a", "cfg/b", "cfg/c", "cfg/d", "cfg/e", "cfg/f");
        for (String key : keys) {
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class,
                    proposer.propose(SCOPE, List.of(key), put(key, "val-" + key)));
        }
        // The reader resolves the SAME shard the writer used -> every committed key is visible.
        for (String key : keys) {
            io.configd.store.ReadResult rr = reader.get(key);
            assertTrue(rr.found(), "sharded reader must find committed key '" + key + "'");
            assertEquals("val-" + key, new String(rr.value(), StandardCharsets.UTF_8));
        }
        // Scatter-gather: a prefix whose keys hash to different shards is merged across all shards.
        Map<String, io.configd.store.ReadResult> prefix = reader.getPrefix("cfg/");
        for (String key : keys) {
            assertTrue(prefix.containsKey(key),
                    "getPrefix must scatter-gather '" + key + "' from its shard (merged view)");
        }
    }

    @Test
    void n1ShardedReaderIsTheSingleStore(@TempDir Path dataDir) throws Exception {
        Fixture fx = bringUp(1, dataDir);
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics());
        ConfigReadService.ConfigReader reader =
                ConfigdServer.shardedConfigReader(fx.shardMap, fx.runtimesByGid, fx.runtimes, SCOPE);

        assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class,
                proposer.propose(SCOPE, List.of("only"), put("only", "1")));
        assertEquals("1", new String(reader.get("only").value(), StandardCharsets.UTF_8));
        assertEquals(1, fx.runtimes.size(), "N=1 ⇒ exactly one shard");
    }

    // ---- cross-shard DISCLAIM guard on the live write path ----------------------------------

    @Test
    void crossShardMultiKeyWriteIsRejected(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics());

        // Find two keys that resolve to DIFFERENT shards.
        String[] spanning = twoKeysOnDifferentShards(fx.shardMap);
        var result = proposer.propose(SCOPE, List.of(spanning[0], spanning[1]),
                put(spanning[0], "x")); // command body is irrelevant - rejected pre-append
        var rejected = assertInstanceOf(ConfigWriteService.ProposeCommitResult.CrossShardRejected.class,
                result, "a multi-key write spanning shards must be rejected (DISCLAIM)");
        assertTrue(rejected.reason().contains("cross-shard"),
                "rejection must carry a clear cross-shard reason: " + rejected.reason());

        // And a co-located multi-key write (both keys on ONE shard) is NOT rejected by the guard.
        String[] coLocated = twoKeysOnSameShard(fx.shardMap);
        var ok = proposer.propose(SCOPE, List.of(coLocated[0], coLocated[1]), put(coLocated[0], "y"));
        assertFalse(ok instanceof ConfigWriteService.ProposeCommitResult.CrossShardRejected,
                "a co-located multi-key write must NOT be cross-shard rejected");
    }

    // ---- shard-aware leader redirect --------------------------------------------------------

    @Test
    void leaderHintResolvesTheOwningShardLeader(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        // The exact lambda the server wires as the ConfigWriteService LeaderHintSupplier.
        ConfigWriteService.LeaderHintSupplier hint = (scope, key) -> {
            io.configd.raft.RaftNode owner = fx.driver.getGroup(fx.shardMap.shardFor(scope, key));
            return owner != null ? owner.leaderId() : null;
        };
        // Every group is its own single-node leader (NODE), so the hint for ANY key is NODE - but the
        // point is that it is resolved on the OWNING shard's node, not a captured group-0 node.
        for (String key : List.of("alpha", "bravo", "charlie", "delta", "echo")) {
            int shard = fx.shardMap.shardFor(SCOPE, key);
            assertEquals(fx.driver.getGroup(shard).leaderId(), hint.currentLeader(SCOPE, key),
                    "leader hint for '" + key + "' must come from its owning shard " + shard);
            assertEquals(NODE, hint.currentLeader(SCOPE, key), "single-node leader is NODE");
        }
    }

    // ---- the LIVE HTTP read path is sharded (closes the reviewers' test-gap) ----------------

    @Test
    void staleGetIsShardedThroughTheHttpHandler(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics());
        ConfigReadService.ConfigReader reader =
                ConfigdServer.shardedConfigReader(fx.shardMap, fx.runtimesByGid, fx.runtimes, SCOPE);
        // Stale path doesn't confirm leadership, but the service requires a (now keyed+scoped) confirmer.
        ConfigReadService readService = new ConfigReadService(reader, (scope, key) -> true);

        // A key on a shard k!=0 - so reading the captured GROUP-0 store (the pre-fix bug) would 404.
        String key = keyOnNonZeroShard(fx.shardMap);
        int shard = fx.shardMap.shardFor(SCOPE, key);
        assertNotEquals(0, shard, "test key must be on a non-zero shard for non-vacuity");
        assertInstanceOf(ConfigWriteService.ProposeCommitResult.Committed.class,
                proposer.propose(SCOPE, List.of(key), put(key, "sharded-value")));

        VersionedConfigStore group0Store = fx.runtimesByGid.get(0).configStore();
        assertFalse(group0Store.get(key).found(),
                "control: a shard-" + shard + " key must NOT be in the group-0 store");

        // Wire the handler EXACTLY as the server does: stale reads via the sharded readService, keyed hint,
        // and the legacy group-0 configStore as the (now-unused for sharded reads) direct field. Auth off.
        AdminApiHandler handler = new AdminApiHandler(
                new HealthService(), /* exporter */ null, group0Store, /* writeService */ null,
                readService, /* auth */ null, /* acl */ null, StrongReadPolicy.defaultPolicy(),
                (scope, k) -> {
                    io.configd.raft.RaftNode owner = fx.driver.getGroup(fx.shardMap.shardFor(scope, k));
                    return owner != null ? owner.leaderId() : null;
                },
                /* auditLog */ null, /* replayGuard */ null);

        AdminApiHandler.AdminResponse resp = handler.handle(getReq("/v1/config/" + key));
        assertEquals(200, resp.status(),
                "a stale GET of a shard-" + shard + " key must be served via the sharded reader, not 404 "
                        + "from the group-0 store (the pre-fix BLOCKER)");
        assertEquals("sharded-value", new String(resp.body(), StandardCharsets.UTF_8));
    }

    // ---- Wiring Increment 1: read-your-writes per scope (the same (scope,key) routes write + read) ----

    @Test
    void scopeAwareReadYourWritesAtN1ForEveryScope(@TempDir Path dataDir) throws Exception {
        Fixture fx = bringUp(1, dataDir);
        ConfigWriteService writeService = new ConfigWriteService(
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics()), null, null);
        ConfigReadService.ConfigReader reader =
                ConfigdServer.shardedConfigReader(fx.shardMap, fx.runtimesByGid, fx.runtimes, ConfigScope.GLOBAL);
        ConfigReadService readService = new ConfigReadService(reader, (scope, key) -> true);

        // For EVERY scope, a write to (scope, key) is read back from (scope, key). At N=1 every scope =>
        // group 0, so read-your-writes holds and is byte-identical regardless of scope (the increment is
        // a no-op on routing at N=1).
        for (ConfigScope scope : ConfigScope.values()) {
            String key = "ryow-" + scope;
            assertInstanceOf(ConfigWriteService.WriteResult.Committed.class,
                    writeService.put(key, ("val-" + scope).getBytes(StandardCharsets.UTF_8), scope),
                    "write of (" + scope + ", " + key + ") must commit");
            io.configd.store.ReadResult rr = readService.staleRead(scope, key);
            assertTrue(rr.found(), "read of (" + scope + ", " + key + ") must see its own write");
            assertEquals("val-" + scope, new String(rr.value(), StandardCharsets.UTF_8));
        }
        assertEquals(1, fx.runtimes.size(), "N=1 ⇒ one shard; every scope resolved group 0");
    }

    @Test
    void scopeIsLoadBearingForRoutingAtNGreaterThanOne(@TempDir Path dataDir) throws Exception {
        final int n = 4;
        Fixture fx = bringUp(n, dataDir);
        ConfigWriteService writeService = new ConfigWriteService(
                ConfigdServer.raftProposer(fx.driver, fx.shardMap, TIMEOUT_MS, metrics()), null, null);
        ConfigReadService.ConfigReader reader =
                ConfigdServer.shardedConfigReader(fx.shardMap, fx.runtimesByGid, fx.runtimes, ConfigScope.GLOBAL);

        // A key whose REGIONAL shard differs from its GLOBAL shard (scope folds into the hash, so the
        // wiring is NON-vacuous: scope genuinely selects a different shard at N>1).
        String key = keyWithDifferentShardPerScope(fx.shardMap, ConfigScope.GLOBAL, ConfigScope.REGIONAL);
        int regionalShard = fx.shardMap.shardFor(ConfigScope.REGIONAL, key);
        int globalShard = fx.shardMap.shardFor(ConfigScope.GLOBAL, key);
        assertNotEquals(globalShard, regionalShard, "vacuity: the test key must route differently per scope");

        // Write under REGIONAL -> lands in REGIONAL's shard.
        assertInstanceOf(ConfigWriteService.WriteResult.Committed.class,
                writeService.put(key, "regional-value".getBytes(StandardCharsets.UTF_8), ConfigScope.REGIONAL));

        // Read-your-writes holds ONLY for the matching scope: a REGIONAL read hits its shard; a GLOBAL
        // read routes to a DIFFERENT shard and misses. This proves the read path actually routes by the
        // caller's scope (not vacuously GLOBAL) - the core read-your-writes-per-scope guarantee at N>1.
        io.configd.store.ReadResult matched = reader.get(ConfigScope.REGIONAL, key);
        assertTrue(matched.found(), "a REGIONAL read of a REGIONAL write must hit its shard " + regionalShard);
        assertEquals("regional-value", new String(matched.value(), StandardCharsets.UTF_8));

        io.configd.store.ReadResult mismatched = reader.get(ConfigScope.GLOBAL, key);
        assertFalse(mismatched.found(),
                "a GLOBAL read routes to a DIFFERENT shard (" + globalShard + ") and must MISS the REGIONAL write");
    }

    private static String keyWithDifferentShardPerScope(StaticShardMap map, ConfigScope a, ConfigScope b) {
        for (int i = 0; i < 100_000; i++) {
            String cand = "scopekey" + i;
            if (map.shardFor(a, cand) != map.shardFor(b, cand)) {
                return cand;
            }
        }
        throw new AssertionError("could not find a key routing differently for " + a + " vs " + b);
    }

    // ---- fixture / helpers ------------------------------------------------------------------

    private record Fixture(MultiRaftDriver driver, StaticShardMap shardMap,
                           List<ConfigdServer.RaftGroupRuntime> runtimes,
                           Map<Integer, ConfigdServer.RaftGroupRuntime> runtimesByGid) {}

    /** Builds N single-node groups via the real buildRaftGroup, owner-binds + self-elects each. */
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
                    /*signer=*/null, ConfigStateMachine.InvariantChecker.NOOP,
                    io.configd.raft.RaftNode.InvariantChecker.NOOP, metrics(),
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
            assertEquals(RaftRole.LEADER, rt.raftNode().role(), "group " + gid + " must self-elect");
            runtimes.add(rt);
        }
        Map<Integer, ConfigdServer.RaftGroupRuntime> byGid = new HashMap<>();
        for (ConfigdServer.RaftGroupRuntime rt : runtimes) {
            byGid.put(rt.groupId(), rt);
        }
        return new Fixture(driver, shardMap, runtimes, byGid);
    }

    private static String[] twoKeysOnDifferentShards(StaticShardMap map) {
        String base = "k0";
        int s0 = map.shardFor(SCOPE, base);
        for (int i = 1; i < 10_000; i++) {
            String cand = "k" + i;
            if (map.shardFor(SCOPE, cand) != s0) {
                return new String[] {base, cand};
            }
        }
        throw new AssertionError("could not find two keys on different shards");
    }

    private static String[] twoKeysOnSameShard(StaticShardMap map) {
        String base = "k0";
        int s0 = map.shardFor(SCOPE, base);
        for (int i = 1; i < 10_000; i++) {
            String cand = "k" + i;
            if (map.shardFor(SCOPE, cand) == s0) {
                return new String[] {base, cand};
            }
        }
        throw new AssertionError("could not find two co-located keys");
    }

    private static String keyOnNonZeroShard(StaticShardMap map) {
        for (int i = 0; i < 100_000; i++) {
            String cand = "key" + i;
            if (map.shardFor(SCOPE, cand) != 0) {
                return cand;
            }
        }
        throw new AssertionError("could not find a key on a non-zero shard");
    }

    private static AdminApiHandler.AdminRequest getReq(String path) {
        return new AdminApiHandler.AdminRequest() {
            @Override public String method() { return "GET"; }
            @Override public URI uri() { return URI.create(path); }
            @Override public String header(String name) { return null; }
            @Override public byte[] body() { return new byte[0]; }
        };
    }

    private static byte[] put(String key, String value) {
        return CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static IntegrityEnvelope integrity() {
        return new IntegrityEnvelope(new SecretKeySpec(new byte[32], "HmacSHA256"));
    }

    private static ConfigdMetrics metrics() {
        return new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
    }
}

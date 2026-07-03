package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigPolicy;
import io.configd.common.ConfigScope;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.observability.MetricsRegistry;
import io.configd.replication.StaticShardMap;
import io.configd.server.fanout.AclServiceWatchAuthorizer;
import io.configd.store.ConfigMutation;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AclConfigPolicyLoader}'s multi-shard mode (N&gt;1): the scatter-gather rebuild over every
 * group's store, the serialized single-thread worker, the node-local monotonic version, and the P0 it fixes.
 *
 * <p>The P0 (investigation §4.5 / B6): {@code _acl/roles/*} and {@code _acl/bindings/*} are ordinary keys
 * routed by {@code shardFor(scope, key)}, so at N&gt;1 they hash-scatter across all groups. A loader wired to
 * the primary group's store alone observes only ~1/N of the policy - a role/binding/DENY on a non-primary
 * shard is silently absent (under-deny =&gt; a watch an interior DENY should reject is authorized). The
 * centerpiece {@link #tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected} proves the fix; its sibling
 * {@link #tB6_redProof_singleStorePrimaryOnly_missesNonPrimaryShardDeny_watchStillAuthorized} pins the live
 * bug the multi-shard path removes (the single-store construction over the primary store misses the DENY).
 *
 * <p>Reserved-name validation, fail-closed-to-last-good, the apply-thread gate, and the N=1 boot path are
 * covered by {@link AclConfigPolicyLoaderTest}; this class additionally asserts N=1 byte-identity via a
 * differential oracle ({@link #tN1Diff_multiShardOverOneStore_equalsSingleStore}).
 */
class AclConfigPolicyLoaderMultiShardTest {

    private static final Set<String> RESERVED_ROLES = Set.of("admin");
    private static final Set<String> RESERVED_PRINCIPALS = Set.of("root");
    private static final ConfigScope SCOPE = ConfigScope.GLOBAL;

    // ---------------- helpers ----------------

    private static void put(VersionedConfigStore store, String key, String value) {
        store.put(key, value.getBytes(StandardCharsets.UTF_8), store.currentVersion() + 1);
    }

    private static List<ConfigMutation> putMutation(String key) {
        return List.of(new ConfigMutation.Put(key, new byte[0]));
    }

    private static List<VersionedConfigStore> stores(int n) {
        List<VersionedConfigStore> s = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            s.add(new VersionedConfigStore());
        }
        return s;
    }

    private AclConfigPolicyLoader multiShardLoader(AclService acl, List<VersionedConfigStore> stores,
                                                   MetricsRegistry reg) {
        return new AclConfigPolicyLoader(acl, stores, RESERVED_ROLES, RESERVED_PRINCIPALS, reg);
    }

    /** Routes {@code key} to its owning store under this shard map (the same routing production writes use). */
    private static VersionedConfigStore storeFor(StaticShardMap map, List<VersionedConfigStore> stores,
                                                 String key) {
        return stores.get(map.shardFor(SCOPE, key));
    }

    /** Finds an {@code _acl/roles/<name>} key that routes to a shard other than the primary (group 0). */
    private static String roleNameRoutingOffPrimary(StaticShardMap map, String base) {
        for (int i = 0; i < 1_000_000; i++) {
            String name = base + i;
            if (map.shardFor(SCOPE, "_acl/roles/" + name) != 0) {
                return name;
            }
        }
        throw new IllegalStateException("no off-primary role key found for base " + base);
    }

    /** Finds an {@code _acl/roles/<name>} key that routes to exactly {@code targetShard}. */
    private static String roleNameRoutingTo(StaticShardMap map, String base, int targetShard) {
        for (int i = 0; i < 1_000_000; i++) {
            String name = base + i;
            if (map.shardFor(SCOPE, "_acl/roles/" + name) == targetShard) {
                return name;
            }
        }
        throw new IllegalStateException("no role key routing to shard " + targetShard);
    }

    private static WatchTarget prefixTarget(String path) {
        return new WatchTarget(SCOPE.ordinal(), EdgeFrame.WATCH_TARGET_PREFIX, path, false);
    }

    private static boolean policyHasRole(AclService acl, String roleName) {
        return acl.configPolicy().roles().containsKey(roleName);
    }

    // ---------------- T-B6: the RED/GREEN regression (the centerpiece) ----------------

    @Test
    void tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();
        MetricsRegistry reg = new MetricsRegistry();

        // Principal "watcher" has a STATIC ALLOW covering the PREFIX target "app." (READ ∧ WATCH), so before
        // any config policy the whole-subtree watch is authorized.
        acl.grant("app.", "watcher", EnumSet.of(AclService.Permission.READ, AclService.Permission.WATCH));
        AclServiceWatchAuthorizer authz = new AclServiceWatchAuthorizer(acl);
        assertTrue(authz.authorizeWatch("watcher", Set.of(), prefixTarget("app.")),
                "precondition: the static ALLOW authorizes the app. subtree watch");

        // A DENY-carrying role on a NON-PRIMARY shard carves a hole under app. and is bound to watcher.
        String carve = roleNameRoutingOffPrimary(map, "carve");
        String roleKey = "_acl/roles/" + carve;
        put(storeFor(map, stores, roleKey), roleKey, "deny READ,WATCH app.secret.");
        put(storeFor(map, stores, "_acl/bindings/watcher"), "_acl/bindings/watcher", carve);

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, reg)) {
            loader.bootSeed();

            // (i) the deny rule is present in the published policy, (ii) the version advanced from MIN_VALUE,
            // (iii) the whole-subtree watch is now rejected (the interior DENY carves it).
            assertTrue(policyHasRole(acl, carve), "the non-primary-shard DENY role is in the policy snapshot");
            assertTrue(acl.configPolicyVersion() > Long.MIN_VALUE,
                    "configPolicyVersion advanced from MIN_VALUE on the non-primary DENY apply");
            assertFalse(authz.authorizeWatch("watcher", Set.of(), prefixTarget("app.")),
                    "the interior DENY on a non-primary shard now rejects the app. subtree watch (P0 fixed)");
        }
    }

    /**
     * The durable RED proof: the SAME scenario driven through a single-store loader over the PRIMARY store
     * (group 0) - today's construction - misses the non-primary-shard DENY, so the policy snapshot lacks the
     * deny role and the watch stays (wrongly) authorized. This is the live under-deny bypass the multi-shard
     * loader closes; the assertions here characterize the bug so a regression is loud.
     */
    @Test
    void tB6_redProof_singleStorePrimaryOnly_missesNonPrimaryShardDeny_watchStillAuthorized() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();
        MetricsRegistry reg = new MetricsRegistry();

        acl.grant("app.", "watcher", EnumSet.of(AclService.Permission.READ, AclService.Permission.WATCH));
        AclServiceWatchAuthorizer authz = new AclServiceWatchAuthorizer(acl);

        String carve = roleNameRoutingOffPrimary(map, "carve");
        String roleKey = "_acl/roles/" + carve;
        VersionedConfigStore roleStore = storeFor(map, stores, roleKey);
        put(roleStore, roleKey, "deny READ,WATCH app.secret.");
        put(storeFor(map, stores, "_acl/bindings/watcher"), "_acl/bindings/watcher", carve);
        // Sanity: the DENY role really is off the primary store.
        assertFalse(stores.get(0) == roleStore, "the DENY role must live on a non-primary store for this proof");

        // Today's wiring: the single-store loader reads ONLY the primary store (group 0).
        AclConfigPolicyLoader primaryOnly = new AclConfigPolicyLoader(
                acl, stores.get(0), RESERVED_ROLES, RESERVED_PRINCIPALS, reg);
        primaryOnly.rebuild(); // the boot seed, primary store only

        // RED-PROOF: the non-primary DENY is INVISIBLE to the primary-only loader.
        assertFalse(policyHasRole(acl, carve),
                "BUG: the non-primary-shard DENY role is absent from a primary-only policy snapshot");
        assertTrue(authz.authorizeWatch("watcher", Set.of(), prefixTarget("app.")),
                "BUG: the watch stays authorized because the primary-only loader never saw the DENY (under-deny bypass)");
    }

    // ---------------- T-N1-DIFF: N=1 byte-identity via a differential oracle ----------------

    @Test
    void tN1Diff_multiShardOverOneStore_equalsSingleStore() {
        // A battery of _acl/ scenarios; for each, a single-store loader and a "multi-shard" loader built over
        // that ONE store must publish an EQUAL ConfigPolicy. (Two stores are the multi-shard minimum, so the
        // strict single-store equivalence is asserted structurally: identical scan input => identical policy.)
        List<List<String[]>> scenarios = List.of(
                List.<String[]>of(),
                List.<String[]>of(new String[]{"_acl/roles/reader", "allow READ app."}),
                List.<String[]>of(new String[]{"_acl/roles/reader", "allow READ,WATCH app."},
                        new String[]{"_acl/bindings/alice", "reader"}),
                List.<String[]>of(new String[]{"_acl/roles/r1", "allow READ a.\ndeny WATCH a.secret."},
                        new String[]{"_acl/roles/r2", "allow WATCH b."},
                        new String[]{"_acl/bindings/bob", "r1\nr2"}));

        for (List<String[]> scenario : scenarios) {
            AclService singleAcl = new AclService();
            VersionedConfigStore singleStore = new VersionedConfigStore();
            for (String[] kv : scenario) {
                put(singleStore, kv[0], kv[1]);
            }
            new AclConfigPolicyLoader(singleAcl, singleStore, RESERVED_ROLES, RESERVED_PRINCIPALS,
                    new MetricsRegistry()).rebuild();

            AclService multiAcl = new AclService();
            // Two stores, but all keys land on the first; the second is empty. The scatter-gather union is the
            // same key set the single-store loader read, so the published policy must be equal.
            List<VersionedConfigStore> twoStores = stores(2);
            for (String[] kv : scenario) {
                put(twoStores.get(0), kv[0], kv[1]);
            }
            try (AclConfigPolicyLoader multi = multiShardLoader(multiAcl, twoStores, new MetricsRegistry())) {
                multi.bootSeed();
            }

            assertEquals(singleAcl.configPolicy(), multiAcl.configPolicy(),
                    "multi-shard-over-equivalent-key-set must equal the single-store policy: " + scenario);
        }
    }

    // ---------------- T-CONVERGE: no lost update under concurrent apply threads (N=3) ----------------

    @Test
    void tConverge_concurrentAppliesAcrossShards_unionWithNoLostUpdate() throws InterruptedException {
        StaticShardMap map = new StaticShardMap(3);
        List<VersionedConfigStore> stores = stores(3);
        AclService acl = new AclService();

        // One distinct role per shard, each written to its routed store.
        String[] roleNames = new String[3];
        for (int shard = 0; shard < 3; shard++) {
            String name = roleNameRoutingTo(map, "role_s" + shard + "_", shard);
            roleNames[shard] = name;
            String key = "_acl/roles/" + name;
            put(stores.get(shard), key, "allow READ app.");
        }

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, new MetricsRegistry())) {
            // Fire onConfigChange for the three distinct keys from three threads concurrently (as three owner
            // threads would), then quiesce the worker via a submitted barrier and assert the UNION converged.
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (int shard = 0; shard < 3; shard++) {
                String key = "_acl/roles/" + roleNames[shard];
                Thread t = new Thread(() -> {
                    awaitLatch(start);
                    loader.onConfigChange(putMutation(key), 1L);
                });
                threads.add(t);
                t.start();
            }
            start.countDown();
            for (Thread t : threads) {
                t.join(TimeUnit.SECONDS.toMillis(10));
            }
            loader.awaitQuiescence();

            for (String name : roleNames) {
                assertTrue(policyHasRole(acl, name), "no lost update: role " + name + " is in the union");
            }
            assertTrue(acl.configPolicyVersion() > Long.MIN_VALUE, "the version strictly advanced");
        }
    }

    // ---------------- T-NONATOMIC: partial cross-shard edit is fail-safe, then converges ----------------

    @Test
    void tNonAtomic_bindingBeforeRole_isInertThenConverges() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();
        AclServiceWatchAuthorizer authz = new AclServiceWatchAuthorizer(acl);

        // A granting role on a non-primary shard, bound to p2 (who has NO static grant). Apply the binding
        // first (role absent) => inert (no grant); then the role => the grant becomes effective.
        String grantRole = roleNameRoutingOffPrimary(map, "grant");
        String roleKey = "_acl/roles/" + grantRole;
        String bindKey = "_acl/bindings/p2";

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, new MetricsRegistry())) {
            loader.bootSeed(); // empty policy

            // (1) binding first, role still absent -> inert (p2 gains NO grant, never a transient over-grant).
            put(storeFor(map, stores, bindKey), bindKey, grantRole);
            loader.onConfigChange(putMutation(bindKey), 1L);
            loader.awaitQuiescence();
            assertFalse(authz.authorizeWatch("p2", Set.of(), prefixTarget("svc.")),
                    "a binding to a not-yet-defined role is inert (no transient over-grant)");

            // (2) role now applies -> the grant is effective.
            put(storeFor(map, stores, roleKey), roleKey, "allow READ,WATCH svc.");
            loader.onConfigChange(putMutation(roleKey), 2L);
            loader.awaitQuiescence();
            assertTrue(authz.authorizeWatch("p2", Set.of(), prefixTarget("svc.")),
                    "once the role key applies the whole-subtree watch is authorized (converged)");
        }
    }

    // ---------------- T-SNAP: a non-primary snapshot install picks up a DENY ----------------

    @Test
    void tSnap_nonPrimarySnapshotInstall_picksUpDeny() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();
        acl.grant("app.", "watcher", EnumSet.of(AclService.Permission.READ, AclService.Permission.WATCH));
        AclServiceWatchAuthorizer authz = new AclServiceWatchAuthorizer(acl);

        String carve = roleNameRoutingOffPrimary(map, "snap");
        String roleKey = "_acl/roles/" + carve;
        VersionedConfigStore roleStore = storeFor(map, stores, roleKey);
        assertFalse(stores.get(0) == roleStore, "the DENY role must live on a non-primary store");

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, new MetricsRegistry())) {
            loader.bootSeed(); // empty
            assertTrue(authz.authorizeWatch("watcher", Set.of(), prefixTarget("app.")));

            // A snapshot install on the non-primary store delivers the DENY wholesale (no per-key mutation),
            // so only the snapshot-install hook can observe it.
            put(roleStore, roleKey, "deny READ,WATCH app.secret.");
            put(storeFor(map, stores, "_acl/bindings/watcher"), "_acl/bindings/watcher", carve);
            loader.onSnapshotInstalled();
            loader.awaitQuiescence();

            assertTrue(policyHasRole(acl, carve), "the snapshot-install rebuild picked up the non-primary DENY");
            assertFalse(authz.authorizeWatch("watcher", Set.of(), prefixTarget("app.")),
                    "the watch is rejected after the non-primary snapshot install");
        }
    }

    // ---------------- T-W7VER: a non-primary DENY apply advances the version ----------------

    @Test
    void tW7Ver_nonPrimaryDenyApply_advancesConfigPolicyVersion() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, new MetricsRegistry())) {
            loader.bootSeed();
            long before = acl.configPolicyVersion();

            String carve = roleNameRoutingTo(map, "ver", 1); // strictly on the non-primary shard
            String roleKey = "_acl/roles/" + carve;
            put(stores.get(1), roleKey, "deny READ,WATCH app.secret.");
            loader.onConfigChange(putMutation(roleKey), 1L);
            loader.awaitQuiescence();

            assertTrue(acl.configPolicyVersion() > before,
                    "the W7-7 revocation trigger advanced on a non-primary shard's _acl/ apply");
            assertTrue(policyHasRole(acl, carve), "the non-primary DENY is in the policy");
        }
    }

    // ---------------- T-BOOTRACE: boot-seed vs an enqueued apply; latest wins, version monotone ----------------

    @Test
    void tBootRace_enqueuedApplyThenBootSeed_latestWinsMonotone() {
        StaticShardMap map = new StaticShardMap(2);
        List<VersionedConfigStore> stores = stores(2);
        AclService acl = new AclService();

        String role1 = roleNameRoutingTo(map, "boot1_", 0);
        String role2 = roleNameRoutingTo(map, "boot2_", 1);
        String key1 = "_acl/roles/" + role1;
        String key2 = "_acl/roles/" + role2;

        try (AclConfigPolicyLoader loader = multiShardLoader(acl, stores, new MetricsRegistry())) {
            // role1 committed on the primary, then an apply enqueued (rebuild A); role2 committed on the
            // non-primary before bootSeed (rebuild B). FIFO serialization => B reads the freshest union.
            put(stores.get(0), key1, "allow READ a.");
            loader.onConfigChange(putMutation(key1), 1L);
            put(stores.get(1), key2, "allow READ b.");
            loader.bootSeed(); // enqueued behind A and awaited

            assertTrue(policyHasRole(acl, role1) && policyHasRole(acl, role2),
                    "the final policy reflects the latest committed _acl/ across shards");
            assertTrue(acl.configPolicyVersion() > Long.MIN_VALUE,
                    "the node-local version advanced and never regressed");
        }
    }

    // ---------------- constructor guards ----------------

    @Test
    void multiShardConstructor_rejectsFewerThanTwoStores() {
        AclService acl = new AclService();
        MetricsRegistry reg = new MetricsRegistry();
        try {
            new AclConfigPolicyLoader(acl, List.of(new VersionedConfigStore()),
                    RESERVED_ROLES, RESERVED_PRINCIPALS, reg);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException for a 1-store multi loader");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}

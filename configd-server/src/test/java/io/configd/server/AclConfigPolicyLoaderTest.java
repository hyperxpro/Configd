package io.configd.server;

import io.configd.api.AclService;
import io.configd.api.ConfigPolicy;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigMutation;
import io.configd.store.VersionedConfigStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AclConfigPolicyLoader}: the idempotent whole-subtree rebuild, the
 * apply-thread gate (no {@code _acl/} touch => no rebuild), the snapshot-install hook, fail-closed-to-
 * last-good on malformed/reserved policy (never deny-all/allow-all), and the load-bearing proof that no
 * config-loaded role can carve the break-glass root principal (reserved-name validation, N2/N3).
 */
class AclConfigPolicyLoaderTest {

    private static final Set<String> RESERVED_ROLES = Set.of("admin");
    private static final Set<String> RESERVED_PRINCIPALS = Set.of("root");

    private long seq;

    private void put(VersionedConfigStore store, String key, String value) {
        store.put(key, value.getBytes(StandardCharsets.UTF_8), ++seq);
    }

    private AclConfigPolicyLoader loader(AclService acl, VersionedConfigStore store, MetricsRegistry reg) {
        return new AclConfigPolicyLoader(acl, store, RESERVED_ROLES, RESERVED_PRINCIPALS, reg);
    }

    private static long metric(MetricsRegistry reg, String name) {
        MetricsRegistry.MetricValue v = reg.snapshot().metrics().get(name);
        return v == null ? 0L : v.value();
    }

    private static List<ConfigMutation> put(String key) {
        return List.of(new ConfigMutation.Put(key, new byte[0]));
    }

    // ---------------- happy path: boot seed / rebuild reflects the store ----------------

    @Test
    void rebuildLoadsPolicyFromStore() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");

        loader(acl, store, reg).rebuild(); // the boot seed

        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));
        assertFalse(acl.isAllowed("alice", "other.x", AclService.Permission.READ));
        assertFalse(acl.isAllowed("bob", "app.x", AclService.Permission.READ));
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD));
        assertEquals(0L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));
    }

    // ---------------- the apply-thread gate ----------------

    @Test
    void onConfigChangeSkipsRebuildWhenNoAclKeyTouched() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        AclConfigPolicyLoader l = loader(acl, store, reg);

        // Publish a sentinel directly; a NON-_acl/ delta must NOT trigger a rebuild (which would overwrite
        // the sentinel with the store-derived policy) - proving the gate short-circuits with no getPrefix.
        ConfigPolicy sentinel = ConfigPolicy.EMPTY;
        acl.publishConfigPolicy(sentinel);
        l.onConfigChange(List.of(new ConfigMutation.Put("app.host", new byte[0]),
                new ConfigMutation.Delete("db.port")), 7L);

        assertEquals(0L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD), "no rebuild expected");
        assertEquals(sentinel, acl.configPolicy(), "policy must be untouched when no _acl/ key changed");
    }

    @Test
    void onConfigChangeRebuildsWhenAclKeyTouched() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);

        l.onConfigChange(put("_acl/bindings/alice"), 2L);

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD));
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));
    }

    @Test
    void snapshotInstallTriggersRebuild() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");

        loader(acl, store, reg).onSnapshotInstalled();

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD));
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));
    }

    // ---------------- fail-closed-to-last-good ----------------

    @Test
    void malformedReloadKeepsLastGoodNeverDenyAllNeverAllowAll() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);
        l.rebuild(); // last-good: alice READ app.
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));

        // Now write a MALFORMED _acl/ key and reload.
        put(store, "_acl/roles/bad", "allow NOPE app.");
        l.onConfigChange(put("_acl/roles/bad"), 99L);

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "one rejected load");
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD), "no new successful load");
        // LAST-GOOD kept: alice still READ (not deny-all); bob still denied (not allow-all).
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ), "last-good preserved");
        assertFalse(acl.isAllowed("bob", "app.x", AclService.Permission.READ), "not allow-all");
        assertFalse(acl.isAllowed("alice", "app.x", AclService.Permission.WRITE), "not allow-all");
    }

    /**
     * Characterization (INTENTIONAL behavior, not a bug): the whole-subtree parse is all-or-nothing, so one
     * persisted malformed {@code _acl/} key freezes ALL subsequent policy updates until it is removed -
     * every rebuild re-reads it and re-rejects the whole subtree (keeping last-good). This is the deliberate
     * "reject whole load, never silently partial" tradeoff (a partial/truncated policy is more dangerous);
     * the residual is loudly observable on {@code configd.acl.policy.load.failed} and is closed by 2b's
     * validate-at-write-time gate (which still cannot clear an already-committed / snapshot-delivered key).
     */
    @Test
    void poisonKeyFreezesSubsequentUpdates() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);
        l.rebuild(); // good: alice READ
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));

        // A single persisted poison key (unrecognized _acl/ shape) makes every rebuild reject the WHOLE
        // subtree from now on.
        put(store, "_acl/zzz", "junk");
        l.onConfigChange(put("_acl/zzz"), 10L);
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));

        // Even a subsequent VALID write (charlie -> reader) does not apply while the poison key persists.
        put(store, "_acl/bindings/charlie", "reader");
        l.onConfigChange(put("_acl/bindings/charlie"), 11L);
        assertEquals(2L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "still frozen");
        assertFalse(acl.isAllowed("charlie", "app.x", AclService.Permission.READ),
                "a valid update cannot apply while a poison _acl/ key persists (whole-load reject)");
        // Last-good still serves alice (fail-closed, not deny-all/allow-all).
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ), "last-good preserved");
    }

    // ---------------- reserved-name validation (the "admin"/root carve neutralization) ----------------

    @Test
    void configDefiningReservedAdminRoleIsRejected() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/admin", "allow READ x.");
        loader(acl, store, reg).rebuild();

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));
        assertEquals(ConfigPolicy.EMPTY, acl.configPolicy(), "reserved role ⇒ last-good (empty) kept");
    }

    @Test
    void configBindingReservedRootPrincipalIsRejected() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/helper", "allow READ x.");
        put(store, "_acl/bindings/root", "helper");
        loader(acl, store, reg).rebuild();

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));
        assertEquals(ConfigPolicy.EMPTY, acl.configPolicy(), "reserved principal binding ⇒ last-good kept");
    }

    /**
     * The load-bearing neutralization proof: with the loader reserving the {@code root} principal AND
     * {@code ConfigdServer} asserting {@code Set.of()} for root, NO config-loaded role can carve root. Here
     * a hostile/operator config tries to bind root to a deny-everything role; the loader REJECTS the whole
     * load (last-good kept), so root's static grant stands and root remains fully authorized.
     */
    @Test
    void rootIsUncarveableByAnyConfigRole() {
        AclService acl = new AclService();
        acl.grant("", "root", java.util.EnumSet.allOf(AclService.Permission.class)); // static break-glass
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/evil", "deny READ,LIST,WRITE,WATCH,ADMIN ");  // empty prefix => matches all
        put(store, "_acl/bindings/root", "evil");
        loader(acl, store, reg).rebuild();

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "carve attempt rejected");
        // root asserts no roles (N1) and the binding was rejected (N2): root keeps full authority.
        for (AclService.Permission perm : AclService.Permission.values()) {
            assertTrue(acl.isAllowed("root", Set.of(), "any.key", perm),
                    () -> "root must remain authorized for " + perm + " — un-carveable by config");
        }
    }
}

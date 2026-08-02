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


    @Test
    void rebuildLoadsPolicyFromStore() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");

        loader(acl, store, reg).rebuild();

        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));
        assertFalse(acl.isAllowed("alice", "other.x", AclService.Permission.READ));
        assertFalse(acl.isAllowed("bob", "app.x", AclService.Permission.READ));
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD));
        assertEquals(0L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));
    }


    @Test
    void onConfigChangeSkipsRebuildWhenNoAclKeyTouched() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        AclConfigPolicyLoader l = loader(acl, store, reg);

        // Publish a sentinel directly; a delta touching no _acl/ key must not trigger a rebuild (which would
        // overwrite the sentinel with the store-derived policy) - proving the gate short-circuits with no getPrefix.
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


    @Test
    void malformedReloadKeepsLastGoodNeverDenyAllNeverAllowAll() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);
        l.rebuild();
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));

        put(store, "_acl/roles/bad", "allow NOPE app.");
        l.onConfigChange(put("_acl/roles/bad"), 99L);

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "one rejected load");
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD), "no new successful load");
        // Last-good kept: alice still READ (not deny-all); bob still denied (not allow-all).
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ), "last-good preserved");
        assertFalse(acl.isAllowed("bob", "app.x", AclService.Permission.READ), "not allow-all");
        assertFalse(acl.isAllowed("alice", "app.x", AclService.Permission.WRITE), "not allow-all");
    }

    @Test
    void poisonKeyFreezesSubsequentUpdates() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);
        l.rebuild();
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));

        // A single persisted poison key (unrecognized _acl/ shape) makes every rebuild reject the whole
        // subtree from now on.
        put(store, "_acl/zzz", "junk");
        l.onConfigChange(put("_acl/zzz"), 10L);
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));

        // Even a subsequent valid write (charlie granted the reader role) does not apply while the poison
        // key persists.
        put(store, "_acl/bindings/charlie", "reader");
        l.onConfigChange(put("_acl/bindings/charlie"), 11L);
        assertEquals(2L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "still frozen");
        assertFalse(acl.isAllowed("charlie", "app.x", AclService.Permission.READ),
                "a valid update cannot apply while a poison _acl/ key persists (whole-load reject)");
        // Last-good still serves alice (fail-closed, not deny-all/allow-all).
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ), "last-good preserved");
    }


    @Test
    void supportedFormatKeyLoadsNormally() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/format", "1");
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");

        loader(acl, store, reg).rebuild();

        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD));
        assertEquals(0L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED));
    }

    @Test
    void unsupportedFormatReloadKeepsLastGood() {
        AclService acl = new AclService();
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/reader", "allow READ app.");
        put(store, "_acl/bindings/alice", "reader");
        AclConfigPolicyLoader l = loader(acl, store, reg);
        l.rebuild();
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ));

        // A newer node wrote a newer grammar version. An old node must fail closed to last-good, not misparse.
        put(store, "_acl/format", "2");
        l.onConfigChange(put("_acl/format"), 42L);

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "one rejected load");
        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_RELOAD), "no new successful load");
        assertTrue(acl.isAllowed("alice", "app.x", AclService.Permission.READ), "last-good preserved");
        assertFalse(acl.isAllowed("bob", "app.x", AclService.Permission.READ), "not allow-all");
    }


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

    @Test
    void rootIsUncarveableByAnyConfigRole() {
        AclService acl = new AclService();
        acl.grant("", "root", java.util.EnumSet.allOf(AclService.Permission.class));
        VersionedConfigStore store = new VersionedConfigStore();
        MetricsRegistry reg = new MetricsRegistry();
        put(store, "_acl/roles/evil", "deny READ,WRITE,WATCH,ADMIN ");  // empty prefix, so it matches all
        put(store, "_acl/bindings/root", "evil");
        loader(acl, store, reg).rebuild();

        assertEquals(1L, metric(reg, AclConfigPolicyLoader.NAME_POLICY_LOAD_FAILED), "carve attempt rejected");
        // root asserts no roles, and the binding above was rejected: root keeps full authority.
        for (AclService.Permission perm : AclService.Permission.values()) {
            assertTrue(acl.isAllowed("root", Set.of(), "any.key", perm),
                    () -> "root must remain authorized for " + perm + " — un-carveable by config");
        }
    }
}

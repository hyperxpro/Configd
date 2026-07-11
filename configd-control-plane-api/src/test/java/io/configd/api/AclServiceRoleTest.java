package io.configd.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.LIST;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the <b>role-aware</b> ACL layer of {@link AclService}.
 * <p>
 * Authorization unions a principal's <b>own</b> per-prefix grants with its <b>role</b> grants into a
 * single {@code (allow, deny)} pair, then applies the SAME union / absolute-deny-precedence /
 * default-deny / effective-{@code WATCH} = {@code WATCH} AND {@code READ} rules across both. A role's
 * effective membership is the union of the <b>authn-asserted</b> roles passed to
 * {@link AclService#isAllowed(String, Set, String, AclService.Permission)} and the <b>ACL-static</b>
 * bindings added via {@link AclService#assignRole}.
 * <p>
 * The load-bearing guarantee is <b>byte-identity in production</b>: with no role defined/assigned and an
 * empty {@code roles} argument the role-aware path reduces exactly to the own-grants-only
 * evaluation - {@link #emptyRolesByteIdentical()} and {@link #productionShapeThroughRoleAwarePath()} pin
 * this. The deny-through-roles tests prove the critical property that deny is subtracted <b>once</b> over
 * the combined own+role set, so a DENY (own or role) wins over an ALLOW (own or role) in either order.
 */
class AclServiceRoleTest {

    private AclService acl;

    @BeforeEach
    void setUp() {
        acl = new AclService();
    }

    // Test helpers: build single-policy roles out of literal-prefix allow/deny rules.

    private static PolicyRule allowRule(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(caps), Set.of());
    }

    private static PolicyRule denyRule(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(), Set.of(caps));
    }

    private static Role role(String name, PolicyRule... rules) {
        return new Role(name, List.of(new Policy(name + "-policy", List.of(rules))));
    }

    // Byte-identity: empty roles reduce the role-aware path to own-grants-only

    /**
     * Across a battery of grant shapes the 3-arg {@code isAllowed} equals the 4-arg with an empty role
     * set AND with an undefined role name. Proves the 3-arg delegates to the 4-arg with no roles, and
     * that an undefined role contributes nothing (structural byte-identity for every existing caller).
     */
    @Test
    void emptyRolesByteIdentical() {
        acl.grant("", "p", Set.of(READ));
        acl.grant("a.", "p", Set.of(READ, WRITE));
        acl.grant("a.b.", "p", Set.of(WATCH));         // WATCH here, READ inherited from "a."
        acl.deny("a.secret.", "p", Set.of(WRITE));     // deny carve-out
        acl.grant("c.", "p", Set.of(READ, LIST, WATCH));

        String[] keys = {"x", "a.x", "a.b.x", "a.secret.k", "c.k", "miss"};
        for (String key : keys) {
            for (AclService.Permission perm : AclService.Permission.values()) {
                boolean three = acl.isAllowed("p", key, perm);
                boolean fourEmpty = acl.isAllowed("p", Set.of(), key, perm);
                boolean fourUndefined = acl.isAllowed("p", Set.of("undefined-role"), key, perm);
                assertEquals(three, fourEmpty,
                        () -> "3-arg must equal 4-arg(empty roles) for " + key + "/" + perm);
                assertEquals(three, fourUndefined,
                        () -> "an undefined role must add nothing for " + key + "/" + perm);
            }
        }
    }

    // Role grants extend reach

    @Test
    void roleAddsReach() {
        acl.defineRole(new Role("r",
                List.of(new Policy("p", List.of(new PolicyRule("a.", Set.of(READ), Set.of()))))));

        assertFalse(acl.isAllowed("p", Set.of(), "a.x", READ),
                "without the role the principal has no own grant -> denied");
        assertTrue(acl.isAllowed("p", Set.of("r"), "a.x", READ),
                "the role grants READ on a. -> authorized");
        assertFalse(acl.isAllowed("p", Set.of("r"), "b.x", READ),
                "the role's rule is prefix-scoped to a. -> no reach to b.");
    }

    @Test
    void multipleRolesUnion() {
        acl.defineRole(role("reader", allowRule("a.", READ)));
        acl.defineRole(role("writer", allowRule("a.", WRITE)));

        assertTrue(acl.isAllowed("p", Set.of("reader", "writer"), "a.x", READ));
        assertTrue(acl.isAllowed("p", Set.of("reader", "writer"), "a.x", WRITE),
                "two roles union their grants");
        assertFalse(acl.isAllowed("p", Set.of("reader"), "a.x", WRITE),
                "reader alone confers no WRITE");
    }

    @Test
    void roleUnionsWithOwnGrants() {
        acl.grant("a.", "p", Set.of(READ));                  // own READ
        acl.defineRole(role("writer", allowRule("a.", WRITE))); // role WRITE

        assertTrue(acl.isAllowed("p", Set.of("writer"), "a.x", READ), "own READ survives");
        assertTrue(acl.isAllowed("p", Set.of("writer"), "a.x", WRITE), "role WRITE composes with own READ");
    }

    // Deny precedence THROUGH roles - the critical "single subtract over the combined set" property

    @Test
    void denyInRoleOverridesOwnAllow() {
        acl.grant("a.", "p", Set.of(READ, WRITE));            // own ALLOW WRITE
        acl.defineRole(role("blocker", denyRule("a.", WRITE))); // role DENY WRITE

        assertFalse(acl.isAllowed("p", Set.of("blocker"), "a.x", WRITE),
                "a role DENY must beat an own ALLOW (deny subtracted once over the combined set)");
        assertTrue(acl.isAllowed("p", Set.of("blocker"), "a.x", READ),
                "only WRITE was denied; READ remains");
    }

    @Test
    void ownDenyOverridesRoleAllow() {
        acl.defineRole(role("writer", allowRule("a.", WRITE))); // role ALLOW WRITE
        acl.deny("a.", "p", Set.of(WRITE));                     // own DENY WRITE

        assertFalse(acl.isAllowed("p", Set.of("writer"), "a.x", WRITE),
                "an own DENY must beat a role ALLOW");
    }

    @Test
    void ancestorDenyKillsRoleAllowAtDescendant() {
        // OWN deny at ancestor a. beats ROLE allow at descendant a.b. for a.b.x
        acl.defineRole(role("descWriter", allowRule("a.b.", WRITE)));
        acl.deny("a.", "p", Set.of(WRITE));
        assertFalse(acl.isAllowed("p", Set.of("descWriter"), "a.b.x", WRITE),
                "own ancestor DENY beats role descendant ALLOW (deny is absolute over the union)");

        // ROLE deny at ancestor a. beats ROLE allow at descendant a.b. too
        AclService acl2 = new AclService();
        acl2.defineRole(role("descWriter", allowRule("a.b.", WRITE)));
        acl2.defineRole(role("ancBlocker", denyRule("a.", WRITE)));
        assertFalse(acl2.isAllowed("p", Set.of("descWriter", "ancBlocker"), "a.b.x", WRITE),
                "a role DENY at the ancestor beats a role ALLOW at the descendant");
    }

    @Test
    void roleCannotEscalateBeyondItsRules() {
        acl.defineRole(role("readerOnly", allowRule("a.", READ)));

        assertTrue(acl.isAllowed("p", Set.of("readerOnly"), "a.x", READ));
        assertFalse(acl.isAllowed("p", Set.of("readerOnly"), "a.x", WRITE), "READ-only role never confers WRITE");
        assertFalse(acl.isAllowed("p", Set.of("readerOnly"), "a.x", ADMIN), "nor ADMIN");
        assertFalse(acl.isAllowed("p", Set.of("readerOnly"), "a.x", LIST), "nor LIST");
        assertFalse(acl.isAllowed("p", Set.of("readerOnly"), "a.x", WATCH), "nor (effective) WATCH");
    }

    // ACL-static role binding (assignRole) - additive to authn-asserted roles

    @Test
    void assignRoleStaticBinding() {
        acl.defineRole(role("r", allowRule("a.", READ, WRITE)));
        acl.assignRole("p", "r");

        // No authn-asserted roles, yet the static binding applies.
        assertTrue(acl.isAllowed("p", Set.of(), "a.x", READ),
                "ACL-static binding applies even with an empty authn-asserted role set");
        assertTrue(acl.isAllowed("p", Set.of(), "a.x", WRITE));
        // The 3-arg path (delegates with empty roles) also resolves the static binding.
        assertTrue(acl.isAllowed("p", "a.x", READ), "3-arg path resolves the static binding too");
        // A different principal without the binding is denied.
        assertFalse(acl.isAllowed("other", Set.of(), "a.x", READ));

        // Additive to authn-asserted roles: static r (a.) union authn-asserted r2 (b.) both contribute.
        acl.defineRole(role("r2", allowRule("b.", READ)));
        acl.assignRole("q", "r");
        assertTrue(acl.isAllowed("q", Set.of("r2"), "a.x", READ), "static r applies");
        assertTrue(acl.isAllowed("q", Set.of("r2"), "b.x", READ), "authn-asserted r2 also applies (union of sources)");
    }

    /** Assigning the same role twice is stable - no duplication effect, still exactly authorized. */
    @Test
    void assignRoleIsIdempotent() {
        acl.defineRole(role("r", allowRule("a.", READ)));
        acl.assignRole("p", "r");
        acl.assignRole("p", "r"); // second assign of the same role must change nothing

        assertTrue(acl.isAllowed("p", Set.of(), "a.x", READ),
                "double assignRole of the same role stays authorized (idempotent)");
        assertFalse(acl.isAllowed("p", Set.of(), "a.x", WRITE),
                "the double-assign confers nothing beyond the role's single READ rule");
    }

    // The WATCH-requires-READ floor holds across the combined own+role union

    @Test
    void watchFloorThroughRoles() {
        // role WATCH + own READ -> effective WATCH (floor over the combined union)
        acl.grant("a.", "p", Set.of(READ));
        acl.defineRole(role("watcher", allowRule("a.", WATCH)));
        assertTrue(acl.isAllowed("p", Set.of("watcher"), "a.x", WATCH),
                "role WATCH ∧ own READ -> effective WATCH");

        // role WATCH but READ denied on the key's chain -> no effective WATCH
        AclService acl2 = new AclService();
        acl2.grant("a.", "p", Set.of(READ, WATCH));               // own READ+WATCH
        acl2.defineRole(role("readBlocker", denyRule("a.secret.", READ))); // role DENY READ on a child
        assertFalse(acl2.isAllowed("p", Set.of("readBlocker"), "a.secret.k", WATCH),
                "a role DENY(READ) on the chain kills effective WATCH (INV-WATCH-READ through roles)");
        assertTrue(acl2.isAllowed("p", Set.of("readBlocker"), "a.public.k", WATCH),
                "outside the READ carve-out, WATCH ∧ READ still holds");

        // pure-role: WATCH alone (no READ anywhere) is not watchable; adding READ makes it so
        AclService acl3 = new AclService();
        acl3.defineRole(role("watchOnly", allowRule("a.", WATCH)));
        assertFalse(acl3.isAllowed("p", Set.of("watchOnly"), "a.x", WATCH),
                "role WATCH without any READ -> no effective WATCH");
        acl3.defineRole(role("rw", allowRule("a.", READ, WATCH)));
        assertTrue(acl3.isAllowed("p", Set.of("watchOnly", "rw"), "a.x", WATCH),
                "once a role supplies READ, the WATCH floor is satisfied over the union");
    }

    // Default-deny over unknown principal + unknown role

    @Test
    void defaultDenyUnknownPrincipalAndRole() {
        // Unrelated definitions exist, but neither names this principal nor this role.
        acl.defineRole(role("r", allowRule("a.", READ, LIST, WRITE, WATCH, ADMIN)));
        acl.grant("a.", "known", Set.of(READ));

        for (AclService.Permission perm : AclService.Permission.values()) {
            assertFalse(acl.isAllowed("nobody", Set.of("does-not-exist"), "a.x", perm),
                    () -> "unknown principal + undefined role must be denied for " + perm);
        }
    }

    // Null checks (4-arg isAllowed, defineRole, assignRole)

    @Test
    void nullChecks() {
        assertThrows(NullPointerException.class, () -> acl.isAllowed(null, Set.of(), "k", READ));
        assertThrows(NullPointerException.class, () -> acl.isAllowed("p", null, "k", READ));
        assertThrows(NullPointerException.class, () -> acl.isAllowed("p", Set.of(), null, READ));
        assertThrows(NullPointerException.class, () -> acl.isAllowed("p", Set.of(), "k", null));

        assertThrows(NullPointerException.class, () -> acl.defineRole(null));
        assertThrows(NullPointerException.class, () -> acl.assignRole(null, "r"));
        assertThrows(NullPointerException.class, () -> acl.assignRole("p", null));
    }

    // Production shape through the role-aware path (the byte-identity wiring guarantee)

    /**
     * Mirrors production exactly: {@code grant("", "root", allOf)} plus the authn layer asserting the
     * role {@code "admin"} which is <b>never defined</b> as a {@link Role}. Root is authorized for all
     * five caps on every key via its own global grant; the undefined {@code admin} role adds nothing. A
     * non-root principal carrying the same undefined role is fully denied.
     */
    @Test
    void productionShapeThroughRoleAwarePath() {
        acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
        Set<String> prodRoles = Set.of("admin"); // asserted by authn, never defined as a Role

        for (String key : new String[]{"db.host", "app.name", "/a/b/c", "", "x"}) {
            for (AclService.Permission perm : AclService.Permission.values()) {
                assertTrue(acl.isAllowed("root", prodRoles, key, perm),
                        () -> "root authorized for " + perm + " on '" + key + "' (admin role is dormant)");
            }
        }
        for (AclService.Permission perm : AclService.Permission.values()) {
            assertFalse(acl.isAllowed("intruder", prodRoles, "db.host", perm),
                    () -> "non-root denied for " + perm + " — the undefined admin role confers nothing");
        }
    }
}

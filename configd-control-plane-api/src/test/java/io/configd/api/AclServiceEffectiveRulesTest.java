package io.configd.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static io.configd.api.AclService.authorizesWatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AclService#effectiveRules} — the gate's rule-assembly step that feeds the dormant
 * whole-target predicates {@link AclService#authorizesWatch} / {@link AclService#coversTarget} (RFC §02
 * W7-2a). {@code effectiveRules} assembles the principal's complete unioned rule set (own ∪ imperative-role
 * ∪ config-role) resolving the three sources <b>exactly</b> as {@link AclService#isAllowed} does, so that
 * {@code authorizesWatch(effectiveRules(p, roles), key)} agrees with the single-key
 * {@code isAllowed(p, roles, key, WATCH)} on every concrete key (with the documented whole-subtree
 * interior-DENY strengthening that {@code isAllowed} structurally cannot see).
 */
@DisplayName("AclService.effectiveRules — unioned own/role/config rule assembly for the watch gate")
class AclServiceEffectiveRulesTest {

    private static boolean hasRule(Collection<PolicyRule> rules, String prefix,
                                   Set<AclService.Permission> allow, Set<AclService.Permission> deny) {
        return rules.stream().anyMatch(r ->
                r.prefix().equals(prefix) && r.allow().equals(allow) && r.deny().equals(deny));
    }

    @Nested
    @DisplayName("Own per-prefix grants")
    class OwnGrants {

        @Test
        @DisplayName("empty service ⇒ empty rule set; null args rejected")
        void emptyAndNulls() {
            AclService acl = new AclService();
            assertTrue(acl.effectiveRules("nobody", Set.of()).isEmpty());
            assertThrows(NullPointerException.class, () -> acl.effectiveRules(null, Set.of()));
            assertThrows(NullPointerException.class, () -> acl.effectiveRules("p", null));
        }

        @Test
        @DisplayName("every own prefix the principal holds becomes one PolicyRule(prefix, allow, deny)")
        void ownPrefixesBecomeRules() {
            AclService acl = new AclService();
            acl.grant("a.", "alice", Set.of(READ, WATCH));
            acl.grant("a.b.", "alice", Set.of(WRITE));
            acl.deny("a.secret.", "alice", Set.of(READ));
            acl.grant("z.", "bob", Set.of(READ)); // a DIFFERENT principal — must not leak into alice's set

            Collection<PolicyRule> rules = acl.effectiveRules("alice", Set.of());
            assertEquals(3, rules.size(), "alice's three own prefixes, none of bob's");
            assertTrue(hasRule(rules, "a.", Set.of(READ, WATCH), Set.of()));
            assertTrue(hasRule(rules, "a.b.", Set.of(WRITE), Set.of()));
            assertTrue(hasRule(rules, "a.secret.", Set.of(), Set.of(READ)));
        }

        @Test
        @DisplayName("the complete own set carries the interior-DENY a single-key check cannot see")
        void completeSetEnablesInteriorDeny() {
            AclService acl = new AclService();
            acl.grant("a.", "alice", Set.of(READ, WATCH));
            acl.deny("a.secret.", "alice", Set.of(READ));

            Collection<PolicyRule> rules = acl.effectiveRules("alice", Set.of());
            // Whole-subtree watch over "a." is REJECTED by the interior DENY on the descendant "a.secret.".
            assertFalse(authorizesWatch(rules, "a."), "interior DENY on a.secret. blocks the whole-subtree watch");
            // But the exact-key floor on a sibling leaf is unaffected (isAllowed sees only ancestors).
            assertTrue(acl.isAllowed("alice", "a.public", WATCH), "a.public is readable+watchable");
            assertFalse(acl.isAllowed("alice", "a.secret.x", READ), "a.secret.x inherits the descendant DENY");
        }
    }

    @Nested
    @DisplayName("Imperative + config role grants (resolved as isAllowed resolves them)")
    class RoleAndConfig {

        @Test
        @DisplayName("ACL-static role grants fold into the rule set")
        void imperativeRole() {
            AclService acl = new AclService();
            acl.defineRole(new Role("reader",
                    List.of(new Policy("p", List.of(new PolicyRule("team.", Set.of(READ, WATCH), Set.of()))))));
            acl.assignRole("alice", "reader");

            Collection<PolicyRule> rules = acl.effectiveRules("alice", Set.of());
            assertTrue(hasRule(rules, "team.", Set.of(READ, WATCH), Set.of()), "role rule present");
            assertTrue(authorizesWatch(rules, "team."), "role grant authorizes the whole team. subtree");
        }

        @Test
        @DisplayName("authn-asserted role names resolve against BOTH imperative and config role defs")
        void assertedRoleResolvesBothLayers() {
            AclService acl = new AclService();
            acl.defineRole(new Role("r-imp",
                    List.of(new Policy("p", List.of(new PolicyRule("imp.", Set.of(READ, WATCH), Set.of()))))));
            acl.publishConfigPolicy(new ConfigPolicy(
                    java.util.Map.of("r-cfg",
                            new Role("r-cfg", List.of(new Policy("p",
                                    List.of(new PolicyRule("cfg.", Set.of(READ, WATCH), Set.of())))))),
                    java.util.Map.of()));

            // Asserting both role names resolves r-imp against the imperative defs and r-cfg against config.
            Collection<PolicyRule> rules = acl.effectiveRules("alice", Set.of("r-imp", "r-cfg"));
            assertTrue(hasRule(rules, "imp.", Set.of(READ, WATCH), Set.of()));
            assertTrue(hasRule(rules, "cfg.", Set.of(READ, WATCH), Set.of()));
        }

        @Test
        @DisplayName("config-bound role grants fold in via the config snapshot, read once")
        void configBinding() {
            AclService acl = new AclService();
            acl.publishConfigPolicy(new ConfigPolicy(
                    java.util.Map.of("ops",
                            new Role("ops", List.of(new Policy("p",
                                    List.of(new PolicyRule("svc.", Set.of(READ, WATCH), Set.of())))))),
                    java.util.Map.of("alice", Set.of("ops"))));

            Collection<PolicyRule> rules = acl.effectiveRules("alice", Set.of());
            assertTrue(hasRule(rules, "svc.", Set.of(READ, WATCH), Set.of()), "config-bound role rule present");
            assertTrue(authorizesWatch(rules, "svc."));
            assertFalse(authorizesWatch(rules, ""), "no root grant ⇒ FULL watch not authorized");
        }
    }
}

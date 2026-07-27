package io.configd.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AclPolicyTypesTest {

    @Test
    void policyRuleNullChecks() {
        assertThrows(NullPointerException.class, () -> new PolicyRule(null, Set.of(READ), Set.of()));
        assertThrows(NullPointerException.class, () -> new PolicyRule("a.", null, Set.of()));
        assertThrows(NullPointerException.class, () -> new PolicyRule("a.", Set.of(READ), null));
    }

    @Test
    void policyRuleEmptyAllowAndDenyAreAllowed() {
        PolicyRule r = new PolicyRule("a.", Set.of(), Set.of());
        assertTrue(r.allow().isEmpty());
        assertTrue(r.deny().isEmpty());
    }

    @Test
    void policyRuleDefensivelyCopiesAllowAndDeny() {
        Set<AclService.Permission> srcAllow = new HashSet<>(Set.of(READ));
        Set<AclService.Permission> srcDeny = new HashSet<>(Set.of(WRITE));
        PolicyRule r = new PolicyRule("a.", srcAllow, srcDeny);

        srcAllow.add(ADMIN);
        srcDeny.add(ADMIN);

        assertEquals(Set.of(READ), r.allow(), "allow must be an immutable snapshot, unaffected by source mutation");
        assertEquals(Set.of(WRITE), r.deny(), "deny must be an immutable snapshot, unaffected by source mutation");
    }

    @Test
    void policyRuleReturnedSetsAreUnmodifiable() {
        PolicyRule r = new PolicyRule("a.", Set.of(READ), Set.of(WRITE));
        assertThrows(UnsupportedOperationException.class, () -> r.allow().add(ADMIN));
        assertThrows(UnsupportedOperationException.class, () -> r.deny().add(ADMIN));
    }

    @Test
    void policyRuleMatchesIsLiteralStartsWith() {
        PolicyRule r = new PolicyRule("a.", Set.of(READ), Set.of());
        assertTrue(r.matches("a.b"), "a. is a prefix of a.b");
        assertTrue(r.matches("a."), "equal prefix matches");
        assertFalse(r.matches("b.a"), "b.a does not start with a.");
        assertTrue(new PolicyRule("", Set.of(READ), Set.of()).matches("anything"),
                "the empty prefix matches every key (mirrors the global \"\" grant)");
    }

    @Test
    void policyNullChecks() {
        assertThrows(NullPointerException.class, () -> new Policy(null, List.of()));
        assertThrows(NullPointerException.class, () -> new Policy("p", null));
    }

    @Test
    void policyCopiesRulesAndExposesUnmodifiableList() {
        List<PolicyRule> src = new ArrayList<>();
        src.add(new PolicyRule("a.", Set.of(READ), Set.of()));
        Policy p = new Policy("p", src);

        src.add(new PolicyRule("b.", Set.of(WRITE), Set.of()));

        assertEquals(1, p.rules().size(), "rules copied at construction; source mutation must not leak in");
        assertThrows(UnsupportedOperationException.class,
                () -> p.rules().add(new PolicyRule("c.", Set.of(READ), Set.of())));
    }

    @Test
    void policyRejectsNullRuleElement() {
        List<PolicyRule> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new Policy("p", withNull),
                "List.copyOf rejects a null element");
    }

    @Test
    void roleNullChecks() {
        assertThrows(NullPointerException.class, () -> new Role(null, List.of()));
        assertThrows(NullPointerException.class, () -> new Role("r", null));
    }

    @Test
    void roleCopiesPoliciesAndExposesUnmodifiableList() {
        List<Policy> src = new ArrayList<>();
        src.add(new Policy("p1", List.of(new PolicyRule("a.", Set.of(READ), Set.of()))));
        Role r = new Role("r", src);

        src.add(new Policy("p2", List.of()));

        assertEquals(1, r.policies().size(), "policies copied at construction");
        assertThrows(UnsupportedOperationException.class, () -> r.policies().add(new Policy("p3", List.of())));
    }

    @Test
    void roleRejectsNullPolicyElement() {
        List<Policy> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new Role("r", withNull));
    }

    @Test
    void roleRulesFlattensPoliciesInOrder() {
        PolicyRule r1 = new PolicyRule("a.", Set.of(READ), Set.of());
        PolicyRule r2 = new PolicyRule("b.", Set.of(WRITE), Set.of());
        PolicyRule r3 = new PolicyRule("c.", Set.of(ADMIN), Set.of());
        Role role = new Role("r", List.of(
                new Policy("p1", List.of(r1, r2)),
                new Policy("p2", List.of(r3))));

        assertEquals(List.of(r1, r2, r3), role.rules(),
                "rules() flattens across policies in policy-then-rule order");
    }

    @Test
    void roleRulesIsUnmodifiable() {
        Role role = new Role("r",
                List.of(new Policy("p", List.of(new PolicyRule("a.", Set.of(READ), Set.of())))));
        assertThrows(UnsupportedOperationException.class,
                () -> role.rules().add(new PolicyRule("z.", Set.of(READ), Set.of())));
    }

    @Test
    void roleRulesEmptyWhenNoPolicies() {
        assertTrue(new Role("empty", List.of()).rules().isEmpty());
    }
}

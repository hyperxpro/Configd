package io.configd.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.LIST;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-grammar + fail-closed tests for {@link PolicySerializer} (O-6 Seam 2a). Covers the round-trip,
 * the full reject matrix (any malformed input rejects the WHOLE load), whitespace/CRLF/comment handling,
 * empty/spaced prefixes, multi-key assembly, and the load-bearing distinction between a structurally
 * malformed policy (REJECT) and a well-formed-but-incomplete one (ACCEPT, inert) that lets the loader's
 * idempotent rebuild converge across multi-key writes.
 */
class PolicySerializerTest {

    private static Map<String, byte[]> subtree(Object... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((String) kv[i + 1]).getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private static PolicyRule onlyRule(ConfigPolicy p, String role) {
        List<PolicyRule> rules = p.roles().get(role).rules();
        assertEquals(1, rules.size(), "expected exactly one rule for role " + role);
        return rules.get(0);
    }

    // ---------------- round-trip / accept ----------------

    @Test
    void emptySubtreeIsEmptyPolicy() {
        ConfigPolicy p = PolicySerializer.parse(Map.of());
        assertTrue(p.roles().isEmpty());
        assertTrue(p.bindings().isEmpty());
    }

    @Test
    void singleAllowRule() {
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/reader", "allow READ,LIST app."));
        PolicyRule r = onlyRule(p, "reader");
        assertEquals("app.", r.prefix());
        assertEquals(Set.of(READ, LIST), r.allow());
        assertEquals(Set.of(), r.deny());
    }

    @Test
    void allowAndDenyBecomeTwoRules() {
        ConfigPolicy p = PolicySerializer.parse(subtree(
                "_acl/roles/ops", "allow READ,WRITE app.\ndeny WRITE app.secret."));
        List<PolicyRule> rules = p.roles().get("ops").rules();
        assertEquals(2, rules.size());
        assertEquals("app.", rules.get(0).prefix());
        assertEquals(Set.of(READ, WRITE), rules.get(0).allow());
        assertEquals("app.secret.", rules.get(1).prefix());
        assertEquals(Set.of(WRITE), rules.get(1).deny());
    }

    @Test
    void commentsBlankLinesAndCrlfIgnoredAndStripped() {
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/r",
                "# a comment\r\n\r\n   # indented comment\nallow ADMIN secure.\r\n\n"));
        PolicyRule r = onlyRule(p, "r");
        assertEquals("secure.", r.prefix());
        assertEquals(Set.of(ADMIN), r.allow());
    }

    @Test
    void emptyPrefixMatchesEverything() {
        // "allow READ" with no third field ⇒ empty prefix (startsWith("") matches all keys).
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/global", "allow READ"));
        PolicyRule r = onlyRule(p, "global");
        assertEquals("", r.prefix());
        assertTrue(r.matches("anything.at.all"));
    }

    @Test
    void prefixMayContainSpacesVerbatim() {
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/r", "allow READ my key with spaces"));
        assertEquals("my key with spaces", onlyRule(p, "r").prefix());
    }

    @Test
    void allFiveCapabilitiesParse() {
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/r", "allow READ,LIST,WRITE,WATCH,ADMIN x."));
        assertEquals(Set.of(READ, LIST, WRITE, WATCH, ADMIN), onlyRule(p, "r").allow());
    }

    @Test
    void bindingParsesRoleNames() {
        ConfigPolicy p = PolicySerializer.parse(subtree(
                "_acl/bindings/alice", "# alice's roles\nreader\nops\n"));
        assertEquals(Set.of("reader", "ops"), p.bindings().get("alice"));
    }

    @Test
    void multiKeyRolesAndBindingsAssemble() {
        ConfigPolicy p = PolicySerializer.parse(subtree(
                "_acl/roles/reader", "allow READ app.",
                "_acl/roles/ops", "allow WRITE app.",
                "_acl/bindings/alice", "reader",
                "_acl/bindings/bob", "reader\nops"));
        assertEquals(Set.of("reader", "ops"), p.roles().keySet());
        assertEquals(Set.of("reader"), p.bindings().get("alice"));
        assertEquals(Set.of("reader", "ops"), p.bindings().get("bob"));
    }

    @Test
    void bindingToUndefinedRoleIsBenignNotAFailure() {
        // Well-formed-but-incomplete: a binding to a not-yet-loaded role parses fine and is simply inert.
        // This is what lets the loader's whole-subtree rebuild converge across out-of-order multi-key writes.
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/bindings/alice", "not-yet-defined"));
        assertEquals(Set.of("not-yet-defined"), p.bindings().get("alice"));
        assertTrue(p.roles().isEmpty());
    }

    @Test
    void roleWithNoRulesParses() {
        // An empty role value (only comments/blanks) is a defined role with zero rules — inert, not an error.
        ConfigPolicy p = PolicySerializer.parse(subtree("_acl/roles/empty", "# nothing here\n\n"));
        assertTrue(p.roles().containsKey("empty"));
        assertTrue(p.roles().get("empty").rules().isEmpty());
    }

    // ---------------- reject matrix (fail-closed: any malformed input → PolicyParseException) ----------------

    @Test
    void rejectUnknownAclKeyShape() {
        assertThrows(PolicyParseException.class, () -> PolicySerializer.parse(subtree("_acl/foo", "x")));
        assertThrows(PolicyParseException.class, () -> PolicySerializer.parse(subtree("_acl/", "x")));
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/policies/x", "allow READ a.")));
    }

    @Test
    void rejectEmptyRoleOrPrincipalName() {
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/", "allow READ a.")));
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/bindings/", "reader")));
    }

    @Test
    void rejectUnknownEffect() {
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "permit READ a.")));
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "ALLOW READ a.")));  // case-sensitive
    }

    @Test
    void rejectUnknownCapability() {
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow SUPERUSER a.")));
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow read a.")));   // case-sensitive
    }

    @Test
    void rejectEmptyOrMalformedCapList() {
        // double comma / trailing comma / leading comma → empty token
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow READ,,LIST a.")));
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow READ, a.")));   // space ⇒ token "READ,"
        // double space after effect ⇒ empty caps token
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow  READ a.")));
    }

    @Test
    void rejectMissingFields() {
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow")));            // no caps/prefix
        assertThrows(PolicyParseException.class,
                () -> PolicySerializer.parse(subtree("_acl/roles/r", "allow READ a.\nbogusline")));
    }

    @Test
    void rejectIsAllOrNothing() {
        // A single malformed line rejects the WHOLE subtree (never silently partial).
        assertThrows(PolicyParseException.class, () -> PolicySerializer.parse(subtree(
                "_acl/roles/good", "allow READ a.",
                "_acl/roles/bad", "allow NOPE a.")));
    }
}

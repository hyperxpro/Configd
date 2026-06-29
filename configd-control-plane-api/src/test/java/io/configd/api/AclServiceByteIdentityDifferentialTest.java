package io.configd.api;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BYTE-IDENTITY <b>differential</b> proof for O-6 Seam 1 (role-aware ACL).
 * <p>
 * The load-bearing wiring guarantee is that with <b>empty role maps</b> — production's effective state:
 * no {@link AclService#defineRole} / {@link AclService#assignRole} is ever called by server code
 * ({@code ConfigdServer.java:726} is the sole grant) — every authorization decision is identical to the
 * pre-O6 own-grants-only evaluation, regardless of which authn-asserted roles a request carries (in
 * production, {@code authed.roles() == {"admin"}}, a role that is never defined; {@code ConfigdServer.java:720}).
 * <p>
 * Unlike {@link AclServiceRoleTest#emptyRolesByteIdentical()} / {@code productionShapeThroughRoleAwarePath()},
 * which pin the <i>3-arg == 4-arg</i> internal consistency of {@link AclService} against <i>itself</i>,
 * this suite compares {@link AclService} against an <b>independent reference oracle</b> ({@link OracleAcl})
 * that re-implements the pre-O6 semantics — union-of-every-matching-ancestor + absolute deny-precedence +
 * default-deny + effective-{@code WATCH} = {@code WATCH} ∧ {@code READ} — with a deliberately <b>different
 * algorithm</b>: it brute-forces {@code key.startsWith(prefix)} over <i>all</i> stored prefixes rather
 * than navigating {@code floorKey}/{@code lowerKey}. So a regression that dropped an ancestor in the
 * optimized walk (or that let the role layer perturb {@code (allow, deny)} when no role is defined) would
 * make the two disagree. It then fuzzes thousands of random ACL configurations and {@code (principal,
 * key, permission)} triples through four lenses — the 3-arg call, the 4-arg call with no roles, with the
 * exact production {@code {"admin"}}, and with {@code {"admin"}} plus a random undefined role — asserting
 * every one equals the oracle. A deterministic seeded PRNG makes any failure reproducible, and failures
 * print the differing {@code (config, principal, key, permission)}.
 */
class AclServiceByteIdentityDifferentialTest {

    private static final AclService.Permission[] PERMS = AclService.Permission.values();

    // Grant-time principals (a small closed set so configs overlap and ancestors actually compose).
    private static final String[] GRANT_PRINCIPALS = {"root", "alice", "bob", "carol", "dave"};
    // Query-time principals: the grant set plus an unknown principal that must always be default-denied.
    private static final String[] QUERY_PRINCIPALS = {"root", "alice", "bob", "carol", "dave", "mallory"};
    // Prefixes deliberately mixing the empty/global prefix, deep nested chains, siblings, and carve-outs,
    // so a key routinely matches several overlapping ancestors (where union vs longest-match would differ).
    private static final String[] PREFIXES =
            {"", "a.", "a.b.", "a.b.c.", "a.c.", "a.secret.", "b.", "db.", "db.conn."};
    // Query keys spanning exact-prefix hits, descendants of several depths, siblings, and total misses.
    private static final String[] KEYS =
            {"", "a.", "a.x", "a.b", "a.b.x", "a.b.c.y", "a.c.z", "a.secret.k", "b.y", "db.host",
             "db.conn.pool", "miss"};

    /**
     * The independent pre-O6 reference oracle. Mirrors {@link AclService#grant}/{@link AclService#deny}/
     * {@link AclService#revoke} overwrite semantics (grant replaces a principal's ALLOW at the exact
     * prefix, deny replaces its DENY, revoke removes the whole entry) and evaluates a decision by a
     * brute-force scan over <b>all</b> stored prefixes — intentionally a different traversal than
     * {@link AclService}'s sorted-map {@code floorKey}/{@code lowerKey} walk.
     */
    private static final class OracleAcl {
        private static final class Entry {
            EnumSet<AclService.Permission> allow = EnumSet.noneOf(AclService.Permission.class);
            EnumSet<AclService.Permission> deny = EnumSet.noneOf(AclService.Permission.class);
        }

        private final Map<String, Map<String, Entry>> model = new HashMap<>();

        void grant(String prefix, String principal, Set<AclService.Permission> perms) {
            model.computeIfAbsent(prefix, k -> new HashMap<>())
                    .computeIfAbsent(principal, k -> new Entry()).allow = EnumSet.copyOf(perms);
        }

        void deny(String prefix, String principal, Set<AclService.Permission> perms) {
            model.computeIfAbsent(prefix, k -> new HashMap<>())
                    .computeIfAbsent(principal, k -> new Entry()).deny = EnumSet.copyOf(perms);
        }

        void revoke(String prefix, String principal) {
            Map<String, Entry> inner = model.get(prefix);
            if (inner != null) {
                inner.remove(principal);
                if (inner.isEmpty()) {
                    model.remove(prefix);
                }
            }
        }

        boolean decide(String principal, String key, AclService.Permission perm) {
            EnumSet<AclService.Permission> allow = EnumSet.noneOf(AclService.Permission.class);
            EnumSet<AclService.Permission> deny = EnumSet.noneOf(AclService.Permission.class);
            for (Map.Entry<String, Map<String, Entry>> e : model.entrySet()) {
                if (key.startsWith(e.getKey())) {            // brute-force ancestor match (no floor/lower)
                    Entry en = e.getValue().get(principal);
                    if (en != null) {
                        allow.addAll(en.allow);
                        deny.addAll(en.deny);
                    }
                }
            }
            allow.removeAll(deny);                           // absolute deny-precedence; default-deny implicit
            if (perm == WATCH) {                             // effective WATCH = WATCH ∧ READ (INV-WATCH-READ)
                return allow.contains(WATCH) && allow.contains(READ);
            }
            return allow.contains(perm);
        }
    }

    /** A random non-empty capability subset (as an EnumSet, matching AclService's immutable() input). */
    private static EnumSet<AclService.Permission> randomCaps(Random r) {
        EnumSet<AclService.Permission> caps = EnumSet.noneOf(AclService.Permission.class);
        for (AclService.Permission p : PERMS) {
            if (r.nextBoolean()) {
                caps.add(p);
            }
        }
        if (caps.isEmpty()) {
            caps.add(PERMS[r.nextInt(PERMS.length)]);
        }
        return caps;
    }

    // -----------------------------------------------------------------------
    // (a) + (b): fuzz AclService against the independent oracle through four lenses, empty role maps.
    // -----------------------------------------------------------------------

    /**
     * Over many random configurations (built only from grant/deny/revoke — the pre-O6 API surface, so the
     * role maps stay empty as in production), every (principal, key, permission) decision must equal the
     * independent oracle through all four lenses:
     * <ul>
     *   <li>L1 — 3-arg {@code isAllowed(p, key, perm)};</li>
     *   <li>L2 — 4-arg {@code isAllowed(p, Set.of(), key, perm)} (no authn-asserted roles);</li>
     *   <li>L3 — 4-arg {@code isAllowed(p, Set.of("admin"), key, perm)} (the EXACT production shape);</li>
     *   <li>L4 — 4-arg {@code isAllowed(p, Set.of("admin", <random-undefined>), key, perm)}.</li>
     * </ul>
     * Because no role is ever defined, every lens must reduce to the own-grants-only oracle.
     */
    @Test
    void differentialAgainstIndependentOracleAcrossFourLenses() {
        Random r = new Random(0xB17E_1D0FFL); // fixed seed -> reproducible
        int assertions = 0;
        final int configs = 80;

        for (int c = 0; c < configs; c++) {
            AclService acl = new AclService();
            OracleAcl oracle = new OracleAcl();
            List<String> opLog = new ArrayList<>();

            int ops = 1 + r.nextInt(12);
            for (int i = 0; i < ops; i++) {
                String prefix = PREFIXES[r.nextInt(PREFIXES.length)];
                String principal = GRANT_PRINCIPALS[r.nextInt(GRANT_PRINCIPALS.length)];
                int kind = r.nextInt(10); // 0-4 grant, 5-8 deny, 9 revoke
                if (kind <= 4) {
                    EnumSet<AclService.Permission> caps = randomCaps(r);
                    acl.grant(prefix, principal, caps);
                    oracle.grant(prefix, principal, caps);
                    opLog.add("grant(\"" + prefix + "\",\"" + principal + "\"," + caps + ")");
                } else if (kind <= 8) {
                    EnumSet<AclService.Permission> caps = randomCaps(r);
                    acl.deny(prefix, principal, caps);
                    oracle.deny(prefix, principal, caps);
                    opLog.add("deny(\"" + prefix + "\",\"" + principal + "\"," + caps + ")");
                } else {
                    acl.revoke(prefix, principal);
                    oracle.revoke(prefix, principal);
                    opLog.add("revoke(\"" + prefix + "\",\"" + principal + "\")");
                }
            }

            String undefined = "undef-" + r.nextInt(1_000_000); // never defined -> contributes nothing
            for (String principal : QUERY_PRINCIPALS) {
                for (String key : KEYS) {
                    for (AclService.Permission perm : PERMS) {
                        boolean expected = oracle.decide(principal, key, perm);

                        boolean l1 = acl.isAllowed(principal, key, perm);
                        boolean l2 = acl.isAllowed(principal, Set.of(), key, perm);
                        boolean l3 = acl.isAllowed(principal, Set.of("admin"), key, perm);
                        boolean l4 = acl.isAllowed(principal, Set.of("admin", undefined), key, perm);

                        assertEquals(expected, l1, divergence("L1 3-arg", opLog, principal, key, perm));
                        assertEquals(expected, l2, divergence("L2 4-arg empty roles", opLog, principal, key, perm));
                        assertEquals(expected, l3, divergence("L3 4-arg {\"admin\"} (production)", opLog, principal, key, perm));
                        assertEquals(expected, l4, divergence("L4 4-arg {\"admin\",undefined}", opLog, principal, key, perm));
                        assertions += 4;
                    }
                }
            }
        }

        assertTrue(assertions >= 5000, "expected a large differential space; ran " + assertions);
    }

    // -----------------------------------------------------------------------
    // (c): the deployed grant("","root",allOf), evaluated through the production {"admin"} 4-arg path.
    // -----------------------------------------------------------------------

    /**
     * Replicates the deployed config verbatim — the sole {@code grant("", "root", allOf)} — and drives it
     * through the production authn shape {@code authed.roles() == {"admin"}} (a role never defined). Root
     * must be authorized for every capability on every key (its global own grant), and every non-root
     * principal must be fully default-denied; both equal the independent oracle. This extends
     * {@code ProductionByteIdentity} (which pins the 3-arg path) to the 4-arg production shape.
     */
    @Test
    void deployedRootGrantThroughProductionAdminRole() {
        AclService acl = new AclService();
        OracleAcl oracle = new OracleAcl();
        EnumSet<AclService.Permission> allOf = EnumSet.allOf(AclService.Permission.class);
        acl.grant("", "root", allOf);
        oracle.grant("", "root", allOf);

        Set<String> prodRoles = Set.of("admin"); // asserted by authn (ConfigdServer:720); never defined

        for (String key : KEYS) {
            for (AclService.Permission perm : PERMS) {
                // Root: authorized everywhere, and identical to the oracle (which also says true).
                assertTrue(acl.isAllowed("root", prodRoles, key, perm),
                        () -> "root must be authorized for " + perm + " on '" + key + "'");
                assertEquals(oracle.decide("root", key, perm), acl.isAllowed("root", prodRoles, key, perm),
                        divergence("root production path", List.of("grant(\"\",\"root\",allOf)"), "root", key, perm));

                // Every non-root principal: default-denied, identical to the oracle (false).
                for (String p : new String[]{"intruder", "alice", "admin"}) {
                    assertEquals(oracle.decide(p, key, perm), acl.isAllowed(p, prodRoles, key, perm),
                            divergence("non-root production path", List.of("grant(\"\",\"root\",allOf)"), p, key, perm));
                    assertFalse(acl.isAllowed(p, prodRoles, key, perm),
                            () -> "non-root '" + p + "' must be denied " + perm + " on '" + key + "' (admin role is dormant)");
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Hardening: even an ACL-static binding to an UNDEFINED role (which flips the empty-roles guard's
    // branch and runs the role block) must not perturb a single decision while no role is defined.
    // -----------------------------------------------------------------------

    /**
     * Strictly beyond the production shape (server code never calls {@code assignRole}), this proves the
     * guard {@code if (!roles.isEmpty() || !staticRoles.isEmpty())} is sound: binding undefined static
     * roles makes the role block <i>execute</i>, but with no {@link AclService#defineRole} the lookup
     * finds nothing and contributes nothing — so the decision still equals the independent oracle.
     */
    @Test
    void undefinedStaticBindingFlipsGuardYetStaysByteIdentical() {
        Random r = new Random(0x5EEDED_60ADL);
        int assertions = 0;
        final int configs = 40;

        for (int c = 0; c < configs; c++) {
            AclService acl = new AclService();
            OracleAcl oracle = new OracleAcl();
            List<String> opLog = new ArrayList<>();

            int ops = 1 + r.nextInt(10);
            for (int i = 0; i < ops; i++) {
                String prefix = PREFIXES[r.nextInt(PREFIXES.length)];
                String principal = GRANT_PRINCIPALS[r.nextInt(GRANT_PRINCIPALS.length)];
                EnumSet<AclService.Permission> caps = randomCaps(r);
                if (r.nextBoolean()) {
                    acl.grant(prefix, principal, caps);
                    oracle.grant(prefix, principal, caps);
                    opLog.add("grant(\"" + prefix + "\",\"" + principal + "\"," + caps + ")");
                } else {
                    acl.deny(prefix, principal, caps);
                    oracle.deny(prefix, principal, caps);
                    opLog.add("deny(\"" + prefix + "\",\"" + principal + "\"," + caps + ")");
                }
            }

            // Bind every grant-principal to an UNDEFINED static role -> guard's staticRoles branch fires,
            // but the role stays undefined (defineRole is never called), so it must add nothing. The
            // oracle is intentionally NOT told about this binding (it has no role concept).
            for (String principal : GRANT_PRINCIPALS) {
                acl.assignRole(principal, "static-undef-" + r.nextInt(1_000_000));
                opLog.add("assignRole(\"" + principal + "\", <undefined>)");
            }

            for (String principal : QUERY_PRINCIPALS) {
                for (String key : KEYS) {
                    for (AclService.Permission perm : PERMS) {
                        boolean expected = oracle.decide(principal, key, perm);
                        // 3-arg delegates with empty authn roles; the static binding alone flips the guard.
                        assertEquals(expected, acl.isAllowed(principal, key, perm),
                                divergence("guard-flip 3-arg w/ undefined static role", opLog, principal, key, perm));
                        // And with an authn-asserted (also undefined) role on top.
                        assertEquals(expected, acl.isAllowed(principal, Set.of("admin"), key, perm),
                                divergence("guard-flip 4-arg {\"admin\"}", opLog, principal, key, perm));
                        assertions += 2;
                    }
                }
            }
        }

        assertTrue(assertions >= 1000, "expected a substantial guard-flip space; ran " + assertions);
    }

    /** Lazy, fully-detailed divergence message: the config op-log and the exact (principal, key, perm). */
    private static java.util.function.Supplier<String> divergence(
            String lens, List<String> opLog, String principal, String key, AclService.Permission perm) {
        return () -> "DIVERGENCE [" + lens + "] for (principal=\"" + principal + "\", key=\"" + key
                + "\", perm=" + perm + ")\n  config:\n    " + String.join("\n    ", opLog);
    }
}

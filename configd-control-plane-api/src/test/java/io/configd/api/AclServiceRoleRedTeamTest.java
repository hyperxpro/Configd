package io.configd.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.LIST;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-TEAM suite for the <b>role indirection</b> of {@link AclService}. Sibling to, and deliberately
 * disjoint from, {@code AclServiceRedTeamTest} (which attacks the own-grants-only union) and
 * {@code AclServiceRoleTest} (the happy-path role wiring).
 * <p>
 * The role layer folds a principal's <b>own</b> per-prefix grants and its <b>role</b> grants
 * (authn-asserted {@code roles} argument union {@link AclService#assignRole} ACL-static bindings) into a
 * <b>single</b> shared {@code (allow, deny)} pair, subtracts deny <b>once</b> over that combined set,
 * then applies the effective-{@code WATCH} = {@code WATCH} AND {@code READ} floor. Each test here
 * <b>constructs an attack</b> against that role indirection and asserts the <b>correct, secure
 * decision</b>; a failing assertion means the attack succeeded and the test names the defect.
 * <p>
 * The own-grants-only properties (walk-stop/decoy evasion, deny order/specificity independence, union
 * never manufactures, prefix-boundary, revoke residue, cross-principal isolation, concurrency,
 * WATCH-never-out-reads-READ, LIST independent of READ, per-cap DENY inevadable,
 * single-key-vs-whole-target) are already proven in {@code AclServiceRedTeamTest}. This suite
 * <b>extends each relevant property THROUGH the role layer</b> rather than duplicating the own-grants
 * case. The crown jewels are the WATCH-floor tests that fail if a future refactor evaluates own vs. role
 * grants <i>separately and OR-combines them</i> instead of subtracting deny once over the combined set
 * (which would leak a denied descendant's watch).
 */
@DisplayName("AclService — red-team / adversarial role-aware authorization (O-6 Seam 1)")
class AclServiceRoleRedTeamTest {

    private AclService acl;

    @BeforeEach
    void setUp() {
        acl = new AclService();
    }


    private static PolicyRule allowRule(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(caps), Set.of());
    }

    private static PolicyRule denyRule(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(), Set.of(caps));
    }

    private static Role role(String name, PolicyRule... rules) {
        return new Role(name, List.of(new Policy(name + "-pol", List.of(rules))));
    }

    @Nested
    @DisplayName("Role-Attack 1: a DENY (own / other-role / same-role) beats a role ALLOW, every direction & order")
    class EscalationPastDeny {

        @Test
        @DisplayName("OWN deny beats role ALLOW — both deny@ancestor/allow@descendant and the reverse")
        void ownDenyBeatsRoleAllow_bothDirections() {
            AclService a = new AclService();
            a.defineRole(role("descW", allowRule("a.b.", WRITE)));
            a.deny("a.", "p", Set.of(WRITE));
            assertFalse(a.isAllowed("p", Set.of("descW"), "a.b.x", WRITE),
                    "own ancestor DENY(WRITE) must beat a role descendant ALLOW(WRITE)");

            AclService b = new AclService();
            b.defineRole(role("ancW", allowRule("a.", WRITE)));
            b.deny("a.b.", "p", Set.of(WRITE));
            assertAll(
                    () -> assertFalse(b.isAllowed("p", Set.of("ancW"), "a.b.x", WRITE),
                            "own descendant DENY(WRITE) must beat a role ancestor ALLOW(WRITE)"),
                    () -> assertTrue(b.isAllowed("p", Set.of("ancW"), "a.other", WRITE),
                            "sibling outside the own deny keeps the role-granted WRITE (deny is startsWith-scoped)"));
        }

        @Test
        @DisplayName("ANOTHER role's deny beats a role ALLOW — both directions")
        void otherRoleDenyBeatsRoleAllow_bothDirections() {
            AclService a = new AclService();
            a.defineRole(role("descW", allowRule("a.b.", WRITE)));
            a.defineRole(role("ancBlock", denyRule("a.", WRITE)));
            assertFalse(a.isAllowed("p", Set.of("descW", "ancBlock"), "a.b.x", WRITE),
                    "a role DENY at the ancestor must beat a (different) role ALLOW at the descendant");

            AclService b = new AclService();
            b.defineRole(role("ancW", allowRule("a.", WRITE)));
            b.defineRole(role("descBlock", denyRule("a.b.", WRITE)));
            assertFalse(b.isAllowed("p", Set.of("ancW", "descBlock"), "a.b.x", WRITE),
                    "a role DENY at the descendant must beat a (different) role ALLOW at the ancestor");
        }

        @Test
        @DisplayName("SAME-role deny beats SAME-role ALLOW (deny wins within one role's flattened rules)")
        void sameRoleDenyBeatsSameRoleAllow() {
            // One role, two policies: policy A allows WRITE on a., policy B denies WRITE on a.b. The
            // flattened rule set folds both; deny is absolute over the union -> no WRITE on a.b.x.
            Role conflicted = new Role("mixed", List.of(
                    new Policy("allowPol", List.of(allowRule("a.", READ, WRITE))),
                    new Policy("denyPol", List.of(denyRule("a.b.", WRITE)))));
            acl.defineRole(conflicted);
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("mixed"), "a.b.x", WRITE),
                            "a same-role DENY(WRITE) on a.b. beats the same-role ALLOW(WRITE) on a."),
                    () -> assertTrue(acl.isAllowed("p", Set.of("mixed"), "a.b.x", READ),
                            "only WRITE was carved out; the role's READ survives"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("mixed"), "a.other", WRITE),
                            "outside the deny subtree the role's WRITE survives"));
        }

        @Test
        @DisplayName("deny beats role ALLOW for ADMIN too (deny beats 'sudo' through roles)")
        void roleAdminDeniedByDeny() {
            acl.defineRole(role("sudo", allowRule("a.", ADMIN)));
            acl.deny("a.b.", "p", Set.of(ADMIN));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("sudo"), "a.b.x", ADMIN),
                            "an own DENY(ADMIN) must beat a role ALLOW(ADMIN) — deny beats sudo through roles"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("sudo"), "a.x", ADMIN),
                            "outside the carve-out the role ADMIN holds"));
        }

        @Test
        @DisplayName("deny wins in EVERY setup ordering, including assign-before-define")
        void denyWinsRegardlessOfSetupOrder() {
            // Scenario fixed: role allowW ALLOWs WRITE on a.b.; role blockW DENYs WRITE on a.; principal p
            // holds both via assignRole. For a.b.x, deny must win no matter the order of the four calls.
            int leaks = 0;
            for (int order = 0; order < 4; order++) {
                AclService a = new AclService();
                Runnable defineAllow = () -> a.defineRole(role("allowW", allowRule("a.b.", WRITE)));
                Runnable defineBlock = () -> a.defineRole(role("blockW", denyRule("a.", WRITE)));
                Runnable assignAllow = () -> a.assignRole("p", "allowW");
                Runnable assignBlock = () -> a.assignRole("p", "blockW");
                List<Runnable> steps = switch (order) {
                    case 0 -> List.of(defineAllow, defineBlock, assignAllow, assignBlock);
                    case 1 -> List.of(assignAllow, assignBlock, defineAllow, defineBlock); // assign BEFORE define
                    case 2 -> List.of(defineBlock, assignBlock, defineAllow, assignAllow);
                    default -> List.of(assignBlock, defineAllow, assignAllow, defineBlock); // interleaved
                };
                steps.forEach(Runnable::run);
                // No authn-asserted roles: rely purely on the static bindings.
                if (a.isAllowed("p", Set.of(), "a.b.x", WRITE)) {
                    leaks++;
                }
            }
            assertEquals(0, leaks,
                    "role/own deny-precedence must be independent of defineRole/assignRole ordering; "
                            + "WRITE leaked in " + leaks + " of 4 orderings");
        }
    }

    @Nested
    @DisplayName("Role-Attack 2: union of READ/WRITE-only sources (own + roles) never manufactures ADMIN/LIST/WATCH")
    class UnionNeverManufactures {

        @Test
        @DisplayName("exhaustive: 27 own×role1×role2 stacks of {READ}/{WRITE}/{READ,WRITE} confer no ADMIN/LIST/WATCH")
        void noStackOfReadWriteSourcesConfersForbiddenCaps() {
            List<Set<AclService.Permission>> options = List.of(
                    Set.of(READ), Set.of(WRITE), Set.of(READ, WRITE));
            int adminLeaks = 0, listLeaks = 0, watchLeaks = 0;
            for (Set<AclService.Permission> own : options) {
                for (Set<AclService.Permission> r1 : options) {
                    for (Set<AclService.Permission> r2 : options) {
                        AclService a = new AclService();
                        a.grant("a.", "p", own);
                        a.defineRole(role("role1", new PolicyRule("a.b.", r1, Set.of())));
                        a.defineRole(role("role2", new PolicyRule("a.b.c.", r2, Set.of())));
                        String key = "a.b.c.x";
                        if (a.isAllowed("p", Set.of("role1", "role2"), key, ADMIN)) adminLeaks++;
                        if (a.isAllowed("p", Set.of("role1", "role2"), key, LIST)) listLeaks++;
                        if (a.isAllowed("p", Set.of("role1", "role2"), key, WATCH)) watchLeaks++;
                    }
                }
            }
            int admin = adminLeaks, list = listLeaks, watch = watchLeaks;
            assertAll(
                    () -> assertEquals(0, admin, "ADMIN manufactured from READ/WRITE-only sources"),
                    () -> assertEquals(0, list, "LIST manufactured from READ/WRITE-only sources"),
                    () -> assertEquals(0, watch,
                            "effective WATCH manufactured from READ/WRITE-only sources (WATCH was granted nowhere)"));
        }

        @Test
        @DisplayName("union confers exactly the granted set-union across own + roles, nothing more")
        void unionIsExactlyTheGrantedSetUnion() {
            acl.grant("a.", "p", Set.of(READ));
            acl.defineRole(role("w", allowRule("a.", WRITE)));
            acl.defineRole(role("l", allowRule("a.", LIST)));
            assertAll(
                    () -> assertTrue(acl.isAllowed("p", Set.of("w", "l"), "a.x", READ), "own READ"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("w", "l"), "a.x", WRITE), "role w WRITE"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("w", "l"), "a.x", LIST), "role l LIST"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("w", "l"), "a.x", ADMIN), "ADMIN granted nowhere"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("w", "l"), "a.x", WATCH), "WATCH granted nowhere"));
        }
    }

    @Nested
    @DisplayName("Role-Attack 3: degenerate/undefined role shapes grant nothing (default-deny)")
    class DefaultDenyNoEscalation {

        @Test
        @DisplayName("unknown principal + arbitrary UNDEFINED roles -> every capability denied")
        void unknownPrincipalUndefinedRolesAllDenied() {
            acl.defineRole(role("real", allowRule("a.", READ, LIST, WRITE, WATCH, ADMIN)));
            acl.grant("a.", "known", Set.of(READ));
            for (AclService.Permission perm : AclService.Permission.values()) {
                assertFalse(acl.isAllowed("mallory", Set.of("ghost", "phantom"), "a.x", perm),
                        () -> "unknown principal + undefined roles must be denied for " + perm);
            }
        }

        @Test
        @DisplayName("assigned-but-undefined role contributes nothing (binding without a definition)")
        void assignedButUndefinedRoleContributesNothing() {
            acl.assignRole("p", "neverDefined");
            for (AclService.Permission perm : AclService.Permission.values()) {
                assertFalse(acl.isAllowed("p", Set.of(), "a.x", perm),
                        () -> "an assigned role with no definition must add nothing for " + perm);
            }
        }

        @Test
        @DisplayName("empty Role / empty Policy / empty-allow PolicyRule grant nothing")
        void emptyRoleShapesGrantNothing() {
            acl.defineRole(new Role("emptyRole", List.of()));                       // no policies
            acl.defineRole(new Role("emptyPolicy", List.of(new Policy("e", List.of())))); // policy, no rules
            acl.defineRole(role("emptyAllow", new PolicyRule("a.", Set.of(), Set.of()))); // rule, empty allow+deny
            Set<String> all = Set.of("emptyRole", "emptyPolicy", "emptyAllow");
            for (AclService.Permission perm : AclService.Permission.values()) {
                assertFalse(acl.isAllowed("p", all, "a.x", perm),
                        () -> "empty role shapes must manufacture nothing for " + perm);
            }
        }

        @Test
        @DisplayName("a defined role NOT held by the principal grants nothing (must be asserted or assigned)")
        void definedButUnheldRoleGrantsNothing() {
            acl.defineRole(role("powerful", allowRule("", READ, LIST, WRITE, WATCH, ADMIN)));
            for (AclService.Permission perm : AclService.Permission.values()) {
                assertFalse(acl.isAllowed("p", Set.of(), "anything", perm),
                        () -> "a defined role the principal neither asserts nor is assigned grants nothing for " + perm);
            }
        }
    }

    // ROLE-ATTACK 4 - WATCH FLOOR THROUGH ROLES (the crown jewels). effective-WATCH = WATCH AND READ over
    // the COMBINED own union role set. The strongest test: a role that, evaluated in ISOLATION, would authorize
    // the watch (it holds READ+WATCH) must STILL be floored by a READ-deny that lives in a DIFFERENT
    // source (own grant) on the key's chain. A per-source OR-combine would leak this; the shared
    // accumulator + single deny-subtract must not.
    @Nested
    @DisplayName("Role-Attack 4: effective WATCH is floored by READ across the combined own∪role set")
    class WatchFloorThroughRoles {

        @Test
        @DisplayName("CROWN JEWEL: role holds READ+WATCH (would self-authorize) but an OWN READ-deny on the chain floors it")
        void roleReadWatchFlooredByOwnReadDeny() {
            acl.defineRole(role("rw", allowRule("a.", READ, WATCH)));
            acl.deny("a.secret.", "p", Set.of(READ));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("rw"), "a.secret.k", WATCH),
                            "role READ+WATCH MUST be floored by an OWN READ-deny on the chain — proves deny is "
                                    + "subtracted ONCE over the combined own∪role set, NOT per-source "
                                    + "(a per-source OR-combine would LEAK this watch)"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("rw"), "a.secret.k", READ),
                            "READ itself is denied on a.secret.* by the own carve-out"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("rw"), "a.public.k", WATCH),
                            "outside the carve-out the role READ+WATCH yields effective WATCH"));
        }

        @Test
        @DisplayName("mirror: own holds READ+WATCH but a ROLE READ-deny on the chain floors it")
        void ownReadWatchFlooredByRoleReadDeny() {
            acl.grant("a.", "p", Set.of(READ, WATCH));
            acl.defineRole(role("blockRead", denyRule("a.secret.", READ)));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("blockRead"), "a.secret.k", WATCH),
                            "own READ+WATCH MUST be floored by a ROLE READ-deny on the chain (combined-set deny)"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("blockRead"), "a.public.k", WATCH),
                            "outside the role carve-out, own READ+WATCH still yields effective WATCH"));
        }

        @Test
        @DisplayName("a role granting WATCH but NO READ (own or role) anywhere -> no effective WATCH")
        void roleWatchWithoutAnyReadIsIneffective() {
            acl.defineRole(role("watchOnly", allowRule("a.", WATCH, LIST, WRITE, ADMIN)));
            assertFalse(acl.isAllowed("p", Set.of("watchOnly"), "a.x", WATCH),
                    "a role cannot manufacture effective WATCH without READ in the combined set");
        }

        @Test
        @DisplayName("a ROLE WATCH-deny on the chain removes effective WATCH even with own READ+WATCH")
        void roleWatchDenyRemovesEffectiveWatch() {
            acl.grant("a.b.", "p", Set.of(READ, WATCH));
            acl.defineRole(role("noWatch", denyRule("a.", WATCH)));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("noWatch"), "a.b.x", WATCH),
                            "a role DENY(WATCH) at an ancestor removes effective WATCH"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("noWatch"), "a.b.x", READ),
                            "READ is unaffected by the WATCH deny"));
        }

        @Test
        @DisplayName("effective WATCH from a deep REAL role ancestor survives an OWN poisoned-decoy walk")
        void roleWatchSurvivesPoisonedDecoyWalk() {
            // READ+WATCH come from a deep REAL ancestor via a ROLE; OWN denies sit on NON-ancestor decoys.
            // If a decoy leaked (startsWith broken) or the own walk halted early, effective WATCH would vanish.
            acl.defineRole(role("deepRW", allowRule("a.b.c.d.", READ, WATCH)));
            for (String decoy : List.of("a.a", "a.b.a", "a.b.c.a", "a.b.c.d.a")) {
                acl.deny(decoy, "p", Set.of(READ, WATCH));
            }
            assertTrue(acl.isAllowed("p", Set.of("deepRW"), "a.b.c.d.e", WATCH),
                    "role-granted deep READ+WATCH must survive — the own decoy denies are non-ancestors, "
                            + "filtered by startsWith; the role rule matcher is the same literal startsWith");
        }

        @Test
        @DisplayName("READ from own ancestor ∧ WATCH from a role descendant compose to effective WATCH (floor over the union)")
        void ownReadAncestorComposesWithRoleWatchDescendant() {
            acl.grant("a.", "p", Set.of(READ));
            acl.defineRole(role("w", allowRule("a.b.", WATCH)));
            assertAll(
                    () -> assertTrue(acl.isAllowed("p", Set.of("w"), "a.b.x", WATCH),
                            "own READ(ancestor) ∧ role WATCH(descendant) -> effective WATCH (floor is over the union)"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("w"), "a.c.x", WATCH),
                            "a.c.x has own READ but no WATCH (own or role) in its chain -> not watchable"));
        }
    }

    @Nested
    @DisplayName("Role-Attack 5: role-rule matching is exactly startsWith (no sibling/decoy bleed)")
    class DecoyPrefixBoundaryThroughRoles {

        @Test
        @DisplayName("a role DENY on app.secret. does NOT bleed to the sibling app.secretZ")
        void roleDenyDoesNotBleedToLexicalSibling() {
            acl.defineRole(role("appRole",
                    allowRule("app.", READ, WRITE),
                    denyRule("app.secret.", WRITE)));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("appRole"), "app.secret.key", WRITE),
                            "WRITE denied inside the app.secret. subtree by the role deny"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("appRole"), "app.secret.key", READ),
                            "READ still allowed inside the subtree (only WRITE carved out)"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("appRole"), "app.secretZ", WRITE),
                            "app.secretZ is NOT under app.secret. — the role deny must not bleed; app. grants WRITE"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("appRole"), "app.secretZ", READ),
                            "app.secretZ READ authorized by the role's app. grant"));
        }

        @Test
        @DisplayName("a role ALLOW on a NON-ancestor prefix does not reach the key (startsWith confusion)")
        void roleAllowOnNonAncestorDoesNotReach() {
            acl.defineRole(role("decoy", allowRule("app.secretZ", WRITE)));
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("decoy"), "app.secret.key", WRITE),
                            "app.secret.key does NOT start with app.secretZ — the role grant must not reach it"),
                    () -> assertTrue(acl.isAllowed("p", Set.of("decoy"), "app.secretZ.inner", WRITE),
                            "a genuine descendant of the role's prefix IS reached"));
        }

        @Test
        @DisplayName("empty-prefix role rule is global (ancestor of every key) for allow AND deny")
        void emptyPrefixRoleRuleIsGlobal() {
            acl.defineRole(role("globalRead", allowRule("", READ)));
            acl.defineRole(role("globalDenyAdmin", denyRule("", ADMIN)));
            acl.grant("", "p", Set.of(ADMIN));
            assertAll(
                    () -> assertTrue(acl.isAllowed("p", Set.of("globalRead"), "very/deep/key", READ),
                            "empty-prefix role ALLOW(READ) reaches an arbitrarily deep key (global ancestor)"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("globalDenyAdmin"), "very/deep/key", ADMIN),
                            "empty-prefix role DENY(ADMIN) globally beats the own global ADMIN grant"));
        }
    }

    @Nested
    @DisplayName("Role-Attack 6: roles reach only their holders (no cross-principal leakage)")
    class CrossPrincipalCrossRoleIsolation {

        @Test
        @DisplayName("assignRole(alice, r) does not grant r to bob")
        void staticBindingIsPerPrincipal() {
            acl.defineRole(role("r", allowRule("a.", READ, WRITE)));
            acl.assignRole("alice", "r");
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", Set.of(), "a.x", READ), "alice holds r via assignRole"),
                    () -> assertFalse(acl.isAllowed("bob", Set.of(), "a.x", READ),
                            "bob was never assigned r -> default-deny (binding is per-principal)"),
                    () -> assertTrue(acl.isAllowed("bob", Set.of("r"), "a.x", READ),
                            "bob only reaches r by ASSERTING it (authn face-value) — proves the two holder sources"));
        }

        @Test
        @DisplayName("a role's DENY held by alice must not shadow bob's own ALLOW of the same cap")
        void roleDenyDoesNotShadowNonHoldersOwnAllow() {
            acl.defineRole(role("blocker", denyRule("svc.", WRITE)));
            acl.assignRole("alice", "blocker");
            acl.grant("svc.", "alice", Set.of(WRITE));
            acl.grant("svc.", "bob", Set.of(WRITE));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", Set.of(), "svc.config", WRITE),
                            "alice holds blocker -> her own WRITE is denied"),
                    () -> assertTrue(acl.isAllowed("bob", Set.of(), "svc.config", WRITE),
                            "bob does NOT hold blocker -> his own WRITE is untouched by alice's role deny"));
        }

        @Test
        @DisplayName("a role's ALLOW does not leak to a principal who neither asserts nor is assigned it")
        void roleAllowReachesOnlyHolders() {
            acl.defineRole(role("admins", allowRule("", ADMIN)));
            acl.assignRole("alice", "admins");
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", Set.of(), "anything", ADMIN), "alice holds admins"),
                    () -> assertFalse(acl.isAllowed("carol", Set.of(), "anything", ADMIN),
                            "carol neither asserts nor is assigned admins -> no ADMIN"));
        }
    }

    // ROLE-ATTACK 7 - CONCURRENCY. A standing DENY (role or own) must never leak while a writer churns
    // defineRole/assignRole/grant/revoke of the same cap, and isAllowed must never throw on a torn read.
    // Iterations kept modest for the 2-vCPU box (mirrors own-grants Attack 9).
    @Nested
    @DisplayName("Role-Attack 7: a standing deny never leaks under role/grant churn; isAllowed never throws")
    class ConcurrencySafety {

        private static final int READS = 200_000;

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("standing ROLE deny (via assignRole) holds while a writer churns own grant/revoke(WRITE)")
        void standingRoleDenyHoldsUnderOwnGrantChurn() throws InterruptedException {
            acl.defineRole(role("blocker", denyRule("", WRITE)));
            acl.assignRole("alice", "blocker");                   // PERMANENT - never removed
            acl.grant("", "alice", Set.of(READ, WRITE));          // base own allow (deny must still win)

            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<Throwable> writerError = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        acl.grant("a.", "alice", Set.of(WRITE));
                        acl.revoke("a.", "alice");
                    }
                } catch (Throwable t) {
                    writerError.set(t);
                }
            }, "role-churn-writer");
            writer.start();

            int leaks = 0;
            for (int i = 0; i < READS; i++) {
                // The role's global WRITE deny is absolute & permanent; no own grant can make WRITE allowed.
                if (acl.isAllowed("alice", Set.of(), "a.x", WRITE)) {
                    leaks++;
                }
            }
            stop.set(true);
            writer.join();

            int observed = leaks;
            assertAll(
                    () -> assertTrue(writerError.get() == null, "writer threw: " + writerError.get()),
                    () -> assertEquals(0, observed,
                            "WRITE leaked past the standing role DENY in " + observed + " reads (deny-bypass / torn read)"),
                    () -> assertTrue(acl.isAllowed("alice", Set.of(), "a.x", READ), "READ stays allowed throughout"));
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("standing OWN deny holds while a writer churns defineRole/assignRole of a WRITE-granting role")
        void standingOwnDenyHoldsUnderRoleChurn() throws InterruptedException {
            acl.grant("", "alice", Set.of(READ, WRITE));
            acl.deny("", "alice", Set.of(WRITE));   // PERMANENT own WRITE deny - never removed
            acl.assignRole("alice", "granter");     // static binding present; the role's CONTENT is churned

            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<Throwable> writerError = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    boolean toggle = false;
                    while (!stop.get()) {
                        // Redefine the same role between a WRITE-granting shape and an empty shape, and keep
                        // (idempotently) assigning it. The own WRITE deny must win over every transient role ALLOW.
                        acl.defineRole(toggle
                                ? role("granter", allowRule("a.", WRITE))
                                : role("granter", allowRule("a.", READ)));
                        acl.assignRole("alice", "granter");
                        toggle = !toggle;
                    }
                } catch (Throwable t) {
                    writerError.set(t);
                }
            }, "roledef-churn-writer");
            writer.start();

            int leaks = 0;
            for (int i = 0; i < READS; i++) {
                // Reader passes an authn-asserted role too, exercising the effectiveRoles = roles union static
                // branch under churn. The own absolute WRITE deny must hold regardless of the role's content.
                if (acl.isAllowed("alice", Set.of("granter"), "a.x", WRITE)) {
                    leaks++;
                }
            }
            stop.set(true);
            writer.join();

            int observed = leaks;
            assertAll(
                    () -> assertTrue(writerError.get() == null, "writer threw: " + writerError.get()),
                    () -> assertEquals(0, observed,
                            "WRITE leaked past the standing own DENY while a role was churned in " + observed + " reads"));
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("isAllowed never throws while roleDefinitions / principalRoles are churned concurrently")
        void readerNeverThrowsDuringRoleChurn() throws InterruptedException {
            AtomicBoolean stop = new AtomicBoolean(false);
            List<Throwable> errors = new ArrayList<>();
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        acl.defineRole(new Role("r", List.of(
                                new Policy("p", List.of(
                                        allowRule("k.", READ, WRITE, ADMIN),
                                        denyRule("k.secret.", WRITE))))));
                        acl.assignRole("alice", "r");
                        acl.grant("k.", "alice", Set.of(READ));
                        acl.revoke("k.", "alice");
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                }
            }, "role-swap-writer");
            writer.start();

            assertDoesNotThrow(() -> {
                for (int i = 0; i < READS; i++) {
                    acl.isAllowed("alice", Set.of("r"), "k.v", READ);
                    acl.isAllowed("alice", Set.of("r"), "k.secret.v", WRITE);
                }
            }, "isAllowed must read the role maps lock-free and never throw on a torn read");

            stop.set(true);
            writer.join();
            synchronized (errors) {
                assertTrue(errors.isEmpty(), "writer threw: " + errors);
            }
        }
    }

    // ROLE-ATTACK 8 - AUTHN-TRUST BOUNDARY & NAME-COLLISION. Roles are taken at FACE VALUE from the caller
    // (by design - authn asserts them). Principal grants live in `acls` keyed by principal; role grants in
    // `roleDefinitions` keyed by role name. A principal and a role that share a NAME must NOT share grants.
    @Nested
    @DisplayName("Role-Attack 8: principal-name and role-name namespaces never cross (authn face-value)")
    class AuthnTrustNameCollision {

        @Test
        @DisplayName("a principal NAMED 'admin' that does not HOLD role 'admin' gets nothing from the role")
        void principalNameDoesNotImplyRoleMembership() {
            acl.defineRole(role("admin", allowRule("pub.", READ)));
            assertAll(
                    () -> assertFalse(acl.isAllowed("admin", Set.of(), "pub.x", READ),
                            "principal named 'admin' does not implicitly hold the role 'admin' "
                                    + "(no principal-name -> role-name coupling)"),
                    () -> assertTrue(acl.isAllowed("zoe", Set.of("admin"), "pub.x", READ),
                            "any principal that ASSERTS role 'admin' gets its grant — the role is keyed by name, "
                                    + "not by principal"));
        }

        @Test
        @DisplayName("asserting a role literally NAMED after another principal does not inherit that principal's own grants")
        void roleNameCollidingWithPrincipalDoesNotInheritOwnGrants() {
            acl.grant("sys.", "alice", Set.of(ADMIN));
            assertAll(
                    () -> assertFalse(acl.isAllowed("bob", Set.of("alice"), "sys.x", ADMIN),
                            "bob asserting a role named 'alice' must NOT inherit principal alice's own grants "
                                    + "(roleDefinitions has no 'alice' role; own grants live in acls keyed by principal)"),
                    () -> assertTrue(acl.isAllowed("alice", Set.of(), "sys.x", ADMIN),
                            "alice's own grant is reached by alice herself (sanity)"));
        }

        @Test
        @DisplayName("same name, both namespaces populated: a principal's own grant and a like-named role stay disjoint")
        void principalGrantAndLikeNamedRoleStayDisjoint() {
            // Principal "ops" has own READ on "log."; role "ops" grants WRITE on "cfg.". They must not merge.
            acl.grant("log.", "ops", Set.of(READ));
            acl.defineRole(role("ops", allowRule("cfg.", WRITE)));

            assertAll(
                    () -> assertTrue(acl.isAllowed("ops", Set.of(), "log.x", READ), "own grant intact"),
                    () -> assertFalse(acl.isAllowed("ops", Set.of(), "cfg.x", WRITE),
                            "principal ops does not get role ops's WRITE unless it asserts/holds the role"),
                    () -> assertTrue(acl.isAllowed("ops", Set.of("ops"), "cfg.x", WRITE),
                            "asserting role ops adds the role's WRITE on cfg."),
                    () -> assertFalse(acl.isAllowed("ops", Set.of("ops"), "cfg.x", READ),
                            "role ops grants only WRITE on cfg.; READ there was granted by neither store"),
                    () -> assertTrue(acl.isAllowed("eve", Set.of("ops"), "cfg.x", WRITE), "eve gets the role grant"),
                    () -> assertFalse(acl.isAllowed("eve", Set.of("ops"), "log.x", READ),
                            "eve asserting role ops does NOT inherit principal ops's own READ on log."));
        }
    }

    // ROLE-ATTACK 9 - SINGLE-KEY FLOOR THROUGH ROLES (contract boundary, mirrors own-grants Attack 13).
    // isAllowed unions only a key's ANCESTOR grants - own AND role - so it can NEVER see a deny on a
    // DESCENDANT. A role that watches a subtree, checked once at the root, does NOT prove READ over the
    // whole subtree; the subscribe path must re-check per delivered key. Pins the boundary so the gap
    // stays visible now that role grants also flow through it.
    @Nested
    @DisplayName("Role-Attack 9: a role subtree-watch checked at the root misses a descendant deny (single-key floor)")
    class SingleKeyFloorThroughRoles {

        @Test
        @DisplayName("role grants READ+WATCH on a subtree but a descendant READ-deny (own) the watch would deliver is invisible at the root")
        void roleSubtreeWatchRootCheckMissesDescendantDeny() {
            acl.defineRole(role("subtreeWatcher", allowRule("a.", READ, WATCH)));
            acl.deny("a.secret.", "p", Set.of(READ));

            assertTrue(acl.isAllowed("p", Set.of("subtreeWatcher"), "a.", WATCH),
                    "isAllowed at the SUBTREE ROOT says WATCH — the a.secret. READ-deny is a DESCENDANT, "
                            + "invisible to the ancestor-only union; NOT a whole-subtree READ guarantee");
            assertAll(
                    () -> assertFalse(acl.isAllowed("p", Set.of("subtreeWatcher"), "a.secret.k", READ),
                            "a.secret.k is not readable — a role subtree watch authorized only at the root would over-expose it"),
                    () -> assertFalse(acl.isAllowed("p", Set.of("subtreeWatcher"), "a.secret.k", WATCH),
                            "the PER-DELIVERED-KEY floor correctly denies — O-5 must re-check per key, not once at the root"));
        }
    }
}

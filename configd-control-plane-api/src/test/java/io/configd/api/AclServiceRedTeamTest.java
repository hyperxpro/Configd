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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RED-TEAM suite for {@link AclService} union-of-ancestors + absolute deny-precedence + default-deny
 * (RFC §01 A5-4; {@code access-control.md} §4.1; commit a5a8ac2).
 * <p>
 * Each test <b>constructs an attack</b> against the authorization point and asserts the <b>correct,
 * secure decision</b>. If an assertion fails, the attack succeeded and the test names the defect. The
 * suite is deliberately disjoint from {@code AclServiceTest}: it targets the adversarial edges —
 * <b>poisoned-decoy walk evasion</b> (where {@code floorKey} lands on a non-ancestor that out-sorts the
 * real ancestors), deny order/specificity independence, exact {@code startsWith} prefix-boundary
 * scoping, revoke/overwrite residue, cross-principal leakage, and a concurrent grant-churn safety
 * invariant. Several tests are <b>regression-catchers</b>: they fail if a future "optimization"
 * reintroduces an early {@code break} in the ancestor walk (which would silently drop ancestors).
 */
@DisplayName("AclService — red-team / adversarial authorization")
class AclServiceRedTeamTest {

    private AclService acl;

    @BeforeEach
    void setUp() {
        acl = new AclService();
    }

    // =====================================================================================
    // ATTACK 4 — WALK-STOP EVASION (the subtle one).
    // The ancestor walk starts at floorKey(key) and walks back via lowerKey, filtering with
    // key.startsWith(candidate). A "decoy" non-ancestor that sorts BETWEEN real ancestors — or that
    // becomes floorKey(key) itself — must NOT (a) be applied to the key, nor (b) halt the walk before
    // a real, shorter ancestor. These are the strongest tests; a poisoned decoy carries a DENY that
    // would visibly corrupt the decision if the startsWith filter or the full-walk were broken.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 4: poisoned-decoy walk-stop evasion")
    class WalkStopEvasion {

        @Test
        @DisplayName("floorKey lands on a poisoned non-ancestor decoy; ALL real ancestors still contribute")
        void floorKeyLandsOnPoisonedDecoy_allRealAncestorsStillContribute() {
            // key's ancestors that grant caps:
            acl.grant("", "alice", Set.of(READ));               // global
            acl.grant("a.b.", "alice", Set.of(WRITE));          // mid ancestor
            acl.grant("a.b.c.d.", "alice", Set.of(ADMIN));      // deep ancestor

            // POISONED decoys: NON-ancestors of "a.b.c.d.e" that each DENY everything. They lexically
            // interleave between the real ancestors, and "a.b.c.d.a" is GREATER than every granting
            // ancestor and <= the key, so floorKey("a.b.c.d.e") == "a.b.c.d.a" (a decoy). If the walk
            // applied floorKey's entry without the startsWith filter, or halted at the decoy, the
            // poisoned DENY would strip caps / a real ancestor would be missed.
            for (String decoy : List.of("a.a", "a.b.a", "a.b.c.a", "a.b.c.d.a")) {
                acl.deny(decoy, "alice", Set.of(READ, WRITE, ADMIN));
            }

            String key = "a.b.c.d.e"; // none of the decoys is a startsWith-prefix of this key
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", key, READ),
                            "READ from global \"\" must survive the poisoned floorKey decoy + interleaved decoys"),
                    () -> assertTrue(acl.isAllowed("alice", key, WRITE),
                            "WRITE from \"a.b.\" must survive — walk must pass through the decoys to reach it"),
                    () -> assertTrue(acl.isAllowed("alice", key, ADMIN),
                            "ADMIN from \"a.b.c.d.\" must survive — the decoy that became floorKey must be filtered, not applied"));
        }

        @Test
        @DisplayName("global DENY at \"\" is reached through a deep decoy chain (walk does not stop early)")
        void globalDenyReachedThroughDeepDecoyChain() {
            acl.grant("a.b.c.d.", "alice", Set.of(READ, WRITE, ADMIN)); // deep allow of everything
            acl.deny("", "alice", Set.of(WRITE));                       // global carve-out of WRITE only

            // Decoys deny READ+ADMIN: if a decoy leaked, READ/ADMIN would wrongly vanish.
            for (String decoy : List.of("a.a", "a.b.a", "a.b.c.a", "a.b.c.d.a")) {
                acl.deny(decoy, "alice", Set.of(READ, ADMIN));
            }

            String key = "a.b.c.d.e";
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", key, READ),
                            "decoy DENY(READ) must NOT apply — it is not an ancestor"),
                    () -> assertTrue(acl.isAllowed("alice", key, ADMIN),
                            "decoy DENY(ADMIN) must NOT apply — it is not an ancestor"),
                    () -> assertFalse(acl.isAllowed("alice", key, WRITE),
                            "global DENY(WRITE) at \"\" MUST be reached at the bottom of the walk — "
                                    + "if an early break is reintroduced, WRITE would leak here"));
        }

        @Test
        @DisplayName("verbatim task scenario: key a.b.c, grants a. and a.b, decoy non-ancestor a.a")
        void verbatimDecoyBetweenLongerAndShorterAncestor() {
            acl.grant("a.", "alice", Set.of(READ));   // shorter ancestor
            acl.grant("a.b", "alice", Set.of(WRITE));  // longer ancestor (string-prefix of a.b.c)
            acl.deny("a.a", "alice", Set.of(READ, WRITE, ADMIN)); // decoy: sorts between a. and a.b, NOT an ancestor

            // floorKey("a.b.c") == "a.b"; lowerKey -> "a.a" (decoy, filtered) -> "a." (real). Both real
            // ancestors must contribute; the decoy must neither apply nor halt the walk.
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", "a.b.c", READ), "a. must contribute READ past the a.a decoy"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.c", WRITE), "a.b must contribute WRITE"),
                    () -> assertFalse(acl.isAllowed("alice", "a.b.c", ADMIN), "no ancestor grants ADMIN"));
        }

        @Test
        @DisplayName("minimal: floorKey is a non-ancestor sibling that sorts between key and the only grant")
        void floorKeyNonAncestorSibling_shortGrantStillApplies() {
            acl.grant("app.", "alice", Set.of(READ));
            acl.deny("app.secret.a", "alice", Set.of(READ)); // decoy: <= "app.secret.x", NOT its ancestor

            // floorKey("app.secret.x") == "app.secret.a" (decoy). Walk filters it, then reaches "app.".
            assertTrue(acl.isAllowed("alice", "app.secret.x", READ),
                    "READ from \"app.\" must survive even though floorKey landed on a non-ancestor decoy");
        }
    }

    // =====================================================================================
    // ATTACK 2 — DENY BYPASS BY INSERTION ORDER. The decision must be identical regardless of whether
    // deny() or grant() was called first, at the same prefix and at different prefixes.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 2: deny is order-independent")
    class DenyOrderIndependence {

        @Test
        @DisplayName("same prefix: grant-then-deny == deny-then-grant (deny wins both)")
        void samePrefixOrderIndependent() {
            AclService a = new AclService();
            a.grant("x.", "alice", Set.of(READ, WRITE));
            a.deny("x.", "alice", Set.of(WRITE));

            AclService b = new AclService();
            b.deny("x.", "alice", Set.of(WRITE));
            b.grant("x.", "alice", Set.of(READ, WRITE));

            assertAll(
                    () -> assertFalse(a.isAllowed("alice", "x.y", WRITE), "grant-then-deny: WRITE denied"),
                    () -> assertFalse(b.isAllowed("alice", "x.y", WRITE), "deny-then-grant: WRITE denied"),
                    () -> assertTrue(a.isAllowed("alice", "x.y", READ), "READ untouched (a)"),
                    () -> assertTrue(b.isAllowed("alice", "x.y", READ), "READ untouched (b)"));
        }

        @Test
        @DisplayName("different prefixes: deny@ancestor + grant@descendant, both orders -> deny wins")
        void crossPrefixOrderIndependent() {
            AclService a = new AclService();
            a.deny("a.", "alice", Set.of(WRITE));
            a.grant("a.b.", "alice", Set.of(WRITE));

            AclService b = new AclService();
            b.grant("a.b.", "alice", Set.of(WRITE));
            b.deny("a.", "alice", Set.of(WRITE));

            assertAll(
                    () -> assertFalse(a.isAllowed("alice", "a.b.x", WRITE), "deny-first: ancestor deny wins"),
                    () -> assertFalse(b.isAllowed("alice", "a.b.x", WRITE), "grant-first: ancestor deny still wins"));
        }

        @Test
        @DisplayName("re-grant after deny does not resurrect the denied capability")
        void regrantAfterDenyDoesNotResurrect() {
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.deny("a.", "alice", Set.of(WRITE));
            acl.grant("a.", "alice", Set.of(READ, WRITE)); // try to "re-grant" WRITE over the deny

            assertFalse(acl.isAllowed("alice", "a.x", WRITE),
                    "a standing deny must survive a later grant of the same capability");
        }
    }

    // =====================================================================================
    // ATTACK 3 — DENY OUT-ORDERED BY SPECIFICITY. Deny must win whether it sits at a less-specific
    // ancestor (allow more specific) or a more-specific descendant (allow less specific) — including
    // for ADMIN ("deny beats sudo" in both directions).
    // =====================================================================================
    @Nested
    @DisplayName("Attack 3: deny beats sudo regardless of specificity")
    class DenyBeatsSudoBothDirections {

        @Test
        @DisplayName("deny ADMIN at less-specific ancestor beats allow ADMIN at more-specific descendant")
        void denyAtAncestorBeatsAllowAtDescendant_admin() {
            acl.deny("a.", "alice", Set.of(ADMIN));
            acl.grant("a.b.c.", "alice", Set.of(ADMIN));
            assertFalse(acl.isAllowed("alice", "a.b.c.x", ADMIN), "ancestor DENY(ADMIN) beats descendant ALLOW(ADMIN)");
        }

        @Test
        @DisplayName("deny ADMIN at more-specific descendant beats allow ADMIN at less-specific ancestor")
        void denyAtDescendantBeatsAllowAtAncestor_admin() {
            acl.grant("a.", "alice", Set.of(ADMIN));
            acl.deny("a.b.c.", "alice", Set.of(ADMIN));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.b.c.x", ADMIN), "descendant DENY(ADMIN) beats ancestor ALLOW(ADMIN)"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.other", ADMIN),
                            "sibling outside the deny subtree keeps ADMIN (deny is exactly startsWith-scoped)"));
        }

        @Test
        @DisplayName("deny stacked at MULTIPLE ancestors still wins over a stacked allow union")
        void stackedDenyWinsOverStackedAllow() {
            acl.grant("", "alice", Set.of(READ, WRITE, ADMIN));
            acl.grant("a.", "alice", Set.of(READ, WRITE, ADMIN));
            acl.grant("a.b.", "alice", Set.of(READ, WRITE, ADMIN));
            acl.deny("a.b.c.", "alice", Set.of(ADMIN)); // one deny deep down
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.b.c.x", ADMIN), "a single deep DENY(ADMIN) beats THREE stacked ALLOW(ADMIN)"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.c.x", READ), "READ unaffected"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.c.x", WRITE), "WRITE unaffected"));
        }
    }

    // =====================================================================================
    // ATTACK 1 — UNINTENDED ALLOW VIA UNION. The union must never manufacture a capability that no
    // matching ancestor granted. In particular, stacking READ/WRITE grants must NEVER yield ADMIN.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 1: union never manufactures an ungranted capability")
    class UnionNeverManufacturesCapability {

        @Test
        @DisplayName("exhaustive: ADMIN never appears from any stack of READ/WRITE-only ancestor grants")
        void adminNeverAppearsFromReadWriteGrants() {
            // Enumerate every non-empty subset of {READ, WRITE} at three nested ancestors of "a.b.c.x".
            List<Set<AclService.Permission>> options = List.of(
                    Set.of(READ), Set.of(WRITE), Set.of(READ, WRITE));
            String[] prefixes = {"a.", "a.b.", "a.b.c."};
            int failures = 0;
            for (Set<AclService.Permission> p0 : options) {
                for (Set<AclService.Permission> p1 : options) {
                    for (Set<AclService.Permission> p2 : options) {
                        AclService a = new AclService();
                        a.grant(prefixes[0], "alice", p0);
                        a.grant(prefixes[1], "alice", p1);
                        a.grant(prefixes[2], "alice", p2);
                        if (a.isAllowed("alice", "a.b.c.x", ADMIN)) {
                            failures++; // would be a privilege-escalation defect
                        }
                    }
                }
            }
            assertTrue(failures == 0,
                    "union of READ/WRITE-only grants manufactured ADMIN in " + failures + " of 27 combinations");
        }

        @Test
        @DisplayName("union confers exactly the set-union of granted caps, nothing more")
        void unionIsExactlyTheGrantedSetUnion() {
            acl.grant("a.", "alice", Set.of(READ));
            acl.grant("a.b.", "alice", Set.of(WRITE));
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", "a.b.x", READ)),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.x", WRITE)),
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", ADMIN), "ADMIN was granted nowhere"));
        }
    }

    // =====================================================================================
    // ATTACK 5 — PREFIX-BOUNDARY CONFUSION. Matching is exactly key.startsWith(prefix). A dot-terminated
    // deny must apply inside its subtree and NOT bleed to a lexical sibling outside the subtree.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 5: deny scoping is exactly startsWith (no sibling bleed)")
    class PrefixBoundaryScoping {

        @Test
        @DisplayName("deny app.secret. applies to app.secret.key, not to sibling app.secretZ")
        void dotTerminatedDenyDoesNotBleedToLexicalSibling() {
            acl.grant("app.", "alice", Set.of(READ, WRITE));
            acl.deny("app.secret.", "alice", Set.of(WRITE));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "app.secret.key", WRITE),
                            "WRITE denied inside the app.secret. subtree"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secret.key", READ),
                            "READ still allowed inside the subtree (only WRITE denied)"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secretZ", WRITE),
                            "app.secretZ is NOT under app.secret. — the deny must not bleed; the app. grant authorizes it"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secretZ", READ),
                            "app.secretZ READ authorized by the app. grant"));
        }

        @Test
        @DisplayName("INFO: a non-separator-terminated grant matches lexical siblings (documented flat-prefix semantics)")
        void nonSeparatorTerminatedPrefixMatchesSiblings_documented() {
            // This pins the PRE-EXISTING flat-prefix contract (key.startsWith, unchanged by a5a8ac2):
            // a grant on "app.secret" (no trailing dot) also matches "app.secretZ" and "app.secrets".
            // It is a deployment footgun (callers MUST dot-terminate subtree prefixes), NOT a defect of
            // this change. Asserting it documents the sharp edge so a future regression is visible.
            acl.grant("app.secret", "alice", Set.of(READ)); // intentionally no trailing dot
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", "app.secret.key", READ), "matches the intended subtree"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secretZ", READ),
                            "ALSO matches the sibling — documented flat-prefix behavior (use a trailing separator to scope)"),
                    () -> assertFalse(acl.isAllowed("alice", "app.public", READ), "does not match unrelated keys"));
        }

        @Test
        @DisplayName("INFO: the startsWith matching caveats apply UNIFORMLY to all 5 capabilities (incl. LIST/WATCH)")
        void prefixMatchingIsCapabilityUniform_documented() {
            // DL-O3-02: O-3 adds LIST/WATCH to the VOCABULARY but does NOT change MATCHING. The literal
            // key.startsWith() caveats (DL-W2-03: fail-safe sibling over-reach; fail-OPEN subtree-root gap)
            // are properties of the rule PREFIX, evaluated identically for every capability — they apply
            // uniformly to LIST and WATCH exactly as to READ/WRITE/ADMIN. The segment-aware (A3.4 glob) fix
            // remains the deferred binary/driver surface. This pins the uniformity so a future per-cap
            // matching divergence is visible.
            acl.grant("app.", "alice", Set.of(READ, LIST, WRITE, WATCH, ADMIN));
            // Carve out enumeration + streaming of the secrets subtree (a dot-terminated, well-scoped deny).
            acl.deny("app.secret.", "alice", Set.of(LIST, WATCH));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "app.secret.k", LIST),
                            "LIST denied inside app.secret. (same startsWith boundary as READ/WRITE)"),
                    () -> assertFalse(acl.isAllowed("alice", "app.secret.k", WATCH),
                            "WATCH denied inside app.secret. (deny(WATCH) removes effective WATCH)"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secret.k", READ),
                            "READ still allowed inside the subtree — only LIST/WATCH were carved out"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secretZ", LIST),
                            "app.secretZ is NOT under app.secret. — the LIST deny does not bleed (uniform boundary)"),
                    () -> assertTrue(acl.isAllowed("alice", "app.secretZ", WATCH),
                            "WATCH (∧ READ) authorized on the sibling — the deny does not bleed; matching is capability-uniform"));
        }
    }

    // =====================================================================================
    // ATTACK 6 — GLOBAL DENY at the empty prefix "" overriding a root allow (also exercised in
    // WalkStopEvasion). "" is an ancestor of every key.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 6: empty-prefix global deny overrides everything")
    class GlobalEmptyPrefixDeny {

        @Test
        @DisplayName("deny ADMIN at \"\" overrides a root allow-all, for arbitrarily deep keys")
        void emptyPrefixDenyOverridesRootAllowAtDepth() {
            acl.grant("", "alice", Set.of(READ, WRITE, ADMIN));
            acl.deny("", "alice", Set.of(ADMIN));
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", "very/deep/nested/key", READ)),
                    () -> assertTrue(acl.isAllowed("alice", "very/deep/nested/key", WRITE)),
                    () -> assertFalse(acl.isAllowed("alice", "very/deep/nested/key", ADMIN),
                            "global DENY(ADMIN) at \"\" beats the root ALLOW-all"));
        }

        @Test
        @DisplayName("empty-prefix deny beats a MORE-specific descendant allow (deny precedence + global scope)")
        void emptyPrefixDenyBeatsDescendantAllow() {
            acl.deny("", "alice", Set.of(WRITE));
            acl.grant("a.b.", "alice", Set.of(WRITE)); // more specific, but deny is absolute
            assertFalse(acl.isAllowed("alice", "a.b.x", WRITE), "global \"\" deny beats descendant grant");
        }
    }

    // =====================================================================================
    // ATTACK 7 — REVOKE RESIDUE & OVERWRITE EDGES.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 7: revoke / overwrite leave no exploitable residue")
    class RevokeAndOverwrite {

        @Test
        @DisplayName("revoke removes BOTH allow and deny at the prefix (no residual allow)")
        void revokeRemovesAllowAndDeny() {
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.deny("a.", "alice", Set.of(WRITE));
            acl.revoke("a.", "alice");
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", READ), "no residual ALLOW after revoke"),
                    () -> assertFalse(acl.isAllowed("alice", "a.x", WRITE), "no residual after revoke"));
        }

        @Test
        @DisplayName("re-grant is REPLACE, not merge: narrowing a grant actually drops the removed caps")
        void regrantReplacesAndNarrows() {
            acl.grant("a.", "alice", Set.of(READ, WRITE, ADMIN));
            acl.grant("a.", "alice", Set.of(READ)); // operator narrows to READ-only
            assertAll(
                    () -> assertTrue(acl.isAllowed("alice", "a.x", READ), "READ retained"),
                    () -> assertFalse(acl.isAllowed("alice", "a.x", WRITE), "WRITE dropped by the narrowing re-grant"),
                    () -> assertFalse(acl.isAllowed("alice", "a.x", ADMIN), "ADMIN dropped by the narrowing re-grant"));
        }

        @Test
        @DisplayName("re-deny is REPLACE: relaxing a deny actually lifts the removed deny caps")
        void redenyReplaces() {
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.deny("a.", "alice", Set.of(READ, WRITE));
            acl.deny("a.", "alice", Set.of(READ)); // relax: now only READ denied
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", READ), "READ still denied"),
                    () -> assertTrue(acl.isAllowed("alice", "a.x", WRITE), "WRITE deny lifted by the narrower re-deny"));
        }

        @Test
        @DisplayName("revoke is scoped to (prefix, principal): does not touch other prefixes or principals")
        void revokeIsScoped() {
            acl.grant("a.", "alice", Set.of(READ));
            acl.grant("a.b.", "alice", Set.of(WRITE));
            acl.grant("a.", "bob", Set.of(READ));
            acl.revoke("a.", "alice");
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", READ), "alice's a. grant gone"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.x", WRITE), "alice's a.b. grant untouched"),
                    () -> assertTrue(acl.isAllowed("bob", "a.x", READ), "bob's a. grant untouched by alice's revoke"));
        }

        @Test
        @DisplayName("revoking a deny carve-out re-exposes the subtree cleanly (no half-state)")
        void revokingDenyCarveOutReExposes() {
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.deny("a.secret.", "alice", Set.of(WRITE));
            assertFalse(acl.isAllowed("alice", "a.secret.k", WRITE), "carve-out active");
            acl.revoke("a.secret.", "alice");
            assertTrue(acl.isAllowed("alice", "a.secret.k", WRITE), "carve-out fully removed; ancestor grant re-applies");
        }
    }

    // =====================================================================================
    // ATTACK 8 — CROSS-PRINCIPAL LEAKAGE. One principal's grants/denies must never affect another.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 8: no cross-principal leakage")
    class CrossPrincipalIsolation {

        @Test
        @DisplayName("alice's deny does not deny bob; bob's grant does not help alice")
        void grantsAndDeniesArePerPrincipal() {
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.grant("a.", "bob", Set.of(READ, WRITE));
            acl.deny("a.", "alice", Set.of(WRITE));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", WRITE), "alice WRITE denied"),
                    () -> assertTrue(acl.isAllowed("bob", "a.x", WRITE), "bob WRITE unaffected by alice's deny"));
        }

        @Test
        @DisplayName("a principal with no rules is default-denied even when others are granted globally")
        void unknownPrincipalDefaultDeniedAmidstGlobalGrant() {
            acl.grant("", "root", EnumSet.allOf(AclService.Permission.class)); // mirrors the deployed production grant (ConfigdServer:726 allOf)
            assertAll(
                    () -> assertFalse(acl.isAllowed("mallory", "anything", READ), "mallory has no rules -> default-deny"),
                    () -> assertTrue(acl.isAllowed("root", "anything", READ), "root's global grant intact"));
        }

        @Test
        @DisplayName("a deny on one principal at a shared prefix must not shadow another principal's allow")
        void denyDoesNotShadowAcrossPrincipalsAtSharedPrefix() {
            acl.grant("svc.", "bob", Set.of(READ, WRITE, ADMIN));
            acl.deny("svc.", "alice", Set.of(READ, WRITE, ADMIN)); // alice fully denied here
            assertTrue(acl.isAllowed("bob", "svc.config", ADMIN), "bob keeps ADMIN despite alice's blanket deny at svc.");
        }
    }

    // =====================================================================================
    // PRODUCTION BYTE-IDENTITY — the only deployed grant is grant("", "root", all). With a single rule
    // the union model must decide identically to the historical longest-match-only model.
    // =====================================================================================
    @Nested
    @DisplayName("Deployed config: single global root grant is byte-identical")
    class ProductionShape {

        @Test
        @DisplayName("grant(\"\",\"root\",all) authorizes every key/cap for root and nobody else")
        void singleRootGrantBehavesAsLongestMatch() {
            acl.grant("", "root", EnumSet.allOf(AclService.Permission.class));
            assertAll(
                    () -> assertTrue(acl.isAllowed("root", "a/b/c", READ)),
                    () -> assertTrue(acl.isAllowed("root", "a/b/c", WRITE)),
                    () -> assertTrue(acl.isAllowed("root", "a/b/c", ADMIN)),
                    () -> assertTrue(acl.isAllowed("root", "a/b/c", LIST), "root holds the new LIST cap too"),
                    () -> assertTrue(acl.isAllowed("root", "a/b/c", WATCH), "root holds effective WATCH (WATCH ∧ READ)"),
                    () -> assertTrue(acl.isAllowed("root", "", READ), "even the empty key"),
                    () -> assertFalse(acl.isAllowed("intruder", "a/b/c", READ), "non-root default-denied"),
                    () -> assertFalse(acl.isAllowed("intruder", "a/b/c", LIST), "non-root default-denied (new cap)"),
                    () -> assertFalse(acl.isAllowed("intruder", "a/b/c", WATCH), "non-root default-denied (new cap)"));
        }
    }

    // =====================================================================================
    // ATTACK 9 — CONCURRENCY. A standing, never-removed DENY must hold as a SAFETY invariant while a
    // concurrent writer churns grant/revoke of the same capability. The denied cap must NEVER leak,
    // and isAllowed must never throw on a torn read (GrantEntry is immutable + swapped wholesale).
    // =====================================================================================
    @Nested
    @DisplayName("Attack 9: concurrent grant-churn never leaks past a standing deny")
    class ConcurrencySafety {

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("standing DENY(WRITE) holds while a writer churns grant/revoke(WRITE) at a descendant")
        void standingDenyHoldsUnderGrantChurn() throws InterruptedException {
            acl.grant("", "alice", Set.of(READ, WRITE)); // base allow
            acl.deny("", "alice", Set.of(WRITE));        // PERMANENT deny of WRITE — never removed

            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<Throwable> writerError = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        acl.grant("a.", "alice", Set.of(WRITE));  // try to (re)grant WRITE on a subtree
                        acl.revoke("a.", "alice");
                    }
                } catch (Throwable t) {
                    writerError.set(t);
                }
            }, "acl-churn-writer");
            writer.start();

            int leaks = 0;
            for (int i = 0; i < 500_000; i++) {
                // The "" deny of WRITE is absolute and permanent; no concurrent grant on "a." can ever
                // make WRITE allowed on "a.x". A single true here is a torn-state / deny-bypass defect.
                if (acl.isAllowed("alice", "a.x", WRITE)) {
                    leaks++;
                }
            }
            stop.set(true);
            writer.join();

            int observedLeaks = leaks;
            assertAll(
                    () -> assertTrue(writerError.get() == null,
                            "writer threw: " + writerError.get()),
                    () -> assertTrue(observedLeaks == 0,
                            "WRITE leaked past the standing absolute deny in " + observedLeaks + " reads (deny-bypass / torn read)"),
                    () -> assertTrue(acl.isAllowed("alice", "a.x", READ), "READ stays allowed throughout"));
        }

        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("isAllowed never throws while an entry is swapped grant<->deny on the same prefix")
        void readerNeverThrowsDuringEntrySwap() throws InterruptedException {
            AtomicBoolean stop = new AtomicBoolean(false);
            List<Throwable> errors = new ArrayList<>();
            Thread writer = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        acl.grant("k.", "alice", Set.of(READ, WRITE, ADMIN));
                        acl.deny("k.", "alice", Set.of(READ, WRITE, ADMIN));
                        acl.revoke("k.", "alice");
                    }
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(t); }
                }
            }, "acl-swap-writer");
            writer.start();

            assertDoesNotThrow(() -> {
                for (int i = 0; i < 500_000; i++) {
                    acl.isAllowed("alice", "k.v", READ);
                    acl.isAllowed("alice", "k.v", ADMIN);
                }
            }, "isAllowed must observe a consistent (allow,deny) pair and never throw on a torn read");

            stop.set(true);
            writer.join();
            synchronized (errors) {
                assertTrue(errors.isEmpty(), "writer threw: " + errors);
            }
        }
    }

    // =====================================================================================
    // ATTACK 10 — WATCH CAN NEVER OUT-READ A READ (INV-WATCH-READ / R-CAP-2). The effective-WATCH floor
    // (WATCH ∧ READ) must hold under every adversarial shape: WATCH held while READ is absent or denied,
    // deny ordering, deep decoy walks, global READ deny. A single authorized WATCH without effective READ
    // is a watch-bypass — exactly the class of defect §6/INV-WATCH-READ exists to prevent.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 10: WATCH is never authorized without effective READ")
    class WatchNeverOutreadsRead {

        @Test
        @DisplayName("WATCH+LIST+WRITE+ADMIN but NO READ -> no effective WATCH")
        void everyCapButReadDoesNotYieldWatch() {
            acl.grant("a.", "alice", Set.of(WATCH, LIST, WRITE, ADMIN)); // everything EXCEPT READ
            assertFalse(acl.isAllowed("alice", "a.x", WATCH),
                    "WATCH must be ineffective without READ even when every other capability is held");
        }

        @Test
        @DisplayName("a global READ deny at \"\" kills effective WATCH for an otherwise-watchable descendant")
        void globalReadDenyKillsDescendantWatch() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));
            acl.deny("", "alice", Set.of(READ)); // global READ carve-out (deny precedence + global scope)
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", WATCH),
                            "global DENY(READ) at \"\" floors away effective WATCH everywhere"),
                    () -> assertFalse(acl.isAllowed("alice", "a.x", READ), "READ itself is denied"));
        }

        @Test
        @DisplayName("the READ-floor is insertion-order independent (grant/deny in either order -> no watch)")
        void watchFloorIsOrderIndependent() {
            AclService a = new AclService();
            a.grant("a.", "alice", Set.of(READ, WATCH));
            a.deny("a.", "alice", Set.of(READ));

            AclService b = new AclService();
            b.deny("a.", "alice", Set.of(READ));
            b.grant("a.", "alice", Set.of(READ, WATCH));

            assertAll(
                    () -> assertFalse(a.isAllowed("alice", "a.x", WATCH), "grant-then-deny(READ): no watch"),
                    () -> assertFalse(b.isAllowed("alice", "a.x", WATCH), "deny(READ)-then-grant: no watch"));
        }

        @Test
        @DisplayName("effective WATCH survives a poisoned-decoy walk (decoy READ/WATCH denies are non-ancestors)")
        void watchFloorSurvivesPoisonedDecoyWalk() {
            // READ + WATCH come from a deep REAL ancestor; the decoys are NON-ancestors that deny READ+WATCH.
            // If a decoy leaked (startsWith broken) or the walk halted early, effective WATCH would vanish.
            acl.grant("a.b.c.d.", "alice", Set.of(READ, WATCH));
            for (String decoy : List.of("a.a", "a.b.a", "a.b.c.a", "a.b.c.d.a")) {
                acl.deny(decoy, "alice", Set.of(READ, WATCH));
            }
            assertTrue(acl.isAllowed("alice", "a.b.c.d.e", WATCH),
                    "effective WATCH must survive — the decoy READ/WATCH denies are non-ancestors, filtered by startsWith");
        }

        @Test
        @DisplayName("WATCH granted at a descendant but READ only at an ancestor still composes to effective WATCH")
        void watchAtDescendantComposesWithAncestorRead() {
            acl.grant("a.", "alice", Set.of(READ));        // READ from the ancestor
            acl.grant("a.b.", "alice", Set.of(WATCH));     // WATCH from the descendant (no READ here)
            assertTrue(acl.isAllowed("alice", "a.b.x", WATCH),
                    "union: READ(ancestor) ∧ WATCH(descendant) -> effective WATCH (the floor is over the UNION)");
            assertFalse(acl.isAllowed("alice", "a.c.x", WATCH),
                    "a.c.x has READ but no WATCH in its ancestor chain -> not watchable");
        }
    }

    // =====================================================================================
    // ATTACK 11 — LIST AND READ NEVER CROSS (R-CAP-1). Neither implies the other under any union/deny
    // shape; a stack of one must never manufacture the other.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 11: LIST and READ are non-crossing under union")
    class ListReadNonCrossing {

        @Test
        @DisplayName("no stack of non-LIST grants ever manufactures LIST")
        void noStackOfReadGrantsConfersList() {
            acl.grant("", "alice", Set.of(READ));
            acl.grant("a.", "alice", Set.of(READ, WRITE));
            acl.grant("a.b.", "alice", Set.of(READ, WRITE, WATCH, ADMIN)); // everything but LIST
            assertFalse(acl.isAllowed("alice", "a.b.x", LIST),
                    "a union of non-LIST grants must never manufacture LIST");
        }

        @Test
        @DisplayName("no stack of non-READ grants ever manufactures READ (or effective WATCH)")
        void noStackOfListGrantsConfersReadOrWatch() {
            acl.grant("", "alice", Set.of(LIST));
            acl.grant("a.", "alice", Set.of(LIST, WATCH));
            acl.grant("a.b.", "alice", Set.of(LIST, WRITE, WATCH, ADMIN)); // everything but READ
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", READ),
                            "a union of non-READ grants must never manufacture READ"),
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", WATCH),
                            "no READ in the union -> no effective WATCH despite WATCH being granted"));
        }
    }

    // =====================================================================================
    // ATTACK 12 — PER-CAPABILITY DENY OF THE NEW CAPS IS NOT EVADABLE. A deny of LIST or WATCH cannot be
    // out-ordered, out-specified, or unioned around; deny(READ) is the second, equivalent way to kill
    // effective WATCH. Mirrors the proven DenyBeatsSudo / DenyOrderIndependence properties for LIST/WATCH.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 12: per-capability DENY of LIST/WATCH is absolute and inevadable")
    class NewCapabilityDenyNotEvadable {

        @Test
        @DisplayName("one deep DENY(LIST) beats three stacked ALLOW(LIST)")
        void denyListBeatsStackedListAllows() {
            acl.grant("", "alice", Set.of(LIST));
            acl.grant("a.", "alice", Set.of(LIST));
            acl.grant("a.b.", "alice", Set.of(LIST));
            acl.deny("a.b.c.", "alice", Set.of(LIST));
            assertFalse(acl.isAllowed("alice", "a.b.c.x", LIST),
                    "a single deep DENY(LIST) beats three stacked ALLOW(LIST)");
        }

        @Test
        @DisplayName("DENY(WATCH) at an ancestor beats ALLOW(READ+WATCH) at a descendant")
        void denyWatchAtAncestorBeatsDescendantAllow() {
            acl.deny("a.", "alice", Set.of(WATCH));
            acl.grant("a.b.", "alice", Set.of(READ, WATCH));
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", WATCH),
                            "ancestor DENY(WATCH) removes effective WATCH at the descendant"),
                    () -> assertTrue(acl.isAllowed("alice", "a.b.x", READ),
                            "READ is unaffected by the WATCH deny"));
        }

        @Test
        @DisplayName("deny(READ) is the second, equivalent way to revoke effective WATCH")
        void denyReadAlsoRevokesEffectiveWatch() {
            acl.grant("", "alice", Set.of(READ, WATCH));
            acl.deny("a.b.", "alice", Set.of(READ)); // deny READ, NOT watch
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", WATCH),
                            "deny(READ) kills effective WATCH (the READ floor) even though WATCH is not denied"),
                    () -> assertFalse(acl.isAllowed("alice", "a.b.x", READ), "READ denied"),
                    () -> assertTrue(acl.isAllowed("alice", "a.other", WATCH),
                            "outside the READ carve-out the watch is intact"));
        }

        @Test
        @DisplayName("re-grant after deny(LIST/WATCH) does not resurrect the denied capability")
        void regrantDoesNotResurrectDeniedNewCaps() {
            acl.grant("a.", "alice", Set.of(READ, LIST, WATCH));
            acl.deny("a.", "alice", Set.of(LIST, WATCH));
            acl.grant("a.", "alice", Set.of(READ, LIST, WATCH)); // try to re-grant over the standing deny
            assertAll(
                    () -> assertFalse(acl.isAllowed("alice", "a.x", LIST), "standing DENY(LIST) survives the re-grant"),
                    () -> assertFalse(acl.isAllowed("alice", "a.x", WATCH), "standing DENY(WATCH) survives the re-grant"),
                    () -> assertTrue(acl.isAllowed("alice", "a.x", READ), "READ (never denied) remains"));
        }
    }

    // =====================================================================================
    // ATTACK 13 — isAllowed IS SINGLE-KEY, NOT WHOLE-TARGET. The O-3 floor (WATCH ∧ READ) is correct for a
    // single KEY, but `isAllowed(p, prefix, …)` only unions a key's ANCESTOR grants (floorKey → lowerKey);
    // it can NEVER see a deny on a DESCENDANT of `prefix`. So a single isAllowed-at-the-subtree-root does
    // NOT prove READ over the whole subtree. These tests pin that contract boundary so that the future
    // O-5 (watch) / O-2 (list) subscribe path enforces the floor PER DELIVERED KEY (or with a whole-target
    // cover-check, as docs/design/.../WatchAuthz.authorizeWatch does via coversTarget) — NOT with one
    // isAllowed call at the subtree root, which would over-expose. See finding RC-O3-1.
    // =====================================================================================
    @Nested
    @DisplayName("Attack 13: isAllowed is a single-key floor — a subtree watch/list must re-check per delivered key")
    class SingleKeyFloorIsNotWholeTargetCover {

        @Test
        @DisplayName("subtree WATCH authorized at the root despite a descendant READ-deny the watch would deliver")
        void subtreeWatchRootCheckMissesDescendantReadDeny() {
            acl.grant("a.", "alice", Set.of(READ, WATCH));    // read+watch the a. subtree
            acl.deny("a.secret.", "alice", Set.of(READ));      // ...but NOT read a.secret.*

            // What a "single isAllowed at the subtree root" (the comment's shortcut) would decide:
            assertTrue(acl.isAllowed("alice", "a.", WATCH),
                    "isAllowed at the SUBTREE ROOT says WATCH — the a.secret. READ-deny is a DESCENDANT, "
                            + "invisible to the ancestor-only walk; this is NOT a whole-subtree READ guarantee");
            // ...yet a key that very watch would stream is NOT readable, and the PER-KEY floor correctly denies:
            assertFalse(acl.isAllowed("alice", "a.secret.k", READ),
                    "a.secret.k is not readable — a subtree watch authorized only at the root would over-expose it");
            assertFalse(acl.isAllowed("alice", "a.secret.k", WATCH),
                    "the PER-DELIVERED-KEY floor is the correct enforcement and denies — O-5 must check per key, "
                            + "not once at the subtree root (INV-WATCH-READ)");
        }

        @Test
        @DisplayName("subtree LIST authorized at the root despite a descendant LIST-deny it would enumerate")
        void subtreeListRootCheckMissesDescendantListDeny() {
            acl.grant("a.", "alice", Set.of(READ, LIST));      // list the a. subtree
            acl.deny("a.secret.", "alice", Set.of(LIST));       // ...but NOT enumerate a.secret.*

            assertTrue(acl.isAllowed("alice", "a.", LIST),
                    "isAllowed at the SUBTREE ROOT says LIST — the descendant LIST-deny is invisible to the "
                            + "ancestor-only walk (same structural gap as WATCH; the O-2 list endpoint must not "
                            + "authorize a subtree enumeration with one isAllowed at the prefix)");
            assertFalse(acl.isAllowed("alice", "a.secret.k", LIST),
                    "a.secret.* is not enumerable — the per-key floor denies; a root-only LIST check would leak it");
        }
    }
}

package io.configd.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.configd.api.AclService.Permission.ADMIN;
import static io.configd.api.AclService.Permission.LIST;
import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static io.configd.api.AclService.Permission.WRITE;
import static io.configd.api.AclService.authorizesWatch;
import static io.configd.api.AclService.coversTarget;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the dormant whole-target authorization predicate {@link AclService#coversTarget} and its
 * watch floor {@link AclService#authorizesWatch} (RFC §02 W7-1/W7-2/W7-2a; §01 §6 A6-1..A6-4).
 * <p>
 * These two methods close the hole {@link AclService#isAllowed} documents at its INV-WATCH-READ floor: a
 * single-key check unions only a key's <b>ancestor</b> grants, so it cannot see a {@code READ}/{@code WATCH}
 * deny on a <b>descendant</b> of the key. {@code coversTarget} adds the missing <b>interior-DENY term</b>
 * ({@code D.prefix.startsWith(target)}) so a subtree/FULL watch can be authorized once at subscription with
 * whole-target coverage rather than per delivered key. Matching is the same literal {@code startsWith} the
 * rest of {@link AclService} uses (segment-awareness is the DL-O3-02 debt, pinned by {@link SegmentBoundary}).
 * <p>
 * The rule collections here model the principal's <b>unioned</b> rule set (own ∪ role ∪ config) that the
 * later O-5 subscribe gate will assemble from the same sources {@code isAllowed} accumulates; the predicate
 * is source-agnostic, so the tests simply build flat {@code List<PolicyRule>}s.
 */
@DisplayName("AclService.coversTarget / authorizesWatch — whole-target watch authorization (dormant)")
class AclServiceCoversTargetTest {

    // --- helpers: a one-liner ALLOW/DENY rule at a literal prefix --------------------------------------

    private static PolicyRule allow(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(caps), Set.of());
    }

    private static PolicyRule deny(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(), Set.of(caps));
    }

    // -----------------------------------------------------------------------
    // (1) Floor — a watch needs BOTH READ and WATCH over the whole target
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("WATCH floor — authorizesWatch requires both READ and WATCH coverage")
    class Floor {

        /** WATCH covers the subtree but READ does not ⇒ no watch (a watch can never out-read a read). */
        @Test
        void watchWithoutReadDoesNotAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", WATCH)); // WATCH only, no READ

            assertTrue(coversTarget(rules, "a.", WATCH), "WATCH covers the subtree");
            assertFalse(coversTarget(rules, "a.", READ), "READ does NOT cover the subtree");
            assertFalse(authorizesWatch(rules, "a."),
                    "WATCH without READ over the target yields no watch authz (INV-WATCH-READ)");
        }

        /** The converse: READ covers the subtree but WATCH does not ⇒ no watch. */
        @Test
        void readWithoutWatchDoesNotAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", READ)); // READ only, no WATCH

            assertTrue(coversTarget(rules, "a.", READ), "READ covers the subtree");
            assertFalse(coversTarget(rules, "a.", WATCH), "WATCH does NOT cover the subtree");
            assertFalse(authorizesWatch(rules, "a."),
                    "READ alone does not confer WATCH (WATCH is separately grantable)");
        }

        /** Both capabilities cover the subtree ⇒ authorized. */
        @Test
        void readAndWatchTogetherAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(authorizesWatch(rules, "a."),
                    "READ ∧ WATCH over the whole target → authorized as a streaming read");
        }
    }

    // -----------------------------------------------------------------------
    // (2) Interior DENY — THE CRUX: a deny strictly BELOW the target defeats whole-target coverage
    //     (the exact case a single-key ancestor-only check cannot see)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Interior DENY — the case a single-key ancestor-only check cannot catch")
    class InteriorDeny {

        /**
         * ALLOW {@code "a."} {READ,WATCH}, DENY {@code "a.secret."} {READ} (strictly below {@code "a."}),
         * target {@code "a."}. The interior READ deny carves a hole, so whole-target READ is NOT covered and
         * the watch is rejected — even though an ancestor-only single-key check at the root would WRONGLY
         * pass (it never sees a deny below the key).
         */
        @Test
        void interiorReadDenyDefeatsWholeTargetReadAndWatch() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH), deny("a.secret.", READ));

            // The interior deny "a.secret." is strictly BELOW the target "a." — NOT an ancestor of it,
            // so an ancestor-only single-key walk (what isAllowed does) never sees it:
            assertFalse("a.".startsWith("a.secret."),
                    "the deny is strictly below the target → invisible to an ancestor-only walk");
            boolean ancestorOnlyWouldGrantRead =
                    rules.stream().anyMatch(r -> r.allow().contains(READ) && "a.".startsWith(r.prefix()))
                    && rules.stream().noneMatch(r -> r.deny().contains(READ) && "a.".startsWith(r.prefix()));
            assertTrue(ancestorOnlyWouldGrantRead,
                    "an ancestor-only single-key READ check at the target root would WRONGLY pass");

            // coversTarget's INTERIOR-DENY term (D.prefix.startsWith(target)) is what rejects it:
            assertFalse(coversTarget(rules, "a.", READ),
                    "the interior READ deny carves a hole in the subtree → whole-target READ NOT covered");
            assertFalse(authorizesWatch(rules, "a."),
                    "a watch over the subtree must be rejected, not silently filtered (W7-2/W7-2a)");
            // WATCH itself is uncarved here — proving it is specifically the READ floor that rejects.
            assertTrue(coversTarget(rules, "a.", WATCH),
                    "only READ was carved; WATCH still covers the whole subtree");
        }

        /** Symmetric: an interior WATCH deny defeats the watch while READ coverage remains. */
        @Test
        void interiorWatchDenyDefeatsWholeTargetWatch() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH), deny("a.secret.", WATCH));

            assertTrue(coversTarget(rules, "a.", READ), "READ is uncarved");
            assertFalse(coversTarget(rules, "a.", WATCH),
                    "the interior WATCH deny carves a hole → whole-target WATCH NOT covered");
            assertFalse(authorizesWatch(rules, "a."));
        }
    }

    // -----------------------------------------------------------------------
    // (3) Ancestor DENY — a deny at/above the target defeats coverage (today's check also catches this)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Ancestor DENY — deny at/above the target (the regression guard)")
    class AncestorDeny {

        /**
         * Root ALLOW READ would otherwise cover {@code "a.b."}, but a DENY at the ancestor {@code "a."}
         * removes it. This is the disjunct a single-key {@code isAllowed} <i>also</i> catches (an ancestor
         * deny) — the regression guard, contrasted with the interior case above which it cannot.
         */
        @Test
        void ancestorDenyDefeatsAnAncestorAllow() {
            List<PolicyRule> rules = List.of(allow("", READ), deny("a.", READ));

            assertTrue("a.b.".startsWith("a."), "the deny IS an ancestor of the target (catchable)");
            assertFalse(coversTarget(rules, "a.b.", READ),
                    "an ancestor DENY (target.startsWith(D.prefix)) defeats whole-target coverage");
        }
    }

    // -----------------------------------------------------------------------
    // (4) Sub-target ALLOWs only — a union of grants strictly below the target cannot cover it
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Sub-target ALLOWs only — cannot cover an unbounded subtree")
    class SubTargetAllowsOnly {

        /**
         * ALLOW {@code "a.b."} and ALLOW {@code "a.c."} (both strictly below {@code "a."}); no
         * ancestor-or-equal ALLOW of {@code "a."}. A union of sub-grants cannot cover the unbounded subtree
         * rooted at {@code "a."} (e.g. {@code "a.d.x"} is reached by neither).
         */
        @Test
        void unionOfStrictlySubTargetAllowsDoesNotCover() {
            List<PolicyRule> rules = List.of(allow("a.b.", READ, WATCH), allow("a.c.", READ, WATCH));

            assertFalse("a.".startsWith("a.b."), "neither ALLOW is an ancestor-or-equal of the target");
            assertFalse("a.".startsWith("a.c."));
            assertFalse(coversTarget(rules, "a.", READ),
                    "no ancestor-or-equal ALLOW carries READ over the whole subtree");
            assertFalse(authorizesWatch(rules, "a."));
        }
    }

    // -----------------------------------------------------------------------
    // (5) Positive — an ancestor-or-equal ALLOW with no intersecting DENY covers the subtree (and deep keys)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Positive — ancestor-or-equal ALLOW, no intersecting DENY")
    class Positive {

        @Test
        void ancestorAllowCoversSubtreeRootAndDeepKeys() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(coversTarget(rules, "a.", READ));
            assertTrue(coversTarget(rules, "a.", WATCH));
            assertTrue(authorizesWatch(rules, "a."), "subtree root is covered");

            // a concrete deeper key under the ALLOW is covered too (KEY target).
            assertTrue(coversTarget(rules, "a.b.x", READ));
            assertTrue(coversTarget(rules, "a.b.x", WATCH));
            assertTrue(authorizesWatch(rules, "a.b.x"), "a deep key under the ALLOW is covered");
        }
    }

    // -----------------------------------------------------------------------
    // (6) FULL (target == "") — only the root principal covers it; any interior deny closes the bypass
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FULL target (\"\") — root grant ∧ no cap-DENY anywhere")
    class FullTarget {

        /** A subtree-only principal (ALLOW {@code "a."}) does NOT cover FULL — no root-prefix ALLOW. */
        @Test
        void subtreeOnlyPrincipalDoesNotCoverFull() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertFalse(coversTarget(rules, "", READ),
                    "\"\".startsWith(\"a.\") is false → no root-prefix ALLOW carries READ for FULL");
            assertFalse(authorizesWatch(rules, ""), "a subtree grant cannot authorize a FULL watch");
        }

        /** The root principal (ALLOW {@code ""}) covers FULL. */
        @Test
        void rootPrincipalCoversFull() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH));

            assertTrue(coversTarget(rules, "", READ));
            assertTrue(coversTarget(rules, "", WATCH));
            assertTrue(authorizesWatch(rules, ""), "root grant covers the whole store (FULL)");
        }

        /**
         * Root principal + ANY interior cap-DENY ⇒ FULL not covered for that cap. At {@code target == ""}
         * the interior disjunct {@code D.prefix.startsWith("")} is true for EVERY deny, so a single
         * {@code DENY "a.secret." {READ}} anywhere blocks FULL READ — closing the full-store bypass.
         */
        @Test
        void rootPrincipalWithInteriorDenyDoesNotCoverFull() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH), deny("a.secret.", READ));

            assertTrue("a.secret.".startsWith(""), "every prefix startsWith(\"\") → every deny intersects FULL");
            assertFalse(coversTarget(rules, "", READ),
                    "any interior READ deny blocks FULL READ coverage (interior disjunct fires at root)");
            assertFalse(authorizesWatch(rules, ""), "the FULL-store bypass is closed by the interior term");
            // the positive control: WATCH (not denied anywhere) still covers FULL.
            assertTrue(coversTarget(rules, "", WATCH), "WATCH, denied nowhere, still covers FULL");
        }
    }

    // -----------------------------------------------------------------------
    // (7) Segment boundary — DL-O3-02 pin: literal startsWith over-grants ALLOW, over-denies DENY
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Segment boundary — DL-O3-02 literal-startsWith behavior (pinned, not fixed)")
    class SegmentBoundary {

        /**
         * ALLOW {@code "team"} {READ}, target {@code "teamX"}. Raw {@code startsWith} treats {@code "team"}
         * as an ancestor of {@code "teamX"}, so coverage is granted across the non-segment boundary. This
         * LOCKS the known DL-O3-02 over-grant for V1 — it is deliberately NOT "fixed" here (segment-aware
         * matching is the deferred binary/driver surface).
         */
        @Test
        void allowOverGrantsAcrossSegmentBoundary() {
            List<PolicyRule> rules = List.of(allow("team", READ));

            assertTrue(coversTarget(rules, "teamX", READ),
                    "literal startsWith over-grants: ALLOW \"team\" covers \"teamX\" (DL-O3-02, pinned)");
        }

        /**
         * The fail-closed counterpart: DENY {@code "team"} {READ} also intersects {@code "teamX"} via the
         * same literal {@code startsWith}, so it over-DENIES — coverage errs toward rejecting, never exposing.
         */
        @Test
        void denyOverDeniesAcrossSegmentBoundary() {
            List<PolicyRule> rules = List.of(allow("", READ), deny("team", READ));

            assertFalse(coversTarget(rules, "teamX", READ),
                    "literal startsWith over-denies: DENY \"team\" intersects \"teamX\" → fail-closed (DL-O3-02)");
        }
    }

    // -----------------------------------------------------------------------
    // (8) Equal-prefix — the boundary of "ancestor-OR-equal"
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Equal-prefix — ancestor-OR-EQUAL boundary")
    class EqualPrefix {

        @Test
        void allowAtExactlyTheTargetCovers() {
            List<PolicyRule> rules = List.of(allow("a.b.", READ, WATCH));

            assertTrue("a.b.".startsWith("a.b."), "equal prefix is the boundary of ancestor-or-equal");
            assertTrue(coversTarget(rules, "a.b.", READ));
            assertTrue(authorizesWatch(rules, "a.b."), "an ALLOW at exactly the target covers it");
        }
    }

    // -----------------------------------------------------------------------
    // (9) Multi-source union — own ∪ role ∪ config folded into one flat rule list (what the gate produces)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multi-source union — own ∪ role ∪ config (the gate's assembled rule set)")
    class MultiSourceUnion {

        /**
         * Models the three sources {@code isAllowed} accumulates, folded into the one flat list PR-2's gate
         * will assemble: own ALLOW {@code "a."} READ + role ALLOW {@code "a."} WATCH cover the subtree with
         * both caps ⇒ watch authorized. Adding a config interior DENY {@code "a.secret."} READ then defeats
         * it ⇒ watch rejected — proving deny-precedence composes across sources exactly as the union does.
         */
        @Test
        void unionedOwnAndRoleCover_thenConfigInteriorDenyDefeats() {
            PolicyRule ownReadGrant = allow("a.", READ);   // "own" source
            PolicyRule roleWatchGrant = allow("a.", WATCH); // "role" source
            PolicyRule configInteriorDeny = deny("a.secret.", READ); // "config" source

            List<PolicyRule> ownPlusRole = List.of(ownReadGrant, roleWatchGrant);
            assertTrue(authorizesWatch(ownPlusRole, "a."),
                    "own READ ∪ role WATCH cover the subtree → watch authorized");

            List<PolicyRule> ownPlusRolePlusConfig = List.of(ownReadGrant, roleWatchGrant, configInteriorDeny);
            assertFalse(authorizesWatch(ownPlusRolePlusConfig, "a."),
                    "a config interior READ deny composes with absolute precedence → watch rejected");
        }
    }

    // -----------------------------------------------------------------------
    // (10) Default-deny — no ALLOW means no coverage
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Default-deny — no matching ALLOW ⇒ not covered")
    class DefaultDeny {

        @Test
        void emptyRuleSetCoversNothing() {
            List<PolicyRule> rules = List.of();

            assertFalse(coversTarget(rules, "a.", READ));
            assertFalse(coversTarget(rules, "", READ), "not even FULL");
            assertFalse(authorizesWatch(rules, "a."));
        }

        @Test
        void noAncestorOrEqualAllowCoversNothing() {
            List<PolicyRule> rules = List.of(allow("b.", READ, WATCH)); // unrelated subtree

            assertFalse(coversTarget(rules, "a.", READ),
                    "an ALLOW on an unrelated/sibling subtree does not cover the target");
            assertFalse(authorizesWatch(rules, "a."));
        }
    }

    // -----------------------------------------------------------------------
    // Null-arg guards — coversTarget validates all three args; authorizesWatch delegates to it
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Null-arg guards")
    class NullChecks {

        @Test
        void coversTargetNullRulesThrows() {
            assertThrows(NullPointerException.class, () -> coversTarget(null, "a.", READ));
        }

        @Test
        void coversTargetNullTargetThrows() {
            assertThrows(NullPointerException.class, () -> coversTarget(List.of(), null, READ));
        }

        @Test
        void coversTargetNullCapThrows() {
            assertThrows(NullPointerException.class, () -> coversTarget(List.of(), "a.", null));
        }

        @Test
        void authorizesWatchNullRulesThrows() {
            assertThrows(NullPointerException.class, () -> authorizesWatch(null, "a."));
        }

        @Test
        void authorizesWatchNullTargetThrows() {
            assertThrows(NullPointerException.class, () -> authorizesWatch(List.of(), null));
        }
    }

    // -----------------------------------------------------------------------
    // Cross-capability hygiene — coverage of one cap does not manufacture another
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-capability hygiene — per-capability, no cross-conferral")
    class CrossCapabilityHygiene {

        /** Covering READ ∧ WATCH must not manufacture coverage of WRITE/LIST/ADMIN over the subtree. */
        @Test
        void watchCoverageDoesNotConferOtherCaps() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(authorizesWatch(rules, "a."));
            assertFalse(coversTarget(rules, "a.", WRITE), "READ ∧ WATCH coverage must not confer WRITE");
            assertFalse(coversTarget(rules, "a.", LIST), "READ ∧ WATCH coverage must not confer LIST");
            assertFalse(coversTarget(rules, "a.", ADMIN), "READ ∧ WATCH coverage must not confer ADMIN");
        }
    }

    // -----------------------------------------------------------------------
    // (RT-1) RED-TEAM — differential fuzz against an INDEPENDENT per-key witness oracle.
    //        This mechanizes the soundness proof: whole-target coverage is the universal lift of the
    //        per-key (ancestor-only) isAllowed decision over EVERY key in the subtree. The oracle is
    //        computed WITHOUT coversTarget's two-flag interior-term trick, so agreement across 200k random
    //        rule sets is overwhelming evidence the predicate neither over-exposes (false TRUE = a denied
    //        descendant slips through) nor over-rejects (false FALSE).
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Red-team — coversTarget ≡ ∀K∈subtree: per-key isAllowed (differential fuzz)")
    class RedTeamDifferential {

        // Alphabet chosen to maximise prefix-comparability collisions and the nasty boundaries:
        //   interior chain  a. ⊃ a.b. ⊃ a.b.c            (interior-deny carve)
        //   secret carve    a.secret.
        //   sibling trap    a.b  vs  a.bc                 (a.b IS a literal prefix of a.bc!)
        //   segment pair    team / team. / teamX          (DL-O3-02 over-grant)
        //   FULL/root  ""   and a disjoint subtree  x.
        private static final String[] PREFIXES = {
                "", "a", "a.", "a.b", "a.b.", "a.b.c", "a.bc",
                "a.secret", "a.secret.", "b.", "team", "team.", "teamX", "x."
        };
        private static final AclService.Permission[] CAPS = AclService.Permission.values();

        /** A random rule: a random prefix, with each cap independently in ALLOW and/or DENY. */
        private static PolicyRule randomRule(Random r) {
            EnumSet<AclService.Permission> a = EnumSet.noneOf(AclService.Permission.class);
            EnumSet<AclService.Permission> d = EnumSet.noneOf(AclService.Permission.class);
            for (AclService.Permission p : CAPS) {
                if (r.nextInt(100) < 40) a.add(p);   // ~40% ALLOW
                if (r.nextInt(100) < 30) d.add(p);   // ~30% DENY — so allow∩deny on the SAME rule is common
            }
            return new PolicyRule(PREFIXES[r.nextInt(PREFIXES.length)], a, d);
        }

        /**
         * The SPEC, computed independently of {@code coversTarget}: whole-target coverage is the universal
         * lift of the per-key (ancestor-only) decision —
         *   ∀ K ∈ subtree(target): (∃ ancestor-or-equal ALLOW carrying cap) ∧ (∄ ancestor-or-equal DENY carrying cap).
         * The subtree is infinite, but the per-key decision only changes at rule-prefix boundaries, so a
         * FINITE witness set is provably sufficient: {target} ∪ {every rule prefix that is a
         * descendant-or-equal of target}. (A false-TRUE would require a denied descendant K; that K forces
         * target and the deny prefix to be prefix-comparable, and the deny prefix is itself such a witness.)
         */
        private static boolean witnessOracle(List<PolicyRule> rules, String target, AclService.Permission cap) {
            List<String> witnesses = new ArrayList<>();
            witnesses.add(target);
            for (PolicyRule rule : rules) {
                if (rule.prefix().startsWith(target)) {    // descendant-or-equal of target → inside the subtree
                    witnesses.add(rule.prefix());
                }
            }
            for (String k : witnesses) {
                boolean allowK = false, denyK = false;
                for (PolicyRule rule : rules) {
                    if (k.startsWith(rule.prefix())) {     // ancestor-or-equal of k → matches under isAllowed
                        if (rule.allow().contains(cap)) allowK = true;
                        if (rule.deny().contains(cap))  denyK = true;
                    }
                }
                if (!(allowK && !denyK)) return false;     // this key is NOT per-key-authorized → not covered
            }
            return true;
        }

        @Test
        @DisplayName("200k random rule sets: coversTarget == the independent per-key witness oracle")
        void coversTargetEqualsWitnessOracleUnderFuzz() {
            Random r = new Random(0xC0FFEEL);   // fixed seed → reproducible, CI-stable
            int iters = 200_000;
            for (int i = 0; i < iters; i++) {
                int n = r.nextInt(7);           // 0..6 rules
                List<PolicyRule> rules = new ArrayList<>(n);
                for (int j = 0; j < n; j++) rules.add(randomRule(r));
                String target = PREFIXES[r.nextInt(PREFIXES.length)];
                AclService.Permission cap = CAPS[r.nextInt(CAPS.length)];

                boolean expected = witnessOracle(rules, target, cap);
                boolean actual = coversTarget(rules, target, cap);
                if (expected != actual) {
                    org.junit.jupiter.api.Assertions.fail(
                            "DIFFERENTIAL MISMATCH (iter " + i + "): coversTarget=" + actual
                            + " but per-key oracle=" + expected
                            + " | target=\"" + target + "\" cap=" + cap + " rules=" + rules);
                }
            }
        }

        @Test
        @DisplayName("200k random rule sets: authorizesWatch == ∀K∈subtree: per-key READ ∧ WATCH")
        void authorizesWatchEqualsPerKeyReadAndWatchUnderFuzz() {
            Random r = new Random(0xBADBEEFL);
            int iters = 200_000;
            for (int i = 0; i < iters; i++) {
                int n = r.nextInt(7);
                List<PolicyRule> rules = new ArrayList<>(n);
                for (int j = 0; j < n; j++) rules.add(randomRule(r));
                String target = PREFIXES[r.nextInt(PREFIXES.length)];

                boolean expected = witnessOracle(rules, target, READ) && witnessOracle(rules, target, WATCH);
                boolean actual = authorizesWatch(rules, target);
                if (expected != actual) {
                    org.junit.jupiter.api.Assertions.fail(
                            "WATCH-FLOOR MISMATCH (iter " + i + "): authorizesWatch=" + actual
                            + " but (∀K: READ ∧ WATCH)=" + expected
                            + " | target=\"" + target + "\" rules=" + rules);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // (RT-2) RED-TEAM — boundary PoCs the hand matrix leaves un-pinned.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Red-team — boundary PoCs (same-rule allow∩deny, deny==target, disjoint deny, null element)")
    class RedTeamBoundaries {

        /**
         * Deny-precedence on ONE rule: a single rule carries cap in BOTH allow and deny at an
         * ancestor-or-equal prefix — granted and denied fire on the same rule, so deny must win. (The matrix
         * only composed deny from a SEPARATE rule; this pins the single-rule intersection.)
         */
        @Test
        void sameRuleAllowAndDenySameCap_denyWins() {
            List<PolicyRule> rules = List.of(
                    new PolicyRule("a.", Set.of(READ, WATCH), Set.of(READ, WATCH)));
            assertFalse(coversTarget(rules, "a.", READ), "allow∩deny on one rule → deny wins (READ)");
            assertFalse(coversTarget(rules, "a.", WATCH), "allow∩deny on one rule → deny wins (WATCH)");
            assertFalse(authorizesWatch(rules, "a."), "no watch when the sole rule denies what it allows");
        }

        /** A DENY whose prefix EXACTLY equals the target is an ancestor-or-equal deny → rejects the subtree. */
        @Test
        void denyPrefixEqualToTarget_rejects() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH), deny("a.", READ));
            assertFalse(coversTarget(rules, "a.", READ),
                    "a deny at exactly the target root carves the whole subtree (ancestor-or-equal boundary)");
            assertFalse(authorizesWatch(rules, "a."));
        }

        /**
         * Over-rejection bound (availability): a deny on a subtree DISJOINT from the target (neither ancestor
         * nor descendant) must NOT reject the target — the interior term is scoped, not global. The lone
         * exception is FULL ("") where every deny is interior by construction (the documented full-store
         * closure), asserted here as the positive control.
         */
        @Test
        void disjointDenyDoesNotOverReject() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH), deny("b.", READ));
            assertTrue(coversTarget(rules, "a.", READ), "a deny on the disjoint b. subtree must not block a.");
            assertTrue(authorizesWatch(rules, "a."));
            assertFalse(coversTarget(rules, "", READ), "at FULL, the b. deny is interior → blocks FULL READ");
        }

        /**
         * Robustness: a null element in the rule set fails CLOSED by throwing (NPE at PolicyRule.allow()),
         * never silently skipping it into a permissive decision. The assembling gate owns non-null elements;
         * this pins that the predicate cannot swallow a null into an over-grant.
         */
        @Test
        void nullRuleElement_failsClosedByThrowing() {
            List<PolicyRule> rules = new ArrayList<>();
            rules.add(allow("a.", READ, WATCH));
            rules.add(null);
            assertThrows(NullPointerException.class, () -> coversTarget(rules, "a.", READ),
                    "a null rule element must NPE (fail-closed), not be skipped into an over-grant");
        }
    }

    // -----------------------------------------------------------------------
    // Review follow-ups — cheap completeness pins the lanes proposed (no-op rule; split-cap ancestors).
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Review follow-ups — no-op rule + split-cap ancestors")
    class ReviewFollowups {

        /**
         * A no-op rule — empty allow AND empty deny (a lingering prefix, or a rule carrying only caps
         * unrelated to the queried one) — contributes nothing: beside a real ancestor ALLOW the subtree
         * stays covered, and alone it leaves default-deny intact. Closes the "rule contributes nothing"
         * branch as an explicit named pin (the differential fuzz also exercises it).
         */
        @Test
        void noOpRuleChangesNothing() {
            PolicyRule noOp = allow("a."); // empty varargs → empty allow AND empty deny

            assertTrue(coversTarget(List.of(allow("a.", READ, WATCH), noOp), "a.", READ),
                    "a no-op rule beside a real ALLOW does not change coverage");
            assertTrue(authorizesWatch(List.of(allow("a.", READ, WATCH), noOp), "a."));
            assertFalse(coversTarget(List.of(noOp), "a.", READ),
                    "a no-op rule alone grants nothing → default-deny");
            assertFalse(authorizesWatch(List.of(noOp), "a."));
        }

        /**
         * Split-cap ancestors: an ancestor-or-equal ALLOW carries WATCH but only a STRICTLY-BELOW rule
         * carries READ. Each {@code coversTarget} pass needs its OWN ancestor-or-equal ALLOW for its cap,
         * so {@code authorizesWatch} (READ ∧ WATCH over the whole target) is NOT satisfied — a sub-target
         * READ cannot complete the floor a subtree WATCH grant opened.
         */
        @Test
        void splitCapAncestorsDoNotSatisfyTheFloor() {
            List<PolicyRule> rules = List.of(allow("a.", WATCH), allow("a.b.", READ));

            assertTrue(coversTarget(rules, "a.", WATCH), "WATCH has an ancestor-or-equal ALLOW of \"a.\"");
            assertFalse(coversTarget(rules, "a.", READ),
                    "READ's only ALLOW (\"a.b.\") is strictly below \"a.\" → does not cover the subtree");
            assertFalse(authorizesWatch(rules, "a."),
                    "each cap needs its own ancestor-or-equal ALLOW; a sub-target READ cannot complete the floor");
        }
    }
}

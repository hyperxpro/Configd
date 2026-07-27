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
 * Tests for the whole-target authorization predicate {@link AclService#coversTarget} and its
 * watch floor {@link AclService#authorizesWatch}.
 * <p>
 * These two methods close the hole {@link AclService#isAllowed} documents at its WATCH-requires-READ
 * floor: a single-key check unions only a key's <b>ancestor</b> grants, so it cannot see a {@code READ}/
 * {@code WATCH} deny on a <b>descendant</b> of the key. {@code coversTarget} adds the missing
 * <b>interior-DENY term</b> ({@code D.prefix.startsWith(target)}) so a subtree/FULL watch can be
 * authorized once at subscription with whole-target coverage rather than per delivered key. Matching is
 * the same literal {@code startsWith} the rest of {@link AclService} uses (segment-awareness is deferred
 * and pinned by test).
 * <p>
 * The rule collections here model the principal's <b>unioned</b> rule set (own union role union config)
 * that the watch-subscribe gate will assemble from the same sources {@code isAllowed} accumulates; the
 * predicate is source-agnostic, so the tests simply build flat {@code List<PolicyRule>}s.
 */
@DisplayName("AclService.coversTarget / authorizesWatch — whole-target watch authorization (dormant)")
class AclServiceCoversTargetTest {


    private static PolicyRule allow(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(caps), Set.of());
    }

    private static PolicyRule deny(String prefix, AclService.Permission... caps) {
        return new PolicyRule(prefix, Set.of(), Set.of(caps));
    }


    @Nested
    @DisplayName("WATCH floor — authorizesWatch requires both READ and WATCH coverage")
    class Floor {

        @Test
        void watchWithoutReadDoesNotAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", WATCH));

            assertTrue(coversTarget(rules, "a.", WATCH), "WATCH covers the subtree");
            assertFalse(coversTarget(rules, "a.", READ), "READ does NOT cover the subtree");
            assertFalse(authorizesWatch(rules, "a."),
                    "WATCH without READ over the target yields no watch authz (INV-WATCH-READ)");
        }

        @Test
        void readWithoutWatchDoesNotAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", READ));

            assertTrue(coversTarget(rules, "a.", READ), "READ covers the subtree");
            assertFalse(coversTarget(rules, "a.", WATCH), "WATCH does NOT cover the subtree");
            assertFalse(authorizesWatch(rules, "a."),
                    "READ alone does not confer WATCH (WATCH is separately grantable)");
        }

        @Test
        void readAndWatchTogetherAuthorize() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(authorizesWatch(rules, "a."),
                    "READ ∧ WATCH over the whole target → authorized as a streaming read");
        }
    }


    @Nested
    @DisplayName("Interior DENY — the case a single-key ancestor-only check cannot catch")
    class InteriorDeny {

        @Test
        void interiorReadDenyDefeatsWholeTargetReadAndWatch() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH), deny("a.secret.", READ));

            assertFalse("a.".startsWith("a.secret."),
                    "the deny is strictly below the target → invisible to an ancestor-only walk");
            boolean ancestorOnlyWouldGrantRead =
                    rules.stream().anyMatch(r -> r.allow().contains(READ) && "a.".startsWith(r.prefix()))
                    && rules.stream().noneMatch(r -> r.deny().contains(READ) && "a.".startsWith(r.prefix()));
            assertTrue(ancestorOnlyWouldGrantRead,
                    "an ancestor-only single-key READ check at the target root would WRONGLY pass");

            assertFalse(coversTarget(rules, "a.", READ),
                    "the interior READ deny carves a hole in the subtree → whole-target READ NOT covered");
            assertFalse(authorizesWatch(rules, "a."),
                    "a watch over the subtree must be rejected, not silently filtered (W7-2/W7-2a)");
            assertTrue(coversTarget(rules, "a.", WATCH),
                    "only READ was carved; WATCH still covers the whole subtree");
        }

        @Test
        void interiorWatchDenyDefeatsWholeTargetWatch() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH), deny("a.secret.", WATCH));

            assertTrue(coversTarget(rules, "a.", READ), "READ is uncarved");
            assertFalse(coversTarget(rules, "a.", WATCH),
                    "the interior WATCH deny carves a hole → whole-target WATCH NOT covered");
            assertFalse(authorizesWatch(rules, "a."));
        }
    }


    @Nested
    @DisplayName("Ancestor DENY — deny at/above the target (the regression guard)")
    class AncestorDeny {

        @Test
        void ancestorDenyDefeatsAnAncestorAllow() {
            List<PolicyRule> rules = List.of(allow("", READ), deny("a.", READ));

            assertTrue("a.b.".startsWith("a."), "the deny IS an ancestor of the target (catchable)");
            assertFalse(coversTarget(rules, "a.b.", READ),
                    "an ancestor DENY (target.startsWith(D.prefix)) defeats whole-target coverage");
        }
    }


    @Nested
    @DisplayName("Sub-target ALLOWs only — cannot cover an unbounded subtree")
    class SubTargetAllowsOnly {

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


    @Nested
    @DisplayName("Positive — ancestor-or-equal ALLOW, no intersecting DENY")
    class Positive {

        @Test
        void ancestorAllowCoversSubtreeRootAndDeepKeys() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(coversTarget(rules, "a.", READ));
            assertTrue(coversTarget(rules, "a.", WATCH));
            assertTrue(authorizesWatch(rules, "a."), "subtree root is covered");

            assertTrue(coversTarget(rules, "a.b.x", READ));
            assertTrue(coversTarget(rules, "a.b.x", WATCH));
            assertTrue(authorizesWatch(rules, "a.b.x"), "a deep key under the ALLOW is covered");
        }
    }


    @Nested
    @DisplayName("FULL target (\"\") — root grant ∧ no cap-DENY anywhere")
    class FullTarget {

        @Test
        void subtreeOnlyPrincipalDoesNotCoverFull() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertFalse(coversTarget(rules, "", READ),
                    "\"\".startsWith(\"a.\") is false → no root-prefix ALLOW carries READ for FULL");
            assertFalse(authorizesWatch(rules, ""), "a subtree grant cannot authorize a FULL watch");
        }

        @Test
        void rootPrincipalCoversFull() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH));

            assertTrue(coversTarget(rules, "", READ));
            assertTrue(coversTarget(rules, "", WATCH));
            assertTrue(authorizesWatch(rules, ""), "root grant covers the whole store (FULL)");
        }

        /**
         * Root principal + ANY interior cap-DENY -> FULL not covered for that cap. At {@code target == ""}
         * the interior disjunct {@code D.prefix.startsWith("")} is true for EVERY deny, so a single
         * {@code DENY "a.secret." {READ}} anywhere blocks FULL READ - closing the full-store bypass.
         */
        @Test
        void rootPrincipalWithInteriorDenyDoesNotCoverFull() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH), deny("a.secret.", READ));

            assertTrue("a.secret.".startsWith(""), "every prefix startsWith(\"\") → every deny intersects FULL");
            assertFalse(coversTarget(rules, "", READ),
                    "any interior READ deny blocks FULL READ coverage (interior disjunct fires at root)");
            assertFalse(authorizesWatch(rules, ""), "the FULL-store bypass is closed by the interior term");
            assertTrue(coversTarget(rules, "", WATCH), "WATCH, denied nowhere, still covers FULL");
        }
    }


    @Nested
    @DisplayName("Segment boundary — DL-O3-02 literal-startsWith behavior (pinned, not fixed)")
    class SegmentBoundary {

        /**
         * ALLOW {@code "team"} {READ}, target {@code "teamX"}. Raw {@code startsWith} treats {@code "team"}
         * as an ancestor of {@code "teamX"}, so coverage is granted across the non-segment boundary. This
         * LOCKS the known over-grant - it is deliberately NOT "fixed" here (segment-aware matching is
         * deferred).
         */
        @Test
        void allowOverGrantsAcrossSegmentBoundary() {
            List<PolicyRule> rules = List.of(allow("team", READ));

            assertTrue(coversTarget(rules, "teamX", READ),
                    "literal startsWith over-grants: ALLOW \"team\" covers \"teamX\" (DL-O3-02, pinned)");
        }

        /**
         * The fail-closed counterpart: DENY {@code "team"} {READ} also intersects {@code "teamX"} via the
         * same literal {@code startsWith}, so it over-DENIES - coverage errs toward rejecting, never exposing.
         */
        @Test
        void denyOverDeniesAcrossSegmentBoundary() {
            List<PolicyRule> rules = List.of(allow("", READ), deny("team", READ));

            assertFalse(coversTarget(rules, "teamX", READ),
                    "literal startsWith over-denies: DENY \"team\" intersects \"teamX\" → fail-closed (DL-O3-02)");
        }
    }


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


    @Nested
    @DisplayName("Multi-source union — own ∪ role ∪ config (the gate's assembled rule set)")
    class MultiSourceUnion {

        @Test
        void unionedOwnAndRoleCover_thenConfigInteriorDenyDefeats() {
            PolicyRule ownReadGrant = allow("a.", READ);
            PolicyRule roleWatchGrant = allow("a.", WATCH);
            PolicyRule configInteriorDeny = deny("a.secret.", READ);

            List<PolicyRule> ownPlusRole = List.of(ownReadGrant, roleWatchGrant);
            assertTrue(authorizesWatch(ownPlusRole, "a."),
                    "own READ ∪ role WATCH cover the subtree → watch authorized");

            List<PolicyRule> ownPlusRolePlusConfig = List.of(ownReadGrant, roleWatchGrant, configInteriorDeny);
            assertFalse(authorizesWatch(ownPlusRolePlusConfig, "a."),
                    "a config interior READ deny composes with absolute precedence → watch rejected");
        }
    }


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
            List<PolicyRule> rules = List.of(allow("b.", READ, WATCH));

            assertFalse(coversTarget(rules, "a.", READ),
                    "an ALLOW on an unrelated/sibling subtree does not cover the target");
            assertFalse(authorizesWatch(rules, "a."));
        }
    }


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


    @Nested
    @DisplayName("Cross-capability hygiene — per-capability, no cross-conferral")
    class CrossCapabilityHygiene {

        @Test
        void watchCoverageDoesNotConferOtherCaps() {
            List<PolicyRule> rules = List.of(allow("a.", READ, WATCH));

            assertTrue(authorizesWatch(rules, "a."));
            assertFalse(coversTarget(rules, "a.", WRITE), "READ ∧ WATCH coverage must not confer WRITE");
            assertFalse(coversTarget(rules, "a.", LIST), "READ ∧ WATCH coverage must not confer LIST");
            assertFalse(coversTarget(rules, "a.", ADMIN), "READ ∧ WATCH coverage must not confer ADMIN");
        }
    }

    // Red-team: differential fuzz against an independent per-key witness oracle.
    // This mechanizes the soundness proof: whole-target coverage is the universal lift of the
    // per-key (ancestor-only) isAllowed decision over EVERY key in the subtree. The oracle is
    // computed WITHOUT coversTarget's two-flag interior-term trick, so agreement across 200k random
    // rule sets is overwhelming evidence the predicate neither over-exposes (false TRUE = a denied
    // descendant slips through) nor over-rejects (false FALSE).

    @Nested
    @DisplayName("Red-team — coversTarget ≡ ∀K∈subtree: per-key isAllowed (differential fuzz)")
    class RedTeamDifferential {

        // Alphabet chosen to maximise prefix-comparability collisions and the nasty boundaries:
        //   interior chain  a. ⊃ a.b. ⊃ a.b.c            (interior-deny carve)
        //   secret carve    a.secret.
        //   sibling trap    a.b  vs  a.bc                 (a.b IS a literal prefix of a.bc!)
        //   segment pair    team / team. / teamX          (segment-boundary over-grant)
        //   FULL/root  ""   and a disjoint subtree  x.
        private static final String[] PREFIXES = {
                "", "a", "a.", "a.b", "a.b.", "a.b.c", "a.bc",
                "a.secret", "a.secret.", "b.", "team", "team.", "teamX", "x."
        };
        private static final AclService.Permission[] CAPS = AclService.Permission.values();

        private static PolicyRule randomRule(Random r) {
            EnumSet<AclService.Permission> a = EnumSet.noneOf(AclService.Permission.class);
            EnumSet<AclService.Permission> d = EnumSet.noneOf(AclService.Permission.class);
            for (AclService.Permission p : CAPS) {
                if (r.nextInt(100) < 40) a.add(p);
                if (r.nextInt(100) < 30) d.add(p);   // ~30% DENY - so allow∩deny on the SAME rule is common
            }
            return new PolicyRule(PREFIXES[r.nextInt(PREFIXES.length)], a, d);
        }

        /**
         * The SPEC, computed independently of {@code coversTarget}: whole-target coverage is the universal
         * lift of the per-key (ancestor-only) decision -
         *   for all K in subtree(target): (exists ancestor-or-equal ALLOW carrying cap) AND (no ancestor-or-equal DENY carrying cap).
         * The subtree is infinite, but the per-key decision only changes at rule-prefix boundaries, so a
         * FINITE witness set is provably sufficient: {target} union {every rule prefix that is a
         * descendant-or-equal of target}. (A false-TRUE would require a denied descendant K; that K forces
         * target and the deny prefix to be prefix-comparable, and the deny prefix is itself such a witness.)
         */
        private static boolean witnessOracle(List<PolicyRule> rules, String target, AclService.Permission cap) {
            List<String> witnesses = new ArrayList<>();
            witnesses.add(target);
            for (PolicyRule rule : rules) {
                if (rule.prefix().startsWith(target)) {
                    witnesses.add(rule.prefix());
                }
            }
            for (String k : witnesses) {
                boolean allowK = false, denyK = false;
                for (PolicyRule rule : rules) {
                    if (k.startsWith(rule.prefix())) {
                        if (rule.allow().contains(cap)) allowK = true;
                        if (rule.deny().contains(cap))  denyK = true;
                    }
                }
                if (!(allowK && !denyK)) return false;
            }
            return true;
        }

        @Test
        @DisplayName("200k random rule sets: coversTarget == the independent per-key witness oracle")
        void coversTargetEqualsWitnessOracleUnderFuzz() {
            Random r = new Random(0xC0FFEEL);
            int iters = 200_000;
            for (int i = 0; i < iters; i++) {
                int n = r.nextInt(7);
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


    @Nested
    @DisplayName("Red-team — boundary PoCs (same-rule allow∩deny, deny==target, disjoint deny, null element)")
    class RedTeamBoundaries {

        /**
         * Deny-precedence on ONE rule: a single rule carries cap in BOTH allow and deny at an
         * ancestor-or-equal prefix - granted and denied fire on the same rule, so deny must win. (The matrix
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

        @Test
        void denyPrefixEqualToTarget_rejects() {
            List<PolicyRule> rules = List.of(allow("", READ, WATCH), deny("a.", READ));
            assertFalse(coversTarget(rules, "a.", READ),
                    "a deny at exactly the target root carves the whole subtree (ancestor-or-equal boundary)");
            assertFalse(authorizesWatch(rules, "a."));
        }

        /**
         * Over-rejection bound (availability): a deny on a subtree DISJOINT from the target (neither ancestor
         * nor descendant) must NOT reject the target - the interior term is scoped, not global. The lone
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


    @Nested
    @DisplayName("Review follow-ups — no-op rule + split-cap ancestors")
    class ReviewFollowups {

        /**
         * A no-op rule - empty allow AND empty deny (a lingering prefix, or a rule carrying only caps
         * unrelated to the queried one) - contributes nothing: beside a real ancestor ALLOW the subtree
         * stays covered, and alone it leaves default-deny intact. Closes the "rule contributes nothing"
         * branch as an explicit named pin (the differential fuzz also exercises it).
         */
        @Test
        void noOpRuleChangesNothing() {
            PolicyRule noOp = allow("a.");

            assertTrue(coversTarget(List.of(allow("a.", READ, WATCH), noOp), "a.", READ),
                    "a no-op rule beside a real ALLOW does not change coverage");
            assertTrue(authorizesWatch(List.of(allow("a.", READ, WATCH), noOp), "a."));
            assertFalse(coversTarget(List.of(noOp), "a.", READ),
                    "a no-op rule alone grants nothing → default-deny");
            assertFalse(authorizesWatch(List.of(noOp), "a."));
        }

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

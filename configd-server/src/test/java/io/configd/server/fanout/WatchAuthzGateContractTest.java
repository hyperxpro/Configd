package io.configd.server.fanout;

import io.configd.api.AclService;
import io.configd.api.AclService.Permission;
import io.configd.api.PolicyRule;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization of the shard-independent watch-authorization gate contract that the multi-shard watch
 * coordinator enforces across N legs. These are read-only pins of EXISTING behavior; they add no
 * production code and change none.
 *
 * <h2>What the multi-shard watch coordinator relies on and where it is pinned</h2>
 * <ul>
 *   <li><b>Shard-independence (pinned here).</b> {@link AclService#coversTarget}/{@link
 *       AclService#authorizesWatch} are pure, {@code static} functions of {@code (rules, target, cap)} with
 *       no gid/shard parameter and no instance state, so the whole-target decision is invariant to the
 *       ORDER in which per-shard rules are merged. A multi-shard watch is therefore a single whole-target
 *       decision over the union of every shard's rules, identical regardless of scatter-gather order - the
 *       property that makes "one gate, all N legs or none" (INV-MSW-ATOMIC) sound.</li>
 *   <li><b>The KEY / PREFIX / FULL branch table incl. FULL/full_chain_verify -&gt; root {@code ""}</b> is
 *       pinned here via {@link AclServiceWatchAuthorizer}, and more exhaustively in
 *       {@code AclServiceWatchAuthorizerTest} (the RFC §2 W7 matrix).</li>
 *   <li><b>Establish-order (version read BEFORE the gate; zero data frames before a NOT_AUTHORIZED reject)</b>
 *       is pinned by {@code WatchRevocationTest.revocationRacingTheCreateIsCaughtOnFirstReauth} and
 *       {@code WatchVeneerDriverTest.{denyingAuthorizerRejectsWithNotAuthorizedAndZeroDataFrames,
 *       fullChainVerifyDenyEmitsZeroNotifyBeforeReject}} - not duplicated here.</li>
 *   <li><b>Fail-closed absolutes (null authorizer / {@code "plaintext"} identity / any throwable =&gt; deny)</b>
 *       are pinned by {@code WatchVeneerDriverTest.{nullAuthorizerFailsClosed,
 *       unauthenticatedPlaintextIdentityFailsClosedEvenWhenAuthorizerAllows, throwingAuthorizerFailsClosed}}
 *       - the {@code FanOutConnectionDriver} gate, not this adapter's, so pinned there.</li>
 * </ul>
 */
class WatchAuthzGateContractTest {

    private static PolicyRule allow(String prefix, Permission... caps) {
        return new PolicyRule(prefix, EnumSet.copyOf(List.of(caps)), Set.of());
    }

    private static PolicyRule deny(String prefix, Permission... caps) {
        return new PolicyRule(prefix, Set.of(), EnumSet.copyOf(List.of(caps)));
    }

    /**
     * Asserts a boolean verdict computed over {@code rules} is invariant to rule order: it equals the verdict
     * over the reversed list and over several fixed-seed shuffles. Simulates the same rule set arriving in a
     * different scatter-gather (per-shard merge) order.
     */
    private static void assertOrderInvariant(List<PolicyRule> rules, boolean expected,
                                             java.util.function.Predicate<List<PolicyRule>> verdict) {
        assertEquals(expected, verdict.test(rules), "canonical order");
        List<PolicyRule> reversed = new ArrayList<>(rules);
        Collections.reverse(reversed);
        assertEquals(expected, verdict.test(reversed), "reversed order");
        for (long seed = 1; seed <= 8; seed++) {
            List<PolicyRule> shuffled = new ArrayList<>(rules);
            Collections.shuffle(shuffled, new Random(seed));
            assertEquals(expected, verdict.test(shuffled), "shuffle seed " + seed);
        }
    }

    @Test
    void coversTargetIsInvariantToRuleMergeOrder() {
        // An ancestor ALLOW carries READ over app.; an interior DENY carves app.secret.; an unrelated grant.
        List<PolicyRule> rules = List.of(
                allow("app.", Permission.READ, Permission.WATCH),
                deny("app.secret.", Permission.READ),
                allow("other.", Permission.READ));
        // READ over the whole app. subtree is NOT covered (the interior DENY carves it) - and that verdict
        // does not depend on which shard the DENY was gathered from.
        assertOrderInvariant(rules, false, r -> AclService.coversTarget(r, "app.", Permission.READ));
        // WATCH over app. IS covered (no WATCH deny anywhere under app.).
        assertOrderInvariant(rules, true, r -> AclService.coversTarget(r, "app.", Permission.WATCH));
        // A narrower target with no interior deny under it is covered for READ.
        assertOrderInvariant(rules, true, r -> AclService.coversTarget(r, "app.public.", Permission.READ));
    }

    @Test
    void authorizesWatchIsInvariantToRuleMergeOrder() {
        List<PolicyRule> covered = List.of(
                allow("svc.", Permission.READ, Permission.WATCH),
                allow("other.", Permission.WATCH));
        assertOrderInvariant(covered, true, r -> AclService.authorizesWatch(r, "svc."));

        // Add an interior READ deny -> the whole-subtree watch is no longer authorized (READ and WATCH floor),
        // still order-independent.
        List<PolicyRule> carved = new ArrayList<>(covered);
        carved.add(deny("svc.internal.", Permission.READ));
        assertOrderInvariant(carved, false, r -> AclService.authorizesWatch(r, "svc."));
    }

    @Test
    void fullAndFullChainVerifyMapToRootAndDependOnAWholeStoreGrant() {
        // A root-scope READ and WATCH grant authorizes FULL and full_chain_verify (effective target "").
        AclService rootGranted = new AclService();
        rootGranted.grant("", "rooty", EnumSet.of(Permission.READ, Permission.WATCH));
        AclServiceWatchAuthorizer rootAuthz = new AclServiceWatchAuthorizer(rootGranted);
        assertTrue(rootAuthz.authorizeWatch("rooty", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_FULL, "", false)), "root grant authorizes FULL");
        assertTrue(rootAuthz.authorizeWatch("rooty", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, "app.", true)),
                "root grant authorizes full_chain_verify (maps to root \"\")");

        // A subtree-only principal is DENIED FULL / full_chain_verify - the cross-tenant-leak guard: the
        // effective target "" cannot be covered by a non-root grant.
        AclService subtree = new AclService();
        subtree.grant("app.", "subby", EnumSet.of(Permission.READ, Permission.WATCH));
        AclServiceWatchAuthorizer subAuthz = new AclServiceWatchAuthorizer(subtree);
        assertFalse(subAuthz.authorizeWatch("subby", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_FULL, "", false)),
                "a subtree-only grant does not authorize FULL (no whole-store cover)");
        assertFalse(subAuthz.authorizeWatch("subby", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, "app.", true)),
                "a subtree-only grant does not authorize full_chain_verify (maps to root \"\")");
    }

    @Test
    void keyFloorIsNotBlockedByADescendantDenyThatBlocksThePrefixSubtree() {
        // A descendant-key DENY under a.b (on a.b.c) must NOT block a KEY watch on a.b (exact-key floor),
        // but MUST block a PREFIX watch over the a.b subtree (interior DENY). One merged rule set, no shard
        // input - the same asymmetry the multi-shard watch coordinator relies on.
        AclService acl = new AclService();
        acl.grant("a.b", "p", EnumSet.of(Permission.READ, Permission.WATCH));
        acl.deny("a.b.c", "p", EnumSet.of(Permission.READ));
        AclServiceWatchAuthorizer authz = new AclServiceWatchAuthorizer(acl);

        assertTrue(authz.authorizeWatch("p", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_KEY, "a.b", false)),
                "KEY watch on a.b is the exact-key floor - a descendant-key DENY does not block it");
        assertFalse(authz.authorizeWatch("p", Set.of(),
                new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, "a.b", false)),
                "PREFIX watch over a.b IS blocked by the interior descendant DENY");
    }
}

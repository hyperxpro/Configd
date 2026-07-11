package io.configd.server.fanout;

import io.configd.api.AclService;
import io.configd.api.Policy;
import io.configd.api.PolicyRule;
import io.configd.api.Role;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.configd.api.AclService.Permission.READ;
import static io.configd.api.AclService.Permission.WATCH;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watch-authorization matrix for the production {@link AclServiceWatchAuthorizer} - the
 * security-critical adapter branch (KEY uses the single-key floor; PREFIX/FULL/{@code
 * full_chain_verify} use the whole-subtree cover; FULL/{@code full_chain_verify} map to the root
 * effective target {@code ""}). Drives a real {@link AclService} with grants/denies/roles and asserts
 * the {@code authorizeWatch} verdict.
 */
@DisplayName("AclServiceWatchAuthorizer — the RFC §2 W7 watch-authorization matrix")
class AclServiceWatchAuthorizerTest {

    private static WatchTarget key(String path) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_KEY, path, false);
    }

    private static WatchTarget prefix(String path) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, path, false);
    }

    private static WatchTarget full() {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_FULL, "", false);
    }

    /** A {@code full_chain_verify} target over a PREFIX path (forces the root-scope requirement). */
    private static WatchTarget fcvPrefix(String path) {
        return new WatchTarget(0, EdgeFrame.WATCH_TARGET_PREFIX, path, true);
    }

    private static boolean authz(AclService acl, String principal, WatchTarget t) {
        return new AclServiceWatchAuthorizer(acl).authorizeWatch(principal, Set.of(), t);
    }

    @Test
    @DisplayName("baseline: PREFIX with READ∧WATCH over the subtree is allowed")
    void prefixWithReadAndWatchAllowed() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH));
        assertTrue(authz(acl, "p", prefix("a.")));
    }

    @Test
    @DisplayName("1 — PREFIX lacking WATCH (or READ) is rejected (the READ∧WATCH floor)")
    void prefixLackingCapRejected() {
        AclService noWatch = new AclService();
        noWatch.grant("a.", "p", Set.of(READ));
        assertFalse(authz(noWatch, "p", prefix("a.")), "no WATCH ⇒ reject");
        AclService noRead = new AclService();
        noRead.grant("a.", "p", Set.of(WATCH));
        assertFalse(authz(noRead, "p", prefix("a.")), "no READ ⇒ reject (a watch is a streaming read)");
    }

    @Test
    @DisplayName("2 — an interior DENY under the PREFIX rejects the whole watch (W7-2a), not narrowed")
    void interiorDenyRejectsNotNarrowed() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH));
        acl.deny("a.secret.", "p", Set.of(READ)); // a hole carved inside the subtree
        assertFalse(authz(acl, "p", prefix("a.")),
                "the interior DENY on the descendant a.secret. must reject the whole-subtree watch");
    }

    @Test
    @DisplayName("3 — an ancestor DENY rejects the PREFIX watch")
    void ancestorDenyRejects() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH));
        acl.deny("", "p", Set.of(WATCH)); // deny at the root (an ancestor of a.)
        assertFalse(authz(acl, "p", prefix("a.")));
    }

    @Test
    @DisplayName("4 — an over-broad target (grant ⊊ target) is rejected, never narrowed to the subset")
    void overBroadTargetRejectedNotFiltered() {
        AclService acl = new AclService();
        acl.grant("a.b.", "p", Set.of(READ, WATCH)); // the grant is a strict subset of the target a.
        assertFalse(authz(acl, "p", prefix("a.")),
                "no ancestor-or-equal ALLOW covers a.; the watch is rejected, not narrowed to a.b.");
    }

    @Test
    @DisplayName("5 — full_chain_verify by a subtree-only principal is rejected (maps to root, W7-3)")
    void fullChainVerifyBySubtreePrincipalRejected() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH)); // a subtree grant, not the root ""
        assertFalse(authz(acl, "p", fcvPrefix("a.")),
                "full_chain_verify streams the whole signed chain ⇒ requires a root-scope grant");
    }

    @Test
    @DisplayName("6 — FULL by a root-granted principal is allowed; by a subtree principal is rejected")
    void fullRequiresRootGrant() {
        AclService root = new AclService();
        root.grant("", "root", Set.of(READ, WATCH));
        assertTrue(authz(root, "root", full()), "a root READ∧WATCH grant authorizes a FULL watch");

        AclService subtree = new AclService();
        subtree.grant("a.", "p", Set.of(READ, WATCH));
        assertFalse(authz(subtree, "p", full()), "FULL ⟺ a root grant; a subtree grant is rejected");
    }

    @Test
    @DisplayName("7 — segment-boundary is the literal startsWith model (DL-O3-02), both ways")
    void segmentBoundaryLiteralModel() {
        // ALLOW "team" literally covers "teamX" (startsWith); a segment-confusable grant over-covers.
        AclService allow = new AclService();
        allow.grant("team", "p", Set.of(READ, WATCH));
        assertTrue(authz(allow, "p", prefix("teamX")), "ALLOW 'team' covers 'teamX' (literal startsWith)");
        // DENY "team" literally denies "teamX"; a segment-confusable carve-out over-denies (fail-closed).
        AclService deny = new AclService();
        deny.grant("", "p", Set.of(READ, WATCH));
        deny.deny("team", "p", Set.of(READ));
        assertFalse(authz(deny, "p", prefix("teamX")), "DENY 'team' denies 'teamX' (literal startsWith)");
    }

    @Test
    @DisplayName("15 — an authenticated-but-ungranted principal is rejected on every target form")
    void ungrantedPrincipalRejected() {
        AclService acl = new AclService();
        acl.grant("a.", "alice", Set.of(READ, WATCH)); // alice is granted; mallory is not
        assertFalse(authz(acl, "mallory", prefix("a.")), "ungranted principal ⇒ reject (PREFIX)");
        assertFalse(authz(acl, "mallory", key("a.k")), "ungranted principal ⇒ reject (KEY)");
        assertFalse(authz(acl, "mallory", full()), "ungranted principal ⇒ reject (FULL)");
    }

    @Test
    @DisplayName("KEY is the exact-key floor — NOT blocked by a DENY on a different descendant key")
    void keyWatchNotBlockedByDescendantKeyDeny() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH));
        acl.deny("a.b.c", "p", Set.of(READ)); // a.b.c is a different (descendant) key, not a.b itself
        assertTrue(authz(acl, "p", key("a.b")),
                "a KEY watch on the exact path a.b is unaffected by a DENY on the descendant key a.b.c");
        assertFalse(acl.isAllowed("p", "a.b.c", WATCH), "the descendant key a.b.c itself is correctly denied");
    }

    @Test
    @DisplayName("PREFIX over the same path IS blocked by the interior descendant DENY (contrast with KEY)")
    void prefixWatchBlockedByInteriorDeny() {
        AclService acl = new AclService();
        acl.grant("a.", "p", Set.of(READ, WATCH));
        acl.deny("a.b.c", "p", Set.of(READ));
        assertFalse(authz(acl, "p", prefix("a.b")),
                "a PREFIX/subtree watch on a.b includes a.b.c, so its interior DENY rejects the watch");
    }

    @Test
    @DisplayName("KEY requires WATCH on the exact key")
    void keyWatchExactFloor() {
        AclService granted = new AclService();
        granted.grant("a.b", "p", Set.of(READ, WATCH));
        assertTrue(authz(granted, "p", key("a.b")));
        AclService noWatch = new AclService();
        noWatch.grant("a.b", "p", Set.of(READ));
        assertFalse(authz(noWatch, "p", key("a.b")), "KEY watch needs WATCH on the exact key");
    }

    @Test
    @DisplayName("role-sourced grants compose (effectiveRules resolves own ∪ role ∪ config)")
    void roleGrantedWatchAllowed() {
        AclService acl = new AclService();
        acl.defineRole(new Role("watcher",
                List.of(new Policy("p", List.of(new PolicyRule("svc.", Set.of(READ, WATCH), Set.of()))))));
        acl.assignRole("p", "watcher");
        assertTrue(authz(acl, "p", prefix("svc.")),
                "a role granting READ∧WATCH over svc. authorizes the subtree watch");
    }

    private static boolean authzSubscribe(AclService acl, String principal) {
        return new AclServiceWatchAuthorizer(acl).authorizeSubscribe(principal, Set.of());
    }

    @Test
    @DisplayName("SUBSCRIBE — a root-prefix READ grant authorizes the whole-store feed")
    void subscribeRootReadAllowed() {
        AclService acl = new AclService();
        acl.grant("", "edge", Set.of(READ));
        assertTrue(authzSubscribe(acl, "edge"), "a root READ grant covers the whole store");
    }

    @Test
    @DisplayName("SUBSCRIBE — a subtree-only grant does not cover the whole store")
    void subscribeSubtreeGrantRejected() {
        AclService acl = new AclService();
        acl.grant("a.", "edge", Set.of(READ)); // a strict subset of the root
        assertFalse(authzSubscribe(acl, "edge"), "a subtree READ grant cannot cover the root prefix");
    }

    @Test
    @DisplayName("SUBSCRIBE — any READ deny anywhere blocks the whole-store feed")
    void subscribeRootReadWithAnyDenyRejected() {
        AclService acl = new AclService();
        acl.grant("", "edge", Set.of(READ));
        acl.deny("a.secret.", "edge", Set.of(READ)); // a hole carved anywhere under the root
        assertFalse(authzSubscribe(acl, "edge"),
                "at the root, every deny matches the interior term, so any READ deny blocks SUBSCRIBE");
    }

    @Test
    @DisplayName("SUBSCRIBE — WATCH is not sufficient; READ is what a streaming read requires")
    void subscribeWatchOnlyRootRejected() {
        AclService acl = new AclService();
        acl.grant("", "edge", Set.of(WATCH)); // WATCH over the root but no READ
        assertFalse(authzSubscribe(acl, "edge"), "SUBSCRIBE authorizes on READ, not WATCH");
    }

    @Test
    @DisplayName("SUBSCRIBE — a root READ carried by a role authorizes the feed")
    void subscribeRoleCarriedRootReadAllowed() {
        AclService acl = new AclService();
        acl.defineRole(new Role("hydrator",
                List.of(new Policy("edge", List.of(new PolicyRule("", Set.of(READ), Set.of()))))));
        acl.assignRole("edge", "hydrator");
        assertTrue(authzSubscribe(acl, "edge"),
                "a role granting root READ authorizes the whole-store feed (effectiveRules unions roles)");
    }

    @Test
    @DisplayName("SUBSCRIBE — an ungranted principal is denied the whole-store feed")
    void subscribeUngrantedRejected() {
        AclService acl = new AclService();
        acl.grant("", "edge", Set.of(READ)); // edge holds root READ; mallory does not
        assertFalse(authzSubscribe(acl, "mallory"), "an ungranted principal cannot open the feed");
    }
}

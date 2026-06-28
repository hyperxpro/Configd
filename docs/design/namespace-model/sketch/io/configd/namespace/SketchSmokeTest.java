package io.configd.namespace;

import java.util.List;

import static io.configd.namespace.Capability.*;
import static io.configd.namespace.Scope.GLOBAL;

/**
 * Compile-checked smoke test for the namespace-model design sketch — a {@code main()} of asserts, not a
 * JUnit test (the sketch is a standalone design artifact, not wired into the build). It exercises:
 * <ul>
 *   <li>path normalization (RFC §3) — canonical forms and rejected forms;</li>
 *   <li>the union+deny evaluation (access-control.md §4.3 worked example, verbatim);</li>
 *   <li>the watch-authz contract (§6) — subtree watch allowed; over-broad rejected; full_chain_verify
 *       requires root scope.</li>
 * </ul>
 * Run with {@code java -ea}. Prints "SKETCH OK" iff every assertion holds.
 */
public final class SketchSmokeTest {

    public static void main(String[] args) {
        normalization();
        unionAndDeny();
        watchAuthz();
        System.out.println("SKETCH OK");
    }

    // ---- RFC §3: path normalization -------------------------------------------------------------

    private static void normalization() {
        check(ConfigPath.of("/a/b").value().equals("/a/b"), "plain path");
        check(ConfigPath.of("/a/b/").value().equals("/a/b"), "trailing slash stripped");
        check(ConfigPath.of("/").value().equals("/"), "root");
        check(ConfigPath.of("/db.host").value().equals("/db.host"), "legacy dotted key as single segment");
        rejects(() -> ConfigPath.of("a/b"), "non-absolute rejected");
        rejects(() -> ConfigPath.of("/a//b"), "empty segment rejected");
        rejects(() -> ConfigPath.of("/a/../b"), "relative segment rejected");
        rejects(() -> ConfigPath.of("/a/b c"), "space (non-seg-char) rejected");
        check(ConfigPath.of("/a/b/c/d").value().equals("/a/b/c/d"), "deep path is valid");
    }

    // ---- access-control.md §4.3: the worked example, verbatim -----------------------------------

    private static void unionAndDeny() {
        // principal p holds roles {tenant-payments, payments-secrets-reader}; effective rules:
        List<PolicyRule> p = List.of(
                PolicyRule.allow("/team-payments/**", READ, LIST, WATCH, WRITE),   // tenant-payments
                PolicyRule.allow("/team-payments/secrets/**", READ),               // payments-secrets-reader
                PolicyRule.deny("/team-payments/secrets/**", LIST)                 // carve-out
        );

        // READ + WRITE on a flags key (union from rule 1)
        check(PolicySet.authorized(p, GLOBAL, ConfigPath.of("/team-payments/flags/checkout"), READ), "READ flags");
        check(PolicySet.authorized(p, GLOBAL, ConfigPath.of("/team-payments/flags/checkout"), WRITE), "WRITE flags");

        // READ a known secret (union of rules 1 AND 2) — but LIST denied (rule 3 deny wins)
        ConfigPath secret = ConfigPath.of("/team-payments/secrets/stripe-key");
        check(PolicySet.authorized(p, GLOBAL, secret, READ), "READ known secret (union)");
        check(!PolicySet.authorized(p, GLOBAL, secret, LIST), "LIST secret DENIED (deny precedence)");

        // tenant isolation: nothing outside the subtree (default-deny)
        check(!PolicySet.authorized(p, GLOBAL, ConfigPath.of("/team-billing/invoices/x"), READ), "cross-tenant default-deny");

        // list authz: LIST over the secrets prefix is denied (deny intersects the target)
        check(!WatchAuthz.authorizeList(p, GLOBAL, "/team-payments/secrets/").allowed(), "LIST secrets/ denied");
        // ...but LIST over the flags prefix is allowed (rule 1 covers it, no deny)
        check(WatchAuthz.authorizeList(p, GLOBAL, "/team-payments/flags/").allowed(), "LIST flags/ allowed");
    }

    // ---- access-control.md §6 / RFC §6: the watch-authz contract --------------------------------

    private static void watchAuthz() {
        List<PolicyRule> tenant = List.of(
                PolicyRule.allow("/team-payments/**", READ, LIST, WATCH, WRITE)
        );

        // A subtree watch fully inside the grant: authorized (has READ ∧ WATCH over T).
        WatchAuthz.Target paymentsSub = new WatchAuthz.SubtreeTarget("/team-payments/secrets/**");
        check(WatchAuthz.authorizeWatch(tenant, GLOBAL, paymentsSub, false).allowed(),
                "subtree watch within grant allowed");

        // An over-broad subtree watch (beyond the grant): REJECTED, not filtered (A6-2).
        WatchAuthz.Target billingSub = new WatchAuthz.SubtreeTarget("/team-billing/**");
        check(!WatchAuthz.authorizeWatch(tenant, GLOBAL, billingSub, false).allowed(),
                "over-broad watch rejected");

        // full_chain_verify by a SUBTREE principal: REJECTED — requires root scope (A6-3, the bypass closed).
        check(!WatchAuthz.authorizeWatch(tenant, GLOBAL, paymentsSub, true).allowed(),
                "full_chain_verify without root scope rejected");
        // A FULL target by the same principal: also rejected (root required).
        check(!WatchAuthz.authorizeWatch(tenant, GLOBAL, new WatchAuthz.FullTarget(), false).allowed(),
                "FULL target without root scope rejected");

        // A root-scoped principal CAN full_chain_verify and watch FULL.
        List<PolicyRule> rootRole = List.of(PolicyRule.allow("/**", READ, LIST, WATCH, WRITE, ADMIN));
        check(WatchAuthz.authorizeWatch(rootRole, GLOBAL, paymentsSub, true).allowed(),
                "root principal full_chain_verify allowed");
        check(WatchAuthz.authorizeWatch(rootRole, GLOBAL, new WatchAuthz.FullTarget(), false).allowed(),
                "root principal FULL watch allowed");

        // INV-WATCH-READ: a principal with WATCH but NOT READ over the target cannot watch it.
        List<PolicyRule> watchNoRead = List.of(PolicyRule.allow("/team-payments/**", WATCH, LIST, WRITE));
        check(!WatchAuthz.authorizeWatch(watchNoRead, GLOBAL, paymentsSub, false).allowed(),
                "WATCH without READ rejected (WATCH >= READ)");
    }

    // ---- tiny harness ---------------------------------------------------------------------------

    private static void check(boolean cond, String what) {
        if (!cond) {
            throw new AssertionError("FAILED: " + what);
        }
    }

    private static void rejects(Runnable r, String what) {
        rejects(r, what, true);
    }

    private static void rejects(Runnable r, String what, boolean shouldThrow) {
        boolean threw = false;
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        if (threw != shouldThrow) {
            throw new AssertionError((shouldThrow ? "expected reject: " : "unexpected reject: ") + what);
        }
    }
}

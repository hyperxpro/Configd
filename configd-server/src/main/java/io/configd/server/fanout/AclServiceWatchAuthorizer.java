package io.configd.server.fanout;

import io.configd.api.AclService;
import io.configd.api.AclService.Permission;
import io.configd.distribution.fanout.WatchAuthorizer;
import io.configd.distribution.fanout.WatchTarget;
import io.configd.distribution.wire.EdgeFrame;

import java.util.Objects;
import java.util.Set;

/**
 * The production {@link WatchAuthorizer} (RFC §2 W7) — the {@code configd-server} adapter that bridges
 * the fan-out plane's authorization SPI to the in-core {@link AclService}. It lives here (not in
 * {@code configd-distribution-service}) because the fan-out plane is built <em>before</em>
 * {@code configd-control-plane-api} in the reactor and so cannot see {@code AclService} (the LOCKED
 * module seam); {@code ConfigdServer} constructs this adapter and threads it into the
 * {@code NettyFanOutServer}/{@code FanOutServer} → {@code FanOutConnectionDriver}, mirroring the
 * existing SPI discipline (auth-SPI, KMS-SPI, {@code NettyTransport.select}).
 *
 * <h2>The authorization branch (the security crux, RFC §2 §4.2)</h2>
 * The branch is deliberately asymmetric — a {@code KEY} target uses the <b>single-key floor</b> while
 * {@code PREFIX}/{@code FULL}/{@code full_chain_verify} targets use the <b>whole-subtree cover</b>:
 * <ol>
 *   <li><b>{@code full_chain_verify} or {@code FULL} → root effective target {@code ""}</b>
 *       (PR-1 handoff obligation #1). The verbatim signed-chain / whole-scope stream is authorized
 *       <b>only</b> by a root-scope {@code READ ∧ WATCH} grant; mapping the effective target to the
 *       empty prefix BEFORE {@link AclService#authorizesWatch} makes a subtree-only principal fail —
 *       else it would receive the full chain across every tenant (a cross-tenant leak). FULL ⟺ a
 *       root-prefix grant falls straight out of {@code coversTarget} ({@code "".startsWith(A.prefix)}
 *       holds only when {@code A.prefix == ""}).</li>
 *   <li><b>{@code KEY} → {@link AclService#isAllowed}{@code (…, WATCH)} (the exact-key floor).</b>
 *       {@code isAllowed} already enforces effective-{@code WATCH} = {@code WATCH ∧ READ} on the exact
 *       key, unioning only that key's <em>ancestor</em> grants. It MUST NOT use the whole-subtree
 *       {@code authorizesWatch}/{@code coversTarget}: that predicate's <b>interior-DENY</b> term would
 *       wrongly reject an exact-key watch on {@code a.b} when a {@code DENY} exists on a
 *       <em>descendant</em> key {@code a.b.c} — a different key, outside what a {@code KEY} target
 *       covers (a {@code KEY} target covers the one exact path, never a subtree). Over-restricting a
 *       single-key watch by a descendant carve-out is a false reject; the ancestor-only union is the
 *       correct, faithful floor for the exact key.</li>
 *   <li><b>{@code PREFIX} (subtree) → {@link AclService#authorizesWatch} over the target prefix.</b>
 *       A subtree watch must be {@code READ ∧ WATCH}-covered at <b>every</b> key under the prefix, so
 *       an interior {@code DENY} carving a hole inside the subtree MUST reject the whole watch (W7-2a)
 *       — exactly what {@code coversTarget}'s interior-DENY term enforces and a single-key
 *       {@code isAllowed} structurally cannot see.</li>
 * </ol>
 *
 * <p><b>Reject, not filter (W7-2).</b> Every branch authorizes the <b>whole</b> target or returns
 * {@code false}; the adapter never narrows an over-broad target to its authorized subset. The
 * per-watch routing filter (the veneer's {@code WatchTarget.matches}) is a downstream, distinct
 * concern that runs only over an already-authorized target.
 *
 * <p>The asserted-roles {@code Set} is {@link Set#of()} on the cert-DN edge path; this adapter (via
 * {@link AclService#effectiveRules} / {@link AclService#isAllowed}) resolves the principal's
 * ACL-static and config-bound roles internally, so the role union is identical to the HTTP admin
 * path. The adapter is stateless and thread-safe (the {@code AclService} reads are lock-free
 * snapshot reads); fail-closed behaviour for {@code null}/unauthenticated principals and thrown
 * exceptions is the {@code FanOutConnectionDriver}'s gate, not this adapter's (it returns a plain
 * boolean verdict).
 */
public final class AclServiceWatchAuthorizer implements WatchAuthorizer {

    private final AclService aclService;

    /**
     * @param aclService the in-core ACL evaluator (the SAME instance the HTTP admin path uses, so the
     *                   watch gate and the admin gate decide identically); never {@code null}
     */
    public AclServiceWatchAuthorizer(AclService aclService) {
        this.aclService = Objects.requireNonNull(aclService, "aclService");
    }

    @Override
    public boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target) {
        // (1) FULL / full_chain_verify → root effective target ("") before the whole-subtree cover.
        if (target.fullChainVerify() || target.isFull()) {
            return aclService.authorizesWatch(aclService.effectiveRules(principal, roles), "");
        }
        // (2) KEY → the exact-key floor (WATCH ∧ READ on the one key). NOT coversTarget: its
        // interior-DENY term would wrongly reject an exact-key watch over a descendant-key DENY.
        if (target.targetKind() == EdgeFrame.WATCH_TARGET_KEY) {
            return aclService.isAllowed(principal, roles, target.path(), Permission.WATCH);
        }
        // (3) PREFIX (subtree) → whole-subtree cover (READ ∧ WATCH over all of it, interior-DENY rejects).
        return aclService.authorizesWatch(aclService.effectiveRules(principal, roles), target.path());
    }
}

package io.configd.distribution.fanout;

import java.util.Set;

/**
 * The watch-authorization SPI (RFC §2 W7) — the security gate the watch veneer calls at
 * {@code WATCH_CREATE} time, before any data frame is emitted for a watch.
 *
 * <p><b>Module seam (LOCKED).</b> {@code configd-distribution-service} (the fan-out plane)
 * is built <em>before</em> {@code configd-control-plane-api} in the reactor and therefore
 * cannot see {@code AclService}. The veneer depends only on this SPI; the real adapter
 * ({@code AclServiceWatchAuthorizer}) lives in {@code configd-server} (which depends on
 * both) and is wired by {@code ConfigdServer} in a later increment. This mirrors the
 * existing SPI discipline in the codebase (auth-SPI, KMS-SPI, {@code NettyTransport.select}):
 * fail-loud on misconfiguration, <b>fail-closed</b> on any doubt.
 *
 * <p><b>Whole-target semantics (W7-2 / W7-2a).</b> An implementation MUST authorize the
 * <em>whole</em> target — {@code READ ∧ WATCH} covering <b>all</b> of it — and MUST reject
 * (not silently narrow) an over-broad target. A PREFIX/FULL target with an intersecting
 * interior {@code DENY} MUST be rejected. {@code full_chain_verify}/{@code FULL} targets
 * (see {@link WatchTarget#fullChainVerify()} / {@link WatchTarget#isFull()}) require a
 * root-scope grant (W7-3) — the adapter maps them to the empty (root) effective target
 * before evaluation.
 *
 * <p><b>Fail-closed contract.</b> The veneer treats a {@code null} authorizer, an
 * unauthenticated principal ({@code "plaintext"}), a {@code false} return, <b>and any
 * throwable</b> thrown by an implementation all as <b>deny</b> — and rejects the watch with
 * {@code WATCH_CANCELED(NOT_AUTHORIZED)} and zero preceding data frames (W7-5). An
 * implementation therefore need not catch its own exceptions for safety, but SHOULD avoid
 * throwing for an ordinary "not authorized" outcome (return {@code false}).
 */
@FunctionalInterface
public interface WatchAuthorizer {

    /**
     * Decides whether {@code principal} (with the asserted {@code roles}) may WATCH the
     * <b>whole</b> {@code target}.
     *
     * @param principal the authenticated identity (the mTLS cert-DN on the edge path);
     *                  never {@code null}
     * @param roles     the principal's asserted roles ({@link Set#of()} on the cert-DN edge
     *                  path, where the adapter resolves ACL-static / config-bound roles
     *                  internally); never {@code null}
     * @param target    the watch target ({@code scope}, kind, path, {@code full_chain_verify})
     * @return {@code true} iff the principal holds {@code READ ∧ WATCH} over <b>all</b> of
     *         {@code target}; {@code false} to deny. The caller treats any thrown
     *         {@link Throwable} as {@code false} (fail-closed).
     */
    boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target);
}

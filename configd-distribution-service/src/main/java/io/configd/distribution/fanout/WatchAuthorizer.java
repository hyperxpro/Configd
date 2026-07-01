package io.configd.distribution.fanout;

import java.util.Set;

/**
 * The watch-authorization SPI (W7) - the security gate the watch veneer calls at
 * {@code WATCH_CREATE} time, before any data frame is emitted for a watch.
 *
 * <p><b>Module seam (LOCKED).</b> {@code configd-distribution-service} (the fan-out plane)
 * is built before {@code configd-control-plane-api} in the reactor and therefore cannot see
 * {@code AclService}. The veneer depends only on this SPI; the real adapter
 * ({@code AclServiceWatchAuthorizer}) lives in {@code configd-server} (which depends on
 * both) and is wired by {@code ConfigdServer}. This mirrors the existing SPI discipline in
 * the codebase (auth-SPI, KMS-SPI, {@code NettyTransport.select}): fail-loud on
 * misconfiguration, <b>fail-closed</b> on any doubt.
 *
 * <p><b>Whole-target semantics (W7-2 / W7-2a).</b> An implementation MUST authorize the
 * whole target - {@code READ} and {@code WATCH} covering <b>all</b> of it - and MUST reject
 * (not silently narrow) an over-broad target. A PREFIX/FULL target with an intersecting
 * interior {@code DENY} MUST be rejected. {@code full_chain_verify}/{@code FULL} targets
 * (see {@link WatchTarget#fullChainVerify()} / {@link WatchTarget#isFull()}) require a
 * root-scope grant (W7-3) - the adapter maps them to the empty (root) effective target
 * before evaluation.
 *
 * <p><b>Fail-closed contract.</b> The veneer treats a {@code null} authorizer, an
 * unauthenticated principal ({@code "plaintext"}), a {@code false} return, <b>and any
 * throwable</b> thrown by an implementation all as <b>deny</b> - and rejects the watch with
 * {@code WATCH_CANCELED(NOT_AUTHORIZED)} and zero preceding data frames (W7-5). An
 * implementation need not catch its own exceptions for safety, but SHOULD avoid throwing
 * for an ordinary "not authorized" outcome (return {@code false}).
 *
 * <p><b>Legacy full-store SUBSCRIBE.</b> The same SPI also gates the pre-existing whole-store
 * {@code SUBSCRIBE} hydration feed via {@link #authorizeSubscribe}. That default fails closed too, so
 * an implementation that does not speak subscribe-authorization denies the feed. The one asymmetry
 * with the watch path lives in the caller, not here: the driver admits {@code SUBSCRIBE} when the
 * authorizer is {@code null} (an unauthenticated deployment has no principal model to evaluate),
 * whereas a {@code null} authorizer rejects every {@code WATCH_CREATE}.
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

    /**
     * Decides whether {@code principal} (with the asserted {@code roles}) may open a legacy full-store
     * {@code SUBSCRIBE} - the server-to-edge whole-store hydration feed (ADR-0038). A full-store
     * {@code SUBSCRIBE} is a streaming read of the ENTIRE store, so it must never expose what a read
     * could not: an implementation authorizes it only against the degenerate whole-target
     * {@code READ} cover over the root prefix {@code ""} (a root-prefix {@code READ} grant with no
     * intersecting {@code READ} deny anywhere), the same {@code READ} half a {@code full_chain_verify}
     * watch requires. {@code WATCH} capability is deliberately NOT required - a {@code SUBSCRIBE} is a
     * read feed, not a watch.
     *
     * <p>The default fails CLOSED: an implementation that does not speak subscribe-authorization denies
     * the whole-store feed. The caller treats a {@code false} return <b>and any thrown
     * {@link Throwable}</b> as deny; a {@code null} authorizer is the caller's separate auth-off admit
     * (see the class-level note).
     *
     * @param principal the authenticated identity (the mTLS cert-DN on the edge path); never {@code null}
     * @param roles     the principal's asserted roles ({@link Set#of()} on the cert-DN edge path, where
     *                  the adapter resolves ACL-static / config-bound roles internally); never {@code null}
     * @return {@code true} iff the principal holds {@code READ} over the WHOLE store (the root prefix
     *         {@code ""}); the default {@code false} denies (fail-closed)
     */
    default boolean authorizeSubscribe(String principal, Set<String> roles) {
        return false;
    }

    /**
     * The current authorization-policy version - a monotonic counter the veneer polls to drive
     * <b>bounded watch revocation</b> (W7-7). The veneer caches the version each live watch was
     * last authorized at and, when this value <b>advances</b>, re-runs {@link #authorizeWatch} for every
     * live watch on the connection, force-closing any whose principal no longer holds {@code READ ∧
     * WATCH} over its target - within a bounded latency of the policy change. When the version is
     * unchanged the veneer does no re-authorization work (a single comparison per tick), so a policy
     * that never changes (the production default, no {@code _acl/} keys) costs nothing.
     * <p>
     * The default is the constant {@code 0} - an implementation that does not expose a policy version
     * (e.g. a fixed test authorizer) thus never triggers re-authorization. The production adapter
     * overrides this with the live {@code AclService} config-policy version.
     *
     * @return a monotonic policy version; the default {@code 0} means "never changes"
     */
    default long policyVersion() {
        return 0L;
    }
}

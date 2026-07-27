package io.configd.distribution.fanout;

import java.util.Set;

/**
 * The watch-authorization SPI - the security gate the watch veneer calls at
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
 * <p><b>Whole-target semantics.</b> An implementation MUST authorize the
 * whole target - {@code READ} and {@code WATCH} covering <b>all</b> of it - and MUST reject
 * (not silently narrow) an over-broad target. A PREFIX/FULL target with an intersecting
 * interior {@code DENY} MUST be rejected. {@code full_chain_verify}/{@code FULL} targets
 * (see {@link WatchTarget#fullChainVerify()} / {@link WatchTarget#isFull()}) require a
 * root-scope grant - the adapter maps them to the empty (root) effective target
 * before evaluation.
 *
 * <p><b>Fail-closed contract.</b> The veneer treats a {@code null} authorizer, an
 * unauthenticated principal ({@code "plaintext"}), a {@code false} return, <b>and any
 * throwable</b> thrown by an implementation all as <b>deny</b> - and rejects the watch with
 * {@code WATCH_CANCELED(NOT_AUTHORIZED)} and zero preceding data frames. An
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

    boolean authorizeWatch(String principal, Set<String> roles, WatchTarget target);

    default boolean authorizeSubscribe(String principal, Set<String> roles) {
        return false;
    }

    default long policyVersion() {
        return 0L;
    }
}

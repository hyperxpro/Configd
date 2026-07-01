package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;

import java.util.Objects;

/**
 * The target of a watch (W2-2): the {@code (scope, kind, path)} a watch subscribes to, plus
 * the {@code full_chain_verify} delivery-mode flag (W8-4). A small, dependency-free value
 * type - it deliberately carries the scope as a raw {@code int} (not a {@code ConfigScope})
 * so the fan-out plane stays decoupled from the config-store / api scope enum and so it can
 * be passed verbatim to the {@link WatchAuthorizer} SPI.
 *
 * <p>This type couples the authorization surface and the per-watch routing surface but keeps
 * them <b>distinct</b> (section 7 vs section 5 of the RFC):
 * <ul>
 *   <li><b>Authorization</b> (the gate) consumes the whole target via {@link WatchAuthorizer}
 *       - authorize all of it or reject (W7-2). The {@code full_chain_verify}/FULL flag
 *       routes the adapter to the root effective target (W7-3).</li>
 *   <li><b>Routing</b> (the per-watch filter, W5-6) consumes {@link #matches(String)} - a
 *       literal key match (KEY exact, PREFIX {@code startsWith}, FULL all), the same literal
 *       model as the ACL {@code PolicyRule}. Filtering NEVER authorizes; the gate already
 *       authorized the whole target (so a FULL / {@code full_chain_verify} watch matches all
 *       keys precisely because it was gated by a root-scope grant, W7-2).</li>
 * </ul>
 *
 * @param scope           the {@code ConfigScope} ordinal as a raw {@code int} (0=GLOBAL,
 *                        1=REGIONAL, 2=LOCAL)
 * @param targetKind      {@link EdgeFrame#WATCH_TARGET_KEY} / {@link EdgeFrame#WATCH_TARGET_PREFIX}
 *                        / {@link EdgeFrame#WATCH_TARGET_FULL}
 * @param path            the canonical path (empty for FULL); the literal match prefix/key
 * @param fullChainVerify the untrusted-edge verbatim mode flag (W8-4); requires root scope
 *                        (W7-3) and in v1 is served as a FULL key stream
 */
public record WatchTarget(int scope, int targetKind, String path, boolean fullChainVerify) {

    public WatchTarget {
        Objects.requireNonNull(path, "path must not be null");
        // Structural-only: the kind must name a real target form. Scope/kind RANGE and the
        // path grammar is validated upstream (WatchTargetValidator) and surfaced as
        // BAD_SUBSCRIBE; here we only forbid building a structurally-nonsense target.
        if (targetKind != EdgeFrame.WATCH_TARGET_KEY
                && targetKind != EdgeFrame.WATCH_TARGET_PREFIX
                && targetKind != EdgeFrame.WATCH_TARGET_FULL) {
            throw new IllegalArgumentException("unknown targetKind: " + targetKind);
        }
        if (targetKind == EdgeFrame.WATCH_TARGET_FULL && !path.isEmpty()) {
            throw new IllegalArgumentException("FULL target must carry an empty path");
        }
    }

    /** True iff this is a FULL (whole-scope / root) target. */
    public boolean isFull() {
        return targetKind == EdgeFrame.WATCH_TARGET_FULL;
    }

    /**
     * True iff this target matches <b>every</b> key - FULL or {@code full_chain_verify} (W8-4) -
     * so a catch-up snapshot needs <b>no</b> filtering (it was gated by a root-scope grant, W7-3).
     * Mirrors the {@link #matches(String)} short-circuit; lets {@link FilteringReplaySource} skip
     * the snapshot rebuild for a whole-store-authorized watch.
     */
    public boolean isMatchAll() {
        return fullChainVerify || isFull();
    }

    /**
     * The per-watch routing filter (W5-6) - true iff a mutation on {@code key} belongs to
     * this target. This is <b>routing, not authorization</b> (the gate already authorized
     * the whole target):
     * <ul>
     *   <li>{@code full_chain_verify} or FULL => every key matches (the root-gated full stream);</li>
     *   <li>KEY => exact literal equality;</li>
     *   <li>PREFIX => literal {@code startsWith} (the subtree form {@code /a/} matches
     *       {@code /a/b}; the same literal model as {@code PolicyRule.matches}).</li>
     * </ul>
     */
    public boolean matches(String key) {
        if (fullChainVerify || isFull()) {
            return true;
        }
        if (targetKind == EdgeFrame.WATCH_TARGET_KEY) {
            return key.equals(path);
        }
        if (targetKind == EdgeFrame.WATCH_TARGET_PREFIX) {
            return key.startsWith(path);
        }
        return false; // unreachable post-construction; fail-closed
    }
}

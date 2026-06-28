package io.configd.namespace;

import java.util.List;

/**
 * The watch-authorization contract (access-control.md §6; RFC §6) — the gap the watch research left open,
 * closed. A watch is authorized AT SUBSCRIPTION as a streaming read; over-broad targets are REJECTED (not
 * silently filtered); a {@code full_chain_verify}/{@code FULL} watch requires a ROOT-scope grant.
 *
 * <p>This is the enforcement the edge fan-out subscribe path runs, using {@link PolicySet} over the same
 * replicated policy as the control plane (§7). It returns a {@link Decision} BEFORE any data frame flows.
 */
public final class WatchAuthz {

    private WatchAuthz() {}

    /** The watch target (RFC §6): a single key, a subtree, or the whole store. */
    public sealed interface Target permits KeyTarget, SubtreeTarget, FullTarget {
        PathPattern asPattern();
    }

    /** A single-key watch — one shard. */
    public record KeyTarget(ConfigPath key) implements Target {
        @Override public PathPattern asPattern() { return new PathPattern.Exact(key.value()); }
    }

    /** A prefix/subtree watch — scatters across all shards. */
    public record SubtreeTarget(String prefix) implements Target {
        @Override public PathPattern asPattern() { return PathPattern.parse(prefix); }
    }

    /** The full-store watch — equivalent to the root subtree {@code /**}. */
    public record FullTarget() implements Target {
        @Override public PathPattern asPattern() { return new PathPattern.Subtree(""); }
    }

    /** The subscription-time decision; {@code reason} is for audit on a reject (§8). */
    public record Decision(boolean allowed, String reason) {
        static Decision allow() { return new Decision(true, "ok"); }
        static Decision deny(String reason) { return new Decision(false, reason); }
    }

    /**
     * Authorizes a watch subscription (RFC A6-1…A6-4). Returns {@link Decision#allowed()} == false to be
     * mapped to a terminal 403-class reject with ZERO data frames emitted first (A6-5).
     *
     * @param fullChainVerify the untrusted-edge flag (watch RFC §7): streams the whole signed chain
     *                        verbatim with no edge filtering, so it REQUIRES root scope (A6-3).
     */
    public static Decision authorizeWatch(List<PolicyRule> rules, Scope scope, Target target,
                                          boolean fullChainVerify) {
        // A6-3: full_chain_verify OR a FULL target requires READ ∧ WATCH over the ROOT (/**).
        boolean rootRequired = fullChainVerify || target instanceof FullTarget;
        PathPattern effective = rootRequired ? new PathPattern.Subtree("") : target.asPattern();

        // A6-1 / R-CAP-2: a watch requires BOTH READ and WATCH over the ENTIRE (effective) target.
        if (!PolicySet.coversTarget(rules, scope, effective, Capability.READ)) {
            return Decision.deny(rootRequired
                    ? "full_chain_verify/FULL requires READ over root /** (A6-3)"
                    : "watch target exceeds READ grant — rejected, not filtered (A6-2)");
        }
        if (!PolicySet.coversTarget(rules, scope, effective, Capability.WATCH)) {
            return Decision.deny(rootRequired
                    ? "full_chain_verify/FULL requires WATCH over root /** (A6-3)"
                    : "watch target exceeds WATCH grant — rejected, not filtered (A6-2)");
        }
        return Decision.allow();
    }

    /** Authorizes a {@code list} over a prefix (RFC A4-6): same whole-target check, capability LIST. */
    public static Decision authorizeList(List<PolicyRule> rules, Scope scope, String prefix) {
        PathPattern target = PathPattern.parse(prefix);
        return PolicySet.coversTarget(rules, scope, target, Capability.LIST)
                ? Decision.allow()
                : Decision.deny("list target exceeds LIST grant — rejected, not filtered (A4-6)");
    }
}

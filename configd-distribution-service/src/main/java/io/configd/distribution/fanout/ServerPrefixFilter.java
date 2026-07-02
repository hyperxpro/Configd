package io.configd.distribution.fanout;

import io.configd.distribution.CommitNotification;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.store.ConfigMutation;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The server-side keep/drop predicate for a filtered SUBSCRIBE drain (ADR-0045, Track 1). One
 * instance per filtering session, built from the edge's subscribed prefix set and the
 * deployment's strong-read prefixes. It decides whether a whole signed delta is delivered to
 * this edge or dropped; it NEVER rewrites or coalesces a delta (ADR-0038 leg (a) stands), so
 * the delivered NOTIFY still carries verbatim leader-signed bytes.
 *
 * <p>A delta is <b>kept</b> iff any of its mutations touches a key that either matches a
 * subscribed prefix or is a strong-read key. The strong-read carve-out mirrors the edge's
 * {@code PrefixStorageFilter} always-store rule: strong-read keys are shipped to every edge
 * regardless of its prefix set, so a narrow edge still holds them for suppression-detectability.
 * Matching is literal {@code startsWith}, the same predicate the edge uses.
 *
 * <p>Stateless and immutable after construction; the session core calls it on its single
 * session-loop thread.
 */
final class ServerPrefixFilter {

    private final List<String> prefixes;
    private final Set<String> strongReadPrefixes;

    /**
     * @param prefixes           the edge's subscribed key prefixes (non-empty when this filter
     *                           is installed - an empty set / full-store subscription installs no
     *                           filter at all)
     * @param strongReadPrefixes the always-shipped key prefixes (may be empty)
     */
    ServerPrefixFilter(List<String> prefixes, Set<String> strongReadPrefixes) {
        this.prefixes = List.copyOf(prefixes);
        this.strongReadPrefixes = Set.copyOf(strongReadPrefixes);
    }

    /**
     * Whether a session subscribing with {@code subscribe} filters server-side under {@code
     * config}: the posture is on, the edge opted in, and the subscription is a non-empty prefix
     * set (full-store / empty-prefix is match-all, so filtering would be inert). The single
     * source of the filter-active decision, shared by the drain and the snapshot path.
     */
    static boolean isActive(FanOutConfig config, EdgeFrame.Subscribe subscribe) {
        return config.serverSidePrefixFilter()
                && subscribe.acceptsFiltered()
                && !subscribe.fullStore()
                && !subscribe.prefixes().isEmpty();
    }

    /**
     * The key-level keep predicate (subscribed-prefix match OR strong-read key), for filtering
     * the catch-up snapshot the same way the live tail is filtered ({@link FilteringReplaySource}).
     */
    Predicate<String> keyPredicate() {
        return key -> matchesPrefix(key) || isStrongRead(key);
    }

    /**
     * True iff this delta must be delivered to the edge: it touches a subscribed-prefix key or
     * a strong-read key. A delta with no matching mutation is dropped whole (the cursor still
     * advances over it - the edge learns the covered position from the HEARTBEAT).
     */
    boolean keep(CommitNotification notification) {
        for (ConfigMutation m : notification.delta().mutations()) {
            String key = m.key();
            if (matchesPrefix(key) || isStrongRead(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrefix(String key) {
        for (String p : prefixes) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStrongRead(String key) {
        for (String p : strongReadPrefixes) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}

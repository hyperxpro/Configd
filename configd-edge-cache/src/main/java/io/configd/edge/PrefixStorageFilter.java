package io.configd.edge;

import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Edge-side prefix <b>storage</b> filter.
 * <p>
 * The full signed delta chain is delivered to every edge (transport filtering is
 * forbidden - a relay must not be able to suppress keys undetectably). Signature
 * verification therefore happens over the <b>original</b> delta (in
 * {@link DeltaApplier}, byte-fidelity preserved). <em>After</em> verification, the
 * subscription is applied as a storage filter: only mutations whose key matches the
 * subscription set are stored; non-matching mutations are dropped from the stored payload
 * but the <b>chain version still advances</b> (same {@code fromVersion}/{@code toVersion},
 * a subset of mutations), so gap detection and monotonicity are unaffected.
 *
 * <h2>Filtering rule</h2>
 * A mutation is stored iff EITHER:
 * <ul>
 *   <li>the subscription set is empty (full-store subscription - store everything); OR</li>
 *   <li>the mutation's key matches a subscribed prefix; OR</li>
 *   <li>the key is a {@link StrongReadKeyClass strong-read key} - ALWAYS stored regardless
 *       of subscription (store-and-fail-closed-serve): the suppression detectability the
 *       signed chain provides requires the edge to hold these keys; the serving refusal is
 *       the edge process's job.</li>
 * </ul>
 *
 * <h2>Why a separate stateless helper</h2>
 * The filtered delta must be applied identically to BOTH the {@link EdgeConfigClient}'s
 * internal store AND the monitor-wired read store the {@link EdgeClientCore} keeps in
 * lockstep (so they stay byte-identical and the monotonic-read seam stays live). A single
 * pure function computes the filtered delta once; both stores receive the same result.
 *
 * <p>This class is stateless and thread-safe.
 */
final class PrefixStorageFilter {

    private final PrefixSubscription subscriptions;
    private final StrongReadKeyClass strongReadKeyClass;

    /**
     * @param subscriptions      the edge's prefix subscription (empty prefixes = full store)
     * @param strongReadKeyClass the strong-read key-class predicate (always-store keys)
     */
    PrefixStorageFilter(PrefixSubscription subscriptions, StrongReadKeyClass strongReadKeyClass) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
        this.strongReadKeyClass =
                Objects.requireNonNull(strongReadKeyClass, "strongReadKeyClass must not be null");
    }

    /**
     * Returns a delta carrying only the mutations this edge should store, preserving
     * {@code fromVersion}/{@code toVersion} so the chain version advances even when every
     * mutation is filtered out (an empty-mutation delta is valid - it just bumps the
     * version). The returned delta is unsigned/legacy form: it is never re-verified (the
     * original already was) and never re-serialized over the wire - it exists only to
     * drive {@link LocalConfigStore#applyDelta}.
     * <p>
     * If no mutation is filtered (full-store subscription, or every key matches), the
     * original delta is returned unchanged to avoid an allocation on the common path.
     *
     * @param delta the original, already-verified delta (non-null)
     * @return the delta to apply to the store (same versions, filtered mutations)
     */
    ConfigDelta filter(ConfigDelta delta) {
        Objects.requireNonNull(delta, "delta must not be null");

        // Empty subscription set = full-store: store everything, nothing to filter.
        if (subscriptions.prefixes().isEmpty()) {
            return delta;
        }

        List<ConfigMutation> kept = null; // lazily allocated only if we actually drop one
        List<ConfigMutation> mutations = delta.mutations();
        for (int i = 0; i < mutations.size(); i++) {
            ConfigMutation m = mutations.get(i);
            boolean store = shouldStore(m.key());
            if (store) {
                if (kept != null) {
                    kept.add(m);
                }
            } else {
                if (kept == null) {
                    // First drop: materialize the prefix that was all-kept so far.
                    kept = new ArrayList<>(mutations.size());
                    kept.addAll(mutations.subList(0, i));
                }
                // skip m - non-matching, non-strong-read: not stored, version still advances
            }
        }

        if (kept == null) {
            // Nothing dropped - keep the original (no allocation).
            return delta;
        }
        // Versions preserved so the chain advances; legacy unsigned form (never re-verified).
        return new ConfigDelta(delta.fromVersion(), delta.toVersion(), kept);
    }

    /** True if a key must be stored: subscribed-prefix match OR a strong-read key. */
    private boolean shouldStore(String key) {
        return subscriptions.matches(key) || strongReadKeyClass.isStrongReadKey(key);
    }
}

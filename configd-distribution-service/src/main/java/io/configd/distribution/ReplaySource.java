package io.configd.distribution;

import io.configd.store.ConfigSnapshot;

/**
 * The authoritative recovery path a consumer uses after
 * {@link CommitNotificationSource#readSince(long)} returns a GAP.
 *
 * <p>The {@link CommitNotificationSource} (a bounded ring) is a hot-path cache;
 * the {@link ReplaySource} is the source of truth, backed by the durable
 * log+snapshot. The durable prefix reconstructs ALL committed state, so any
 * notification the cache evicted is still recoverable here.
 *
 * <h2>Contract</h2>
 * A {@link Replay} delivers a <b>snapshot-equivalent state at sequence S</b> plus
 * the floor seq from which the consumer resumes cursor-based tailing on the
 * {@link CommitNotificationSource}. Concretely: the consumer applies the snapshot
 * wholesale (it already encodes the cumulative effect of every committed mutation
 * up to {@code S}), sets its cursor to {@code S}, then calls
 * {@code readSince(S)} to tail forward. This is exactly-once over effect:
 * the consumer observes every committed mutation's effect on the store, even
 * across an overflow, with no hole and no duplicate application.
 *
 * <h2>Why snapshot-equivalent (not full historical-log replay)</h2>
 * Replaying the current store state as a snapshot at its current version is
 * sufficient for this contract and far cheaper than reconstructing the full
 * historical delta sequence from the WAL: the edge data plane is
 * eventually-consistent and applies cumulative state, not an audited
 * mutation-by-mutation log. A consumer that snapshots at S and tails from S sees
 * every later mutation individually and every earlier mutation folded into the
 * snapshot. Full per-mutation historical replay (WAL scan) is heavier and buys
 * the data plane nothing it can observe. If a future auditing consumer needs the
 * exact historical mutation stream, that is a separate, WAL-backed replay seam.
 */
public interface ReplaySource {

    Replay replayFromSnapshot();

    record Replay(ConfigSnapshot snapshot, long seq) {
        public Replay {
            if (snapshot == null) {
                throw new IllegalArgumentException("snapshot must not be null");
            }
            if (seq < 0) {
                throw new IllegalArgumentException("seq must be non-negative: " + seq);
            }
        }
    }
}

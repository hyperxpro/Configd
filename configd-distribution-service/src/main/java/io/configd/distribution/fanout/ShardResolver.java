package io.configd.distribution.fanout;

/**
 * Resolves a watch target to the shard group ids its keys can live on - the coverage input the
 * multi-shard coordinator narrows each watch's client-facing {@code WATCH_CREATED} /
 * {@code WATCH_PROGRESS} vector by. Coverage is driven by the target (never by the client's cursor):
 * <ul>
 *   <li>a KEY target hashes to <b>exactly one</b> shard for the cluster's lifetime;</li>
 *   <li>a PREFIX / FULL / {@code full_chain_verify} target is not hash-contiguous, so it
 *       <b>scatters across every</b> shard.</li>
 * </ul>
 *
 * <p>The interface lives in the transport-agnostic fan-out plane; the server implements it over its
 * {@code ShardMap} (KEY -&gt; {@code shardFor(scope, path)}; else all {@code shardIds()}), so the
 * fan-out plane stays decoupled from the replication engine's routing. At {@code N = 1} every target
 * resolves to the single-element set {@code {0}} (byte-identical to the single-shard drain).
 */
@FunctionalInterface
public interface ShardResolver {

    /**
     * The shard group ids this target's keys can live on. The returned ids MUST be a subset of the
     * connection's shard set and ascending by gid (the coalesced {@code WATCH_CREATED} /
     * {@code WATCH_PROGRESS} vectors inherit that order, satisfying the {@code WatchCursor}
     * strict-ascending invariant naturally).
     *
     * @param target the authorized watch target (whole-logical-path; scope/kind/path)
     * @return the covered gids, ascending; a single element for KEY, all shards for PREFIX/FULL
     */
    int[] coveredGids(WatchTarget target);
}

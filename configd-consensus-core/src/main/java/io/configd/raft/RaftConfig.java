package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for a single {@link RaftNode}.
 *
 * <p><b>Timing units (RR-006).</b> The {@code ...Ms}-named fields are real
 * milliseconds. {@link RaftNode} is tick-driven and does not see wall-clock
 * time, so it converts each duration into a tick count via {@code tickPeriodMs}
 * (the period of one {@code RaftNode.tick()} call as scheduled by the caller).
 * Production schedules ticks every 10&nbsp;ms ({@code ConfigdServer.TICK_PERIOD_MS});
 * the deterministic simulation harness ticks every 1&nbsp;ms. Setting
 * {@code tickPeriodMs} to that period makes the documented millisecond values
 * the values actually realized at runtime. Before RR-006 the {@code ...Ms}
 * values were consumed directly as tick counts, inflating every interval by the
 * tick period (10&times; in production: a documented 150–300&nbsp;ms election
 * timeout became 1.5–3&nbsp;s).
 *
 * @param nodeId              this node's unique identifier
 * @param peers               the set of peer node identifiers (excluding this node)
 * @param electionTimeoutMinMs minimum election timeout in milliseconds (default 150)
 * @param electionTimeoutMaxMs maximum election timeout in milliseconds (default 300)
 * @param heartbeatIntervalMs  heartbeat interval in milliseconds (default 50)
 * @param maxBatchSize         maximum number of entries per AppendEntries RPC (default 64)
 * @param maxBatchBytes        maximum total bytes per AppendEntries RPC (default 256 KB)
 * @param maxPendingProposals  maximum uncommitted entries before rejecting proposals (default 1024)
 * @param maxInflightAppends   maximum in-flight AppendEntries RPCs per peer (default 10)
 * @param tickPeriodMs         milliseconds represented by one {@code RaftNode.tick()};
 *                             the divisor used to convert the {@code ...Ms} durations
 *                             into tick counts (production 10&nbsp;ms, simulation 1&nbsp;ms)
 */
public record RaftConfig(
        NodeId nodeId,
        Set<NodeId> peers,
        int electionTimeoutMinMs,
        int electionTimeoutMaxMs,
        int heartbeatIntervalMs,
        int maxBatchSize,
        int maxBatchBytes,
        int maxPendingProposals,
        int maxInflightAppends,
        int tickPeriodMs
) {

    public RaftConfig {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(peers, "peers must not be null");
        peers = Set.copyOf(peers); // defensive copy, unmodifiable
        if (electionTimeoutMinMs <= 0) {
            throw new IllegalArgumentException("electionTimeoutMinMs must be positive: " + electionTimeoutMinMs);
        }
        if (electionTimeoutMaxMs < electionTimeoutMinMs) {
            throw new IllegalArgumentException("electionTimeoutMaxMs must be >= electionTimeoutMinMs");
        }
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be positive: " + heartbeatIntervalMs);
        }
        if (heartbeatIntervalMs >= electionTimeoutMinMs) {
            throw new IllegalArgumentException(
                    "heartbeatIntervalMs (" + heartbeatIntervalMs + ") must be < electionTimeoutMinMs ("
                            + electionTimeoutMinMs + ")");
        }
        if (tickPeriodMs <= 0) {
            throw new IllegalArgumentException("tickPeriodMs must be positive: " + tickPeriodMs);
        }
        // RR-006: validate the relationship that actually governs liveness — the
        // *derived tick counts*, not the raw millisecond values. After rounding,
        // the minimum election timeout must still be strictly more than the
        // heartbeat interval by a safety factor so a leader can emit several
        // heartbeats within one election window; otherwise followers time out
        // before the leader can refresh them and the cluster live-locks in
        // perpetual elections. Mirrors etcd/raft's election:heartbeat >= ~10
        // guidance applied at the resolution the node actually runs at.
        int heartbeatTicks = Math.max(1, Math.round((float) heartbeatIntervalMs / tickPeriodMs));
        int electionMinTicks = Math.max(1, Math.round((float) electionTimeoutMinMs / tickPeriodMs));
        if (electionMinTicks < heartbeatTicks * MIN_ELECTION_HEARTBEAT_TICK_RATIO) {
            throw new IllegalArgumentException(
                    "derived election-timeout ticks (" + electionMinTicks + " = " + electionTimeoutMinMs
                            + "ms/" + tickPeriodMs + "ms) must be >= " + MIN_ELECTION_HEARTBEAT_TICK_RATIO
                            + "x the derived heartbeat ticks (" + heartbeatTicks + " = " + heartbeatIntervalMs
                            + "ms/" + tickPeriodMs + "ms); election timeout would fire before the leader can"
                            + " refresh followers (tickPeriodMs too coarse for these durations)");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive: " + maxBatchSize);
        }
        if (maxBatchBytes <= 0) {
            throw new IllegalArgumentException("maxBatchBytes must be positive: " + maxBatchBytes);
        }
        if (maxPendingProposals <= 0) {
            throw new IllegalArgumentException("maxPendingProposals must be positive: " + maxPendingProposals);
        }
        if (maxInflightAppends <= 0) {
            throw new IllegalArgumentException("maxInflightAppends must be positive: " + maxInflightAppends);
        }
    }

    /**
     * Minimum ratio of the derived minimum-election-timeout tick count to the
     * derived heartbeat tick count. A leader must be able to emit several
     * heartbeats inside one election window; 3 is a conservative floor (the
     * default 150&nbsp;ms/50&nbsp;ms gives exactly 3) below which the cluster is
     * prone to election storms.
     */
    static final int MIN_ELECTION_HEARTBEAT_TICK_RATIO = 3;

    /**
     * Minimum election timeout expressed in ticks: {@code electionTimeoutMinMs}
     * divided by {@code tickPeriodMs}, rounded to nearest, floored at 1.
     */
    public int electionTimeoutMinTicks() {
        return toTicks(electionTimeoutMinMs);
    }

    /**
     * Maximum election timeout expressed in ticks: {@code electionTimeoutMaxMs}
     * divided by {@code tickPeriodMs}, rounded to nearest, floored at 1.
     */
    public int electionTimeoutMaxTicks() {
        return toTicks(electionTimeoutMaxMs);
    }

    /**
     * Heartbeat interval expressed in ticks: {@code heartbeatIntervalMs} divided
     * by {@code tickPeriodMs}, rounded to nearest, floored at 1.
     */
    public int heartbeatIntervalTicks() {
        return toTicks(heartbeatIntervalMs);
    }

    /**
     * Converts a millisecond duration into a tick count at this config's tick
     * period: {@code round(ms / tickPeriodMs)} with a floor of 1 so no interval
     * collapses to zero ticks (which would fire every tick).
     */
    private int toTicks(int ms) {
        return Math.max(1, Math.round((float) ms / tickPeriodMs));
    }

    /**
     * Total number of nodes in the cluster (this node + peers).
     */
    public int clusterSize() {
        return peers.size() + 1;
    }

    /**
     * Majority quorum size: floor(clusterSize/2) + 1.
     */
    public int quorumSize() {
        return clusterSize() / 2 + 1;
    }

    /**
     * Convenience builder with default durations and a {@code tickPeriodMs} of 1,
     * i.e. one tick == one millisecond.
     *
     * <p>This is the form used by the deterministic simulation harness and unit
     * tests, which advance time one millisecond per {@code tick()}. With a 1&nbsp;ms
     * tick the {@code ...Ms} durations map one-to-one onto tick counts (150&nbsp;ms
     * &rarr; 150 ticks), exactly as before RR-006, so simulation schedules remain
     * byte-identical. Production must instead use {@link #of(NodeId, Set, int)}
     * with its real tick period (10&nbsp;ms) so the documented millisecond budgets
     * are realized — see the type-level note.
     */
    public static RaftConfig of(NodeId nodeId, Set<NodeId> peers) {
        return of(nodeId, peers, 1);
    }

    /**
     * Convenience builder with default durations and an explicit tick period.
     * Production passes its scheduler period (e.g. 10&nbsp;ms) so the documented
     * 150–300&nbsp;ms election timeout / 50&nbsp;ms heartbeat are the intervals
     * actually realized at runtime (RR-006).
     *
     * @param tickPeriodMs milliseconds per {@code RaftNode.tick()} (must be positive)
     */
    public static RaftConfig of(NodeId nodeId, Set<NodeId> peers, int tickPeriodMs) {
        return new RaftConfig(nodeId, peers, 150, 300, 50, 64, 256 * 1024, 1024, 10, tickPeriodMs);
    }
}

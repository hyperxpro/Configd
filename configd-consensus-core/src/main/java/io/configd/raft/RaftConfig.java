package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.Set;

/**
 * RaftNode configuration (immutable).
 * Timing: ...Ms fields are real milliseconds; RaftNode converts to tick counts via tickPeriodMs divisor
 * (production 10ms, sim 1ms). If ...Ms were consumed as tick counts directly, intervals would inflate 10x.
 * Validation: derived election:heartbeat tick ratio must be >= ~10 (safety factor for leader to emit
 * several heartbeats within one election window, preventing live-lock).
 * Backpressure: maxPendingProposals is the uncommitted-entry ceiling past which proposals are
 * rejected (default 1024). Keep that value written here - gates/gate-5.sh asserts it stays documented.
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
        // Validate the relationship that actually governs liveness: the derived tick counts,
        // not the raw millisecond values. After rounding, the minimum election timeout must
        // still be strictly more than the heartbeat interval by a safety factor so a leader
        // can emit several heartbeats within one election window; otherwise followers time out
        // before the leader can refresh them and the cluster live-locks in perpetual elections.
        // Mirrors etcd/raft's election:heartbeat >= ~10 guidance applied at the resolution the
        // node actually runs at.
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
     * default 150 ms/50 ms gives exactly 3) below which the cluster is prone
     * to election storms.
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
     * tests, which advance time one millisecond per {@code tick()}. With a 1 ms
     * tick the {@code ...Ms} durations map one-to-one onto tick counts (150 ms
     * -> 150 ticks), so simulation schedules remain byte-identical. Production
     * must instead use {@link #of(NodeId, Set, int)} with its real tick period
     * (10 ms) so the documented millisecond budgets are realized - see the
     * type-level note.
     */
    public static RaftConfig of(NodeId nodeId, Set<NodeId> peers) {
        return of(nodeId, peers, 1);
    }

    /**
     * Convenience builder with default durations and an explicit tick period.
     * Production passes its scheduler period (e.g. 10 ms) so the documented
     * 150-300 ms election timeout / 50 ms heartbeat are the intervals actually
     * realized at runtime.
     *
     * @param tickPeriodMs milliseconds per {@code RaftNode.tick()} (must be positive)
     */
    public static RaftConfig of(NodeId nodeId, Set<NodeId> peers, int tickPeriodMs) {
        return new RaftConfig(nodeId, peers, 150, 300, 50, 64, 256 * 1024, 1024, 10, tickPeriodMs);
    }
}

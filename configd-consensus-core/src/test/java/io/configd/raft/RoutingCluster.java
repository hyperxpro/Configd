package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic in-process routing cluster of real {@link RaftNode}s for
 * consensus liveness / recovery tests. A seeded, single-thread message bus
 * with a per-node DROP partition: every frame to or from a partitioned node is
 * dropped (a clean network DROP - no RST). One
 * {@link #step()} ticks all nodes then delivers one round (~1 tick of latency).
 * <p>
 * Election is driven by a delivery shuttle so exactly one node times out first
 * (no split vote), matching the established {@code RaftNodeReplicationUnitTest}
 * pattern. Node ids are {@code NodeId.of(1..n)}.
 */
final class RoutingCluster {

    record Frame(NodeId src, NodeId dst, RaftMessage msg) {}

    final List<NodeId> ids = new ArrayList<>();
    final Map<NodeId, RaftNode> nodes = new LinkedHashMap<>();
    final Set<NodeId> partitioned = new HashSet<>();
    private final List<Frame> queue = new ArrayList<>();

    RoutingCluster(int n) {
        this(n, Map.of());
    }

    /**
     * @param n        cluster size
     * @param checkers per-node {@link RaftNode.InvariantChecker} (absent => NOOP)
     */
    RoutingCluster(int n, Map<NodeId, RaftNode.InvariantChecker> checkers) {
        for (int i = 1; i <= n; i++) {
            ids.add(NodeId.of(i));
        }
        for (NodeId id : ids) {
            Set<NodeId> peers = new HashSet<>(ids);
            peers.remove(id);
            RaftConfig config = RaftConfig.of(id, peers);
            RaftNode.InvariantChecker checker =
                    checkers.getOrDefault(id, RaftNode.InvariantChecker.NOOP);
            RaftNode node = new RaftNode(config, new RaftLog(),
                    (target, msg) -> queue.add(new Frame(id, target, msg)),
                    seqSm(), new java.util.Random(id.id() * 31L + 7), Storage.inMemory(), checker);
            nodes.put(id, node);
        }
    }

    RaftNode node(NodeId id) {
        return nodes.get(id);
    }

    NodeId first() {
        return ids.getFirst();
    }

    void partition(NodeId id) {
        partitioned.add(id);
    }

    void heal(NodeId id) {
        partitioned.remove(id);
    }

    void deliverRound() {
        List<Frame> batch = new ArrayList<>(queue);
        queue.clear();
        for (Frame f : batch) {
            if (partitioned.contains(f.src()) || partitioned.contains(f.dst())) {
                continue; // dropped on the partition
            }
            RaftNode tgt = nodes.get(f.dst());
            if (tgt != null) {
                tgt.handleMessage(f.msg());
            }
        }
    }

    void step() {
        for (RaftNode n : nodes.values()) {
            n.tick();
        }
        deliverRound();
    }

    void step(int times) {
        for (int i = 0; i < times; i++) {
            step();
        }
    }

    /**
     * Drives the first node to leadership with its term no-op committed and
     * replicated, via a delivery shuttle (only the first node ticks, so it alone
     * times out -> candidate; no follower ticks -> no split vote). Returns the
     * leader's node id.
     */
    NodeId electFirst() {
        RaftNode leader = nodes.get(first());
        for (int i = 0; i < 301; i++) {
            leader.tick();
        }
        for (int r = 0; r < 60; r++) {
            deliverRound(); // PreVote -> RequestVote -> leader -> no-op -> commit
        }
        if (leader.role() != RaftRole.LEADER) {
            throw new IllegalStateException("electFirst: node did not become leader (role="
                    + leader.role() + ")");
        }
        return first();
    }

    /** A state machine that counts mutating commands (empty command == no-op). */
    static StateMachine seqSm() {
        return new StateMachine() {
            @Override public long apply(long index, long term, byte[] command) {
                return (command == null || command.length == 0) ? StateMachine.NON_MUTATING : index;
            }
            @Override public byte[] snapshot() { return new byte[0]; }
            @Override public void restoreSnapshot(byte[] snapshot) { }
        };
    }
}

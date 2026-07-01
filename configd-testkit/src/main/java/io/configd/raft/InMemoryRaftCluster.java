package io.configd.raft;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Reusable in-memory Raft cluster for performance drivers
 * (the real-state-machine analogue of the inline harness in
 * {@code RaftCommitBenchmark}). Wires {@code clusterSize} {@link RaftNode}s with a
 * directly-routing in-memory transport, elects a leader by ticking, and exposes
 * {@link #driveToCommit(long)} to push a proposed entry through replicate->commit->apply.
 *
 * <p>{@link #realStateMachines(int)} attaches a real {@link ConfigStateMachine} over a
 * {@link VersionedConfigStore} to every node, so a committed entry actually decodes its
 * command and applies a HAMT {@code put} - the realistic allocating write profile the GC
 * bake-off and the local commit-latency microbench need (vs. a no-op state machine, which
 * under-prices allocation).
 *
 * <p><b>Scope honesty:</b> the transport and storage are in-memory; there is NO network
 * and NO fsync. The latency measured against this cluster is the in-process consensus CPU
 * cost - the {@code local_commit_component} of methodology section 2, never the cross-region total.
 */
public final class InMemoryRaftCluster {

    private final Map<NodeId, RaftNode> nodes;
    private final Map<NodeId, RoutingTransport> transports;
    private NodeId leaderId;

    private InMemoryRaftCluster(Map<NodeId, RaftNode> nodes, Map<NodeId, RoutingTransport> transports) {
        this.nodes = nodes;
        this.transports = transports;
    }

    /** Builds a cluster whose every node runs a real {@link ConfigStateMachine}. */
    public static InMemoryRaftCluster realStateMachines(int clusterSize) {
        Map<NodeId, RaftNode> nodes = new LinkedHashMap<>();
        Map<NodeId, RoutingTransport> transports = new LinkedHashMap<>();

        List<NodeId> ids = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            ids.add(NodeId.of(i));
        }
        for (int i = 0; i < clusterSize; i++) {
            NodeId id = ids.get(i);
            Set<NodeId> peers = new LinkedHashSet<>(ids);
            peers.remove(id);

            RaftConfig config = RaftConfig.of(id, peers);
            RaftLog log = new RaftLog();
            RoutingTransport transport = new RoutingTransport();
            // Real state machine: decode command + HAMT put on every apply.
            ConfigStateMachine sm = new ConfigStateMachine(new VersionedConfigStore(), Clock.system());
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");

            RaftNode node = new RaftNode(config, log, transport, sm, rng);
            nodes.put(id, node);
            transports.put(id, transport);
        }
        for (RoutingTransport t : transports.values()) {
            t.setCluster(nodes);
        }
        return new InMemoryRaftCluster(nodes, transports);
    }

    /** Ticks every node until one becomes leader (mirrors RaftCommitBenchmark). */
    public void electLeader() {
        for (int tick = 0; tick < 1000; tick++) {
            for (RaftNode node : nodes.values()) {
                node.tick();
            }
            deliverAll();
            for (var e : nodes.entrySet()) {
                if (e.getValue().role() == RaftRole.LEADER) {
                    leaderId = e.getKey();
                    return;
                }
            }
        }
        throw new IllegalStateException("no leader elected after 1000 ticks");
    }

    public RaftNode leader() {
        return nodes.get(leaderId);
    }

    /**
     * Drives the cluster (deliver + tick) until the leader's commit index reaches at
     * least {@code targetIndex}, bounded so a stuck cluster does not spin forever.
     */
    public void driveToCommit(long targetIndex) {
        RaftNode leader = nodes.get(leaderId);
        for (int i = 0; i < 50; i++) {
            deliverAll();
            for (RaftNode node : nodes.values()) {
                node.tick();
            }
            if (leader.log().commitIndex() >= targetIndex) {
                return;
            }
        }
    }

    private void deliverAll() {
        boolean delivered = true;
        while (delivered) {
            delivered = false;
            for (RoutingTransport t : transports.values()) {
                if (t.deliverPending()) {
                    delivered = true;
                }
            }
        }
    }

    /** In-memory transport that buffers sends and routes them directly to target nodes. */
    private static final class RoutingTransport implements RaftTransport {
        private final List<Pending> outbox = new ArrayList<>();
        private Map<NodeId, RaftNode> cluster;

        void setCluster(Map<NodeId, RaftNode> cluster) {
            this.cluster = cluster;
        }

        @Override
        public void send(NodeId target, RaftMessage message) {
            outbox.add(new Pending(target, message));
        }

        boolean deliverPending() {
            if (outbox.isEmpty()) {
                return false;
            }
            List<Pending> batch = new ArrayList<>(outbox);
            outbox.clear();
            for (Pending p : batch) {
                RaftNode node = cluster.get(p.target);
                if (node != null) {
                    node.handleMessage(p.message);
                }
            }
            return true;
        }

        private record Pending(NodeId target, RaftMessage message) {}
    }
}

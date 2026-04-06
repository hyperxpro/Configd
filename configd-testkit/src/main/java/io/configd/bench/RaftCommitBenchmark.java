package io.configd.bench;

import io.configd.common.NodeId;
import io.configd.raft.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * Measures single Raft group commit throughput using an in-memory
 * deterministic simulation.
 * <p>
 * Sets up a 3-node Raft cluster with in-memory transport and state machines.
 * Elects a leader, then measures the cost of proposing entries and ticking
 * them through to commit.
 * <p>
 * This benchmark exercises the full Raft protocol path:
 * propose -> append -> broadcast -> replicate -> commit -> apply.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2)
public class RaftCommitBenchmark {

    @Param({"3", "5"})
    int clusterSize;

    private Map<NodeId, RaftNode> nodes;
    private Map<NodeId, InMemoryTransport> transports;
    private NodeId leaderId;
    private byte[] proposalData;

    @Setup(Level.Trial)
    public void setUp() {
        nodes = new LinkedHashMap<>();
        transports = new LinkedHashMap<>();
        proposalData = new byte[128];

        List<NodeId> allNodeIds = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            allNodeIds.add(NodeId.of(i));
        }

        // Create nodes with in-memory transports
        for (int i = 0; i < clusterSize; i++) {
            NodeId nodeId = allNodeIds.get(i);
            Set<NodeId> peers = new LinkedHashSet<>(allNodeIds);
            peers.remove(nodeId);

            RaftConfig config = RaftConfig.of(nodeId, peers);
            RaftLog log = new RaftLog();
            InMemoryTransport transport = new InMemoryTransport(nodeId);
            NoOpStateMachine sm = new NoOpStateMachine();
            RandomGenerator rng = RandomGenerator.of("L64X128MixRandom");

            RaftNode node = new RaftNode(config, log, transport, sm, rng);
            nodes.put(nodeId, node);
            transports.put(nodeId, transport);
        }

        // Wire transports together
        for (var entry : transports.entrySet()) {
            entry.getValue().setCluster(nodes, transports);
        }

        // Elect a leader by ticking enough to trigger election timeout
        electLeader();
    }

    private void electLeader() {
        // Tick all nodes until one becomes leader
        for (int tick = 0; tick < 1000; tick++) {
            for (RaftNode node : nodes.values()) {
                node.tick();
            }
            deliverAllMessages();

            // Check for leader
            for (var entry : nodes.entrySet()) {
                if (entry.getValue().role() == RaftRole.LEADER) {
                    leaderId = entry.getKey();
                    return;
                }
            }
        }
        throw new IllegalStateException("No leader elected after 1000 ticks");
    }

    private void deliverAllMessages() {
        boolean delivered = true;
        while (delivered) {
            delivered = false;
            for (var transport : transports.values()) {
                if (transport.deliverPending()) {
                    delivered = true;
                }
            }
        }
    }

    /**
     * Proposes a single entry and ticks the cluster until it commits.
     * Measures the full commit cycle cost.
     */
    @Benchmark
    public void proposeAndCommit(Blackhole bh) {
        RaftNode leader = nodes.get(leaderId);
        long commitBefore = leader.log().commitIndex();

        ProposalResult result = leader.propose(proposalData);
        bh.consume(result);

        // Deliver messages and tick until commit advances
        for (int i = 0; i < 50; i++) {
            deliverAllMessages();
            for (RaftNode node : nodes.values()) {
                node.tick();
            }
            if (leader.log().commitIndex() > commitBefore) {
                break;
            }
        }
        bh.consume(leader.log().commitIndex());
    }

    /**
     * In-memory transport that directly routes messages between nodes.
     */
    private static final class InMemoryTransport implements RaftTransport {
        private final NodeId localId;
        private final List<PendingMsg> outbox = new ArrayList<>();
        private Map<NodeId, RaftNode> cluster;
        private Map<NodeId, InMemoryTransport> allTransports;

        InMemoryTransport(NodeId localId) {
            this.localId = localId;
        }

        void setCluster(Map<NodeId, RaftNode> cluster, Map<NodeId, InMemoryTransport> allTransports) {
            this.cluster = cluster;
            this.allTransports = allTransports;
        }

        @Override
        public void send(NodeId target, RaftMessage message) {
            outbox.add(new PendingMsg(target, message));
        }

        boolean deliverPending() {
            if (outbox.isEmpty()) return false;
            List<PendingMsg> toDeliver = new ArrayList<>(outbox);
            outbox.clear();
            for (PendingMsg msg : toDeliver) {
                RaftNode targetNode = cluster.get(msg.target);
                if (targetNode != null) {
                    targetNode.handleMessage(msg.message);
                }
            }
            return true;
        }

        record PendingMsg(NodeId target, RaftMessage message) {}
    }

    /**
     * Minimal state machine that does nothing on apply.
     */
    private static final class NoOpStateMachine implements StateMachine {
        @Override
        public void apply(long index, long term, byte[] command) {
            // No-op for benchmarking
        }

        @Override
        public byte[] snapshot() {
            return new byte[0];
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
            // No-op
        }
    }
}

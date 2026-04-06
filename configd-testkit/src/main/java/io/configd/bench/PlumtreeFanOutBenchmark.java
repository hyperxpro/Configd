package io.configd.bench;

import io.configd.common.NodeId;
import io.configd.distribution.PlumtreeNode;
import io.configd.distribution.PlumtreeNode.MessageId;
import io.configd.distribution.PlumtreeNode.OutboundMessage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Measures fan-out performance of {@link PlumtreeNode#broadcast(MessageId, byte[])}
 * to N eager subscribers.
 * <p>
 * The broadcast method iterates over all eager peers and all lazy peers,
 * producing one {@link OutboundMessage.EagerPush} per eager peer and one
 * {@link OutboundMessage.IHave} per lazy peer. This benchmark measures the
 * cost of that fan-out with all peers in the eager set (spanning tree).
 * <p>
 * The shared payload buffer is reused across broadcasts to simulate the
 * real pattern where the same config update bytes are fanned out to all
 * subscribers without copying.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
public class PlumtreeFanOutBenchmark {

    @Param({"10", "50", "100", "500"})
    int fanOut;

    private PlumtreeNode node;
    private byte[] sharedPayload;
    private long versionCounter;

    @Setup(Level.Trial)
    public void setUp() {
        node = new PlumtreeNode(NodeId.of(0), 10_000, 100);
        sharedPayload = new byte[256];

        // Add all peers as eager peers (spanning tree)
        for (int i = 1; i <= fanOut; i++) {
            node.addEagerPeer(NodeId.of(i));
        }

        versionCounter = 0;
    }

    /**
     * Broadcasts a message to all eager peers and drains the outbox.
     * Measures: message creation + outbox drain cost.
     */
    @Benchmark
    public void broadcastAndDrain(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        node.broadcast(id, sharedPayload);

        Queue<OutboundMessage> outbox = node.drainOutbox();
        bh.consume(outbox.size());
    }

    /**
     * Broadcasts a message without draining the outbox.
     * Isolates the cost of the broadcast fan-out logic itself.
     */
    @Benchmark
    public void broadcastOnly(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        node.broadcast(id, sharedPayload);
        bh.consume(versionCounter);
    }

    /**
     * Simulates receiving an eager push from a peer and forwarding
     * to all other eager peers. This is the relay hot path.
     */
    @Benchmark
    public void receiveAndForward(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        // Receive from peer 1 — should forward to all other eager peers
        boolean isNew = node.receiveEagerPush(NodeId.of(1), id, sharedPayload);
        bh.consume(isNew);

        Queue<OutboundMessage> outbox = node.drainOutbox();
        bh.consume(outbox.size());
    }
}

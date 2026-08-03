package io.configd.bench;

import io.configd.common.NodeId;
import io.configd.distribution.PlumtreeNode;
import io.configd.distribution.PlumtreeNode.MessageId;
import io.configd.distribution.PlumtreeNode.OutboundMessage;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Queue;
import java.util.concurrent.TimeUnit;

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

        for (int i = 1; i <= fanOut; i++) {
            node.addEagerPeer(NodeId.of(i));
        }

        versionCounter = 0;
    }

    @Benchmark
    public void broadcastAndDrain(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        node.broadcast(id, sharedPayload);

        Queue<OutboundMessage> outbox = node.drainOutbox();
        bh.consume(outbox.size());
    }

    @Benchmark
    public void broadcastOnly(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        node.broadcast(id, sharedPayload);
        bh.consume(versionCounter);
    }

    @Benchmark
    public void receiveAndForward(Blackhole bh) {
        versionCounter++;
        MessageId id = new MessageId(versionCounter, System.nanoTime());
        boolean isNew = node.receiveEagerPush(NodeId.of(1), id, sharedPayload);
        bh.consume(isNew);

        Queue<OutboundMessage> outbox = node.drainOutbox();
        bh.consume(outbox.size());
    }
}

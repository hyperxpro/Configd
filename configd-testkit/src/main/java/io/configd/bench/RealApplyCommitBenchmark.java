package io.configd.bench;

import io.configd.raft.InMemoryRaftCluster;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2)
public class RealApplyCommitBenchmark {

    @Param({"3"})
    int clusterSize;

    @Param({"256"})
    int valueBytes;

    private InMemoryRaftCluster cluster;
    private RaftNode leader;
    private byte[] value;
    private long seq;

    @Setup(Level.Trial)
    public void setUp() {
        cluster = InMemoryRaftCluster.realStateMachines(clusterSize);
        cluster.electLeader();
        leader = cluster.leader();
        value = new byte[valueBytes];
        // deterministic, non-zero payload (avoid all-zero arrays the JIT might special-case)
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (i * 31 + 7);
        }
        seq = 0;
    }

    @Benchmark
    public void proposeCommitApply(Blackhole bh) {
        // Bounded key-space for realistic HAMT path-copy churn on overwrite.
        String key = "config/service/bench/" + ((seq++) & 0xFFFFF);
        byte[] command = CommandCodec.encodePut(key, value);
        ProposeOutcome out = leader.propose(command);
        bh.consume(out);
        if (out.accepted()) {
            cluster.driveToCommit(out.index());
        }
        bh.consume(leader.log().commitIndex());
    }
}

package io.configd.bench;

import io.configd.raft.InMemoryRaftCluster;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftNode;
import io.configd.store.CommandCodec;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Session 5 / Workstream B — write-commit benchmark over a real 3- or 5-node in-memory
 * Raft cluster whose nodes run a <b>real {@code ConfigStateMachine}</b> (decode command +
 * HAMT {@code put} on every apply). Unlike {@link RaftCommitBenchmark} (no-op state
 * machine), this exercises the realistic ~2–5 KB/op allocation profile of the production
 * write path, so:
 *
 * <ul>
 *   <li><b>{@code -prof gc}</b> reports the true allocation rate (B/op + MB/s) for the
 *       GC bake-off (Phase 1, ADR-0041).</li>
 *   <li><b>{@code -Xlog:gc*}</b> alongside it carries a populated GC pause distribution
 *       (the methodology's "no ZGC-because-low-pause without the pause histogram").</li>
 *   <li><b>{@code Mode.SampleTime}</b> reports the local quorum-commit latency
 *       distribution (Phase 2 {@code local_commit_component}) as an HdrHistogram —
 *       in-memory transport + storage, so it is the in-process consensus CPU cost,
 *       no real network, no fsync.</li>
 * </ul>
 *
 * <p><b>CO note (methodology §3a):</b> Throughput/SampleTime here time per-invocation
 * service time with no externally-imposed arrival schedule, so coordinated omission is
 * structurally absent (same argument as the read-path JMH benches). The cross-region total
 * is {@code local_commit_component + RTT} per methodology §2, computed in the result doc and
 * labelled ENV-BLOCKED (M-1). This benchmark proves ONLY the local component.
 *
 * <p>Default mode is {@link Mode#Throughput}; override per phase, e.g.
 * {@code -bm sample} for the latency distribution. Run under one collector at a time and
 * state it.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2)
public class RealApplyCommitBenchmark {

    @Param({"3"})
    int clusterSize;

    /** Value payload size (the log-entry value); 256 B is a representative config value. */
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

    /**
     * Propose one real PUT command and drive it through quorum replicate → commit →
     * apply (HAMT put on every node). One commit per invocation.
     */
    @Benchmark
    public void proposeCommitApply(Blackhole bh) {
        // bounded key-space -> realistic overwrite churn (the HAMT path-copy on update)
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

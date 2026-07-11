package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.distribution.CommitNotification;
import io.configd.distribution.CommitNotificationSource;
import io.configd.distribution.FanOutBuffer;
import io.configd.distribution.ReplaySource;
import io.configd.distribution.SnapshotReplaySource;
import io.configd.probe.PropagationProbe;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The edge-data-plane deterministic simulation. It <b>composes</b> (does
 * NOT fork) an inner {@link AdversarialSim} for the control-plane cluster - whose
 * behavior is completely unchanged - and layers the edge fan-out on top:
 * <ul>
 *   <li>a per-CP-node {@link FanOutBuffer} wired via
 *       {@link io.configd.store.ConfigStateMachine#addListener} <b>exactly mirroring
 *       {@code ConfigdServer}'s production wiring</b>: seq = the listener version S,
 *       {@code commitTimestampMillis} = that node's sim clock at apply;</li>
 *   <li>a SECOND {@link AdversarialNetwork} for CP->edge fan-out channels, seeded
 *       from {@link AdversarialSchedule#mixSeed}{@code (seed, TAG_EDGE_NET)} so the
 *       CP network and every existing seed behavior stay byte-identical;</li>
 *   <li>a separate {@link EdgeFaultSchedule} (its own mixSeed sub-stream) for edge
 *       partition/crash/lag faults - the CP fault grammar is untouched;</li>
 *   <li>a roster of {@link EdgeActor}s (ids 100+), each subscribed to one CP node;</li>
 *   <li>a {@link StreamDriver} seam - the server-side per-edge streaming logic.
 *       Defaults to {@link StreamDriver#NONE} (nothing is delivered - the honest
 *       current state); tests may inject a {@link DirectInjectionDriver}.</li>
 * </ul>
 *
 * <h2>mixSeed tag registry (all >= 1_010, non-colliding)</h2>
 * <ul>
 *   <li>{@link #TAG_EDGE_NET} = 1_010 - CP->edge {@link AdversarialNetwork} seed</li>
 *   <li>{@link #TAG_EDGE_NETCFG} = 1_011 - edge network dup/drop base rates</li>
 *   <li>{@link EdgeFaultSchedule#TAG_EDGE_FAULT} = 1_012 - edge fault schedule</li>
 * </ul>
 * (CP tags, unchanged: 1_001 fault, 1_002 workload, 2_001 net, 3_001 skew,
 * 3_002 netcfg; per-node election streams use the small node id.)
 *
 * <h2>Tick order</h2>
 * {@link #tick()}: (1) {@code cpSim.tick()} (advances CP time, applies CP
 * faults/ops, fires the fan-out listeners - checks CP invariants); (2) apply due
 * edge faults; (3) deliver due edge-network messages into edge inboxes; (4) tick
 * the {@link StreamDriver} seam; (5) tick edges; (6) {@link EdgeInvariants#checkAll};
 * (7) record {@link EdgeActivity}.
 *
 * <p>Not thread-safe; single sim thread.
 */
final class EdgeFanOutSim {

    /** CP->edge network seed tag. */
    static final int TAG_EDGE_NET = 1_010;
    /** Edge network dup/drop config seed tag. */
    static final int TAG_EDGE_NETCFG = 1_011;

    /** Default number of edge faults when faults are enabled. */
    private static final int DEFAULT_EDGE_FAULT_COUNT = 6;

    private final long seed;
    private final int totalTicks;

    private final AdversarialSim cpSim;
    private final AdversarialNetwork edgeNetwork;
    private final EdgeFaultSchedule edgeFaults;
    private final StreamDriver streamDriver;

    /** Per-CP-node fan-out buffer (the source the StreamDriver reads). */
    private final List<FanOutBuffer> fanOutBuffers = new ArrayList<>();
    /** Per-CP-node replay source (the GAP recovery seam). */
    private final List<ReplaySource> replaySources = new ArrayList<>();

    private final List<EdgeActor> edges = new ArrayList<>();
    private final EdgeActivity activity = new EdgeActivity();
    private final EdgeInvariants invariants;

    /** Active CP->edge partitions, by edgeId - connected() consults this. */
    private final Set<Integer> partitionedEdges = new HashSet<>();

    /** Per-cpNode seqs already turned into a liveness obligation (dedup). */
    private final Map<Integer, Set<Long>> recordedPublications = new HashMap<>();

    /**
     * Observer-only propagation probe. Null until {@link #attachProbe} is called. When
     * attached it samples {@code recordPublished} at the FanOutBuffer publish site and
     * {@code recordVisible} at each edge's apply moment. Strictly observer-only: it reads
     * already-computed values and never perturbs the determinism digest ({@code ProbeMechanismTest}).
     */
    private PropagationProbe probe;

    private long currentTimeMs;
    private int tickIndex;
    private int edgeFaultCursor;

    /** Builds an edge sim with {@code edgeCount} edges, no edge faults, and {@link StreamDriver#NONE}. */
    EdgeFanOutSim(long seed, int cpNodeCount, int edgeCount, int totalTicks) {
        this(seed, cpNodeCount, edgeCount, totalTicks, false, StreamDriver.NONE,
                AdversarialSchedule.defaultIntensity(), EdgeInvariants.BOUND_MS);
    }

    /**
     * @param seed         master seed (shared with the CP sim)
     * @param cpNodeCount  CP node count (ids 0..n-1)
     * @param edgeCount    edge count (ids 100..100+edgeCount-1)
     * @param totalTicks   run length
     * @param edgeFaults   whether to schedule edge faults
     * @param streamDriver the server-side streaming seam (default {@link StreamDriver#NONE})
     * @param intensity    CP fault/op intensity (kept identical to plain AdversarialSim by default)
     * @param boundMs      eventual-delivery bound
     */
    EdgeFanOutSim(long seed, int cpNodeCount, int edgeCount, int totalTicks,
            boolean edgeFaults, StreamDriver streamDriver,
            AdversarialSchedule.Intensity intensity, long boundMs) {
        this(seed, cpNodeCount, edgeCount, totalTicks, edgeFaults, streamDriver, intensity,
                boundMs, FanOutBuffer_CAPACITY);
    }

    /**
     * Full constructor with an explicit per-CP-node fan-out ring capacity (some tests
     * shrink it so the replay horizon is crossable at sim scale; production and the gate
     * path stay at {@link #FanOutBuffer_CAPACITY}. The delegating constructors are
     * unchanged, so existing seeds stay byte-identical).
     */
    EdgeFanOutSim(long seed, int cpNodeCount, int edgeCount, int totalTicks,
            boolean edgeFaults, StreamDriver streamDriver,
            AdversarialSchedule.Intensity intensity, long boundMs, int fanOutBufferCapacity) {
        this.seed = seed;
        this.totalTicks = totalTicks;
        this.streamDriver = streamDriver;
        this.currentTimeMs = 1_700_000_000_000L; // matches AdversarialSim's epoch

        // Inner CP sim - unchanged behavior (same seed, same intensity, same ticks).
        this.cpSim = new AdversarialSim(seed, cpNodeCount, totalTicks, intensity, null);

        // Per-CP-node fan-out buffer + replay source, wired to the production
        // listener seam exactly as ConfigdServer does it.
        for (int i = 0; i < cpNodeCount; i++) {
            final int cpNode = i;
            FanOutBuffer buffer = new FanOutBuffer(fanOutBufferCapacity);
            ReplaySource replay = new SnapshotReplaySource(() -> cpSim.store(cpNode).snapshot());
            fanOutBuffers.add(buffer);
            replaySources.add(replay);
            // Mirror ConfigdServer: build ConfigDelta from (mutations, version) and
            // publish a full CommitNotification with the leader commit timestamp.
            cpSim.stateMachine(cpNode).addListener((mutations, version) -> {
                long fromVersion = version - 1;
                // The sim path uses an unsigned legacy delta. The production wiring
                // forwards stateMachine.lastSignature()/epoch/nonce; the sim's
                // ConfigStateMachine has no signer, so signature is null.
                ConfigDelta delta = new ConfigDelta(fromVersion, version, mutations);
                // Capture the PUBLISHING node's SKEWED clock as the commit timestamp,
                // mirroring production where the leader's (skewed) clock stamps the commit
                // on the apply thread. The global unskewed cpSim.currentTime() could not
                // exercise the +/-50 ms NTP-skew error the contract treats as the only
                // residual error, which the staleness/skew-tripwire tests need. The CP
                // digest folds role/term/leader/log-indices/store-version - not commit
                // timestamps - so this leaves EdgeSeedCompatTest byte-identical (verified).
                long commitTimestampMillis = cpSim.skewedClock(cpNode).currentTimeMillis();
                buffer.publish(new CommitNotification(version, commitTimestampMillis, delta));
                recordPublicationObligation(version, cpNode, commitTimestampMillis);
                // Observer-only: publish timestamp is the leader commit timestamp. Keyed
                // by seq, so overwriting on a re-publish is idempotent; a no-op when no
                // probe is attached.
                if (probe != null) {
                    probe.recordPublished(version, commitTimestampMillis);
                }
            });
        }

        // Second AdversarialNetwork for CP->edge channels. mixSeed with a distinct tag
        // so the CP network's stream is untouched (byte-identical historical seeds).
        this.edgeNetwork = new AdversarialNetwork(
                AdversarialSchedule.mixSeed(seed, TAG_EDGE_NET), 1, 10);
        // Edge network dup base rate from its own sub-stream (does not touch CP).
        var netCfg = java.util.random.RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, TAG_EDGE_NETCFG));
        this.edgeNetwork.setDupRate(0.02 + 0.03 * netCfg.nextDouble());

        this.invariants = new EdgeInvariants(seed, activity, boundMs);

        // Edge roster: round-robin subscription across CP nodes for spread.
        for (int e = 0; e < edgeCount; e++) {
            int edgeId = EdgeActor.EDGE_ID_BASE + e;
            int subscribedCp = (cpNodeCount == 0) ? -1 : (e % cpNodeCount);
            edges.add(new EdgeActor(edgeId, subscribedCp, () -> currentTimeMs));
        }

        // Edge fault schedule (its own sub-stream; empty when faults are off).
        this.edgeFaults = new EdgeFaultSchedule(seed, edgeCount, totalTicks,
                edgeFaults ? DEFAULT_EDGE_FAULT_COUNT : 0);

        // Edge network delivers EdgeStream messages into the addressed edge's inbox.
        this.edgeNetwork.setDeliveryHandler((target, message) -> {
            EdgeActor edge = edgeById(target.id());
            if (edge != null) {
                edge.deliver((EdgeStream) message);
            }
        });
    }

    /** Same capacity as production ({@code ConfigdServer.FANOUT_BUFFER_CAPACITY}). */
    private static final int FanOutBuffer_CAPACITY = 10_000;

    /**
     * Attaches an observer-only {@link io.configd.probe.PropagationProbe}. Publish
     * samples are fed at the FanOutBuffer publish site (publish ts = leader commit
     * timestamp); visibility samples are fed at each edge's apply moment (visible ts =
     * logical sim time) via the {@link EdgeApplyObserver} seam. The probe records
     * {@code staleness = visibleTs - publishTs} per seq and edge.
     * <p>
     * This is strictly observer-only and must not change behavior or the determinism
     * digest - {@code ProbeMechanismTest} proves the digest is identical with and
     * without a probe attached. Idempotent re-attach replaces the probe binding.
     *
     * @param probe the probe to attach (non-null)
     */
    void attachProbe(PropagationProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        for (EdgeActor edge : edges) {
            edge.setApplyObserver((edgeId, seq, commitTsMillis, visibleTsMillis) ->
                    probe.recordVisible(edgeId, seq, visibleTsMillis));
        }
    }

    EdgeActivity activity() { return activity; }

    EdgeInvariants invariants() { return invariants; }

    List<EdgeActor> edges() { return edges; }

    AdversarialSim cpSim() { return cpSim; }

    long currentTime() { return currentTimeMs; }

    CommitNotificationSource source(int cpNode) { return fanOutBuffers.get(cpNode); }

    ReplaySource replaySource(int cpNode) { return replaySources.get(cpNode); }

    /**
     * TEST SEAM: partitions edge {@code edgeIndex}'s CP->edge channel (the same mechanism the
     * EDGE_PARTITION_ADD fault uses), so a staleness test can deterministically cut an edge off
     * from its stream. Additive accessor - never invoked on the gate path, so it does not
     * perturb {@code EdgeSeedCompatTest}.
     */
    void partitionEdge(int edgeIndex) {
        EdgeActor edge = edges.get(edgeIndex);
        partitionedEdges.add(edge.edgeId());
        edgeNetwork.addPartition(NodeId.of(edge.subscribedCpNode()), NodeId.of(edge.edgeId()));
    }

    /**
     * TEST SEAM: enables the real recovery loop for edge {@code edgeIndex} - the
     * core's {@link io.configd.edge.EdgeClientCore.ConnectionDirective}s (gap resubscribe,
     * DISCONNECTED re-bootstrap, heartbeat silence, poison retries) are acted on by a real
     * {@link C1StreamDriver#resubscribe} instead of being drained-and-ignored. Opt-in and
     * additive: never invoked on the gate path, so existing seeds stay byte-identical.
     * Requires the sim to run a {@link C1StreamDriver}.
     */
    void enableEdgeRecovery(int edgeIndex) {
        if (!(streamDriver instanceof C1StreamDriver c1)) {
            throw new IllegalStateException("edge recovery requires a C1StreamDriver");
        }
        EdgeActor edge = edges.get(edgeIndex);
        edge.setDirectiveSink(directive -> {
            if (directive instanceof
                    io.configd.edge.EdgeClientCore.ConnectionDirective.ReconnectNextEndpoint r) {
                c1.resubscribe(driverContext(), edge, r.resumeCursor());
            } else if (directive instanceof
                    io.configd.edge.EdgeClientCore.ConnectionDirective.TerminalFailure t) {
                terminalFailures.add("edge " + edge.edgeId() + ": " + t.reason());
            }
        });
    }

    /** Terminal directives observed via {@link #enableEdgeRecovery} (assertions). */
    List<String> terminalFailures() {
        return List.copyOf(terminalFailures);
    }

    private final List<String> terminalFailures = new ArrayList<>();

    /** TEST SEAM: heals a partition created by {@link #partitionEdge(int)}. */
    void healEdge(int edgeIndex) {
        EdgeActor edge = edges.get(edgeIndex);
        partitionedEdges.remove(edge.edgeId());
        edgeNetwork.removePartition(NodeId.of(edge.subscribedCpNode()), NodeId.of(edge.edgeId()));
    }

    /**
     * TEST SEAM: a zero-state edge joins the running sim, subscribed to
     * {@code subscribedCpNode}. The {@link C1StreamDriver} subscribes it lazily on the
     * next {@link #tick()} with resume cursor 0 - against a populated ring the server's
     * {@code decideMode} answers SNAPSHOT_FIRST, so the join runs the real production
     * bootstrap (snapshot transfer + exact-cutover tail) under whatever writes are
     * flowing. Opt-in and additive: never invoked on the gate path and consumes no RNG,
     * so existing seeds stay byte-identical. The joiner participates in every per-tick
     * invariant and in {@link #finalCheck()} like any roster edge; the scheduled
     * {@link EdgeFaultSchedule} never targets it, since its indices were drawn against
     * the construction-time roster - i.e. faults land only on the other edges.
     *
     * @param subscribedCpNode the CP node the joiner subscribes to
     * @return the joiner's roster index (for {@link #edges()}/{@link #partitionEdge})
     */
    int joinEdge(int subscribedCpNode) {
        int index = edges.size();
        edges.add(new EdgeActor(EdgeActor.EDGE_ID_BASE + index, subscribedCpNode,
                () -> currentTimeMs));
        return index;
    }

    /**
     * TEST SEAM: sets the CP->edge duplication rate (e.g. {@code 1.0} so every frame
     * across the bootstrap cutover is duplicated, to exercise the dup-channel path).
     * RNG-stream-safe: the dup draw happens on every send regardless of the rate, so
     * changing the rate changes no draw sequence.
     */
    void setEdgeDupRateForTest(double rate) {
        edgeNetwork.setDupRate(rate);
    }

    /** Duplicated CP->edge sends so far (the dup-channel non-vacuity witness). */
    long edgeDupCount() {
        return edgeNetwork.dupCount();
    }

    /** Runs the whole scheduled simulation, checking edge invariants after every tick. */
    void run() {
        for (int t = 0; t < totalTicks; t++) {
            tick();
        }
    }

    /** One edge-sim tick (see class javadoc for the order). */
    void tick() {
        // (1) CP sim advances (its own time, faults, ops, fan-out listeners) and
        // checks CP invariants. Keep our clock in lockstep with the CP clock so the
        // commit timestamps (recorded in the listener via cpSim.currentTime()) and
        // the edge staleness clock share one logical time base.
        cpSim.tick();
        currentTimeMs = cpSim.currentTime();
        tickIndex++;

        // (2) apply due edge faults.
        applyDueEdgeFaults();

        // (3) deliver due edge-network messages into edge inboxes.
        edgeNetwork.deliverDue(currentTimeMs);

        // (4) tick the StreamDriver seam (NONE delivers nothing by default).
        streamDriver.drive(driverContext());

        // (5) tick edges (drain inbox + apply).
        for (EdgeActor edge : edges) {
            edge.tick();
        }

        // (6) SAFETY + LIVENESS: edge invariants every tick.
        invariants.checkAll(edges, currentTimeMs, this::connected);

        // (7) record edge activity from this tick's deltas.
        recordActivityDelta();
    }

    /** Activity counters are aggregated from per-edge counters at end of tick. */
    private long lastTotalDelivered;
    private int lastTotalGaps;
    private int lastTotalSnapshots;

    private void recordActivityDelta() {
        long delivered = 0;
        int gaps = 0;
        int snapshots = 0;
        for (EdgeActor edge : edges) {
            delivered += edge.deliveredCount();
            gaps += edge.gapsDetected();
            snapshots += edge.snapshotsApplied();
        }
        activity.recordDelivered(delivered - lastTotalDelivered);
        activity.recordGapsDetected(gaps - lastTotalGaps);
        activity.recordSnapshotsApplied(snapshots - lastTotalSnapshots);
        lastTotalDelivered = delivered;
        lastTotalGaps = gaps;
        lastTotalSnapshots = snapshots;
    }

    /**
     * Heals all edge faults, drains the edge network + inboxes for a bounded window,
     * then runs the convergence check (invariant c) against the CP leader's
     * authoritative store. Intended to be called by tests at end of run.
     * <p>
     * With {@link StreamDriver#NONE} the edges are empty while the leader is
     * populated, so this throws - that is the executable backlog
     * ({@link EdgePropagationBacklogTest}).
     */
    void finalCheck() {
        // Heal all CP->edge partitions and resume any lagging edges.
        partitionedEdges.clear();
        edgeNetwork.healAll();
        for (EdgeActor edge : edges) {
            if (edge.alive()) {
                edge.unlag();
            }
        }
        // Drain window: let the driver push, the network deliver, and edges apply.
        for (int t = 0; t < DRAIN_WINDOW_TICKS; t++) {
            currentTimeMs++;
            streamDriver.drive(driverContext());
            edgeNetwork.deliverDue(currentTimeMs);
            for (EdgeActor edge : edges) {
                edge.tick();
            }
            invariants.checkAll(edges, currentTimeMs, this::connected);
        }
        int leader = cpSim.findLeader();
        ConfigSnapshot authoritative = (leader >= 0)
                ? cpSim.store(leader).snapshot()
                : cpSim.store(0).snapshot(); // no leader now: compare against node 0
        invariants.finalCheck(edges, authoritative);
    }

    /**
     * Like {@link #finalCheck()} but FIRST heals the CP-side faults (network partitions /
     * delay spikes) and ticks the CP cluster to quiescence, so a genuine quiet drain window
     * exists for the edges to converge against a settled CP store.
     * <p>
     * This is the convergence check the adversarial gate sweep uses: under the full CP fault
     * schedule the cluster is mid-divergence at end of run; healing CP + ticking it to a
     * stable single leader+committed prefix is the "where a quiet drain window exists"
     * precondition for edge convergence (otherwise an edge subscribed to a behind follower
     * can never catch the leader). It throws {@link SimInvariants.SafetyViolation} on edge
     * divergence exactly like {@link #finalCheck()} - callers that treat convergence as
     * recorded-liveness (the sweep) catch it; the no-fault scenario tests do not.
     */
    void finalCheckHealingCp() {
        settleCp();
        // Run the standard edge heal + drain + convergence against the settled CP.
        finalCheck();
    }

    /**
     * Heals the CP network and ticks the CP cluster to quiescence (re-election +
     * replication + commit). Idempotent enough for a single end-of-run settle.
     */
    void settleCp() {
        cpSim.network().healAll();
        for (int t = 0; t < CP_SETTLE_TICKS; t++) {
            cpSim.tick();
        }
        currentTimeMs = cpSim.currentTime();
    }

    /**
     * True iff, right now, every CP node's store version equals the current leader's - i.e.
     * the CP cluster has fully converged, so a genuine quiet drain window exists for the
     * edges. Used by the gate sweep to bucket "convergence expected" seeds from
     * "never-healed" ones (an edge subscribed to a frozen/behind CP node legitimately cannot
     * converge - that is a CP-sim liveness limit, not a fault in the edge layer). Returns
     * false when there is no leader.
     */
    boolean cpFullyConverged() {
        int leader = cpSim.findLeader();
        if (leader < 0) {
            return false;
        }
        long lv = cpSim.store(leader).currentVersion();
        for (int i = 0; i < cpSim.nodeCount(); i++) {
            if (cpSim.store(i).currentVersion() != lv) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bounded drain window for {@link #finalCheck()}. Sized to let the recovery loop
     * complete: a stranded edge recovers via the server's ack-lag demotion -> snapshot ->
     * (network latency 1 - 10 ms) -> edge apply -> ack cycle, which the session retries every
     * couple of ticks until the edge's CURSOR_ACK confirms the snapshot. A few hundred ticks
     * is ample for the handful of edges per sim; 600 leaves generous margin over the
     * worst-case retry chain without making the no-fault tests slow.
     */
    private static final int DRAIN_WINDOW_TICKS = 600;

    /** CP settle window for {@link #finalCheckHealingCp()} (re-election + replication). */
    private static final int CP_SETTLE_TICKS = 300;

    private StreamDriver.Context driverContext() {
        return new StreamDriver.Context() {
            @Override public List<EdgeActor> edges() {
                List<EdgeActor> live = new ArrayList<>();
                for (EdgeActor e : edges) {
                    if (e.alive()) live.add(e);
                }
                return live;
            }
            @Override public CommitNotificationSource source(int cpNode) {
                return fanOutBuffers.get(cpNode);
            }
            @Override public ReplaySource replaySource(int cpNode) {
                return replaySources.get(cpNode);
            }
            @Override public void send(EdgeActor edge, EdgeStream message) {
                edgeNetwork.send(NodeId.of(edge.subscribedCpNode()),
                        NodeId.of(edge.edgeId()), message, currentTimeMs);
            }
            @Override public long nowMs() {
                return currentTimeMs;
            }
        };
    }

    private void recordPublicationObligation(long seq, int cpNode, long publishedAtMs) {
        // Dedup: record the obligation once per (cpNode, seq) - the earliest publish.
        Set<Long> seen = recordedPublications.computeIfAbsent(cpNode, k -> new HashSet<>());
        if (!seen.add(seq)) {
            return;
        }
        // The edges that owe observation: those subscribed to this CP node and alive.
        List<Integer> owing = new ArrayList<>();
        for (EdgeActor edge : edges) {
            if (edge.subscribedCpNode() == cpNode && edge.alive()) {
                owing.add(edge.edgeId());
            }
        }
        invariants.recordPublication(seq, cpNode, publishedAtMs, owing);
    }

    private void applyDueEdgeFaults() {
        List<EdgeFaultSchedule.EdgeFault> faults = edgeFaults.faults();
        while (edgeFaultCursor < faults.size()
                && faults.get(edgeFaultCursor).tick() <= tickIndex) {
            applyEdgeFault(faults.get(edgeFaultCursor));
            edgeFaultCursor++;
        }
    }

    private void applyEdgeFault(EdgeFaultSchedule.EdgeFault fault) {
        if (fault.edgeIndex() >= edges.size()) {
            return;
        }
        EdgeActor edge = edges.get(fault.edgeIndex());
        switch (fault.kind()) {
            case EDGE_PARTITION_ADD -> {
                partitionedEdges.add(edge.edgeId());
                edgeNetwork.addPartition(
                        NodeId.of(edge.subscribedCpNode()), NodeId.of(edge.edgeId()));
            }
            case EDGE_PARTITION_REMOVE -> {
                partitionedEdges.remove(edge.edgeId());
                edgeNetwork.removePartition(
                        NodeId.of(edge.subscribedCpNode()), NodeId.of(edge.edgeId()));
            }
            case EDGE_CRASH -> {
                if (edge.alive()) {
                    edge.crash();
                    activity.recordEdgeCrash();
                }
            }
            case EDGE_RESTART -> {
                if (!edge.alive()) {
                    edge.restart();
                    activity.recordEdgeRestart();
                }
            }
            case EDGE_LAG_BEGIN -> edge.lag();
            case EDGE_LAG_END -> edge.unlag();
        }
    }

    /** True if edge {@code edgeId}'s CP->edge channel is not currently partitioned. */
    private boolean connected(int edgeId) {
        return !partitionedEdges.contains(edgeId);
    }

    private EdgeActor edgeById(int id) {
        for (EdgeActor e : edges) {
            if (e.edgeId() == id) {
                return e;
            }
        }
        return null;
    }
}

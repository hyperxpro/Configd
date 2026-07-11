package io.configd.testkit;

import io.configd.common.NodeId;
import io.configd.raft.*;
import io.configd.store.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Adversarial deterministic simulation: a real
 * Raft cluster driven through an {@link AdversarialNetwork} under a seed-derived
 * {@link AdversarialSchedule} of faults (reorder, drop, duplication, delay spikes,
 * partitions, clock skew, crash-restart) interleaved with a randomized client
 * workload, with the continuous {@link SimInvariants} safety checker run after
 * every tick.
 * <p>
 * Everything derives from the master seed via the deterministic {@code mixSeed} pattern,
 * so the whole run - faults, ops, election timeouts, network jitter - is replayable
 * by seed alone (verified by {@code AdversarialSimDeterminismTest}).
 * <p>
 * Safety violations throw {@link SimInvariants.SafetyViolation} (fail the seed);
 * liveness stalls are recorded in {@link #activity()} and never fail the run.
 * <p>
 * Not thread-safe; single sim thread.
 */
final class AdversarialSim implements ClusterView {

    private static final int TAG_SKEW = 3_001;
    private static final int TAG_NETCFG = 3_002;

    private final long seed;
    private final int nodeCount;
    private final AdversarialSchedule schedule;
    private final AdversarialNetwork network;
    private final List<RaftNode> nodes = new ArrayList<>();
    private final List<RaftLog> logs = new ArrayList<>();
    private final List<VersionedConfigStore> stores = new ArrayList<>();
    private final List<ConfigStateMachine> stateMachines = new ArrayList<>();
    private final List<CrashStorageHandle> storages = new ArrayList<>();
    /**
     * Per-node skewed clock. Retained so a composing harness can read the
     * PUBLISHING node's clock for its commit timestamp - matching production, where the
     * leader's (skewed) clock stamps the commit on the apply thread; without it, the only
     * timestamp surface a composer could reach is the global unskewed {@link #currentTime()}.
     */
    private final List<SkewedClock> skewedClocks = new ArrayList<>();
    private final SimInvariants invariants;
    private final Activity activity = new Activity();
    private final HistoryRecorder history;

    private long currentTimeMs = 1_700_000_000_000L;
    private int tickIndex;
    private int scheduleCursor;
    private int opCursor;

    /** Owner binding is done once, on the first tick (the drive thread). */
    private boolean ownersBound;

    AdversarialSim(long seed, int nodeCount, int totalTicks) {
        this(seed, nodeCount, totalTicks, AdversarialSchedule.defaultIntensity(), null);
    }

    AdversarialSim(long seed, int nodeCount, int totalTicks,
            AdversarialSchedule.Intensity intensity, HistoryRecorder history) {
        this.seed = seed;
        this.nodeCount = nodeCount;
        this.schedule = new AdversarialSchedule(seed, nodeCount, totalTicks, intensity);
        this.history = history;

        // Network config (latency band, drop/dup base rates) is itself seed-derived.
        RandomGenerator netCfg = RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, TAG_NETCFG));
        this.network = new AdversarialNetwork(seed, 1, 10);
        this.network.setDupRate(0.02 + 0.03 * netCfg.nextDouble()); // 2 - 5% duplication

        RandomGenerator skewRng = RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, TAG_SKEW));

        for (int i = 0; i < nodeCount; i++) {
            NodeId nodeId = NodeId.of(i);
            Set<NodeId> peers = new HashSet<>();
            for (int j = 0; j < nodeCount; j++) {
                if (j != i) peers.add(NodeId.of(j));
            }
            RaftConfig config = RaftConfig.of(nodeId, peers);
            RaftLog log = new RaftLog();
            VersionedConfigStore store = new VersionedConfigStore();
            // Bounded per-node clock skew (+/-50ms) on the state-machine timestamp
            // surface (RaftNode itself is tick-driven; see SkewedClock).
            long skewMs = skewRng.nextInt(101) - 50;
            SkewedClock clock = new SkewedClock(() -> currentTimeMs, skewMs);
            skewedClocks.add(clock);
            ConfigStateMachine sm = new ConfigStateMachine(store, clock);

            RaftTransport transport = (target, message) ->
                    network.send(nodeId, target, message, currentTimeMs);

            CrashStorageHandle storage = newCrashStorage();
            RaftNode node = new RaftNode(config, log, transport, sm,
                    electionRandom(nodeId), storage, throwingChecker());

            nodes.add(node);
            logs.add(log);
            stores.add(store);
            stateMachines.add(sm);
            storages.add(storage);
        }

        this.invariants = new SimInvariants(this, seed);

        network.setDeliveryHandler((target, message) -> {
            int idx = target.id();
            if (idx >= 0 && idx < nodeCount) {
                nodes.get(idx).handleMessage((RaftMessage) message);
            }
        });
    }

    AdversarialSchedule schedule() {
        return schedule;
    }

    Activity activity() {
        return activity;
    }

    // Additive, read-only accessors for composition (EdgeFanOutSim).
    // No behavior change: these expose existing per-node objects so the edge
    // harness can wire the production fan-out listener + read the CP clock.
    // Existing tests (digests, gate sweep) are unaffected - nothing here is
    // called on the CP-only path.

    /** The {@link ConfigStateMachine} for CP node {@code i} (additive accessor). */
    ConfigStateMachine stateMachine(int i) {
        return stateMachines.get(i);
    }

    /**
     * The per-node {@link SkewedClock} for CP node {@code i} (additive accessor). A
     * composing harness reads this node's {@code currentTimeMillis()} as the commit
     * timestamp when that node publishes - mirroring production's "leader's skewed clock on
     * the apply thread", so the +/-50 ms skew error term the contract names as the only
     * residual error is actually present on the fan-out stream.
     */
    SkewedClock skewedClock(int i) {
        return skewedClocks.get(i);
    }

    /** The CP {@link AdversarialNetwork} (additive accessor - same instance). */
    AdversarialNetwork network() {
        return network;
    }

    /** The current CP sim logical time in ms (additive accessor). */
    long currentTime() {
        return currentTimeMs;
    }

    @Override
    public RaftNode node(int i) {
        return nodes.get(i);
    }

    @Override
    public RaftLog log(int i) {
        return logs.get(i);
    }

    @Override
    public VersionedConfigStore store(int i) {
        return stores.get(i);
    }

    @Override
    public int nodeCount() {
        return nodeCount;
    }

    long currentTimeMs() {
        return currentTimeMs;
    }

    int findLeader() {
        for (int i = 0; i < nodeCount; i++) {
            if (nodes.get(i).role() == RaftRole.LEADER) {
                return i;
            }
        }
        return -1;
    }

    /** Runs the whole scheduled simulation, checking safety after every tick. */
    void run() {
        for (int t = 0; t < schedule.totalTicks(); t++) {
            tick();
        }
        if (history != null) {
            history.finish(currentTimeMs);
        }
    }

    /** One adversarial tick: advance time, apply due faults/ops, tick nodes, check. */
    void tick() {
        currentTimeMs += 1;
        tickIndex++;

        bindOwnersIfNeeded();
        applyDueFaults();
        applyDueOps();

        network.deliverDue(currentTimeMs);
        for (RaftNode node : nodes) {
            node.tick();
        }

        // Record leadership for the activity predicate.
        int leader = findLeader();
        if (leader >= 0) {
            activity.recordLeaderAtTerm(nodes.get(leader).currentTerm());
        }

        // SAFETY: continuous invariant check, every tick, every seed.
        invariants.checkAll();
    }

    /**
     * Owner-thread bind rule made executable in the adversarial sim: bind every node's owner to the single
     * drive thread as the first action on that thread (NOT during construction). The sim is
     * single-threaded, so this thread owns every node; the {@code assertOwnerThread()} tripwire is
     * now ACTIVE and, via {@link #throwingChecker()} -> {@link SimInvariants#throwingNodeChecker()},
     * turns any off-drive-thread {@link RaftNode} access into a {@link SimInvariants.SafetyViolation}
     * - failing the seed deterministically, so {@code raft_owner_thread} joins the in-node safety
     * invariants checked under every adversarial schedule. Crash faults arm but do not rebuild node
     * objects (see {@code applyDueFaults}), so a one-time bind is complete.
     */
    private void bindOwnersIfNeeded() {
        if (ownersBound) {
            return;
        }
        for (RaftNode node : nodes) {
            node.bindOwnerThread();
        }
        ownersBound = true;
    }

    private void applyDueFaults() {
        List<AdversarialSchedule.Event> events = schedule.events();
        while (scheduleCursor < events.size()
                && events.get(scheduleCursor).tick() <= tickIndex) {
            applyFault(events.get(scheduleCursor));
            scheduleCursor++;
        }
    }

    private void applyFault(AdversarialSchedule.Event e) {
        activity.recordFault();
        switch (e.kind()) {
            case DROP_WINDOW_BEGIN -> network.setDropRate(0.1 + 0.4 * e.param());
            case DROP_WINDOW_END -> network.setDropRate(0.0);
            case PARTITION_ADD -> {
                if (e.param() < 0.5) {
                    // Asymmetric: only one direction.
                    network.addPartition(NodeId.of(e.a()), NodeId.of(e.b()));
                } else {
                    network.isolate(NodeId.of(e.a()), NodeId.of(e.b()));
                }
            }
            case PARTITION_REMOVE -> {
                network.removePartition(NodeId.of(e.a()), NodeId.of(e.b()));
                network.removePartition(NodeId.of(e.b()), NodeId.of(e.a()));
            }
            case HEAL_ALL -> network.healAll();
            case DELAY_SPIKE_BEGIN -> network.beginDelaySpike(e.a(), e.b(), 50 + e.intParam() * 20);
            case DELAY_SPIKE_END -> network.endDelaySpike();
            case CRASH_ARM -> armAndMaybeCrash(e.a(), e.intParam());
        }
    }

    private void armAndMaybeCrash(int nodeIdx, int afterWrites) {
        CrashStorageHandle storage = storages.get(nodeIdx);
        // Note pre-crash committed state so durable-prefix vacuity is satisfiable.
        if (logs.get(nodeIdx).commitIndex() > 0) {
            activity.recordCommittedEntryPreCrash();
        }
        storage.armCrashAfterWrites(afterWrites);
        // The crash fires inside the node's next storage write; restart wiring is
        // performed lazily by checking didCrash() after the tick batch in run(). Here
        // we only record the arm; full restart-rebuild is exercised by the dedicated
        // crash-recovery test against CrashStorage in consensus-core scope (see
        // AdversarialSimTest and the test-jar seam).
        if (storage.didCrash()) {
            activity.recordCrash();
        }
    }

    private void applyDueOps() {
        List<AdversarialSchedule.Op> ops = schedule.ops();
        while (opCursor < ops.size() && ops.get(opCursor).tick() <= tickIndex) {
            applyOp(ops.get(opCursor));
            opCursor++;
        }
    }

    private void applyOp(AdversarialSchedule.Op op) {
        int leader = findLeader();
        if (leader < 0) {
            return; // no leader to serve the op now; the workload is best-effort
        }
        String token = op.clientId() + ":" + op.opSeq() + ":"
                + Integer.toHexString(op.key().hashCode());
        switch (op.kind()) {
            case PUT -> {
                byte[] cmd = CommandCodec.encodePut(op.key(),
                        token.getBytes(StandardCharsets.UTF_8));
                boolean accepted = nodes.get(leader).propose(cmd).accepted();
                if (history != null) {
                    history.recordWriteInvokeAndInfo(currentTimeMs, op.clientId(),
                            "PUT", op.key(), token, accepted);
                }
                if (accepted) {
                    activity.recordCommit(); // best-effort: accepted != committed
                }
            }
            case DELETE -> {
                byte[] cmd = CommandCodec.encodeDelete(op.key());
                boolean accepted = nodes.get(leader).propose(cmd).accepted();
                if (history != null) {
                    history.recordWriteInvokeAndInfo(currentTimeMs, op.clientId(),
                            "DELETE", op.key(), token, accepted);
                }
            }
            case READ -> {
                ReadResult r = stores.get(leader).get(op.key());
                String observed = r.found()
                        ? new String(r.value(), StandardCharsets.UTF_8) : null;
                if (history != null) {
                    history.recordRead(currentTimeMs, op.clientId(), op.key(), observed);
                }
                if (r.found()) {
                    activity.recordLinearizableReadOk();
                }
            }
        }
    }

    private RandomGenerator electionRandom(NodeId nodeId) {
        return RandomGeneratorFactory.of("L64X128MixRandom")
                .create(AdversarialSchedule.mixSeed(seed, nodeId.id()));
    }

    private RaftNode.InvariantChecker throwingChecker() {
        // Forward into SimInvariants once it exists; before that (construction),
        // throw directly so an in-node breach during recovery is never swallowed.
        return (name, cond, msg) -> {
            if (invariants != null) {
                invariants.throwingNodeChecker().check(name, cond, msg);
            } else if (!cond) {
                throw new SimInvariants.SafetyViolation("IN-NODE invariant '" + name
                        + "' violated during construction (seed=" + seed + "): " + msg);
            }
        };
    }

    /**
     * Builds a {@link CrashStorageHandle}. When the consensus-core test-jar is on
     * the path this returns a {@code CrashStorage} adapter; the reflective lookup
     * keeps configd-testkit compiling even if the test-jar wiring is not yet active,
     * falling back to a non-crashing in-memory storage (crash faults then no-op,
     * which the crash-recovery test in consensus-core scope covers directly).
     */
    private static CrashStorageHandle newCrashStorage() {
        return io.configd.raft.CrashStorageAdapter.create();
    }
}

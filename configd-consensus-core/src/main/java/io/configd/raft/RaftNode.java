package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Full Raft consensus implementation driven by tick() and handleMessage().
 * <p>
 * This class is designed for single-threaded access from the Raft I/O thread
 * (ADR-0009). No synchronization is used. State transitions are driven
 * entirely by two entry points:
 * <ul>
 *   <li>{@link #tick()} — called at regular intervals (e.g., every 1ms)
 *       to drive election timeouts and heartbeat intervals.</li>
 *   <li>{@link #handleMessage(RaftMessage)} — called when a Raft protocol
 *       message arrives from a peer.</li>
 * </ul>
 * <p>
 * Implements:
 * <ul>
 *   <li>Raft §5: Leader election, log replication, safety</li>
 *   <li>Raft §7: Log compaction and snapshot transfer (InstallSnapshot RPC)</li>
 *   <li>Raft §9.6 (Ongaro dissertation): PreVote protocol to prevent term inflation</li>
 *   <li>CheckQuorum: Leader steps down if no majority contact within election timeout</li>
 *   <li>Leadership transfer (Raft §3.10)</li>
 *   <li>ReadIndex protocol for linearizable reads without log writes</li>
 * </ul>
 */
public final class RaftNode {

    private final RaftConfig config;
    private final RaftLog log;
    private final RaftTransport transport;
    private final StateMachine stateMachine;
    private final RandomGenerator random;

    // ---- Persistent state (on all servers) ----
    // Backed by DurableRaftState for crash safety (Raft §5.2).
    // The in-memory fields mirror the durable state for fast access.
    private final DurableRaftState durableState; // null when using legacy in-memory mode
    private long currentTerm;
    private NodeId votedFor;     // null if not voted in current term

    // ---- Volatile state (visible across threads for cross-thread reads) ----
    // role and leaderId are read by HTTP handler threads (isReadReady, leaderId)
    // but written only by the tick thread. Volatile ensures cross-thread visibility.
    private volatile RaftRole role;
    private volatile NodeId leaderId;     // null if unknown

    // ---- Timer state (tick-based, not wall-clock) ----
    // RR-006: the RaftConfig durations are real milliseconds; they are converted
    // to tick counts once via config.tickPeriodMs() so the documented budgets
    // are realized regardless of how often the caller invokes tick(). Election
    // bounds (min/max) and the heartbeat interval are cached in ticks here.
    private final int electionTimeoutMinTicks;
    private final int electionTimeoutMaxTicks;
    private final int heartbeatTimeoutTicks;
    private int electionTimeoutTicks;  // randomized target in [min, max] ticks
    private int electionTicksElapsed;
    private int heartbeatTicksElapsed;

    // ---- Election state ----
    /** Tracks which nodes granted votes (needed for joint consensus dual-majority). */
    private Set<NodeId> votesReceived;
    /** Tracks which nodes granted pre-votes (needed for joint consensus dual-majority). */
    private Set<NodeId> preVotesReceived;
    private boolean preVoteInProgress;

    // ---- Leader state (reinitialized after election) ----
    private Map<NodeId, Long> nextIndex;   // per peer: next log index to send
    private Map<NodeId, Long> matchIndex;  // per peer: highest log index known replicated
    private Map<NodeId, Integer> inflightCount;  // per peer: in-flight AppendEntries RPCs

    // ---- CheckQuorum state ----
    /** Tracks which peers have responded since the last check-quorum check. */
    private final Map<NodeId, Boolean> peerActivity;

    // ---- Leadership transfer state ----
    private NodeId transferTarget;

    // ---- Snapshot state ----
    /** The most recent snapshot available for transfer to lagging followers. */
    private SnapshotState latestSnapshot;

    // ---- ReadIndex state ----
    /** Tracks pending linearizable read requests. */
    private final ReadIndexState readIndexState;

    /**
     * Map of readId → one-shot callback fired when {@code isReadReady(readId)}
     * becomes true (or when leadership is lost and the read is cancelled).
     * <p>
     * F-0022 fix: replaces the per-poll CompletableFuture allocation in
     * ConfigdServer's linearizable-read path. The tick thread polls
     * {@code isReadReady} after every heartbeat confirm / apply, and fires
     * the callback exactly once. All access is from the tick thread only.
     */
    private final Map<Long, Runnable> readReadyCallbacks = new HashMap<>();

    // ---- Commit-outcome seam (RR-004 / ADR-0033) ----
    /**
     * Pending one-shot commit-outcome callbacks for proposed entries, keyed by
     * the proposed log {@code index}. Symmetric to {@link #readReadyCallbacks};
     * all access is tick-thread only (R-01). Each record remembers the proposed
     * {@code term} so the firing logic can distinguish COMMITTED (same term
     * applied at {@code index}) from LOST (a different term applied at
     * {@code index}). Bounded by the same {@code maxPendingProposals}
     * backpressure that bounds in-flight proposals, plus deadline cleanup on the
     * registrant side (one-shot, late-completion-tolerant).
     */
    private final Map<Long, PendingCommit> commitOutcomeCallbacks = new HashMap<>();

    /**
     * Recently applied {@code index -> appliedSeq} for entries applied by
     * {@link #applyCommitted}. Lets a commit-outcome registration that arrives
     * <em>after</em> the entry has already applied (the single-node
     * immediate-commit path, where {@code propose} commits inline) still surface
     * the correct applied-mutation sequence for that exact index. Pruned to the
     * indices that may still be queried: entries below the lowest still-pending
     * callback index are dropped, and the map is hard-capped to avoid unbounded
     * growth if a caller never registers.
     */
    private final Map<Long, Long> appliedSeqByIndex = new HashMap<>();

    /** Hard cap on {@link #appliedSeqByIndex} retained without a pending registrant. */
    private static final int MAX_RETAINED_APPLIED_SEQ = 4096;

    // ---- S7.5 group commit (leader durable-index gating + coalescing flush) ----
    /**
     * Highest log index force-synced to durable storage on THIS node while leader. The leader
     * counts its own log toward a commit quorum (in {@link #maybeAdvanceCommitIndex}) ONLY up to
     * this index: a buffered-but-unfsynced self-copy must never be the deciding vote, or a leader
     * crash before the flush could lose an entry that was reported "committed" (Raft durability
     * safety). Advanced by {@link #flushDurable()} after a group fsync and after the rare durable
     * control-entry appends (no-op, config changes). Tick-thread only (R-01).
     */
    private long durableIndex = 0L;

    /** True while a coalescing flush task is already scheduled — prevents redundant scheduling. */
    private boolean flushScheduled = false;

    /**
     * Pluggable scheduler for the coalescing group-commit flush. The default runs the flush
     * INLINE (synchronous per-append durability — unchanged semantics for tests and in-memory
     * mode). The production server wires {@link #setGroupCommit} to dispatch the flush onto the
     * single tick executor so concurrently-proposed entries coalesce into one fsync (PART 2
     * headline: the fix for the per-op-fsync ceiling).
     */
    @FunctionalInterface
    public interface FlushScheduler {
        /** Schedule {@code flush} to run on the tick thread after {@code delayMicros} (0 = ASAP). */
        void schedule(Runnable flush, long delayMicros);
    }

    private FlushScheduler flushScheduler = (flush, delayMicros) -> flush.run(); // INLINE default
    private int groupCommitMaxBatch = Integer.MAX_VALUE; // entries per fsync cap (bounds latency)
    private long groupCommitLingerMicros = 0L;           // linger to grow a batch (0 = no linger)

    /**
     * Enables asynchronous coalescing group commit (S7.5 PART 2). Called once at server wiring,
     * before the node is started, with a {@code scheduler} that dispatches the flush onto the tick
     * executor. {@code maxBatch} caps entries per fsync (bounds worst-case commit latency and the
     * uncommitted backlog); {@code lingerMicros} optionally delays the flush to accumulate a larger
     * batch — the throughput/latency knob swept for the sizing curve. With the default (INLINE)
     * scheduler the node keeps the original synchronous per-append durability.
     */
    public void setGroupCommit(FlushScheduler scheduler, int maxBatch, long lingerMicros) {
        this.flushScheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (maxBatch <= 0) {
            throw new IllegalArgumentException("maxBatch must be positive: " + maxBatch);
        }
        if (lingerMicros < 0) {
            throw new IllegalArgumentException("lingerMicros must be >= 0: " + lingerMicros);
        }
        this.groupCommitMaxBatch = maxBatch;
        this.groupCommitLingerMicros = lingerMicros;
    }

    /** A pending commit-outcome callback bound to a proposed {@code (index, term)}. */
    private record PendingCommit(long term, java.util.function.Consumer<CommitOutcome> callback) {}

    // ---- Reconfiguration state (Joint Consensus, Raft §6) ----
    /**
     * The current cluster configuration. Starts as a simple config
     * derived from {@link RaftConfig#peers()}. During reconfiguration,
     * this becomes a joint config (C_old,new). After the joint config
     * is committed and C_new is committed, it returns to simple.
     */
    private ClusterConfig clusterConfig;

    /**
     * True if a no-op entry has been committed in the current term.
     * Required before any config change can be proposed (prevents
     * the single-server reconfig bug — Ongaro, raft-dev 2015).
     */
    private boolean noopCommittedInCurrentTerm;

    /**
     * True if there is an uncommitted config change in the log.
     * Only one config change may be in-flight at a time.
     */
    private boolean configChangePending;

    /**
     * Runtime invariant checker for Raft safety properties (Rule 13).
     * Bridges TLA+ invariants to runtime assertions. In test mode,
     * violations throw immediately; in production, they increment metrics.
     */
    @FunctionalInterface
    public interface InvariantChecker {
        void check(String name, boolean condition, String message);
        InvariantChecker NOOP = (name, condition, message) -> {};
    }

    private final InvariantChecker invariantChecker;

    // ---- R-01' owner-thread tripwire (Phase 0 threading-contract enforcement) ----
    // Mirrors ConfigStateMachine.assertOwnerThread (RR-029/W-1). INERT until an owner is
    // explicitly bound via bindOwnerThread(): production is not yet wired to bind (Workstream B)
    // and existing single-threaded tests never bind, so this is a zero-behaviour-change addition.
    // Once bound, every OWNER-ONLY entry point asserts it runs on the owner thread; a violation
    // routes through the existing invariantChecker (throws in test/sim, metric+SEVERE in prod).
    // volatile so a foreign thread reliably observes the bound owner — else a violation could be
    // missed (a false negative). Written on the owner thread at bind; RE-BOUND across a Stage-2
    // rehoming handoff (beginHandoff -> HANDOFF sentinel on the losing owner, adoptOwnerThread ->
    // the gaining owner). The volatile + the driver's executor barriers order A's detach before B's
    // adopt, so there is never a window where two real threads both equal ownerThread (no
    // double-ownership). See docs/phase0-B-stage2/m2-rehoming-handoff-design.md.
    private volatile Thread ownerThread;

    // Stage 2 M2 (rehoming): the "in-handoff, owned by nobody" sentinel. A Thread object that is
    // never started, so it equals no running thread — while ownerThread==HANDOFF, EVERY guarded
    // entry point fires for any real caller, so the ambiguous handoff window is fully net-covered.
    private static final Thread HANDOFF = new Thread("raft-owner-handoff-sentinel");

    // ---- H-3: the owner-published monitoring snapshot (Workstream B; docs/phase0-B/h3-monitor-view-design.md) ----
    // The R-01 monitoring reads (currentTerm/log/...) were safe only because the metrics scrape ran
    // INLINE on the single tick thread. Once owners are bound and tick() fans out, that scrape runs
    // off the group's owner — an unsynchronised read of non-volatile consensus state. Resolution: the
    // owner publishes an immutable RaftMetrics snapshot via this single volatile reference at the end
    // of every tick(); any thread reads it via monitorView() with one volatile load. Immutable record
    // + volatile publish ⇒ a coherent, never-torn, never-partial, at-most-one-tick-stale view that
    // never blocks the owner. Seeded in the constructor so a startup-racing scrape never sees null.
    private volatile RaftMetrics monitorView;

    /**
     * Creates a new RaftNode with durable state persistence.
     * <p>
     * The {@code storage} parameter provides crash-safe persistence for
     * {@code currentTerm} and {@code votedFor} as required by Raft §5.2.
     * On construction, any previously persisted state is loaded from storage.
     *
     * @param config       cluster configuration
     * @param log          the Raft log (caller retains a reference for inspection)
     * @param transport    message transport
     * @param stateMachine application state machine
     * @param random       random generator (seeded for deterministic testing)
     * @param storage      durable storage for persistent Raft state
     */
    public RaftNode(RaftConfig config, RaftLog log, RaftTransport transport,
                    StateMachine stateMachine, RandomGenerator random,
                    Storage storage, InvariantChecker invariantChecker) {
        this(config, log, transport, stateMachine, random, storage, invariantChecker,
                io.configd.common.IntegrityEnvelope.keyless());
    }

    /**
     * Creates a new RaftNode with an explicit at-rest integrity codec for the
     * durable Raft state (PA-2021 / ADR-0042). The server passes a KEYED
     * {@link io.configd.common.IntegrityEnvelope} so a forged {@code votedFor} is
     * refused on load; the other constructors default to keyless (unchanged
     * behavior for tests and in-memory mode).
     * <p>
     * Note: the snapshot/WAL integrity codec is carried by {@code log} (it was
     * constructed with the same envelope by the caller); this parameter only wires
     * the {@link DurableRaftState} envelope, which RaftNode owns.
     *
     * @param integrity the at-rest integrity codec for {@code raft.persistent_state}
     */
    public RaftNode(RaftConfig config, RaftLog log, RaftTransport transport,
                    StateMachine stateMachine, RandomGenerator random,
                    Storage storage, InvariantChecker invariantChecker,
                    io.configd.common.IntegrityEnvelope integrity) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.random = Objects.requireNonNull(random, "random");
        this.invariantChecker = invariantChecker != null ? invariantChecker : InvariantChecker.NOOP;

        // RR-006: convert the millisecond timing budgets to tick counts once,
        // using the configured tick period. RaftConfig already validated that the
        // derived election:heartbeat ratio is sane.
        this.electionTimeoutMinTicks = config.electionTimeoutMinTicks();
        this.electionTimeoutMaxTicks = config.electionTimeoutMaxTicks();
        this.heartbeatTimeoutTicks = config.heartbeatIntervalTicks();

        // Load persisted state (currentTerm, votedFor) from durable storage
        this.durableState = new DurableRaftState(Objects.requireNonNull(storage, "storage"),
                Objects.requireNonNull(integrity, "integrity"));
        this.currentTerm = durableState.currentTerm();
        this.votedFor = durableState.votedFor();
        this.role = RaftRole.FOLLOWER;
        this.leaderId = null;

        this.peerActivity = new HashMap<>();
        this.nextIndex = new HashMap<>();
        this.matchIndex = new HashMap<>();
        this.readIndexState = new ReadIndexState();

        // Initialize cluster config from static RaftConfig as default
        var allVoters = new java.util.HashSet<>(config.peers());
        allVoters.add(config.nodeId());
        this.clusterConfig = ClusterConfig.simple(allVoters);
        this.noopCommittedInCurrentTerm = false;
        this.configChangePending = false;

        // RR-003: restore the state machine from the durable snapshot BEFORE any
        // WAL suffix replays. Without this, a node that restarts after a
        // compaction starts with an EMPTY state machine and applyCommitted
        // silently advances past the missing (compacted) entries — total silent
        // loss of all committed state at/below the snapshot point.
        //
        // lastApplied is initialized to the recovered snapshotIndex whenever a
        // snapshot boundary exists: everything at/below snapshotIndex is, by
        // definition, folded into the snapshot, so the only entries left to
        // replay are the WAL suffix (snapshotIndex+1 .. commitIndex]. Seeding
        // lastApplied past the compacted prefix is also what stops applyCommitted
        // from walking indices [1..snapshotIndex] (which entryAt() returns null
        // for) and tripping the no-gap invariant on a perfectly-recovered node.
        //
        // RaftLog accepts the durable snapshot blob only when its boundary
        // matches the recovered WAL boundary (see RaftLog's recovery rule).
        SnapshotState recovered = log.recoveredSnapshot();
        if (recovered != null) {
            stateMachine.restoreSnapshot(recovered.data());
            latestSnapshot = recovered;
        }
        if (log.snapshotIndex() > 0) {
            // RR-003 (durable-prefix / no-gap invariant, recovery side): a
            // snapshot BOUNDARY exists but no snapshot BYTES restored it. The
            // state machine therefore does NOT reflect [1..snapshotIndex], yet we
            // are about to advance lastApplied past that range — exactly the
            // silent-skip that caused the data loss. This is unreachable when the
            // persist-before-truncate fix holds (a boundary always has matching
            // bytes); it fires only on genuinely unrecoverable storage (the blob
            // lost to medium corruption) or if persistence is defeated. Fail
            // LOUDLY: throw in test/sim, metric + SEVERE log in production — never
            // boot silently into a state machine missing committed entries.
            invariantChecker.check("durable_prefix_no_gap",
                    recovered != null,
                    "Recovered snapshot boundary snapshotIndex=" + log.snapshotIndex()
                            + " but no durable snapshot bytes to restore it — the recoverable"
                            + " prefix is incomplete; refusing to boot with silently-missing"
                            + " committed state below the snapshot point.");
            log.setLastApplied(log.snapshotIndex());
        }

        // Recover cluster config from the log if it contains config entries
        // from a prior run. This ensures config changes survive restarts.
        recomputeConfigFromLog();

        resetElectionTimeout();

        // H-3: seed the monitoring snapshot from the fully-recovered state so a scrape that races
        // startup observes a coherent zero/recovered view, never null. Construction legitimately runs
        // on the wiring thread and reads node state (same as the durable-recovery reads above).
        this.monitorView = buildMetrics();
    }

    /**
     * Creates a new RaftNode with durable storage, no invariant checking.
     */
    public RaftNode(RaftConfig config, RaftLog log, RaftTransport transport,
                    StateMachine stateMachine, RandomGenerator random, Storage storage) {
        this(config, log, transport, stateMachine, random, storage, null);
    }

    /**
     * Creates a new RaftNode with in-memory state (no durability).
     * <p>
     * <b>WARNING:</b> This constructor is for backward compatibility and
     * testing only. In production, use the constructor that accepts
     * {@link Storage} to guarantee Raft safety across restarts.
     */
    public RaftNode(RaftConfig config, RaftLog log, RaftTransport transport,
                    StateMachine stateMachine, RandomGenerator random) {
        this(config, log, transport, stateMachine, random, Storage.inMemory(), null);
    }

    // ========================================================================
    // R-01' owner-thread contract (the R-01 replacement; see docs/phase0/threading-contract.md)
    // ========================================================================

    /**
     * Binds the calling thread as this group's single owner. MUST be invoked as the first task
     * on the group's owner executor at wiring — NEVER during construction (the constructor runs
     * on the wiring thread and legitimately touches state). Static for the life of the process
     * (v1: no resharding). Until this is called the {@link #assertOwnerThread()} tripwire is inert,
     * so production (not yet wired to bind) and existing single-threaded tests are unaffected.
     * <p>
     * Public wiring API: invoked by the owner-executor wiring (Workstream B's {@code
     * MultiRaftDriver}) and by the deterministic-simulation harness, which binds every node to its
     * single drive thread so the tripwire is active across the randomized-schedule invariant surface.
     */
    public void bindOwnerThread() {
        this.ownerThread = Thread.currentThread();
    }

    /**
     * Stage 2 M2 — rehoming handoff, step "quiesce". Run on the CURRENT (losing) owner thread BEFORE the
     * routing flip + {@link #beginHandoff() detach}: force-syncs any entries buffered since the last sync
     * so the gaining owner adopts a clean, fully-durable state (no torn / half-buffered log carried across
     * the handoff). Owner-thread only — asserted here (the public-entry contract) and again inside
     * {@link #flushDurable()}. Public wiring API (the driver's {@code rehomeGroup} submits it as a task on
     * the losing owner). Inert at N=1 / no-rehome (no caller). See the M2 design doc §3 step 2.
     */
    public void quiesceForHandoff() {
        assertOwnerThread();
        flushDurable();
    }

    /**
     * Stage 2 M2 — rehoming handoff, step "detach". Begins a co-tenant rehoming of this group: the
     * CURRENT owner relinquishes the group by re-pointing {@link #ownerThread} at the {@link #HANDOFF}
     * sentinel. MUST be called on the current owner thread (asserted) — only the owner may detach. After
     * this, the group is "owned by nobody": every guarded entry point fires for any caller until a
     * gaining owner calls {@link #adoptOwnerThread()}. Public wiring API (the driver's {@code
     * rehomeGroup} calls it as a task on the losing owner). See the M2 design doc.
     */
    public void beginHandoff() {
        assertOwnerThread();          // only the current owner may begin the handoff
        this.ownerThread = HANDOFF;   // owned by nobody until adopt (volatile publish)
    }

    /**
     * Stage 2 M2 — rehoming handoff, step "adopt". The GAINING owner takes ownership by binding
     * {@link #ownerThread} to the calling (gaining-owner) thread. MUST run on the gaining owner thread,
     * and only on a node currently mid-handoff ({@code ownerThread==HANDOFF}, asserted — a double-adopt
     * or an adopt of a non-migrating node is a violation). Ordered AFTER the losing owner's {@link
     * #beginHandoff()} by the driver's executor {@code .get()} barrier, which also publishes all of the
     * losing owner's final state to this thread (no torn state). Public wiring API.
     */
    public void adoptOwnerThread() {
        if (ownerThread != HANDOFF) {
            invariantChecker.check("raft_owner_adopt", false,
                    "adoptOwnerThread() on a node not mid-handoff: ownerThread="
                            + (ownerThread == null ? "null" : ownerThread.getName())
                            + " (expected the HANDOFF sentinel) — double-adopt or wrong-state rehome");
        }
        this.ownerThread = Thread.currentThread();
    }

    /**
     * Stage 2 M2 — a NON-firing query (does not route through {@code invariantChecker}) used by the
     * driver's check-and-bounce. True iff this node is bound to an owner that is NOT the current thread
     * — a different owner, or the {@link #HANDOFF} sentinel mid-rehome. False if UNBOUND (legacy/test
     * wiring, net inert) or owned by the current thread — in both of those cases marshalled work may run
     * inline here. When true, the driver re-dispatches the work to the current owner instead of touching
     * the node off-owner (no loss, no misroute, no fire). Distinct from {@link #assertOwnerThread()},
     * which FIRES on mismatch.
     */
    public boolean boundToAnotherThread() {
        Thread o = ownerThread;
        return o != null && o != Thread.currentThread();
    }

    /**
     * R-01' tripwire: asserts the current thread is this group's bound owner. No-op until an owner
     * is bound. A violation routes through the existing {@code invariantChecker} (throws in
     * test/sim via a throwing checker; metric + SEVERE in production), exactly like the in-node
     * safety invariants — so the concurrent stress harness can prove it catches an off-owner access.
     */
    private void assertOwnerThread() {
        Thread owner = ownerThread;
        if (owner == null) {
            return; // inert until explicitly bound (Workstream B wiring / the stress harness)
        }
        Thread cur = Thread.currentThread();
        if (owner != cur) {
            invariantChecker.check("raft_owner_thread", false,
                    "RaftNode entry off owner thread: bound to '" + owner.getName()
                            + "' (id=" + owner.threadId() + ") but called from '" + cur.getName()
                            + "' (id=" + cur.threadId() + ") — R-01' single-owner invariant violated");
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Advances the internal timer by one tick. Called at a regular interval
     * (e.g., every 1ms). Drives election timeouts (FOLLOWER/CANDIDATE)
     * and heartbeat intervals (LEADER).
     */
    public void tick() {
        assertOwnerThread();
        switch (role) {
            case FOLLOWER, CANDIDATE -> tickElection();
            case LEADER -> tickHeartbeat();
        }
        // S7.5 group commit: cheap backstop — if any entry is still buffered unsynced and no flush
        // is scheduled (e.g. a dropped scheduling, or the linger window slipping past a tick),
        // schedule one now. The normal path always schedules from propose(); this only bounds the
        // worst-case durability latency to a single tick. Guarded on LEADER (followers fsync inline
        // in appendEntries); re-checks role since tickHeartbeat() above may have stepped us down.
        if (role == RaftRole.LEADER && !flushScheduled && log.lastIndex() > durableIndex) {
            scheduleFlush();
        }

        // H-3: republish the monitoring snapshot at the end of every tick (owner thread). Bounds the
        // monitor view's staleness to one tick interval; off-owner scrapes read it via monitorView().
        publishMonitorView();
    }

    /**
     * Processes an incoming Raft protocol message.
     * <p>
     * Accepts the sealed {@link RaftMessage} type, enabling the JIT to
     * devirtualize the switch dispatch (no megamorphic call site).
     *
     * @param message one of the sealed Raft message types
     */
    public void handleMessage(RaftMessage message) {
        assertOwnerThread();
        switch (message) {
            case AppendEntriesRequest req -> handleAppendEntries(req);
            case AppendEntriesResponse resp -> handleAppendEntriesResponse(resp);
            case RequestVoteRequest req -> handleRequestVote(req);
            case RequestVoteResponse resp -> handleRequestVoteResponse(resp);
            case TimeoutNowRequest req -> handleTimeoutNow(req);
            case InstallSnapshotRequest req -> handleInstallSnapshot(req);
            case InstallSnapshotResponse resp -> handleInstallSnapshotResponse(resp);
        }
    }

    /**
     * Proposes a command to be replicated. Only the leader can accept proposals.
     * <p>
     * RR-004 / ADR-0033: on acceptance the returned {@link ProposeOutcome} carries
     * the assigned {@code (index, term)} so the caller can register a
     * commit-outcome callback ({@link #whenCommitOutcome}) against the exact entry.
     * Acceptance is still leader-LOCAL append; it is NOT a commit acknowledgement.
     * The rejection reasons are unchanged.
     *
     * @param command the command bytes
     * @return the proposal outcome (accepted + position, or a rejection reason)
     */
    public ProposeOutcome propose(byte[] command) {
        assertOwnerThread();
        if (command == null || command.length == 0) {
            throw new IllegalArgumentException(
                    "Command must not be null or empty (empty commands are reserved for no-op entries)");
        }
        // Enforce the wire-encodable limit at the propose() boundary so an
        // oversized client command is rejected before it pollutes the log.
        // MUST equal RaftMessageCodec.MAX_COMMAND_LEN — the per-entry
        // ceiling enforced by the wire codec. The constants live in
        // different modules (configd-server contains the codec; this
        // module cannot import it). Cross-module ownership of this
        // constant is tracked as an iter-4 cleanup.
        final int MAX_COMMAND_LEN = 1 * 1024 * 1024;
        if (command.length > MAX_COMMAND_LEN) {
            throw new IllegalArgumentException(
                    "Command length " + command.length
                            + " exceeds wire-encodable max " + MAX_COMMAND_LEN
                            + " (see RaftMessageCodec.MAX_COMMAND_LEN)");
        }
        if (isConfigChangeEntry(command)) {
            throw new IllegalArgumentException(
                    "Client commands must not start with config change magic bytes (RCFG)");
        }
        if (role != RaftRole.LEADER) {
            return ProposeOutcome.rejected(ProposalResult.NOT_LEADER);
        }
        if (transferTarget != null) {
            return ProposeOutcome.rejected(ProposalResult.TRANSFER_IN_PROGRESS);
        }
        // Backpressure: reject if too many uncommitted entries (Hard Rule #12)
        long uncommitted = log.lastIndex() - log.commitIndex();
        if (uncommitted >= config.maxPendingProposals()) {
            return ProposeOutcome.rejected(ProposalResult.OVERLOADED);
        }
        long newIndex = log.lastIndex() + 1;
        long entryTerm = currentTerm;
        LogEntry entry = new LogEntry(newIndex, entryTerm, command);
        log.appendNoSync(entry);   // buffer durably-pending; the coalescing flush fsyncs the batch
        broadcastAppendEntries();  // replicate immediately — followers fsync before they ACK
        // S7.5 group commit: this leader's own commit-vote for the entry is deferred until the
        // coalescing flush force-syncs it (the durableIndex gate in maybeAdvanceCommitIndex). On
        // the single-node path the INLINE default scheduler flushes inline so the entry still
        // commits before propose() returns; a commit-outcome callback registered afterward
        // resolves immediately via appliedSeqByIndex (see whenCommitOutcome).
        scheduleFlush();
        return ProposeOutcome.accepted(newIndex, entryTerm);
    }

    /**
     * Initiates leadership transfer to the specified target node.
     * The leader will catch up the target and then send TimeoutNow.
     *
     * @param target the node to transfer leadership to
     * @return true if the transfer was initiated
     */
    public boolean transferLeadership(NodeId target) {
        assertOwnerThread();
        if (role != RaftRole.LEADER) {
            return false;
        }
        if (config.nodeId().equals(target)) {
            return false; // Cannot transfer to self
        }
        if (!clusterConfig.isVoter(target)) {
            return false; // Target not in cluster
        }
        if (configChangePending) {
            return false; // Unsafe during reconfig — could split-brain
        }
        this.transferTarget = target;
        // Send entries to catch up the target, then check if already caught up
        sendAppendEntries(target);
        maybeSendTimeoutNow();
        return true;
    }

    /**
     * Triggers a snapshot of the current state machine state and compacts
     * the log. Should be called periodically (e.g., when the log exceeds
     * a size threshold) to bound memory usage.
     * <p>
     * Only takes a snapshot if there are applied entries beyond the current
     * snapshot point.
     *
     * @return true if a snapshot was taken
     */
    public boolean triggerSnapshot() {
        assertOwnerThread();
        long appliedIndex = log.lastApplied();
        if (appliedIndex <= log.snapshotIndex()) {
            return false; // Nothing new to snapshot
        }
        long appliedTerm = log.termAt(appliedIndex);
        if (appliedTerm == -1) {
            return false; // Cannot determine term — should not happen
        }

        // INV-SI-1 twin (SnapshotInstallSpec.SnapshotBoundedByCommitted): the
        // local-snapshot path is the spec's LocalSnapshot action — a node must never
        // snapshot ahead of its committed state. We snapshot at lastApplied, which in
        // a correct system is always <= commitIndex; a regression that advanced
        // lastApplied past commitIndex (or snapshotted a future index) would let a node
        // hold a snapshot ahead of the committed log. This is the falsifiable
        // local-path twin (the receive path checks term-matching/no-revert instead).
        invariantChecker.check("snapshot_bounded",
                appliedIndex <= log.commitIndex(),
                "Local snapshot at index " + appliedIndex + " exceeds commitIndex "
                        + log.commitIndex() + " — snapshotting ahead of committed state (INV-SI-1)");

        byte[] snapshotData = stateMachine.snapshot();
        // FIND-0001 fix: capture the config at lastApplied, not the current
        // in-memory clusterConfig. The current clusterConfig may include
        // uncommitted config changes beyond lastApplied. A snapshot at
        // appliedIndex must record only the config that was effective at
        // that index, not a future uncommitted config.
        byte[] configData = serializeConfigChange(configAtIndex(appliedIndex));
        SnapshotState snapshot = new SnapshotState(snapshotData, appliedIndex, appliedTerm, configData);

        // RR-003 (durable-prefix invariant): persist the snapshot bytes DURABLY
        // BEFORE compaction deletes the WAL prefix. Until this fix the snapshot
        // lived only in the RAM field below, so snapshot -> WAL truncate ->
        // restart silently lost all committed state at/below appliedIndex. The
        // persist-then-compact ordering guarantees a complete prefix (persisted
        // snapshot OR intact WAL) is on durable storage at every instant.
        log.persistSnapshot(snapshot);
        latestSnapshot = snapshot;
        log.compact(appliedIndex, appliedTerm);
        return true;
    }

    /**
     * Threshold-gated Raft-log compaction (RR-005). Triggers
     * {@link #triggerSnapshot()} when the applied-but-not-yet-snapshotted span
     * ({@code lastApplied - snapshotIndex}) exceeds
     * {@code appliedSinceSnapshotThreshold}. This is the size/interval trigger the
     * wired server lacked: before it, the only {@code triggerSnapshot()} caller was
     * the circular {@code sendInstallSnapshot} (reachable only after a snapshot
     * already exists), so the WAL grew for the life of the process and a ≥ 2 GiB WAL
     * eventually crash-looped recovery (the {@code FileStorage} read cap). The
     * server tick loop calls this every tick via {@link io.configd.replication.MultiRaftDriver},
     * so compaction is now actually reachable; it preserves RR-003's
     * persist-before-truncate + {@code durable_prefix_no_gap} (it just calls the same
     * {@code triggerSnapshot()} path).
     *
     * @param appliedSinceSnapshotThreshold applied entries to retain past the snapshot
     *                                       point before compacting; must be &gt;= 0
     * @return true if a snapshot was taken
     */
    public boolean maybeCompact(long appliedSinceSnapshotThreshold) {
        assertOwnerThread();
        if (log.lastApplied() - log.snapshotIndex() > appliedSinceSnapshotThreshold) {
            return triggerSnapshot();
        }
        return false;
    }

    /**
     * Derives the effective cluster configuration at a given log index.
     * Scans backwards from {@code index} to find the most recent config
     * entry at or before that index. Falls back to snapshot config or
     * initial config if no config entries exist in the scanned range.
     * <p>
     * This is the Java equivalent of the TLA+ spec's {@code EffectiveConfig}
     * but bounded to a specific index rather than the full log.
     *
     * @param index the log index to derive config for
     * @return the effective ClusterConfig at that index
     */
    private ClusterConfig configAtIndex(long index) {
        for (long i = Math.min(index, log.lastIndex()); i > log.snapshotIndex(); i--) {
            LogEntry entry = log.entryAt(i);
            if (entry != null && isConfigChangeEntry(entry.command())) {
                return deserializeConfigChange(entry.command());
            }
        }
        // No config entry found in log after snapshot — fall back
        if (latestSnapshot != null && latestSnapshot.clusterConfigData() != null
                && isConfigChangeEntry(latestSnapshot.clusterConfigData())) {
            return deserializeConfigChange(latestSnapshot.clusterConfigData());
        }
        var allVoters = new HashSet<>(config.peers());
        allVoters.add(config.nodeId());
        return ClusterConfig.simple(allVoters);
    }

    /**
     * Starts a linearizable read using the ReadIndex protocol.
     * <p>
     * The returned read ID can be checked via {@link #isReadReady(long)}
     * after the next heartbeat round confirms leadership. The caller
     * should only execute the read against the state machine once
     * {@code isReadReady} returns true.
     * <p>
     * Returns -1 if this node is not the leader.
     *
     * @return a read ID for tracking, or -1 if not leader
     */
    public long readIndex() {
        assertOwnerThread();
        if (role != RaftRole.LEADER) {
            return -1;
        }
        // For single-node clusters, the read is immediately ready
        // since we are trivially the leader with full quorum
        long readId = readIndexState.startRead(log.commitIndex(), currentTerm);
        if (clusterConfig.peersOf(config.nodeId()).isEmpty()) {
            // Single-node cluster: self is always a quorum
            readIndexState.confirmAllLeadership();
        }
        return readId;
    }

    /**
     * Checks whether a previously requested read can be safely served.
     * <p>
     * This method re-verifies that this node is still the leader before
     * confirming readiness. Without this check, a deposed leader could
     * serve a stale read if leadership was lost between confirmation
     * and this call (FIND-0002).
     *
     * @param readId the read ID returned by {@link #readIndex()}
     * @return true if the read can be served with linearizable guarantees
     */
    public boolean isReadReady(long readId) {
        assertOwnerThread();
        // Re-verify leadership: a deposed leader must not serve reads.
        // This closes the TOCTOU window between heartbeat confirmation
        // and read serving (see FIND-0002).
        if (role != RaftRole.LEADER) {
            return false;
        }
        return readIndexState.isReady(readId, log.lastApplied());
    }

    /**
     * Test-only seam (RR-030 / §4.5 assertion-twin firing): registers a pending
     * ReadIndex with an arbitrary recorded {@code (readIndex, term)} and returns its
     * id, so the firing test can drive {@link #assertReadServeInvariants} with a
     * record that violates a ReadIndexSpec twin (readIndex ahead of lastApplied /
     * commitIndex, or term ahead of the node). Not on any production path.
     *
     * @param readIndex the recorded read index
     * @param term      the recorded read term
     * @return the read id
     */
    long injectPendingReadForTest(long readIndex, long term) {
        return readIndexState.startRead(readIndex, term);
    }

    /**
     * Test-only seam (RR-030 / §4.5 assertion-twin firing) for the in-node ConsensusSpec
     * twins whose production call sites sit behind guards that early-return whenever the
     * checked condition would be false, OR are inside private methods the protocol only
     * reaches in the holds-true state. These twins are structurally-guarded
     * defence-in-depth and cannot trip via the protocol path; this seam invokes the
     * IDENTICAL production {@code invariantChecker.check(name, false, …)} call shape (the
     * same expression and the same wired checker) with the condition forced false, so the
     * twin's wiring (throw in test/sim, metric+log in prod) is exercised and observed.
     * This is the same pattern {@code InvariantNetMetricTest} uses for per_key_order
     * (inject the violating precondition; the real check then fires). Documented as such
     * in {@code docs/session-2/assertion-verification.md}.
     *
     * @param name the in-node twin name to fire
     */
    void fireInNodeTwinForTest(String name) {
        switch (name) {
            case "election_safety" -> invariantChecker.check("election_safety",
                    false, "Became leader without quorum (forced firing — RR-030)");
            case "leader_completeness" -> invariantChecker.check("leader_completeness",
                    false, "New leader log behind commitIndex (forced firing — RR-030)");
            case "log_matching" -> invariantChecker.check("log_matching",
                    false, "Log matching violated (forced firing — RR-030)");
            case "version_monotonicity" -> invariantChecker.check("version_monotonicity",
                    false, "Apply entry index not > lastApplied (forced firing — RR-030)");
            case "state_machine_safety" -> invariantChecker.check("state_machine_safety",
                    false, "Entry index != expected nextApply (forced firing — RR-030)");
            case "single_server_invariant" -> invariantChecker.check("single_server_invariant",
                    false, "Multiple concurrent config changes detected (forced firing — RR-030)");
            case "no_op_before_reconfig" -> invariantChecker.check("no_op_before_reconfig",
                    false, "Reached config change path without no-op committed (forced firing — RR-030)");
            case "reconfig_safety" -> invariantChecker.check("reconfig_safety",
                    false, "Config change must use joint consensus (forced firing — RR-030)");
            case "durable_prefix_no_gap" -> invariantChecker.check("durable_prefix_no_gap",
                    false, "Recovered snapshot boundary with no durable bytes (forced firing — RR-030;"
                            + " real-path firing proven by SnapshotCrashRecoveryTest)");
            case "inflight_window_progress" -> invariantChecker.check("inflight_window_progress",
                    false, "Leader silenced toward a peer: inflight window pinned at the cap while the"
                            + " peer is inactive (forced firing — RR-103; real-path firing proven by"
                            + " Rr103InflightWindowRecoveryTest's mutation-revert)");
            default -> throw new IllegalArgumentException("not an in-node seam twin: " + name);
        }
    }

    /**
     * Marks a read as completed, releasing its tracking state.
     *
     * @param readId the read ID to complete
     */
    public void completeRead(long readId) {
        assertOwnerThread();
        readIndexState.complete(readId);
        readReadyCallbacks.remove(readId);
    }

    /**
     * Registers a one-shot callback to fire when the given read ID becomes
     * ready (via {@link #isReadReady(long)}). If the read is already ready
     * at registration time, the callback fires synchronously.
     * <p>
     * F-0022 fix: a single CompletableFuture per linearizable read, completed
     * from the tick thread when the read transitions to ready. Replaces the
     * per-poll CompletableFuture allocation that the previous dispatch loop
     * paid on every iteration.
     * <p>
     * MUST be called from the tick thread (single-threaded executor). All
     * access to {@link ReadIndexState} is tick-thread only per F-0010.
     *
     * @param readId   the read ID from {@link #readIndex()}
     * @param callback the callback to invoke exactly once when ready
     */
    public void whenReadReady(long readId, Runnable callback) {
        assertOwnerThread();
        Objects.requireNonNull(callback, "callback");
        if (isReadReady(readId)) {
            assertReadServeInvariants(readId);
            callback.run();
            return;
        }
        readReadyCallbacks.put(readId, callback);
    }

    /**
     * ReadIndexSpec runtime twins — checked at the exact moment a linearizable
     * read is served (the spec's CompleteReadIndex action), before the callback
     * runs against the state machine. All three are the runtime counterparts of
     * the de-vacuumed ReadIndexSpec safety invariants:
     * <ul>
     *   <li>{@code read_freshness} (INV-RI-3 ReadFreshness): a read is never served
     *       ahead of the applied state — {@code readIndex <= lastApplied}.</li>
     *   <li>{@code no_stale_leader_serve} (INV-RI-4 NoStaleLeaderServe): a read
     *       recorded at term T is never served after the node's term has moved past
     *       T (a stepped-down / stale leader serve) — {@code recordedTerm <=
     *       currentTerm} AND this node is still LEADER.</li>
     *   <li>{@code read_index_bounded} (INV-RI-2 ReadIndexBoundedByMaxIndex): the
     *       served readIndex never exceeds what was committed —
     *       {@code readIndex <= commitIndex}.</li>
     * </ul>
     * Called from both serve paths (immediate in {@link #whenReadReady} and the
     * deferred {@link #fireReadyCallbacks}). Tick-thread only.
     * <p>
     * Package-private (not private) so the assertion-twin firing test can drive it
     * directly with a poisoned {@link ReadIndexState} (a read whose recorded index
     * exceeds lastApplied/commitIndex, or whose term is ahead of the node) — the
     * §4.5 requirement that every twin be observed firing. In the live serve paths
     * the {@link #isReadReady} gate makes the freshness condition hold; the twin is
     * the defence-in-depth that catches a regression which corrupts the read record
     * between the readiness gate and the serve, or removes the gate entirely.
     */
    void assertReadServeInvariants(long readId) {
        long servedIdx = readIndexState.readIndex(readId);
        if (servedIdx < 0) {
            return; // unknown read id — nothing to assert
        }
        long recordedTerm = readIndexState.termOf(readId);

        invariantChecker.check("read_freshness",
                servedIdx <= log.lastApplied(),
                "ReadIndex serve at readIndex " + servedIdx + " exceeds lastApplied "
                        + log.lastApplied() + " — serving a read ahead of applied state (INV-RI-3)");

        invariantChecker.check("no_stale_leader_serve",
                role == RaftRole.LEADER && (recordedTerm == 0 || recordedTerm <= currentTerm),
                "ReadIndex serve: read recorded at term " + recordedTerm
                        + " served while node term=" + currentTerm + " role=" + role
                        + " — stale/stepped-down leader serve (INV-RI-4)");

        invariantChecker.check("read_index_bounded",
                servedIdx <= log.commitIndex(),
                "ReadIndex serve at readIndex " + servedIdx + " exceeds commitIndex "
                        + log.commitIndex() + " — served read beyond committed (INV-RI-2)");
    }

    /**
     * Internal: fires any ready callbacks. Called by the tick thread after
     * each heartbeat confirmation and each apply. Must not be called from
     * outside the tick thread.
     */
    private void fireReadyCallbacks() {
        if (readReadyCallbacks.isEmpty()) {
            return;
        }
        // Iterate via a snapshot to avoid ConcurrentModificationException if
        // a callback calls completeRead(readId) synchronously.
        var entries = new ArrayList<>(readReadyCallbacks.entrySet());
        for (var e : entries) {
            long readId = e.getKey();
            if (isReadReady(readId)) {
                assertReadServeInvariants(readId);
                Runnable cb = readReadyCallbacks.remove(readId);
                if (cb != null) {
                    try {
                        cb.run();
                    } catch (Throwable t) {
                        // A bad callback must not kill the tick thread.
                        System.err.println("RaftNode: readReady callback threw: " + t);
                    }
                }
            }
        }
    }

    // ========================================================================
    // Commit-outcome seam (RR-004 / ADR-0033)
    // ========================================================================

    /**
     * Registers a one-shot callback fired exactly once with the
     * {@link CommitOutcome} of the entry proposed at {@code (index, term)}.
     * Symmetric to {@link #whenReadReady}: if the outcome is already decidable at
     * registration time it fires inline (required for the single-node
     * immediate-commit path); otherwise it is stored and swept on every apply,
     * step-down, and snapshot install covering {@code index}.
     * <p>
     * Outcome predicates (see {@link CommitOutcome}): COMMITTED when the entry
     * applied at {@code index} carries {@code term}; LOST when a different term
     * applied at {@code index} (Log Matching makes the slot permanent);
     * INDETERMINATE_LOCALLY when an InstallSnapshot covers {@code index} before it
     * applied. Truncation-without-apply and step-down do NOT resolve to LOST —
     * those remain pending and surface as Indeterminate at the service deadline
     * (by design: a false "definitely lost" that later commits is a phantom
     * write).
     * <p>
     * MUST be called from the tick thread (single-threaded executor); all access
     * to the callback map is tick-thread only, preserving R-01.
     *
     * @param index    the proposed log index (from {@link ProposeOutcome#index()})
     * @param term     the proposed term (from {@link ProposeOutcome#term()})
     * @param callback invoked exactly once with the outcome
     */
    public void whenCommitOutcome(long index, long term,
                                  java.util.function.Consumer<CommitOutcome> callback) {
        assertOwnerThread();
        Objects.requireNonNull(callback, "callback");
        CommitOutcome decided = decideCommitOutcome(index, term);
        if (decided != null) {
            invokeOutcome(callback, decided);
            return;
        }
        commitOutcomeCallbacks.put(index, new PendingCommit(term, callback));
    }

    /**
     * Cancels a pending commit-outcome callback for {@code index}, releasing its
     * map entry. Called from the tick thread when the registrant has abandoned the
     * wait (its deadline expired and it already reported Indeterminate). Mirrors
     * {@link #completeRead}'s cleanup: prevents a map-entry leak when the outcome
     * would otherwise never become decidable (e.g. an isolated leader that never
     * steps down). No-op if the callback already fired.
     *
     * @param index the proposed log index whose pending callback to drop
     */
    public void cancelCommitOutcome(long index) {
        assertOwnerThread();
        commitOutcomeCallbacks.remove(index);
    }

    /**
     * Returns the decided {@link CommitOutcome} for {@code (index, term)} if it is
     * already determinable from local state, or {@code null} if still pending.
     * <p>
     * Decidable only once {@code lastApplied >= index}: at that point Raft Log
     * Matching guarantees the entry at {@code index} is permanent on this node, so
     * a same-term match is COMMITTED and a different-term match is LOST. If the
     * index has been compacted into a snapshot below {@code lastApplied} without a
     * recorded applied seq, the per-index term is unrecoverable → INDETERMINATE.
     */
    private CommitOutcome decideCommitOutcome(long index, long term) {
        if (log.lastApplied() < index) {
            return null; // not yet applied — outcome still open
        }
        // Applied. Recover the term that actually occupies this index.
        if (index <= log.snapshotIndex()) {
            // Index folded into a snapshot. If we recorded the applied seq while
            // applying it (the proposing leader's normal path), trust that record;
            // otherwise the per-index term is gone (snapshot install on a lagging
            // follower) → locally indeterminate.
            Long seq = appliedSeqByIndex.get(index);
            if (seq != null) {
                return CommitOutcome.committed(seq);
            }
            return CommitOutcome.indeterminateLocally();
        }
        LogEntry entry = log.entryAt(index);
        if (entry == null) {
            // Applied yet absent from the live log and not in a snapshot — should
            // not happen on a consistent node; report indeterminate rather than
            // fabricate a definite outcome.
            return CommitOutcome.indeterminateLocally();
        }
        if (entry.term() == term) {
            Long seq = appliedSeqByIndex.get(index);
            // Same (index,term) => our proposal, committed and applied. seq was
            // recorded at apply; for a non-mutating entry it is the current
            // sequence (any S <= current version satisfies RYW for a no-op).
            return CommitOutcome.committed(seq != null ? seq : currentAppliedSequence());
        }
        // Different term occupies this slot permanently (Log Matching) → lost.
        return CommitOutcome.lost();
    }

    /**
     * Fires any pending commit-outcome callbacks that are now decidable. Called
     * from the tick thread after each apply ({@link #applyCommitted}) and after a
     * snapshot install. Late completion is impossible to double-fire: the entry is
     * removed from the map before the callback runs.
     */
    private void fireCommitOutcomes() {
        if (commitOutcomeCallbacks.isEmpty()) {
            return;
        }
        var entries = new ArrayList<>(commitOutcomeCallbacks.entrySet());
        for (var e : entries) {
            long index = e.getKey();
            PendingCommit pending = e.getValue();
            CommitOutcome outcome = decideCommitOutcome(index, pending.term());
            if (outcome != null) {
                if (commitOutcomeCallbacks.remove(index) != null) {
                    invokeOutcome(pending.callback(), outcome);
                }
            }
        }
    }

    /**
     * Fires INDETERMINATE_LOCALLY for any pending callback whose index is covered
     * by a freshly installed snapshot but was not applied before the install
     * (per-index term unrecoverable from the snapshot). Callbacks whose index the
     * apply path already recorded resolve as COMMITTED via {@link #fireCommitOutcomes}.
     */
    private void fireSnapshotIndeterminate(long snapshotIndex) {
        if (commitOutcomeCallbacks.isEmpty()) {
            return;
        }
        var entries = new ArrayList<>(commitOutcomeCallbacks.entrySet());
        for (var e : entries) {
            long index = e.getKey();
            if (index <= snapshotIndex && !appliedSeqByIndex.containsKey(index)) {
                PendingCommit pending = commitOutcomeCallbacks.remove(index);
                if (pending != null) {
                    invokeOutcome(pending.callback(), CommitOutcome.indeterminateLocally());
                }
            }
        }
    }

    private static void invokeOutcome(java.util.function.Consumer<CommitOutcome> cb,
                                      CommitOutcome outcome) {
        try {
            cb.accept(outcome);
        } catch (Throwable t) {
            // A bad callback must not kill the tick thread.
            System.err.println("RaftNode: commitOutcome callback threw: " + t);
        }
    }

    /**
     * The current applied-mutation sequence, used as the COMMITTED seq for a
     * non-mutating entry. Delegates to the state machine when it exposes a
     * sequence accessor; otherwise falls back to {@code lastApplied}.
     */
    private long currentAppliedSequence() {
        if (lastRecordedSeq >= 0) {
            return lastRecordedSeq;
        }
        return log.lastApplied();
    }

    /**
     * The most recent applied-mutation seq observed from {@link StateMachine#apply}.
     * Tick-thread-only (written in {@code applyCommitted}, read in
     * {@code currentAppliedSequence} on the commit-outcome path), so atomicity is
     * not strictly required; declared {@code volatile} to keep the 64-bit write
     * atomic and clear the SpotBugs AT_NONATOMIC_64BIT_PRIMITIVE finding (RR-025).
     */
    private volatile long lastRecordedSeq = -1;

    // ========================================================================
    // Reconfiguration (Joint Consensus — Raft §6)
    // ========================================================================

    /**
     * Proposes a membership change using the joint consensus protocol (Raft §6).
     * <p>
     * The leader first appends a joint config entry C_old,new to the log.
     * Once committed (requiring agreement from both old and new majorities),
     * the leader automatically appends C_new. Once C_new is committed, the
     * transition is complete.
     * <p>
     * Preconditions:
     * <ul>
     *   <li>This node must be the leader</li>
     *   <li>No other config change may be in-flight (one at a time)</li>
     *   <li>A no-op entry must have been committed in the current term
     *       (prevents the single-server reconfig bug)</li>
     *   <li>No leadership transfer in progress</li>
     * </ul>
     *
     * @param newVoters the proposed new voter set
     * @return true if the config change was accepted and appended to the log
     */
    public boolean proposeConfigChange(Set<NodeId> newVoters) {
        assertOwnerThread();
        if (role != RaftRole.LEADER) {
            return false;
        }
        if (transferTarget != null) {
            return false;
        }
        if (configChangePending) {
            return false; // Only one config change at a time
        }
        if (!noopCommittedInCurrentTerm) {
            return false; // Must commit no-op first (Ongaro, raft-dev 2015)
        }
        if (newVoters.equals(clusterConfig.voters())) {
            return false; // No change needed
        }

        // INV-8: SingleServerInvariant — only one config change in-flight at a time
        invariantChecker.check("single_server_invariant",
                !configChangePending,
                "Multiple concurrent config changes detected");

        // INV-7: NoOpBeforeReconfig — at this point, no-op must be committed
        invariantChecker.check("no_op_before_reconfig",
                noopCommittedInCurrentTerm,
                "Reached config change path without no-op committed");

        // Create joint config C_old,new
        ClusterConfig jointConfig = ClusterConfig.joint(clusterConfig.voters(), newVoters);

        // INV-6: ReconfigSafety — joint config must require quorums from both sets
        invariantChecker.check("reconfig_safety",
                jointConfig.isJoint(),
                "Config change must use joint consensus");

        clusterConfig = jointConfig;
        configChangePending = true;

        // Append config change entry to log with a config change marker
        // Using a special prefix byte to distinguish config entries from normal commands
        byte[] configEntry = serializeConfigChange(jointConfig);
        long newIndex = log.lastIndex() + 1;
        LogEntry entry = new LogEntry(newIndex, currentTerm, configEntry);
        log.append(entry);                 // durable control entry (appendNoSync + syncWal)
        durableIndex = log.lastIndex();    // S7.5: synced — the leader may count it (gating)

        // Initialize tracking for any new peers added by this config change
        for (NodeId peer : clusterConfig.peersOf(config.nodeId())) {
            nextIndex.putIfAbsent(peer, log.lastIndex() + 1);
            matchIndex.putIfAbsent(peer, 0L);
            peerActivity.putIfAbsent(peer, Boolean.TRUE);
        }

        broadcastAppendEntries();
        maybeAdvanceCommitIndex();
        return true;
    }

    /**
     * Returns the current cluster configuration.
     */
    public ClusterConfig clusterConfig() {
        // H-3: OWNER-THREAD-ONLY. ClusterConfig carries a lazy peersCache (HashMap) populated on first
        // peersOf(), so an off-owner read races that cache regardless of field visibility. Monitors read
        // the published view, not the live config. Guard inert until bindOwnerThread().
        assertOwnerThread();
        return clusterConfig;
    }

    /** 4-byte magic prefix for config change entries: "RCFG" in ASCII. */
    private static final byte[] CONFIG_CHANGE_MAGIC = {0x52, 0x43, 0x46, 0x47};

    /**
     * Serializes a config change entry. Uses a 4-byte magic prefix "RCFG"
     * to distinguish from normal application commands.
     */
    private static byte[] serializeConfigChange(ClusterConfig config) {
        // Format: [RCFG:4bytes][isJoint:1byte][oldVoterCount:4bytes][oldVoterIds...][newVoterCount:4bytes][newVoterIds...]
        int size = 4 + 1 + 4 + config.voters().size() * 4;
        if (config.isJoint()) {
            size += 4 + config.newVoters().size() * 4;
        }
        var buf = java.nio.ByteBuffer.allocate(size);
        buf.put(CONFIG_CHANGE_MAGIC); // Config change marker
        buf.put(config.isJoint() ? (byte) 1 : (byte) 0);
        buf.putInt(config.voters().size());
        for (NodeId v : config.voters()) {
            buf.putInt(v.id());
        }
        if (config.isJoint()) {
            buf.putInt(config.newVoters().size());
            for (NodeId v : config.newVoters()) {
                buf.putInt(v.id());
            }
        }
        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * Checks if a log entry is a config change entry (starts with "RCFG" magic).
     */
    static boolean isConfigChangeEntry(byte[] command) {
        return command != null && command.length >= 4
                && command[0] == CONFIG_CHANGE_MAGIC[0]
                && command[1] == CONFIG_CHANGE_MAGIC[1]
                && command[2] == CONFIG_CHANGE_MAGIC[2]
                && command[3] == CONFIG_CHANGE_MAGIC[3];
    }

    /**
     * Deserializes a config change entry back to a ClusterConfig.
     * Inverse of {@link #serializeConfigChange(ClusterConfig)}.
     *
     * @throws IllegalArgumentException if the entry is truncated or corrupt
     */
    static ClusterConfig deserializeConfigChange(byte[] command) {
        try {
            var buf = java.nio.ByteBuffer.wrap(command);
            buf.position(4); // skip RCFG magic
            boolean isJoint = buf.get() == 1;
            int oldCount = buf.getInt();
            if (oldCount < 0 || oldCount > 255) {
                throw new IllegalArgumentException("Invalid voter count: " + oldCount);
            }
            var oldVoters = new HashSet<NodeId>(oldCount);
            for (int i = 0; i < oldCount; i++) {
                oldVoters.add(NodeId.of(buf.getInt()));
            }
            if (isJoint) {
                int newCount = buf.getInt();
                if (newCount < 0 || newCount > 255) {
                    throw new IllegalArgumentException("Invalid new voter count: " + newCount);
                }
                var newVoters = new HashSet<NodeId>(newCount);
                for (int i = 0; i < newCount; i++) {
                    newVoters.add(NodeId.of(buf.getInt()));
                }
                return ClusterConfig.joint(oldVoters, newVoters);
            }
            return ClusterConfig.simple(oldVoters);
        } catch (java.nio.BufferUnderflowException e) {
            throw new IllegalArgumentException("Truncated config change entry (" + command.length + " bytes)", e);
        }
    }

    /**
     * Recomputes the cluster configuration from the log.
     * <p>
     * Per the Raft dissertation (Section 4.1): "A server always uses the
     * latest configuration in its log, regardless of whether the entry is
     * committed." This must be called after any operation that modifies
     * the log (AppendEntries, InstallSnapshot) to ensure the node's
     * cluster config reflects the latest config entry in its log.
     * <p>
     * Corresponds to the TLA+ spec's {@code EffectiveConfig(newLog)} which
     * scans the log for the most recent config entry.
     */
    private void recomputeConfigFromLog() {
        recomputeConfigFromLog(null);
    }

    /**
     * Recomputes the cluster configuration from the log, with an optional
     * fallback config from snapshot metadata.
     * <p>
     * When the log is fully compacted past all config entries (e.g., after
     * a snapshot install where all config entries were in the snapshot),
     * the fallback config is used instead of reverting to the initial
     * static configuration. This prevents a node from silently losing
     * membership changes after snapshot-based recovery.
     *
     * @param snapshotConfigData serialized ClusterConfig from the snapshot,
     *                           or null if no snapshot config is available
     */
    private void recomputeConfigFromLog(byte[] snapshotConfigData) {
        // Scan from the end of the log backwards to find the latest config entry
        for (long i = log.lastIndex(); i > log.snapshotIndex(); i--) {
            LogEntry entry = log.entryAt(i);
            if (entry != null && isConfigChangeEntry(entry.command())) {
                clusterConfig = deserializeConfigChange(entry.command());
                // Update configChangePending based on whether this entry is committed
                configChangePending = (i > log.commitIndex());
                return;
            }
        }
        // No config entry found in log after snapshot.
        // Use the snapshot's config if available (preserves reconfig state across
        // snapshot-based recovery). Fall back to initial config only if no
        // snapshot config exists (fresh node, no reconfigurations ever).
        if (snapshotConfigData != null && isConfigChangeEntry(snapshotConfigData)) {
            clusterConfig = deserializeConfigChange(snapshotConfigData);
        } else if (latestSnapshot != null && latestSnapshot.clusterConfigData() != null
                && isConfigChangeEntry(latestSnapshot.clusterConfigData())) {
            clusterConfig = deserializeConfigChange(latestSnapshot.clusterConfigData());
        } else {
            var allVoters = new HashSet<>(config.peers());
            allVoters.add(config.nodeId());
            clusterConfig = ClusterConfig.simple(allVoters);
        }
        configChangePending = false;
    }

    /**
     * Returns an immutable snapshot of this node's current Raft state
     * for monitoring and diagnostics.
     *
     * @return current metrics
     */
    public RaftMetrics metrics() {
        assertOwnerThread();
        return buildMetrics();
    }

    /**
     * Builds an immutable metrics snapshot from the current node state. Owner-thread-only (no guard):
     * private, invoked from the guarded {@link #metrics()}, from {@link #publishMonitorView()} on the
     * owner thread, and once from the constructor (the wiring thread, which legitimately reads node
     * state) to seed {@link #monitorView}.
     */
    private RaftMetrics buildMetrics() {
        int replicationLagMax = 0;
        if (role == RaftRole.LEADER) {
            long lastIdx = log.lastIndex();
            for (NodeId peer : clusterConfig.peersOf(config.nodeId())) {
                long peerMatch = matchIndex.getOrDefault(peer, 0L);
                int lag = (int) (lastIdx - peerMatch);
                if (lag > replicationLagMax) {
                    replicationLagMax = lag;
                }
            }
        }
        return new RaftMetrics(
                config.nodeId(),
                role,
                currentTerm,
                leaderId,
                log.commitIndex(),
                log.lastApplied(),
                log.lastIndex(),
                log.snapshotIndex(),
                log.size(),
                replicationLagMax
        );
    }

    /**
     * H-3: republish the owner-built monitoring snapshot through the single volatile reference.
     * Owner-thread-only (called at the end of {@link #tick()}). One volatile store of an immutable
     * record — never blocks the owner.
     */
    private void publishMonitorView() {
        this.monitorView = buildMetrics();
    }

    /**
     * H-3 SAFE-CROSS-THREAD monitoring read: returns the last owner-published immutable
     * {@link RaftMetrics} snapshot. A single volatile load — it never tears, never observes a
     * partially-updated structure, never blocks the owner, and is at most one tick interval stale.
     * This is the ONLY supported way for a non-owner thread (Prometheus scrape, admin status) to read
     * consensus monitoring state; the live accessors ({@link #currentTerm()}, {@link #log()}, …) are
     * owner-thread-only and guarded. See docs/phase0-B/h3-monitor-view-design.md.
     */
    public RaftMetrics monitorView() {
        return monitorView;
    }

    // ---- Getters for state inspection (tests and monitoring) ----

    public RaftRole role() { return role; }
    public NodeId leaderId() { return leaderId; }
    public NodeId nodeId() { return config.nodeId(); }

    // H-3: currentTerm/votedFor/log/transferTarget read NON-VOLATILE consensus state and are now
    // OWNER-THREAD-ONLY. Off-owner monitoring must use monitorView() instead; these would otherwise be
    // unsynchronised reads once owners are bound. The guard is inert until bindOwnerThread() (so
    // existing single-threaded tests and the not-yet-bound production server are unaffected) and, once
    // bound, trips raft_owner_thread on any off-owner read — converting the former H-3 blind spot into
    // net-covered surface. See docs/phase0-B/h3-monitor-view-design.md.
    public long currentTerm() { assertOwnerThread(); return currentTerm; }
    public NodeId votedFor() { assertOwnerThread(); return votedFor; }
    public RaftLog log() { assertOwnerThread(); return log; }
    public NodeId transferTarget() { assertOwnerThread(); return transferTarget; }

    // ========================================================================
    // Timer logic
    // ========================================================================

    private void tickElection() {
        electionTicksElapsed++;
        if (electionTicksElapsed >= electionTimeoutTicks) {
            electionTicksElapsed = 0;
            // Election timer fired — we haven't heard from any leader for the
            // full timeout duration. Clear leaderId so we (and our peers via
            // PreVote) know there is no known healthy leader. Without this,
            // the hasRecentLeader check in handlePreVoteRequest creates a
            // livelock after leader isolation: two followers reject each
            // other's PreVotes indefinitely because both think the old leader
            // is still "recent" even though neither has received a heartbeat.
            leaderId = null;
            startPreVote();
        }
    }

    private void tickHeartbeat() {
        heartbeatTicksElapsed++;
        if (heartbeatTicksElapsed >= heartbeatTimeoutTicks) {
            heartbeatTicksElapsed = 0;
            // CheckQuorum: verify that a quorum of peers have been active.
            // Uses set-based isQuorum() to correctly handle joint consensus
            // where both old and new majorities must be active.
            Set<NodeId> activeSet = buildActiveSetAndReset();
            if (!clusterConfig.isQuorum(activeSet)) {
                becomeFollower(currentTerm);
                return;
            }
            confirmPendingReads(activeSet);

            // RR-103: heartbeat decay of the per-peer pipelining window. The window
            // (inflightCount, capped at maxInflightAppends) is incremented on every
            // send and decremented ONLY by a response. Because the periodic heartbeat
            // is itself routed through sendAppendEntries — which skips a peer once its
            // window is full — a peer that loses maxInflightAppends messages
            // (partition / crash / drop) pins the window at the cap and is PERMANENTLY
            // silenced for the rest of the term: no heartbeat, no backfill, no
            // InstallSnapshot, no error, no metric (only a term change resets the map).
            // A heartbeat is a liveness guarantee and must never be suppressible by a
            // flow-control optimization. Free the window of any peer that is BOTH pinned
            // at the cap (the silenced state) AND absent from the active set (no response
            // this interval ⇒ its in-flight RPCs are presumed lost), before the broadcast,
            // so this heartbeat reaches it. Narrowing to the pinned-AND-inactive state
            // (rather than every inactive peer) keeps the window intact for a merely
            // congested-but-alive peer whose RTT exceeds one heartbeat interval (a WAN
            // follower, architecture §12) — it would otherwise be reset mid-pipeline and
            // briefly over-send. A peer draining normally stays in the active set and is
            // never touched.
            for (NodeId peer : clusterConfig.peersOf(config.nodeId())) {
                if (!activeSet.contains(peer)
                        && inflightCount.getOrDefault(peer, 0) >= config.maxInflightAppends()) {
                    inflightCount.put(peer, 0);
                }
                // RR-103 inflight-accounting twin (defence-in-depth, like the other
                // structurally-guarded ConsensusSpec twins): at heartbeat-broadcast time no
                // peer may be both silenced (window at the cap) AND not draining (inactive
                // this interval) — exactly the permanent-silence state. The decay above
                // re-establishes this, so on the un-mutated path the check is a postcondition
                // that always holds; its live value is that a regression DROPPING the decay
                // trips it on the SENDER under any window-pinning seed (throw in test/sim,
                // metric + SEVERE log in prod). Real-path firing is proven by mutation-revert
                // (review §5(iii)); forced firing by AssertionTwinFiringTest.
                invariantChecker.check("inflight_window_progress",
                        inflightCount.getOrDefault(peer, 0) < config.maxInflightAppends()
                                || activeSet.contains(peer),
                        "leader silenced toward peer " + peer + ": inflightCount="
                                + inflightCount.getOrDefault(peer, 0) + " at cap "
                                + config.maxInflightAppends() + " and peer inactive this interval");
            }

            broadcastAppendEntries();
        }
    }

    // ========================================================================
    // AppendEntries handling
    // ========================================================================

    private void handleAppendEntries(AppendEntriesRequest req) {
        // Rule: if term < currentTerm, reject (Raft §5.1)
        if (req.term() < currentTerm) {
            transport.send(req.leaderId(),
                    new AppendEntriesResponse(currentTerm, false, 0, config.nodeId()));
            return;
        }

        // If we see a higher term, step down
        if (req.term() > currentTerm) {
            becomeFollower(req.term());
        } else if (role == RaftRole.CANDIDATE) {
            // If we're a candidate and receive AppendEntries with our current term,
            // a leader has been elected — step down
            becomeFollower(req.term());
        }

        // Reset election timer — we heard from the leader
        electionTicksElapsed = 0;
        leaderId = req.leaderId();

        // Attempt to append entries
        boolean success = log.appendEntries(req.prevLogIndex(), req.prevLogTerm(), req.entries());
        if (!success) {
            transport.send(req.leaderId(),
                    new AppendEntriesResponse(currentTerm, false, 0, config.nodeId()));
            return;
        }

        // INV-3: LogMatching — if two logs contain an entry with the same
        // index and term, they are identical in all preceding entries.
        // This is structurally guaranteed by the AppendEntries consistency check,
        // but we assert it was applied correctly.
        if (!req.entries().isEmpty()) {
            LogEntry lastAppended = req.entries().getLast();
            LogEntry stored = log.entryAt(lastAppended.index());
            invariantChecker.check("log_matching",
                    stored != null && stored.term() == lastAppended.term(),
                    "Log matching violated at index " + lastAppended.index());

            // Raft §4.1: "A server always uses the latest configuration in
            // its log, regardless of whether the entry is committed."
            // Recompute config after any log modification (append or truncation)
            // to match the TLA+ spec's EffectiveConfig(newLog).
            recomputeConfigFromLog();
        }

        // Advance commit index (Raft §5.3 rule 5)
        if (req.leaderCommit() > log.commitIndex()) {
            long lastNewIndex = req.entries().isEmpty() ? log.lastIndex()
                    : req.entries().getLast().index();
            log.setCommitIndex(Math.min(req.leaderCommit(), lastNewIndex));
        }

        applyCommitted();

        // Report the last verified index — the last entry in the batch, or
        // prevLogIndex for an empty heartbeat.  Using log.lastIndex() would be
        // incorrect when the leader limits batch size: entries beyond the batch
        // are unverified and must not be counted as matched.
        long matchIndex = req.entries().isEmpty()
                ? req.prevLogIndex()
                : req.entries().getLast().index();
        transport.send(req.leaderId(),
                new AppendEntriesResponse(currentTerm, true, matchIndex, config.nodeId()));
    }

    private void handleAppendEntriesResponse(AppendEntriesResponse resp) {
        if (role != RaftRole.LEADER) {
            return;
        }

        // Step down if we see a higher term
        if (resp.term() > currentTerm) {
            becomeFollower(resp.term());
            return;
        }

        // Ignore stale responses from prior terms
        if (resp.term() != currentTerm) {
            return;
        }

        // Record peer activity for CheckQuorum
        peerActivity.put(resp.from(), Boolean.TRUE);
        inflightCount.merge(resp.from(), -1, (a, b) -> Math.max(0, a + b));

        if (resp.success()) {
            // Update nextIndex and matchIndex for the follower
            long newMatchIndex = resp.matchIndex();
            matchIndex.put(resp.from(), newMatchIndex);
            nextIndex.put(resp.from(), newMatchIndex + 1);

            maybeAdvanceCommitIndex();
            applyCommitted();

            // If we're transferring leadership and target is caught up, send TimeoutNow
            maybeSendTimeoutNow();
        } else {
            // Decrement nextIndex and retry (Raft §5.3)
            long ni = nextIndex.getOrDefault(resp.from(), log.lastIndex() + 1);
            nextIndex.put(resp.from(), Math.max(1, ni - 1));
            sendAppendEntries(resp.from());
        }
    }

    // ========================================================================
    // RequestVote handling
    // ========================================================================

    private void handleRequestVote(RequestVoteRequest req) {
        if (req.preVote()) {
            handlePreVoteRequest(req);
            return;
        }

        // Non-voters must not grant votes (TLA+ spec: m ∈ VotingMembers(config[m]))
        if (!clusterConfig.isVoter(config.nodeId())) {
            transport.send(req.candidateId(),
                    new RequestVoteResponse(currentTerm, false, config.nodeId(), false));
            return;
        }

        // Rule: if term < currentTerm, reject
        if (req.term() < currentTerm) {
            transport.send(req.candidateId(),
                    new RequestVoteResponse(currentTerm, false, config.nodeId(), false));
            return;
        }

        // Step down if we see a higher term
        if (req.term() > currentTerm) {
            becomeFollower(req.term());
        }

        // Grant vote if: (a) we haven't voted for someone else in this term,
        // and (b) candidate's log is at least as up-to-date as ours (§5.4.1)
        boolean canVote = (votedFor == null || votedFor.equals(req.candidateId()));
        boolean logOk = log.isAtLeastAsUpToDate(req.lastLogTerm(), req.lastLogIndex());

        if (canVote && logOk) {
            durableState.vote(req.candidateId()); // Persist BEFORE in-memory update (Raft §5.2)
            votedFor = req.candidateId();
            electionTicksElapsed = 0; // reset timer on granting vote
            transport.send(req.candidateId(),
                    new RequestVoteResponse(currentTerm, true, config.nodeId(), false));
        } else {
            transport.send(req.candidateId(),
                    new RequestVoteResponse(currentTerm, false, config.nodeId(), false));
        }
    }

    private void handlePreVoteRequest(RequestVoteRequest req) {
        // PreVote (§9.6): respond based on whether we WOULD grant a vote.
        // Do not update term, do not record a vote.
        //
        // Reject if:
        //  (a) req.term < currentTerm — the candidate is stale
        //  (b) we have a current leader and our election timer has not expired
        //      (meaning we recently heard from a leader, so the cluster is healthy
        //      and this candidate is likely partitioned)
        //  (c) the candidate's log is not at least as up-to-date as ours
        boolean wouldGrantPreVote;
        if (req.term() < currentTerm) {
            wouldGrantPreVote = false;
        } else {
            // A follower with a known leader that hasn't timed out should reject
            // PreVote regardless of term. This is the core mechanism that prevents
            // partitioned nodes from disrupting the cluster.
            boolean hasRecentLeader = role == RaftRole.FOLLOWER
                    && leaderId != null
                    && electionTicksElapsed < electionTimeoutTicks;
            boolean logOk = log.isAtLeastAsUpToDate(req.lastLogTerm(), req.lastLogIndex());
            wouldGrantPreVote = !hasRecentLeader && logOk;
        }

        transport.send(req.candidateId(),
                new RequestVoteResponse(currentTerm, wouldGrantPreVote, config.nodeId(), true));
    }

    private void handleRequestVoteResponse(RequestVoteResponse resp) {
        // Step down if we see a higher term
        if (resp.term() > currentTerm) {
            becomeFollower(resp.term());
            return;
        }

        if (resp.preVote()) {
            handlePreVoteResponse(resp);
            return;
        }

        // Only relevant if we're a candidate
        if (role != RaftRole.CANDIDATE) {
            return;
        }
        if (resp.term() != currentTerm) {
            return;
        }

        if (resp.voteGranted()) {
            votesReceived.add(resp.from());
            // Use isQuorum for correct dual-majority check during joint consensus
            if (clusterConfig.isQuorum(votesReceived)) {
                becomeLeader();
            }
        }
    }

    private void handlePreVoteResponse(RequestVoteResponse resp) {
        if (!preVoteInProgress) {
            return;
        }
        // PreVote responses don't change term
        if (resp.voteGranted()) {
            preVotesReceived.add(resp.from());
            // Use isQuorum for correct dual-majority check during joint consensus
            if (clusterConfig.isQuorum(preVotesReceived)) {
                // PreVote succeeded — start real election
                preVoteInProgress = false;
                startElection();
            }
        }
    }

    // ========================================================================
    // TimeoutNow handling (leadership transfer)
    // ========================================================================

    private void handleTimeoutNow(TimeoutNowRequest req) {
        if (req.term() < currentTerm) {
            return; // Stale
        }
        if (req.term() > currentTerm) {
            becomeFollower(req.term());
        }
        // Immediately start an election — bypass PreVote
        startElection();
    }

    // ========================================================================
    // State transitions
    // ========================================================================

    /**
     * Transitions to FOLLOWER state. Clears leader-specific state.
     */
    private void becomeFollower(long newTerm) {
        if (newTerm > currentTerm) {
            durableState.setTerm(newTerm); // Persist BEFORE in-memory update (crash safety)
            currentTerm = newTerm;
            votedFor = null;
        }
        role = RaftRole.FOLLOWER;
        leaderId = null;
        transferTarget = null;
        readIndexState.clear();
        // F-0022: pending callbacks will observe isReadReady==false since
        // readIndexState was cleared; we fire them so the HTTP-side future
        // completes promptly (as "not leader") rather than waiting for the
        // 150ms HTTP deadline. The callback is expected to re-check state.
        if (!readReadyCallbacks.isEmpty()) {
            var toFire = new ArrayList<>(readReadyCallbacks.values());
            readReadyCallbacks.clear();
            for (Runnable cb : toFire) {
                try {
                    cb.run();
                } catch (Throwable t) {
                    System.err.println("RaftNode: readReady callback threw on step-down: " + t);
                }
            }
        }
        // RR-004 / ADR-0033: step-down is NOT a definite-loss trigger — a former
        // leader steps down with its proposed entry still in its log, and a
        // replica holding that entry can win a later election and commit it. So we
        // only RE-EVALUATE pending commit outcomes here (some may now be decidable
        // as COMMITTED/LOST because lastApplied advanced as this node caught up);
        // we do NOT drain the still-undecidable ones as LOST. Those resolve on a
        // later apply at their index, or surface as Indeterminate at the service
        // deadline. (Contrast with the read callbacks above, which are correctly
        // drained because a deposed leader can no longer serve a linearizable read.)
        fireCommitOutcomes();
        resetElectionTimeout();
        electionTicksElapsed = 0;
    }

    /**
     * Starts a PreVote round (§9.6). Does NOT increment the term.
     * Sends PreVote requests to all peers.
     */
    private void startPreVote() {
        // Non-voters must not start elections (TLA+ spec: n ∈ VotingMembers(config[n]))
        if (!clusterConfig.isVoter(config.nodeId())) {
            return;
        }

        // Single-node cluster: become leader immediately
        Set<NodeId> peers = clusterConfig.peersOf(config.nodeId());
        if (peers.isEmpty()) {
            durableState.setTermAndVote(currentTerm + 1, config.nodeId()); // Persist BEFORE in-memory
            currentTerm++;
            votedFor = config.nodeId();
            votesReceived = new HashSet<>();
            votesReceived.add(config.nodeId());
            becomeLeader();
            return;
        }

        preVoteInProgress = true;
        preVotesReceived = new HashSet<>();
        preVotesReceived.add(config.nodeId()); // Vote for self in PreVote

        // Send PreVote with term+1 (what we would use if we start an election)
        RequestVoteRequest preVoteReq = new RequestVoteRequest(
                currentTerm + 1,
                config.nodeId(),
                log.lastIndex(),
                log.lastTerm(),
                true
        );

        for (NodeId peer : peers) {
            transport.send(peer, preVoteReq);
        }

        resetElectionTimeout();
    }

    /**
     * Transitions to CANDIDATE and starts a real election.
     * Increments term, votes for self, sends RequestVote RPCs.
     */
    private void startElection() {
        // Non-voters must not start elections (TLA+ spec: n ∈ VotingMembers(config[n]))
        if (!clusterConfig.isVoter(config.nodeId())) {
            return;
        }

        durableState.setTermAndVote(currentTerm + 1, config.nodeId()); // Persist BEFORE in-memory update
        currentTerm++;
        votedFor = config.nodeId();
        role = RaftRole.CANDIDATE;
        leaderId = null;
        votesReceived = new HashSet<>();
        votesReceived.add(config.nodeId()); // vote for self
        preVoteInProgress = false;

        resetElectionTimeout();
        electionTicksElapsed = 0;

        Set<NodeId> peers = clusterConfig.peersOf(config.nodeId());

        // Single-node: win immediately
        if (peers.isEmpty()) {
            becomeLeader();
            return;
        }

        RequestVoteRequest voteReq = new RequestVoteRequest(
                currentTerm,
                config.nodeId(),
                log.lastIndex(),
                log.lastTerm(),
                false
        );

        for (NodeId peer : peers) {
            transport.send(peer, voteReq);
        }
    }

    /**
     * Transitions to LEADER state. Initializes nextIndex and matchIndex
     * for all peers. Appends a no-op entry to commit entries from prior
     * terms (Raft §5.4.2). Broadcasts initial heartbeats.
     */
    private void becomeLeader() {
        // INV-1: ElectionSafety — verify we won a proper election (dual-majority for joint consensus)
        invariantChecker.check("election_safety",
                clusterConfig.isQuorum(votesReceived) || clusterConfig.peersOf(config.nodeId()).isEmpty(),
                "Became leader without quorum: votes=" + votesReceived
                        + ", config=" + clusterConfig);
        // INV-2: LeaderCompleteness — our log must contain all committed entries
        // (guaranteed by the voting restriction in handleRequestVote, but assert here)
        invariantChecker.check("leader_completeness",
                log.lastIndex() >= log.commitIndex(),
                "New leader log behind commitIndex: lastIndex=" + log.lastIndex()
                        + ", commitIndex=" + log.commitIndex());

        role = RaftRole.LEADER;
        leaderId = config.nodeId();
        transferTarget = null;
        heartbeatTicksElapsed = 0;
        noopCommittedInCurrentTerm = false;

        // Initialize leader volatile state
        nextIndex = new HashMap<>();
        matchIndex = new HashMap<>();
        inflightCount = new HashMap<>();
        peerActivity.clear();
        for (NodeId peer : clusterConfig.peersOf(config.nodeId())) {
            nextIndex.put(peer, log.lastIndex() + 1);
            matchIndex.put(peer, 0L);
            inflightCount.put(peer, 0);
            peerActivity.put(peer, Boolean.TRUE); // Consider everyone active initially
        }

        // Append a no-op entry to commit entries from prior terms (§5.4.2)
        // This no-op must commit before any config changes can be proposed
        long noopIndex = log.lastIndex() + 1;
        log.append(LogEntry.noop(noopIndex, currentTerm)); // durable (appendNoSync + syncWal)
        // S7.5 group commit: log.append() force-syncs the whole WAL, so the no-op AND every
        // inherited entry (loaded from WAL / synced as a follower / any tail buffered in a prior
        // leadership stint) is now durable. Seed durableIndex accordingly before the leader counts
        // itself in any commit quorum.
        durableIndex = log.lastIndex();

        broadcastAppendEntries();
        maybeAdvanceCommitIndex();
    }

    // ========================================================================
    // Log replication helpers
    // ========================================================================

    /**
     * Sends AppendEntries RPCs to all peers in the current cluster config.
     */
    private void broadcastAppendEntries() {
        for (NodeId peer : clusterConfig.peersOf(config.nodeId())) {
            sendAppendEntries(peer);
        }
    }

    /**
     * Sends an AppendEntries RPC to a specific peer. If the peer is so
     * far behind that the required entries have been compacted, sends an
     * InstallSnapshot RPC instead.
     */
    private void sendAppendEntries(NodeId peer) {
        // Pipelining window: skip if too many in-flight RPCs for this peer
        int inflight = inflightCount.getOrDefault(peer, 0);
        if (inflight >= config.maxInflightAppends()) {
            return;
        }

        long ni = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
        long prevIndex = ni - 1;
        long prevTerm = log.termAt(prevIndex);
        if (prevTerm == -1) {
            // prevIndex is before our snapshot — send snapshot instead
            sendInstallSnapshot(peer);
            return;
        }

        List<LogEntry> entries = log.entriesBatch(ni, config.maxBatchSize(), config.maxBatchBytes());

        AppendEntriesRequest req = new AppendEntriesRequest(
                currentTerm,
                config.nodeId(),
                prevIndex,
                prevTerm,
                entries,
                log.commitIndex()
        );

        // The encoder rejects oversized messages with IllegalArgumentException.
        // If we incremented inflightCount before send, a permanent failure
        // for this peer would leak the counter (no response can arrive
        // because no message was sent), eventually exceeding maxInflightAppends
        // and silencing the leader toward this peer until the next term.
        // Increment ONLY after a successful send.
        try {
            transport.send(peer, req);
        } catch (IllegalArgumentException e) {
            System.err.println("Dropping AppendEntries to " + peer
                    + " (codec rejected): " + e.getMessage());
            return;
        }
        inflightCount.merge(peer, 1, Integer::sum);
    }

    /**
     * Sends the latest snapshot to a lagging peer via InstallSnapshot RPC.
     * <p>
     * Called when the peer's nextIndex points to an entry that has been
     * compacted. If no snapshot is available, this is a no-op (the peer
     * will eventually receive entries once the log catches up or a
     * snapshot is taken).
     *
     * @param peer the target follower node
     */
    private void sendInstallSnapshot(NodeId peer) {
        if (latestSnapshot == null) {
            // No snapshot available yet — take one now
            triggerSnapshot();
        }
        if (latestSnapshot == null) {
            return; // Still no snapshot (no applied entries) — nothing to send
        }

        // INV-SI-4 twin (SnapshotInstallSpec.InflightTermMonotonic): a leader must
        // only ship a snapshot it actually holds — the descriptor about to go on the
        // wire must equal what this node has recorded for that index. The spec rejects
        // "leader sends a snapshot it doesn't have". A regression corrupting the
        // outbound descriptor trips here on the SENDER.
        checkSnapshotSendTwin(latestSnapshot.lastIncludedIndex(), latestSnapshot.lastIncludedTerm());

        InstallSnapshotRequest req = new InstallSnapshotRequest(
                currentTerm,
                config.nodeId(),
                latestSnapshot.lastIncludedIndex(),
                latestSnapshot.lastIncludedTerm(),
                0,
                latestSnapshot.data(),
                true,
                latestSnapshot.clusterConfigData()
        );

        // Same inflight-leak guard as sendAppendEntries: send first,
        // increment only on success. Snapshots are large by definition,
        // so the encoder-reject path here is the dominant cause of
        // legitimate IAE in production (see ADR-0029 known limitation).
        try {
            transport.send(peer, req);
        } catch (IllegalArgumentException e) {
            System.err.println("Dropping InstallSnapshot to " + peer
                    + " (codec rejected — snapshot too large for v1 wire): "
                    + e.getMessage());
            return;
        }
        inflightCount.merge(peer, 1, Integer::sum);
    }

    /**
     * Advances the commit index based on matchIndex values (Raft §5.3/§5.4).
     * <p>
     * Only commits entries from the current term (§5.4.2 safety rule):
     * a leader cannot determine commitment of entries from prior terms
     * based on replication count alone.
     * <p>
     * During joint consensus, commitment requires agreement from
     * majorities of BOTH the old and new voter sets.
     */
    private void maybeAdvanceCommitIndex() {
        if (role != RaftRole.LEADER) {
            return;
        }

        // For each index from the last log entry down to commitIndex+1,
        // check if a quorum has replicated it and it's from the current term.
        // Reuse a single set across iterations to avoid per-iteration allocation.
        var replicated = new java.util.HashSet<NodeId>();
        var peers = clusterConfig.peersOf(config.nodeId());
        for (long n = log.lastIndex(); n > log.commitIndex(); n--) {
            if (log.termAt(n) != currentTerm) {
                continue;
            }

            // Build set of nodes that have replicated entry n
            replicated.clear();
            // S7.5 group-commit safety: count THIS leader toward the quorum for index n ONLY if
            // n is durably fsynced locally (durableIndex). A buffered-but-unsynced self-copy must
            // never be the deciding vote — a leader crash before the flush would otherwise lose an
            // entry that quorum logic had marked committed. Followers already report matchIndex
            // only after their own durable append (persist-before-ACK), so peers are always durable.
            if (durableIndex >= n) {
                replicated.add(config.nodeId()); // self (durable)
            }
            for (NodeId peer : peers) {
                if (matchIndex.getOrDefault(peer, 0L) >= n) {
                    replicated.add(peer);
                }
            }

            if (clusterConfig.isQuorum(replicated)) {
                log.setCommitIndex(n);
                applyCommitted();
                break;
            }
        }
    }

    /**
     * Schedules a coalescing group-commit flush for entries appended via
     * {@link RaftLog#appendNoSync} but not yet force-synced. If the unsynced backlog has reached
     * {@link #groupCommitMaxBatch}, flushes immediately (bounding latency and the uncommitted
     * backlog); otherwise schedules a single flush (honoring the linger) unless one is already
     * pending — so concurrently-proposed entries coalesce into ONE fsync. Tick-thread only.
     */
    private void scheduleFlush() {
        long pending = log.lastIndex() - durableIndex;
        if (pending <= 0) {
            return; // nothing buffered (e.g. an INLINE flush already ran)
        }
        if (pending >= groupCommitMaxBatch) {
            flushDurable(); // batch cap reached — flush now, bypass the linger
            return;
        }
        if (flushScheduled) {
            return; // a pending flush will cover this entry too
        }
        flushScheduled = true;
        flushScheduler.schedule(this::flushDurable, groupCommitLingerMicros);
    }

    /**
     * Force-syncs every entry buffered since the last sync (ONE fsync for the whole batch),
     * advances {@link #durableIndex}, then re-evaluates the commit index now that the leader's own
     * entries up to {@code lastIndex} are durable. Runs on the tick thread — inline via the default
     * scheduler, or dispatched onto the single tick executor in production. Because the tick thread
     * is single-threaded, no append can interleave the fsync, so {@code durableIndex == lastIndex}
     * captured here is exact.
     */
    private void flushDurable() {
        // Stage 2 M2 (FlushScheduler retarget): the production FlushScheduler dispatches this flush onto
        // the group's owner executor. If a rehome moved the group AFTER the flush was scheduled, a stale
        // dispatch would otherwise run here OFF the current owner — an unsynchronised touch of log /
        // durableIndex / commit advancement. Guarding converts that into a net fire (throw in test/sim,
        // metric + SEVERE in prod) instead of a SILENT race; on the correct owner (every production path —
        // single-group, dormant rehoming) the call is on-owner and silent. See MultiRaftDriver.dispatchFlush.
        assertOwnerThread();
        flushScheduled = false;
        long target = log.lastIndex();
        if (target <= durableIndex) {
            return; // already durable — nothing to sync
        }
        log.syncWal();           // single fsync covers every appendNoSync since the last sync
        durableIndex = target;   // the leader may now count itself up to here
        maybeAdvanceCommitIndex();
    }

    /**
     * Applies all committed but unapplied entries to the state machine.
     * Also handles config change entries for joint consensus transitions
     * and tracks no-op commitment for reconfiguration safety.
     */
    private void applyCommitted() {
        while (log.lastApplied() < log.commitIndex()) {
            long nextApply = log.lastApplied() + 1;

            LogEntry entry = log.entryAt(nextApply);
            if (entry == null) {
                // RR-003 (durable-prefix / no-gap invariant): a committed index
                // with no log entry and no covering snapshot is a GAP in the
                // recoverable prefix. The pre-fix code SILENTLY skipped here and
                // advanced lastApplied — the loss amplifier that turned an
                // unrestored snapshot into invisible total data loss. Fail
                // LOUDLY instead: throw in test/sim, increment the
                // durable_prefix_no_gap metric + SEVERE log in production
                // (never silently skip a committed entry). nextApply > snapshotIndex
                // here (we only advance lastApplied within (snapshotIndex,
                // commitIndex]), so a null entry means the entry is genuinely
                // missing, not merely compacted-and-restored.
                invariantChecker.check("durable_prefix_no_gap",
                        false,
                        "Committed index " + nextApply + " is missing from the recoverable prefix"
                                + " (snapshotIndex=" + log.snapshotIndex()
                                + ", lastApplied=" + log.lastApplied()
                                + ", commitIndex=" + log.commitIndex()
                                + "): persisted snapshot + WAL suffix do not cover all committed"
                                + " entries — refusing to silently skip.");
                // In production (fail-open) the metric/log has fired; do NOT
                // advance lastApplied past the gap (that is the silent-skip we
                // are killing). Stop applying — the node is in a corrupt
                // recovery state and must not pretend the entry was applied.
                break;
            }

            // INV-5: VersionMonotonicity — the entry we are about to apply must carry an
            // index strictly greater than lastApplied. entry.index() comes from the log
            // (independent of the nextApply computation), so a log returning a stale/wrong
            // entry trips this. (A2/R-05c: the previous form `nextApply > lastApplied` with
            // nextApply := lastApplied + 1 was locally vacuous and could never fire.)
            invariantChecker.check("version_monotonicity",
                    entry.index() > log.lastApplied(),
                    "Apply entry index " + entry.index() + " not > lastApplied " + log.lastApplied());

            // INV-4: StateMachineSafety — entry at this index must match across nodes
            // (structural guarantee from Raft log matching; assert entry consistency)
            invariantChecker.check("state_machine_safety",
                    entry.index() == nextApply,
                    "Entry index " + entry.index() + " != expected " + nextApply);

            // Track no-op commitment in current term (for reconfig safety)
            if (entry.term() == currentTerm && entry.command().length == 0) {
                noopCommittedInCurrentTerm = true;
            }

            // Handle config change entries
            if (isConfigChangeEntry(entry.command())) {
                // RR-004: RCFG entries bypass the state machine; they assign no
                // applied-mutation seq. A commit-outcome callback on this index
                // (clients never propose RCFG, but the seam is generic) surfaces
                // the current sequence — consistent with the non-mutating case.
                handleCommittedConfigChange(entry);
                recordAppliedSeq(entry.index(), currentAppliedSequence());
            } else {
                // RR-004 / ADR-0033: apply now returns the assigned
                // applied-mutation seq (or NON_MUTATING for a no-op). Record it
                // per index so the commit-outcome seam can surface the correct
                // seq for this exact entry.
                long appliedSeq = stateMachine.apply(entry.index(), entry.term(), entry.command());
                if (appliedSeq == StateMachine.NON_MUTATING) {
                    appliedSeq = currentAppliedSequence();
                } else {
                    lastRecordedSeq = appliedSeq;
                }
                recordAppliedSeq(entry.index(), appliedSeq);
            }
            log.setLastApplied(nextApply);
        }
        // F-0022: apply advanced lastApplied — some pending reads may now
        // satisfy the "lastApplied >= readIndex" condition.
        fireReadyCallbacks();
        // RR-004: apply advanced lastApplied — pending commit outcomes at or below
        // the new lastApplied are now decidable (COMMITTED / LOST).
        fireCommitOutcomes();
    }

    /**
     * Records the applied-mutation seq for {@code index} so a commit-outcome
     * registration arriving after the apply can still surface the correct seq.
     * Pruned to indices that may still be queried by a pending callback, and
     * hard-capped so a workload that never registers cannot grow it unbounded.
     */
    private void recordAppliedSeq(long index, long seq) {
        appliedSeqByIndex.put(index, seq);
        // Drop records strictly below the lowest pending-callback index — once
        // every pending callback has registered at an index >= floor, no
        // registration can ever query an older index again. Only prune by floor
        // when callbacks ARE pending: with none pending, an imminent registration
        // (the single-node immediate-commit path registers right after this apply)
        // must still be able to read the seq it just recorded, so we must NOT wipe
        // the recent records — the hard cap below bounds the map in that case.
        long floor = lowestPendingCommitIndex();
        if (floor != Long.MAX_VALUE) {
            appliedSeqByIndex.keySet().removeIf(k -> k < floor);
        }
        // Hard cap: bound the map for a workload that proposes without ever
        // registering a commit-outcome callback — retain only the most recent
        // window of indices (the only ones a late registration could query).
        if (appliedSeqByIndex.size() > MAX_RETAINED_APPLIED_SEQ) {
            long cutoff = log.lastApplied() - MAX_RETAINED_APPLIED_SEQ;
            appliedSeqByIndex.keySet().removeIf(k -> k < cutoff);
        }
    }

    /** Lowest index among pending commit-outcome callbacks, or MAX if none. */
    private long lowestPendingCommitIndex() {
        long min = Long.MAX_VALUE;
        for (long idx : commitOutcomeCallbacks.keySet()) {
            if (idx < min) min = idx;
        }
        return min;
    }

    /**
     * Handles a committed config change entry. If the committed entry
     * was a joint config (C_old,new), the leader automatically proposes
     * the transition to C_new.
     */
    private void handleCommittedConfigChange(LogEntry entry) {
        if (clusterConfig.isJoint()) {
            // Joint config C_old,new committed.
            //
            // Per the TLA+ spec (CommitJointConfig, lines 391-409), the
            // leader appends C_new to complete the transition. Followers
            // do NOT transition their in-memory config to C_new here —
            // they will adopt C_new when the C_new entry arrives via
            // AppendEntries and recomputeConfigFromLog() runs.
            //
            // Rationale: the TLA+ spec's EffectiveConfig always derives
            // config from the log. If we transition the follower's
            // clusterConfig to C_new before C_new is in its log, the
            // follower would use simple C_new quorum rules for elections
            // instead of joint C_old,new rules — a spec divergence that
            // could affect election safety.
            //
            // configChangePending remains TRUE — a C_new entry still needs
            // to be committed to complete the reconfiguration.

            if (role == RaftRole.LEADER) {
                // Leader transitions to C_new and appends the C_new entry
                ClusterConfig newConfig = clusterConfig.transitionToNew();
                clusterConfig = newConfig;

                byte[] configEntry = serializeConfigChange(newConfig);
                long newIndex = log.lastIndex() + 1;
                log.append(new LogEntry(newIndex, currentTerm, configEntry)); // durable control entry
                durableIndex = log.lastIndex(); // S7.5: synced — the leader may count it (gating)
                broadcastAppendEntries();
                maybeAdvanceCommitIndex();

                // If this node is no longer a voter in the new config, step down
                if (!clusterConfig.isVoter(config.nodeId())) {
                    becomeFollower(currentTerm);
                }
            }
            // Followers: clusterConfig stays as joint. recomputeConfigFromLog()
            // will set C_new when the C_new entry arrives via AppendEntries.
        } else {
            // Simple config committed — this completes a C_new transition
            configChangePending = false;

            // If this node is no longer a voter, step down
            if (!clusterConfig.isVoter(config.nodeId())) {
                becomeFollower(currentTerm);
            }
        }
    }

    // ========================================================================
    // InstallSnapshot handling
    // ========================================================================

    /**
     * Handles an InstallSnapshot RPC from the leader (Raft §7).
     * <p>
     * Replaces the follower's state machine and log with the snapshot
     * if the snapshot is more recent than the follower's current state.
     */
    private void handleInstallSnapshot(InstallSnapshotRequest req) {
        // Rule: if term < currentTerm, reject (Raft §5.1).
        // Echo max(snapshotIndex, lastApplied) so the (now-stale)
        // leader sees how far we have already advanced. We must use
        // max because on a follower mid-recovery snapshotIndex can
        // exceed lastApplied (snapshot ingested but state-machine
        // apply lag hasn't caught up); telling the leader we are at
        // lastApplied would understate our position.
        if (req.term() < currentTerm) {
            transport.send(req.leaderId(),
                    new InstallSnapshotResponse(currentTerm, false,
                            config.nodeId(),
                            Math.max(log.snapshotIndex(), log.lastApplied())));
            return;
        }

        // If we see a higher term, step down
        if (req.term() > currentTerm) {
            becomeFollower(req.term());
        } else if (role == RaftRole.CANDIDATE) {
            becomeFollower(req.term());
        }

        // Reset election timer — we heard from the leader
        electionTicksElapsed = 0;
        leaderId = req.leaderId();

        // If the snapshot is not more recent than our current state,
        // ignore. Echo max(snapshotIndex, lastApplied) — using
        // lastApplied alone would tell the leader to send AppendEntries
        // from lastApplied+1, which on a follower that has compacted
        // past lastApplied would cause a prevLogTerm mismatch.
        if (req.lastIncludedIndex() <= log.snapshotIndex()) {
            transport.send(req.leaderId(),
                    new InstallSnapshotResponse(currentTerm, true,
                            config.nodeId(),
                            Math.max(log.snapshotIndex(), log.lastApplied())));
            return;
        }

        // SnapshotInstallSpec runtime twins — checked at the install decision point
        // (the spec's ReceiveInstallSnapshot "newer — install" branch), before we
        // mutate any state. We are here only because req.lastIncludedIndex() >
        // log.snapshotIndex() (the early-return above handled the older/equal case),
        // i.e. this is the spec's installing branch.
        checkSnapshotInstallTwins(req.lastIncludedIndex(), req.lastIncludedTerm());

        // Restore the state machine from the snapshot
        stateMachine.restoreSnapshot(req.data());

        // RR-003: a follower installing a snapshot has the same restart-loss
        // exposure as a leader taking one — it is about to compact away the WAL
        // prefix. Persist the received snapshot bytes durably BEFORE compaction
        // so a restart restores the state machine instead of silently dropping
        // everything at/below lastIncludedIndex. Cache it as latestSnapshot too,
        // so this node can in turn serve it to a lagging peer.
        SnapshotState installed = new SnapshotState(
                req.data(), req.lastIncludedIndex(), req.lastIncludedTerm(),
                req.clusterConfigData());
        log.persistSnapshot(installed);
        latestSnapshot = installed;

        // Compact the log up to the snapshot point
        log.compact(req.lastIncludedIndex(), req.lastIncludedTerm());

        // Update applied state to match the snapshot
        if (req.lastIncludedIndex() > log.commitIndex()) {
            log.setCommitIndex(req.lastIncludedIndex());
        }
        log.setLastApplied(req.lastIncludedIndex());

        // Recompute config from log after snapshot install.
        // The snapshot may represent a state after reconfigurations,
        // and any remaining log entries may contain config changes.
        // Pass the snapshot's cluster config as a fallback for when the log
        // is fully compacted past all config entries.
        recomputeConfigFromLog(req.clusterConfigData());

        // RR-004 / ADR-0033: the snapshot just folded indices up to
        // lastIncludedIndex into compacted state. A pending commit-outcome
        // callback at an index covered by the snapshot whose entry this node never
        // applied (and therefore never recorded) has an unrecoverable per-index
        // term — fire INDETERMINATE_LOCALLY for it. (On the proposing leader apply
        // always precedes local compaction of an index, so this only arises after
        // step-down; the predicate also covers a follower that took the snapshot
        // before catching up.) Callbacks whose index WAS recorded as applied
        // resolve as COMMITTED via fireCommitOutcomes.
        fireSnapshotIndeterminate(req.lastIncludedIndex());
        fireCommitOutcomes();

        // Successful install: lastApplied was just set to
        // req.lastIncludedIndex(); use the same max() form for
        // consistency with the reject paths above.
        transport.send(req.leaderId(),
                new InstallSnapshotResponse(currentTerm, true,
                        config.nodeId(),
                        Math.max(log.snapshotIndex(), log.lastApplied())));
    }

    /**
     * Receive-side SnapshotInstallSpec runtime twins (INV-SI-2 SnapshotMatching,
     * INV-SI-3 NoCommitRevert). Extracted from {@link #handleInstallSnapshot} so the
     * assertion-twin firing test can drive these checks directly with a poisoned
     * incoming descriptor (RR-030 / §4.5). Package-private; the production caller is
     * {@code handleInstallSnapshot}.
     *
     * @param inIdx  the incoming snapshot's lastIncludedIndex
     * @param inTerm the incoming snapshot's lastIncludedTerm
     */
    void checkSnapshotInstallTwins(long inIdx, long inTerm) {
        long curSnapIdx = log.snapshotIndex();
        long curSnapTerm = log.snapshotTerm();

        // INV-SI-3 (NoCommitRevert): a higher-index install must never carry a lower
        // term than the snapshot it replaces — snapshots come from the committed log
        // whose terms are monotonic in index, so a higher index always carries a >=
        // term. A higher-index/lower-term install is a commit revert.
        invariantChecker.check("snapshot_no_commit_revert",
                curSnapIdx == 0 || inTerm >= curSnapTerm,
                "InstallSnapshot at index " + inIdx + " term " + inTerm
                        + " reverts the term of the current snapshot (index " + curSnapIdx
                        + " term " + curSnapTerm + ") — commit revert (INV-SI-3)");

        // INV-SI-2 (SnapshotMatching): if the incoming snapshot's index coincides with
        // a term we already record locally (in-log entry or our own snapshot boundary),
        // the terms must agree — the snapshot equivalent of Log Matching.
        long localTermAtIn = log.termAt(inIdx);
        invariantChecker.check("snapshot_matching",
                localTermAtIn < 0 || localTermAtIn == inTerm,
                "InstallSnapshot at index " + inIdx + " carries term " + inTerm
                        + " but this node records term " + localTermAtIn
                        + " at that index — snapshot/log term mismatch (INV-SI-2)");

        // (INV-SI-1 SnapshotBoundedByCommitted is twinned on the local-snapshot path
        // in triggerSnapshot, where `index <= commitIndex` is falsifiable; the
        // receive-branch precondition req.lastIncludedIndex() > log.snapshotIndex()
        // makes a forward-boundary check vacuous here by construction.)
    }

    /**
     * Send-side SnapshotInstallSpec runtime twin (INV-SI-4 InflightTermMonotonic).
     * Extracted from {@link #sendInstallSnapshot} so the firing test can drive it
     * with a corrupted outbound descriptor (RR-030 / §4.5). Package-private.
     *
     * @param sendIdx  the lastIncludedIndex about to be sent
     * @param sendTerm the lastIncludedTerm about to be sent
     */
    void checkSnapshotSendTwin(long sendIdx, long sendTerm) {
        // termAt returns snapshotTerm at the boundary, the entry term if still in the
        // log, or -1 if the index is unknown to this node (nothing to compare against).
        long recordedTerm = log.termAt(sendIdx);
        invariantChecker.check("snapshot_term_consistent",
                recordedTerm < 0 || recordedTerm == sendTerm,
                "Outbound InstallSnapshot at index " + sendIdx + " carries term " + sendTerm
                        + " but this node records term " + recordedTerm
                        + " at that index — shipping a snapshot it does not hold (INV-SI-4)");
    }

    /**
     * Handles an InstallSnapshot RPC response from a follower.
     * <p>
     * On success, advances the follower's nextIndex and matchIndex
     * to the snapshot's last included index.
     */
    private void handleInstallSnapshotResponse(InstallSnapshotResponse resp) {
        if (role != RaftRole.LEADER) {
            return;
        }

        // Step down if we see a higher term
        if (resp.term() > currentTerm) {
            becomeFollower(resp.term());
            return;
        }

        // Ignore stale responses
        if (resp.term() != currentTerm) {
            return;
        }

        // Record peer activity for CheckQuorum
        peerActivity.put(resp.from(), Boolean.TRUE);
        inflightCount.merge(resp.from(), -1, (a, b) -> Math.max(0, a + b));

        if (resp.success() && latestSnapshot != null) {
            long snapIndex = latestSnapshot.lastIncludedIndex();

            // ADR-0029 W2 leader-side use of resp.lastIncludedIndex():
            // a follower may already be past our cached snapshot
            // (e.g., it caught up via a more-recent snapshot from a
            // different leader during a partition). Trust the
            // follower's reported index, BUT clamp the upper bound
            // so a malicious or buggy follower cannot fast-forward
            // matchIndex beyond what the leader can attest to.
            //
            // The upper bound is `max(commitIndex, snapshotIndex,
            // lastIndex)` — using `commitIndex` alone would regress
            // on a freshly-elected leader whose noop hasn't committed
            // yet but whose durable snapshotIndex is already large
            // (cold-start-from-snapshot scenario), pinning matchIndex
            // to 0 and re-looping snapshot install forever.
            long reported = resp.lastIncludedIndex();
            long upperBound = Math.max(
                    Math.max(log.commitIndex(), log.snapshotIndex()),
                    log.lastIndex());
            long effective = Math.min(Math.max(snapIndex, reported), upperBound);

            matchIndex.put(resp.from(), effective);
            nextIndex.put(resp.from(), effective + 1);

            maybeAdvanceCommitIndex();
            applyCommitted();
        }
    }

    // ========================================================================
    // CheckQuorum
    // ========================================================================

    /**
     * Builds the set of active peers (including self), resets activity tracking,
     * and returns the set. Used by tickHeartbeat for CheckQuorum and ReadIndex
     * confirmation with correct joint consensus dual-majority checking.
     *
     * @return the set of active cluster members (including self)
     */
    private Set<NodeId> buildActiveSetAndReset() {
        Set<NodeId> peers = clusterConfig.peersOf(config.nodeId());
        var activeSet = new HashSet<NodeId>();
        activeSet.add(config.nodeId()); // self is always active

        for (NodeId peer : peers) {
            if (Boolean.TRUE.equals(peerActivity.get(peer))) {
                activeSet.add(peer);
            }
        }

        // Reset activity tracking for the next round
        for (NodeId peer : peers) {
            peerActivity.put(peer, Boolean.FALSE);
        }

        return activeSet;
    }

    /**
     * Confirms pending ReadIndex requests if the active set forms a quorum.
     * Uses set-based quorum check for correct joint consensus handling.
     *
     * @param activeSet the set of active cluster members
     */
    private void confirmPendingReads(Set<NodeId> activeSet) {
        if (clusterConfig.isQuorum(activeSet)) {
            readIndexState.confirmAllLeadership();
            // F-0022: signal any callers waiting on whenReadReady(...).
            fireReadyCallbacks();
        }
    }

    // ========================================================================
    // Leadership transfer helpers
    // ========================================================================

    private void maybeSendTimeoutNow() {
        if (transferTarget == null) {
            return;
        }
        long targetMatchIndex = matchIndex.getOrDefault(transferTarget, 0L);
        if (targetMatchIndex >= log.lastIndex()) {
            transport.send(transferTarget, new TimeoutNowRequest(currentTerm, config.nodeId()));
            transferTarget = null; // Transfer initiated, clear target
        }
    }

    // ========================================================================
    // Election timeout randomization
    // ========================================================================

    private void resetElectionTimeout() {
        // RR-006: randomize within the tick-domain bounds derived from the
        // millisecond budgets (electionTimeoutMin/MaxMs / tickPeriodMs). With the
        // simulation's 1ms tick this is identical to the prior ms-as-ticks values
        // (150..300); in production (10ms tick) it is 15..30 ticks == 150..300ms.
        electionTimeoutTicks = electionTimeoutMinTicks
                + random.nextInt(electionTimeoutMaxTicks - electionTimeoutMinTicks + 1);
    }

    // ---- Test seam (RR-006) ----
    // Package-private accessors that expose the *derived* tick counts so a unit
    // test can pin the ms->tick conversion directly (production code never reads
    // these; the timer logic above uses the cached fields).

    /** The current randomized election-timeout target, in ticks. */
    int electionTimeoutTicksForTest() {
        return electionTimeoutTicks;
    }

    /** The heartbeat interval the leader actually fires at, in ticks. */
    int heartbeatTimeoutTicksForTest() {
        return heartbeatTimeoutTicks;
    }

    /**
     * The current per-peer in-flight AppendEntries window count (RR-103). Test seam so a
     * recovery test can prove the wedge precondition (window pinned at {@code
     * maxInflightAppends}) before exercising the heartbeat decay. Production code never
     * reads this; it is leader-only volatile state.
     */
    int inflightCountForTest(NodeId peer) {
        return inflightCount == null ? 0 : inflightCount.getOrDefault(peer, 0);
    }
}

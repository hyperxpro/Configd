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
    private int electionTimeoutTicks;  // randomized target
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
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.random = Objects.requireNonNull(random, "random");
        this.invariantChecker = invariantChecker != null ? invariantChecker : InvariantChecker.NOOP;

        // Load persisted state (currentTerm, votedFor) from durable storage
        this.durableState = new DurableRaftState(Objects.requireNonNull(storage, "storage"));
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

        // Recover cluster config from the log if it contains config entries
        // from a prior run. This ensures config changes survive restarts.
        recomputeConfigFromLog();

        resetElectionTimeout();
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
    // Public API
    // ========================================================================

    /**
     * Advances the internal timer by one tick. Called at a regular interval
     * (e.g., every 1ms). Drives election timeouts (FOLLOWER/CANDIDATE)
     * and heartbeat intervals (LEADER).
     */
    public void tick() {
        switch (role) {
            case FOLLOWER, CANDIDATE -> tickElection();
            case LEADER -> tickHeartbeat();
        }
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
     *
     * @param command the command bytes
     * @return the result of the proposal attempt
     */
    public ProposalResult propose(byte[] command) {
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
            return ProposalResult.NOT_LEADER;
        }
        if (transferTarget != null) {
            return ProposalResult.TRANSFER_IN_PROGRESS;
        }
        // Backpressure: reject if too many uncommitted entries (Hard Rule #12)
        long uncommitted = log.lastIndex() - log.commitIndex();
        if (uncommitted >= config.maxPendingProposals()) {
            return ProposalResult.OVERLOADED;
        }
        long newIndex = log.lastIndex() + 1;
        LogEntry entry = new LogEntry(newIndex, currentTerm, command);
        log.append(entry);
        broadcastAppendEntries();
        // Check if single-node cluster (commit immediately)
        maybeAdvanceCommitIndex();
        return ProposalResult.ACCEPTED;
    }

    /**
     * Initiates leadership transfer to the specified target node.
     * The leader will catch up the target and then send TimeoutNow.
     *
     * @param target the node to transfer leadership to
     * @return true if the transfer was initiated
     */
    public boolean transferLeadership(NodeId target) {
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
        long appliedIndex = log.lastApplied();
        if (appliedIndex <= log.snapshotIndex()) {
            return false; // Nothing new to snapshot
        }
        long appliedTerm = log.termAt(appliedIndex);
        if (appliedTerm == -1) {
            return false; // Cannot determine term — should not happen
        }

        byte[] snapshotData = stateMachine.snapshot();
        // FIND-0001 fix: capture the config at lastApplied, not the current
        // in-memory clusterConfig. The current clusterConfig may include
        // uncommitted config changes beyond lastApplied. A snapshot at
        // appliedIndex must record only the config that was effective at
        // that index, not a future uncommitted config.
        byte[] configData = serializeConfigChange(configAtIndex(appliedIndex));
        latestSnapshot = new SnapshotState(snapshotData, appliedIndex, appliedTerm, configData);
        log.compact(appliedIndex, appliedTerm);
        return true;
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
        if (role != RaftRole.LEADER) {
            return -1;
        }
        // For single-node clusters, the read is immediately ready
        // since we are trivially the leader with full quorum
        long readId = readIndexState.startRead(log.commitIndex());
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
        // Re-verify leadership: a deposed leader must not serve reads.
        // This closes the TOCTOU window between heartbeat confirmation
        // and read serving (see FIND-0002).
        if (role != RaftRole.LEADER) {
            return false;
        }
        return readIndexState.isReady(readId, log.lastApplied());
    }

    /**
     * Marks a read as completed, releasing its tracking state.
     *
     * @param readId the read ID to complete
     */
    public void completeRead(long readId) {
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
        Objects.requireNonNull(callback, "callback");
        if (isReadReady(readId)) {
            callback.run();
            return;
        }
        readReadyCallbacks.put(readId, callback);
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
        log.append(entry);

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

    // ---- Getters for state inspection (tests and monitoring) ----

    public RaftRole role() { return role; }
    public long currentTerm() { return currentTerm; }
    public NodeId votedFor() { return votedFor; }
    public NodeId leaderId() { return leaderId; }
    public RaftLog log() { return log; }
    public NodeId nodeId() { return config.nodeId(); }
    public NodeId transferTarget() { return transferTarget; }

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
        if (heartbeatTicksElapsed >= config.heartbeatIntervalMs()) {
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
        log.append(LogEntry.noop(noopIndex, currentTerm));

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
            replicated.add(config.nodeId()); // self
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
     * Applies all committed but unapplied entries to the state machine.
     * Also handles config change entries for joint consensus transitions
     * and tracks no-op commitment for reconfiguration safety.
     */
    private void applyCommitted() {
        while (log.lastApplied() < log.commitIndex()) {
            long nextApply = log.lastApplied() + 1;

            // INV-5: VersionMonotonicity — applied index must advance monotonically
            invariantChecker.check("version_monotonicity",
                    nextApply > log.lastApplied(),
                    "Apply index " + nextApply + " not > lastApplied " + log.lastApplied());

            LogEntry entry = log.entryAt(nextApply);
            if (entry != null) {
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
                    handleCommittedConfigChange(entry);
                } else {
                    stateMachine.apply(entry.index(), entry.term(), entry.command());
                }
            }
            log.setLastApplied(nextApply);
        }
        // F-0022: apply advanced lastApplied — some pending reads may now
        // satisfy the "lastApplied >= readIndex" condition.
        fireReadyCallbacks();
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
                log.append(new LogEntry(newIndex, currentTerm, configEntry));
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

        // Restore the state machine from the snapshot
        stateMachine.restoreSnapshot(req.data());

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

        // Successful install: lastApplied was just set to
        // req.lastIncludedIndex(); use the same max() form for
        // consistency with the reject paths above.
        transport.send(req.leaderId(),
                new InstallSnapshotResponse(currentTerm, true,
                        config.nodeId(),
                        Math.max(log.snapshotIndex(), log.lastApplied())));
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
        electionTimeoutTicks = config.electionTimeoutMinMs()
                + random.nextInt(config.electionTimeoutMaxMs() - config.electionTimeoutMinMs() + 1);
    }
}

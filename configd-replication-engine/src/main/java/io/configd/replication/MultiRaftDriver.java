package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescedHeartbeat;
import io.configd.raft.CoalescedHeartbeatTransport;
import io.configd.raft.HeartbeatCoalescer;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages multiple Raft groups on a single node. Each tick advances all
 * groups. Messages from the transport are routed to the correct group.
 * <p>
 * Each group has a dedicated owner thread supplied by the {@link OwnerExecutorPool}.
 * {@link #tickOwner(int)} ticks the groups bound to one owner thread (per-owner scheduling)
 * and {@link #ownerExecutor(int)} gives each group's owner for marshalling. The {@code groups}
 * map is a {@link ConcurrentHashMap} so owner threads and the inbound path read it
 * concurrently with infrequent add/remove.
 * <p>
 * Groups are identified by integer group IDs. Each group ID maps to
 * exactly one {@link RaftNode}. Adding or removing groups is expected
 * to be infrequent (configuration change) and is O(1).
 *
 * @see RaftNode
 */
public final class MultiRaftDriver {

    private final NodeId localNode;
    private final Clock clock;

    /**
     * Map from group ID to the RaftNode driving that group.
     * Iteration order is undefined; tick order among groups is not
     * guaranteed and must not be relied upon.
     */
    private final Map<Integer, RaftNode> groups;

    /**
     * The owner-executor pool. Null in legacy/test wiring (which drives
     * {@link #tick()} on a single thread); set by the server via {@link #setOwnerPool} to enable
     * per-owner ticking ({@link #tickOwner}) and owner-targeted marshalling ({@link #ownerExecutor}).
     * Volatile: published by the wiring thread, read by every owner + inbound thread.
     */
    private volatile OwnerExecutorPool ownerPool;

    /**
     * Dynamic group-to-owner-index mapping - the authority for routing and tick eligibility.
     * Empty by default: {@link #currentOwnerIndex} falls back to the static {@code floorMod(gid, N)}.
     * A rehoming handoff atomically re-points a group here. A {@link ConcurrentHashMap} so owner +
     * inbound threads read it while {@link #rehomeGroup} writes it.
     * Dormant in production (single-group is never rehomed).
     */
    private final Map<Integer, Integer> groupOwner = new ConcurrentHashMap<>();

    /**
     * Groups currently mid-handoff. While a group is here, {@link #tickOwner}/
     * {@link #maybeCompactOwner} skip it and marshalled work re-dispatches (check-and-bounce), so no
     * entry point runs on an ambiguous owner during the handoff window. Cleared once the gaining owner
     * has adopted.
     */
    private final Set<Integer> migrating = ConcurrentHashMap.newKeySet();

    /**
     * One {@link HeartbeatCoalescer} per owner thread (index = owner index), or {@code null} when
     * coalescing is not enabled (legacy/test wiring). Each is touched only on its owner's thread:
     * {@link #tickOwner} opens its window and drains it; the group's {@code CoalescingRaftTransport}
     * (bound to the same instance via {@link #heartbeatCoalescer}) records into it during
     * {@code node.tick()}. Volatile: published by the wiring thread via
     * {@link #enableHeartbeatCoalescing}, read by every owner thread.
     */
    private volatile HeartbeatCoalescer[] coalescers;

    /**
     * Where {@link #tickOwner} drains each owner's coalesced heartbeats (one call per peer).
     * Set together with {@link #coalescers} by {@link #enableHeartbeatCoalescing}; null means
     * coalescing is disabled. The implementation owns the framing (1 group produces a plain
     * AppendEntries; more than 1 produces a {@link CoalescedHeartbeat}).
     */
    private volatile CoalescedHeartbeatTransport heartbeatDrain;

    /**
     * Creates a new MultiRaftDriver.
     *
     * @param localNode the identifier of this node in the cluster
     * @param clock     clock source for time-dependent operations
     * @throws NullPointerException if any argument is null
     */
    public MultiRaftDriver(NodeId localNode, Clock clock) {
        this.localNode = Objects.requireNonNull(localNode, "localNode");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.groups = new ConcurrentHashMap<>();
    }

    /**
     * Registers a Raft group with this driver.
     *
     * @param groupId the unique identifier for this Raft group
     * @param node    the RaftNode instance driving the group's consensus
     * @throws NullPointerException     if {@code node} is null
     * @throws IllegalArgumentException if a group with the given ID is already registered
     */
    public void addGroup(int groupId, RaftNode node) {
        Objects.requireNonNull(node, "node");
        if (groups.containsKey(groupId)) {
            throw new IllegalArgumentException("Group already registered: " + groupId);
        }
        groups.put(groupId, node);
    }

    /**
     * Removes a Raft group from this driver. After removal, the group
     * will no longer be ticked and messages routed to it will be dropped.
     *
     * @param groupId the identifier of the group to remove
     * @throws IllegalArgumentException if no group with the given ID is registered
     */
    public void removeGroup(int groupId) {
        if (groups.remove(groupId) == null) {
            throw new IllegalArgumentException("Group not registered: " + groupId);
        }
        // Also drop any rehoming state so a later addGroup of the same id starts clean on the
        // static floorMod owner - otherwise a stale groupOwner override or migrating mark
        // would route the fresh node to the wrong owner.
        groupOwner.remove(groupId);
        migrating.remove(groupId);
    }

    /**
     * Advances all registered Raft groups by one tick.
     * <p>
     * This is the primary driver loop entry point. The caller (I/O thread)
     * invokes this at a fixed interval (e.g., every 1ms). Each call
     * iterates all groups exactly once - O(groups), not O(groups * peers).
     */
    public void tick() {
        for (RaftNode node : groups.values()) {
            node.tick();
        }
    }

    /**
     * Threshold-gated Raft-log compaction across all groups. The server tick loop calls this
     * so {@link RaftNode#maybeCompact(long)} is reachable in the wired server - without it
     * the only {@code triggerSnapshot()} caller was the circular {@code sendInstallSnapshot},
     * so each group's WAL grew for the life of the process. O(groups); a group only snapshots
     * when its applied-since-snapshot span exceeds the threshold (snapshot work is amortized
     * and rare).
     *
     * @param appliedSinceSnapshotThreshold applied entries a group may retain past its
     *                                       snapshot point before compacting
     */
    public void maybeCompact(long appliedSinceSnapshotThreshold) {
        for (RaftNode node : groups.values()) {
            node.maybeCompact(appliedSinceSnapshotThreshold);
        }
    }

    /**
     * Binds the owner-executor pool. Called once by the server at wiring, before any owner is
     * scheduled. After this, {@link #tickOwner}/{@link #ownerExecutor} are usable; the legacy
     * {@link #tick()}/{@link #maybeCompact} remain for single-threaded test wiring.
     */
    public void setOwnerPool(OwnerExecutorPool pool) {
        this.ownerPool = Objects.requireNonNull(pool, "pool");
    }

    /** The owner-executor pool, or null if not set (legacy/test wiring). */
    public OwnerExecutorPool ownerPool() {
        return ownerPool;
    }

    /**
     * Enables coalesced heartbeats: creates one {@link HeartbeatCoalescer} per owner and routes
     * each owner's per-tick drain to {@code drain}. Call once at wiring after {@link #setOwnerPool},
     * before any group is ticked; then bind each group's {@code CoalescingRaftTransport} to
     * {@link #heartbeatCoalescer} for its owner. Strictly additive - until this runs,
     * {@link #tickOwner} ticks exactly as before (legacy/test wiring leaves coalescing off).
     *
     * @param drain where {@link #tickOwner} sends each owner's coalesced heartbeats (one call per peer)
     * @throws IllegalStateException if the owner pool has not been set
     * @throws NullPointerException  if {@code drain} is null
     */
    public void enableHeartbeatCoalescing(CoalescedHeartbeatTransport drain) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run before enableHeartbeatCoalescing()");
        }
        Objects.requireNonNull(drain, "drain");
        HeartbeatCoalescer[] hcs = new HeartbeatCoalescer[p.size()];
        for (int i = 0; i < hcs.length; i++) {
            hcs[i] = new HeartbeatCoalescer();
        }
        this.coalescers = hcs;
        this.heartbeatDrain = drain;
    }

    /**
     * The {@link HeartbeatCoalescer} for an owner, for binding that owner's groups'
     * {@code CoalescingRaftTransport} decorators. Available only after {@link #enableHeartbeatCoalescing}.
     *
     * @throws IllegalStateException if coalescing has not been enabled
     */
    public HeartbeatCoalescer heartbeatCoalescer(int ownerIndex) {
        HeartbeatCoalescer[] hcs = coalescers;
        if (hcs == null) {
            throw new IllegalStateException("heartbeat coalescing not enabled — call enableHeartbeatCoalescing() first");
        }
        return hcs[ownerIndex];
    }

    /**
     * Demultiplexes a received {@link CoalescedHeartbeat} back into per-group inbound routing:
     * each group's empty AppendEntries is delivered via {@link #routeMessage}, exactly as if it
     * had arrived un-coalesced. Exercised by N&gt;1 test surfaces.
     *
     * <p><b>Threading note - not the production receive path.</b> This calls {@link #routeMessage}
     * inline on the caller's thread, and {@code routeMessage} runs {@code node.handleMessage} on
     * the calling thread for a non-rehomed group, which asserts the owner thread. So this is only
     * safe when the caller's thread is the owner of EVERY group in {@code ch} - i.e. a single-owner
     * sim or test where all groups share one owner. On the real wire a coalesced frame can bundle
     * groups with DIFFERENT owners at N&gt;1, so the production inbound handler decodes the frame
     * and dispatches each group through its own owner executor separately. Calling this from an
     * inbound/IO thread at N&gt;1 would run {@code handleMessage} off-owner and trip the owner
     * assertion.
     *
     * @param from the node that sent the coalesced heartbeat (the AppendEntries also carry {@code leaderId})
     * @param ch   the coalesced heartbeat
     */
    public void routeCoalescedHeartbeat(NodeId from, CoalescedHeartbeat ch) {
        for (Map.Entry<Integer, AppendEntriesRequest> e : ch.groupHeartbeats().entrySet()) {
            routeMessage(e.getKey(), e.getValue());
        }
    }

    /**
     * The owner executor for a group - the ONLY executor on which that group's {@link RaftNode} may
     * run. Inbound/propose/read/flush marshal their {@code RaftNode} work onto this.
     *
     * @throws IllegalStateException if the owner pool has not been set
     */
    public ScheduledExecutorService ownerExecutor(int groupId) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        return p.ownerByIndex(currentOwnerIndex(groupId));
    }

    /**
     * The current owner index for a group: the rehoming override if one exists, else the
     * static {@code floorMod(gid, N)} default. The single source of truth for routing and tick
     * eligibility. At N=1 with no rehome active there is no override, so this is the static mapping.
     *
     * @throws IllegalStateException if the owner pool has not been set
     */
    public int currentOwnerIndex(int groupId) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        Integer override = groupOwner.get(groupId);
        return override != null ? override : p.ownerIndexOf(groupId);
    }

    /**
     * Per-owner consensus tick: ticks every group bound to {@code ownerIndex}. MUST be invoked on that
     * owner's thread (the per-owner scheduled task), so each {@code node.tick()} runs on its group's
     * owner thread. At N=1 a single owner ticks every group, exactly reproducing the {@link #tick()} loop.
     */
    public void tickOwner(int ownerIndex) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        // Open this owner's heartbeat-coalescing window for the duration of its tick. Each
        // group's empty AppendEntries (heartbeat) emitted during node.tick() is buffered into hc
        // instead of sent; at the end we drain hc into one message per peer (cost flat in group
        // count). Gate on BOTH coalescers AND heartbeatDrain (enableHeartbeatCoalescing sets them
        // together): we never open a heartbeat window we cannot drain, so a heartbeat is never
        // buffered only to be silently discarded (which would starve followers).
        HeartbeatCoalescer[] hcs = coalescers;
        CoalescedHeartbeatTransport drain = heartbeatDrain;
        HeartbeatCoalescer hc = (hcs != null && drain != null) ? hcs[ownerIndex] : null;
        if (hc != null) {
            hc.beginTick();
        }
        try {
            for (Map.Entry<Integer, RaftNode> e : groups.entrySet()) {
                int g = e.getKey();
                // Tick only the groups this owner currently owns (rehoming-aware) and that are NOT
                // mid-handoff (a migrating group is owned by nobody until adopt - skipping it keeps
                // the ambiguous window tick-free).
                if (currentOwnerIndex(g) == ownerIndex && !migrating.contains(g)) {
                    e.getValue().tick();
                }
            }
        } finally {
            // Drain the coalesced heartbeats even if a group's tick() threw - otherwise the
            // heartbeats already recorded this tick would be dropped, and a recurring throw would
            // starve followers into spurious elections.
            if (hc != null) {
                drainHeartbeats(hc, drain);
            }
        }
    }

    /**
     * Drains one owner's coalesced heartbeats, sending one message per peer via {@code drain}
     * (guaranteed non-null by the caller's both-wired gate). Per-peer exception isolation: one peer's
     * send failure must not starve the others (fire-and-forget, Raft retransmits). The drain is pure
     * I/O - it never re-enters a {@link RaftNode}, so a concurrent rehome cannot tear it.
     */
    private void drainHeartbeats(HeartbeatCoalescer hc, CoalescedHeartbeatTransport drain) {
        for (Map.Entry<NodeId, Map<Integer, AppendEntriesRequest>> e : hc.drainAndEndTick().entrySet()) {
            try {
                drain.sendCoalesced(e.getKey(), e.getValue());
            } catch (RuntimeException ignored) {
                // isolate this peer; the rest still get their heartbeats
            }
        }
    }

    /**
     * Per-owner threshold-gated Raft-log compaction: {@link RaftNode#maybeCompact(long)} for every
     * group bound to {@code ownerIndex}. MUST run on that owner's thread (same contract as
     * {@link #tickOwner}).
     */
    public void maybeCompactOwner(int ownerIndex, long appliedSinceSnapshotThreshold) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        for (Map.Entry<Integer, RaftNode> e : groups.entrySet()) {
            int g = e.getKey();
            // Same rehoming-aware, not-migrating filter as tickOwner.
            if (currentOwnerIndex(g) == ownerIndex && !migrating.contains(g)) {
                e.getValue().maybeCompact(appliedSinceSnapshotThreshold);
            }
        }
    }

    /**
     * Rehomes {@code groupId} from its current owner to {@code targetOwnerIndex} via a
     * quiesce-then-publish-then-adopt handoff. Dormant in production (single-group is never
     * rehomed); used by tests and a future placement policy.
     *
     * <p>Ordering - the executor {@code .get()} barriers give happens-before, so there is no torn
     * state and no double-ownership window:
     * <ol>
     *   <li>Mark the group migrating (tick + marshalled work now skip or bounce it).</li>
     *   <li>On the losing owner's thread, in order: QUIESCE ({@code node.quiesceForHandoff()} -
     *       force-sync buffered entries so the gaining owner adopts a clean, durable state),
     *       PUBLISH the routing flip ({@code groupOwner->target}), then DETACH
     *       ({@code node.beginHandoff()} to the HANDOFF sentinel).</li>
     *   <li>On the gaining owner's thread: ADOPT ({@code node.adoptOwnerThread()}), ordered after
     *       the detach by the barrier, which also publishes the losing owner's final state here.</li>
     *   <li>Clear migrating (the gaining owner now ticks and serves the group).</li>
     * </ol>
     * If the gaining owner cannot adopt after the losing owner has detached, the handoff rolls back
     * to the losing owner ({@link #abortHandoff}): routing is restored to its exact pre-rehome state
     * and the losing owner re-adopts, so the group resumes with no torn state and no lost message.
     * Only if the losing owner is also unavailable does the group stay loudly wedged on the HANDOFF
     * sentinel (every access fires) - never silently mis-owned. {@code migrating} is always cleared.
     *
     * <p>The handoff is uninterruptible: an interrupt does not abandon it mid-flight (which would let
     * a queued owner task publish/detach AFTER the coordinator unwound - wedging the group); it
     * completes or rolls back atomically, and the interrupt is re-asserted to the caller after.
     *
     * @throws IllegalStateException    if the owner pool is not set, or a handoff step fails on an owner
     * @throws IllegalArgumentException if the group is unknown or already on the target owner
     */
    public void rehomeGroup(int groupId, int targetOwnerIndex) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        if (targetOwnerIndex < 0 || targetOwnerIndex >= p.size()) {
            throw new IllegalArgumentException("targetOwnerIndex " + targetOwnerIndex
                    + " out of range [0," + p.size() + ") — pool has " + p.size() + " owner(s)");
        }
        RaftNode node = groups.get(groupId);
        if (node == null) {
            throw new IllegalArgumentException("Group not registered: " + groupId);
        }
        Integer priorOverride = groupOwner.get(groupId); // null means the group was on its static floorMod owner
        int from = priorOverride != null ? priorOverride : p.ownerIndexOf(groupId);
        if (from == targetOwnerIndex) {
            throw new IllegalArgumentException(
                    "Group " + groupId + " is already owned by owner " + targetOwnerIndex);
        }
        migrating.add(groupId);
        boolean detached = false; // the losing owner has published+detached but the gaining owner has not adopted
        try {
            // QUIESCE + PUBLISH + DETACH on the LOSING owner (serialized with its work for this group).
            runOnOwnerAwait(p.ownerByIndex(from), () -> {
                node.quiesceForHandoff();                  // QUIESCE: force-sync so B adopts a durable state
                groupOwner.put(groupId, targetOwnerIndex); // PUBLISH the routing flip (on the losing owner)
                node.beginHandoff();                        // DETACH: ownerThread -> HANDOFF sentinel
            });
            detached = true;
            // ADOPT on the GAINING owner (ordered after the detach by the await barrier above).
            runOnOwnerAwait(p.ownerByIndex(targetOwnerIndex), node::adoptOwnerThread);
            detached = false; // adopt succeeded - handoff complete, nothing to roll back
        } catch (RuntimeException e) {
            if (detached) {
                // The losing owner detached but the gaining owner could not adopt - roll back to A.
                try {
                    abortHandoff(groupId, from, priorOverride, node, p);
                } catch (RuntimeException abortFailed) {
                    e.addSuppressed(abortFailed); // A also unavailable - group stays wedged on HANDOFF (loud)
                }
            }
            throw e;
        } finally {
            migrating.remove(groupId); // reopen the group for ticking + marshalled work on the current owner
        }
    }

    /**
     * Rolls a partial handoff back to the losing owner (see {@link #rehomeGroup}). The losing
     * owner has detached ({@code ownerThread==HANDOFF}) and routing was published to the target, but
     * the gaining owner never adopted. Restores routing to its exact pre-rehome state
     * ({@code priorOverride}, or no override if the group was on its static owner) and re-binds the
     * losing owner via {@code adoptOwnerThread()} (legal because {@code ownerThread} is still the
     * HANDOFF sentinel). Runs while {@code migrating} is still set, so no tick or marshalled work
     * touches the node during rollback. The losing owner's state is intact and durable (it quiesced
     * before detaching, and the gaining owner never touched it), so there is no torn state and no
     * lost message.
     */
    private void abortHandoff(int groupId, int fromOwnerIndex, Integer priorOverride, RaftNode node,
                              OwnerExecutorPool p) {
        if (priorOverride != null) {
            groupOwner.put(groupId, priorOverride); // restore the prior rehoming override
        } else {
            groupOwner.remove(groupId);             // group was on its static owner - leave no override
        }
        runOnOwnerAwait(p.ownerByIndex(fromOwnerIndex), node::adoptOwnerThread); // HANDOFF -> losing owner
    }

    /**
     * Runs {@code task} on {@code owner} and blocks uninterruptibly until it completes, surfacing any
     * failure. Uninterruptible by design: a {@link Future#get()} interrupt does NOT cancel the
     * already-submitted owner task, so abandoning the wait here would let that task run later
     * (publish, detach, or adopt) AFTER the coordinator unwound and the {@code finally} cleared
     * {@code migrating} - wedging the group in a torn published-but-not-adopted state the rollback
     * was meant to prevent. Instead we keep waiting for the bounded owner task to finish, then
     * re-assert the interrupt to the caller: the handoff completes (or rolls back) atomically and
     * the interrupt is honoured after, never lost.
     */
    private static void runOnOwnerAwait(ScheduledExecutorService owner, Runnable task) {
        Future<?> f = owner.submit(task);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    f.get();
                    return;
                } catch (InterruptedException e) {
                    interrupted = true; // defer - never abandon a queued handoff step mid-flight
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(
                            "rehoming handoff step failed on an owner executor", e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt(); // re-assert the deferred interrupt to the caller
            }
        }
    }

    /**
     * Routes an incoming message to the correct Raft group.
     * <p>
     * If no group with the given ID is registered, the message is
     * silently dropped. This can happen during group removal or
     * when a stale message arrives for a group that has been
     * decommissioned.
     *
     * @param groupId the target Raft group identifier
     * @param message the Raft protocol message to deliver
     */
    public void routeMessage(int groupId, RaftMessage message) {
        RaftNode node = groups.get(groupId);
        if (node == null) {
            return; // absent group - drop stale message for a removed or unknown group
        }
        // Only the group's current owner may touch the node. If the group is mid-handoff, or it has
        // been rehomed and this thread is not its current owner (stale routing), re-dispatch to the
        // current owner instead of running handleMessage off-owner - no message lost (re-queued),
        // none misrouted (only the owner touches the node).
        //
        // The bounce is gated on the group being rehome-affected (migrating, or carrying a dynamic
        // groupOwner override). A never-rehomed group - which is every group in production - never
        // bounces, so a missed marshalling hop still runs handleMessage off-owner and the owner
        // assertion fires ("test the tester" preserved; a bounce here would silently auto-correct a
        // missed hop and mask the bug).
        //
        // Do NOT bounce a group wedged on the HANDOFF sentinel (an abandoned handoff, not migrating).
        // No real owner will ever run it, so re-dispatching would livelock the owner thread (CPU burn
        // and the message never delivered). Fall through so handleMessage's guard fires once (loud) -
        // consistent with the flush path. A transient settled rehome has a real new owner, so
        // isDetached()==false and the stale-routing bounce still lands correctly.
        if (ownerPool != null
                && (migrating.contains(groupId)
                        || (groupOwner.containsKey(groupId) && node.boundToAnotherThread() && !node.isDetached()))) {
            ownerExecutor(groupId).execute(() -> routeMessage(groupId, message));
            return;
        }
        node.handleMessage(message);
    }

    /**
     * Proposes a command to the specified Raft group. Only the leader
     * of that group can accept proposals.
     * <p>
     * Returns a {@link ProposeOutcome} carrying the assigned {@code (index, term)} on acceptance
     * so the caller can register a commit-outcome callback on the owning {@link RaftNode}.
     *
     * @param groupId the target Raft group identifier
     * @param command the command bytes to replicate
     * @return the proposal outcome; {@code rejected(NOT_LEADER)} if the group does
     *         not exist or this node is not the leader
     */
    public ProposeOutcome propose(int groupId, byte[] command) {
        RaftNode node = groups.get(groupId);
        if (node == null) {
            return ProposeOutcome.rejected(ProposalResult.NOT_LEADER);
        }
        // A propose returns its (index,term) synchronously, so it cannot be silently re-queued like
        // routeMessage. If the group is mid-handoff or has been rehomed away from this thread, reject
        // as NOT_LEADER - the external client retries via the leader hint and the retry re-marshals
        // onto the current owner (the wired proposer captures ownerExecutor(gid) once at wiring).
        // Gated on rehome-affected (same as routeMessage) so a missed hop on a never-rehomed group
        // still reaches node.propose() off-owner and fires the owner assertion. Inert at N=1.
        if (ownerPool != null
                && (migrating.contains(groupId)
                        || (groupOwner.containsKey(groupId) && node.boundToAnotherThread()))) {
            return ProposeOutcome.rejected(ProposalResult.NOT_LEADER);
        }
        return node.propose(command);
    }

    /**
     * Marshals a coalescing group-commit flush onto the group's current owner. Wired by the
     * production server as the {@link RaftNode.FlushScheduler} (replacing a closure that captured
     * the owner executor once, which would dispatch onto the old owner after a rehome). The owner
     * is re-resolved via {@link #ownerExecutor} (rehoming-aware) and, when the task runs,
     * {@link #runFlushOnCurrentOwner} applies the same check-and-bounce as {@link #routeMessage}:
     * a flush scheduled before a rehome lands on the new owner, never on an ambiguous owner
     * mid-handoff. The flush ({@code RaftNode.flushDurable}) is owner-guarded, so any residual
     * off-owner dispatch fires rather than silently racing.
     * <p>If the owner pool is not set (legacy/test wiring), the flush runs inline on the caller.
     *
     * @param groupId     the group whose buffered entries to flush
     * @param flush       the flush task (the group's {@code RaftNode::flushDurable} reference)
     * @param delayMicros linger delay before the flush (0 = ASAP)
     */
    public void dispatchFlush(int groupId, Runnable flush, long delayMicros) {
        ScheduledExecutorService owner;
        try {
            owner = ownerExecutor(groupId); // current owner (rehoming-aware)
        } catch (IllegalStateException noPool) {
            flush.run(); // legacy/test wiring without a pool - run inline on the caller, as before
            return;
        }
        Runnable task = () -> runFlushOnCurrentOwner(groupId, flush);
        if (delayMicros <= 0) {
            owner.execute(task);
        } else {
            owner.schedule(task, delayMicros, TimeUnit.MICROSECONDS);
        }
    }

    /**
     * Body of a dispatched flush, run on an owner thread: check-and-bounce, then flush. If the
     * group is mid-handoff, or has been rehomed away from the executing owner (stale dispatch),
     * re-dispatch to the current owner instead of flushing off-owner - mirroring
     * {@link #routeMessage}. A removed group drops the stale flush.
     */
    private void runFlushOnCurrentOwner(int groupId, Runnable flush) {
        RaftNode node = groups.get(groupId);
        if (node == null) {
            return; // group removed - drop the stale flush
        }
        // Bounce while a handoff is in flight (migrating), or for a stale dispatch to the old owner
        // after a settled rehome (re-dispatch lands on the new real owner). Do NOT bounce a group
        // wedged on the HANDOFF sentinel (an abandoned handoff, not migrating): no real owner will
        // ever run it, so re-dispatching would livelock the owner thread. Fall through instead so
        // flushDurable's guard fires once (loud) rather than spinning silently.
        if (migrating.contains(groupId)
                || (groupOwner.containsKey(groupId) && node.boundToAnotherThread() && !node.isDetached())) {
            ownerExecutor(groupId).execute(() -> runFlushOnCurrentOwner(groupId, flush));
            return;
        }
        flush.run(); // on-owner and silent; wedged HANDOFF node fires the guard (loud, no spin)
    }

    /**
     * Returns the {@link RaftNode} for the given group, or {@code null}
     * if no such group is registered.
     *
     * @param groupId the group identifier
     * @return the RaftNode, or null
     */
    public RaftNode getGroup(int groupId) {
        return groups.get(groupId);
    }

    /**
     * Returns an unmodifiable view of the currently registered group IDs.
     *
     * @return set of group IDs; never null
     */
    public Set<Integer> groupIds() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    /**
     * Returns the number of Raft groups currently registered.
     *
     * @return the group count
     */
    public int groupCount() {
        return groups.size();
    }

    /**
     * Returns the local node identifier for this driver.
     *
     * @return the local node ID
     */
    public NodeId localNode() {
        return localNode;
    }

    /**
     * Returns the clock used by this driver.
     *
     * @return the clock instance
     */
    public Clock clock() {
        return clock;
    }
}

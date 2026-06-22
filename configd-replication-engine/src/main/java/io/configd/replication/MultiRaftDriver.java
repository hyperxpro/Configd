package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
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
 * Design: historically a single I/O thread called {@link #tick()} which iterated all groups (R-01).
 * Phase 0 Workstream B adds the owner-executor pool: {@link #tickOwner(int)} ticks the groups bound
 * to one owner thread (per-owner scheduling) and {@link #ownerExecutor(int)} gives each group's owner
 * for marshalling. The {@code groups} map is a {@link ConcurrentHashMap} so owner threads and the
 * inbound path read it concurrently with infrequent add/remove (H-5).
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
     * Phase 0 — Workstream B — the owner-executor pool. Null in legacy/test wiring (which drives
     * {@link #tick()} on a single thread); set by the server via {@link #setOwnerPool} to enable
     * per-owner ticking ({@link #tickOwner}) and owner-targeted marshalling ({@link #ownerExecutor}).
     * Volatile: published by the wiring thread, read by every owner + inbound thread.
     */
    private volatile OwnerExecutorPool ownerPool;

    /**
     * Stage 2 M2 (rehoming) — the DYNAMIC group→owner-index mapping; the authority for routing and tick
     * eligibility. Empty by default: {@link #currentOwnerIndex} falls back to the static
     * {@code floorMod(gid, N)}. A rehoming handoff atomically re-points a group here. A
     * {@link ConcurrentHashMap} so owner + inbound threads read it while {@link #rehomeGroup} writes it.
     * DORMANT in production (single-group is never rehomed); exercised by tests + a future Phase-1
     * placement policy. See docs/phase0-B-stage2/m2-rehoming-handoff-design.md.
     */
    private final Map<Integer, Integer> groupOwner = new ConcurrentHashMap<>();

    /**
     * Stage 2 M2 (rehoming) — groups currently mid-handoff. While a group is here, {@link #tickOwner}/
     * {@link #maybeCompactOwner} skip it and marshalled work re-dispatches (check-and-bounce), so no
     * entry point runs on an ambiguous owner during the handoff window. Cleared once the gaining owner
     * has adopted.
     */
    private final Set<Integer> migrating = ConcurrentHashMap.newKeySet();

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

    // ========================================================================
    // Group management
    // ========================================================================

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
        // Stage 2 M2 (H-5): also drop any rehoming state so a later addGroup of the same id starts
        // clean on the static floorMod owner — otherwise a stale groupOwner override / migrating mark
        // would route the fresh node to the wrong owner (red-team Defect 2).
        groupOwner.remove(groupId);
        migrating.remove(groupId);
    }

    // ========================================================================
    // Tick and message routing
    // ========================================================================

    /**
     * Advances all registered Raft groups by one tick.
     * <p>
     * This is the primary driver loop entry point. The caller (I/O thread)
     * invokes this at a fixed interval (e.g., every 1ms). Each call
     * iterates all groups exactly once — O(groups), not O(groups * peers).
     */
    public void tick() {
        for (RaftNode node : groups.values()) {
            node.tick();
        }
    }

    /**
     * Threshold-gated Raft-log compaction across all groups (RR-005). The server tick loop
     * calls this so {@link RaftNode#maybeCompact(long)} is actually reachable in the wired
     * server — without it the only {@code triggerSnapshot()} caller was the circular
     * {@code sendInstallSnapshot}, so each group's WAL grew for the life of the process.
     * O(groups); a group only snapshots when its applied-since-snapshot span exceeds the
     * threshold (the snapshot work itself is therefore amortized and rare).
     *
     * @param appliedSinceSnapshotThreshold applied entries a group may retain past its
     *                                       snapshot point before compacting
     */
    public void maybeCompact(long appliedSinceSnapshotThreshold) {
        for (RaftNode node : groups.values()) {
            node.maybeCompact(appliedSinceSnapshotThreshold);
        }
    }

    // ========================================================================
    // Owner-executor pool (Phase 0 Workstream B) — per-owner ticking + marshalling
    // ========================================================================

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
     * The owner executor for a group — the ONLY executor on which that group's {@link RaftNode} may
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
     * Stage 2 M2 — the CURRENT owner index for a group: the rehoming override if one exists, else the
     * static {@code floorMod(gid, N)} default. The single source of truth for routing + tick eligibility.
     * At N=1 / no-rehome there is no override, so this is exactly the M1 static mapping.
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
     * Per-owner consensus tick: ticks every group bound to {@code ownerIndex}. MUST be invoked ON that
     * owner's thread (the per-owner scheduled task), so each {@code node.tick()} runs on its group's
     * owner thread — preserving R-01′ (the {@code assertOwnerThread()} net asserts it). At N=1 a single
     * owner ticks every group, exactly reproducing the R-01 {@link #tick()} loop.
     */
    public void tickOwner(int ownerIndex) {
        OwnerExecutorPool p = ownerPool;
        if (p == null) {
            throw new IllegalStateException("owner pool not set — setOwnerPool() must run at wiring");
        }
        for (Map.Entry<Integer, RaftNode> e : groups.entrySet()) {
            int g = e.getKey();
            // Stage 2 M2: tick only the groups this owner CURRENTLY owns (rehoming-aware) and that are
            // NOT mid-handoff (a migrating group is owned by nobody until adopt — skipping it keeps the
            // ambiguous window tick-free). At N=1/no-rehome this is exactly p.ownerIndexOf(g)==ownerIndex.
            if (currentOwnerIndex(g) == ownerIndex && !migrating.contains(g)) {
                e.getValue().tick();
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
            // Stage 2 M2: same rehoming-aware + not-migrating filter as tickOwner.
            if (currentOwnerIndex(g) == ownerIndex && !migrating.contains(g)) {
                e.getValue().maybeCompact(appliedSinceSnapshotThreshold);
            }
        }
    }

    /**
     * Stage 2 M2 — rehome {@code groupId} from its current owner to {@code targetOwnerIndex} via the
     * quiesce→publish→adopt handoff (docs/phase0-B-stage2/m2-rehoming-handoff-design.md). DORMANT in
     * production (single-group is never rehomed); exercised by tests + a future Phase-1 placement policy.
     *
     * <p>Ordering — the executor {@code .get()} barriers give happens-before, so there is no torn state
     * and no double-ownership window:
     * <ol>
     *   <li>mark the group migrating (tick + marshalled work now skip / bounce it);</li>
     *   <li>on the LOSING owner's thread, in order: QUIESCE ({@code node.quiesceForHandoff()} —
     *       force-sync buffered entries so the gaining owner adopts a clean, durable state), PUBLISH the
     *       routing flip ({@code groupOwner→target}), then DETACH ({@code node.beginHandoff()} → the
     *       HANDOFF sentinel);</li>
     *   <li>on the GAINING owner's thread: ADOPT ({@code node.adoptOwnerThread()}), ordered after the
     *       detach by the barrier — which also publishes all of the losing owner's final state here;</li>
     *   <li>clear migrating (the gaining owner now ticks + serves the group).</li>
     * </ol>
     * If the GAINING owner cannot adopt (e.g. its executor is unavailable) after the losing owner has
     * detached, the handoff is rolled back to the losing owner ({@link #abortHandoff}): routing is
     * restored to its exact pre-rehome state and the losing owner re-adopts, so the group resumes on its
     * original owner with no torn state and no lost message. Only if the LOSING owner is ALSO unavailable
     * does the group stay loudly wedged on the HANDOFF sentinel (every access fires) — never silently
     * mis-owned. {@code migrating} is always cleared.
     *
     * <p>The handoff is UNINTERRUPTIBLE (red-team Finding 1): an interrupt does not abandon it mid-flight
     * (which would let a queued owner task publish/detach AFTER the coordinator unwound — wedging the
     * group); it completes or rolls back atomically, and the interrupt is re-asserted to the caller after.
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
        Integer priorOverride = groupOwner.get(groupId); // null ⇒ the group was on its static floorMod owner
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
                node.beginHandoff();                        // DETACH: ownerThread → HANDOFF sentinel
            });
            detached = true;
            // ADOPT on the GAINING owner (ordered after the detach by the await barrier above).
            runOnOwnerAwait(p.ownerByIndex(targetOwnerIndex), node::adoptOwnerThread);
            detached = false; // adopt succeeded — handoff complete, nothing to roll back
        } catch (RuntimeException e) {
            if (detached) {
                // The losing owner detached but the gaining owner could not adopt — roll back to A.
                try {
                    abortHandoff(groupId, from, priorOverride, node, p);
                } catch (RuntimeException abortFailed) {
                    e.addSuppressed(abortFailed); // A also unavailable — group stays wedged on HANDOFF (loud)
                }
            }
            throw e;
        } finally {
            migrating.remove(groupId); // reopen the group for ticking + marshalled work on the current owner
        }
    }

    /**
     * Stage 2 M2 — roll a partial handoff back to the losing owner (see {@link #rehomeGroup}). The losing
     * owner has detached ({@code ownerThread==HANDOFF}) and routing was published to the target, but the
     * gaining owner never adopted. Restore routing to its EXACT pre-rehome state ({@code priorOverride},
     * or no override if the group was on its static owner) and re-bind the losing owner via
     * {@code adoptOwnerThread()} (legal because {@code ownerThread} is still the HANDOFF sentinel). Runs
     * while {@code migrating} is still set, so no tick / marshalled work touches the node during rollback.
     * The losing owner's state is intact and durable (it quiesced before detaching, and the gaining owner
     * never touched it) ⇒ no torn state, no lost message.
     */
    private void abortHandoff(int groupId, int fromOwnerIndex, Integer priorOverride, RaftNode node,
                              OwnerExecutorPool p) {
        if (priorOverride != null) {
            groupOwner.put(groupId, priorOverride); // restore the prior rehoming override
        } else {
            groupOwner.remove(groupId);             // group was on its static owner — leave no override
        }
        runOnOwnerAwait(p.ownerByIndex(fromOwnerIndex), node::adoptOwnerThread); // HANDOFF → losing owner
    }

    /**
     * Runs {@code task} on {@code owner} and blocks UNINTERRUPTIBLY until it completes, surfacing any
     * failure. Uninterruptible by design (red-team Finding 1): a {@link Future#get()} interrupt does NOT
     * cancel the already-submitted owner task, so ABANDONING the wait here would let that task run later
     * (publish / detach / adopt) AFTER the coordinator unwound and the {@code finally} cleared
     * {@code migrating} — wedging the group in a torn published-but-not-adopted state the rollback was
     * meant to prevent. Instead we keep waiting for the bounded owner task to finish, then re-assert the
     * interrupt to the caller: the handoff completes (or rolls back) ATOMICALLY and the interrupt is
     * honoured AFTER, never lost.
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
                    interrupted = true; // defer — never abandon a queued handoff step mid-flight
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
            return; // absent group → drop (stale message for a removed/unknown group), as before
        }
        // Stage 2 M2 check-and-bounce: only the group's CURRENT owner touches the node. If the group is
        // mid-handoff, or it has been REHOMED and this thread is not its current owner (stale routing),
        // RE-DISPATCH to the current owner instead of running handleMessage off-owner — no message lost
        // (re-queued), none misrouted (only the owner touches the node).
        //
        // The bounce is gated on the group being REHOME-AFFECTED (migrating, or carrying a dynamic
        // groupOwner override). A NEVER-REHOMED group — which is EVERY group in production (single-group,
        // dormant rehoming) — never bounces, so a missed marshalling hop still runs handleMessage
        // off-owner and the net FIRES (threading-contract §6.2 "test the tester" preserved; a bounce
        // here would silently auto-correct a missed hop and mask the bug — red-team Defect 1). At N=1 /
        // no-rehome this is identical to M1.
        if (ownerPool != null
                && (migrating.contains(groupId)
                        || (groupOwner.containsKey(groupId) && node.boundToAnotherThread()))) {
            ownerExecutor(groupId).execute(() -> routeMessage(groupId, message));
            return;
        }
        node.handleMessage(message);
    }

    /**
     * Proposes a command to the specified Raft group. Only the leader
     * of that group can accept proposals.
     * <p>
     * RR-004 / ADR-0033: returns a {@link ProposeOutcome} carrying the assigned
     * {@code (index, term)} on acceptance so the caller can register a
     * commit-outcome callback on the owning {@link RaftNode}.
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
        // Stage 2 M2: a propose returns its (index,term) synchronously (H-1), so it cannot be silently
        // re-queued like routeMessage. If the group is mid-handoff or has been rehomed away from this
        // thread, reject as NOT_LEADER — the external client retries (via the leader hint) and the retry
        // re-marshals onto the current owner (there is no internal re-marshal; the wired proposer captures
        // ownerExecutor(gid) once at wiring). Gated on rehome-affected (same as routeMessage) so a missed
        // hop on a NEVER-rehomed group still reaches node.propose() off-owner and FIRES the net (§6.2).
        // Inert at N=1 / no-rehome.
        if (ownerPool != null
                && (migrating.contains(groupId)
                        || (groupOwner.containsKey(groupId) && node.boundToAnotherThread()))) {
            return ProposeOutcome.rejected(ProposalResult.NOT_LEADER);
        }
        return node.propose(command);
    }

    /**
     * Stage 2 M2 — marshal a coalescing group-commit flush onto the group's CURRENT owner. Wired by the
     * production server as the {@link RaftNode.FlushScheduler} (replacing a closure that CAPTURED the owner
     * executor once, which would dispatch onto the OLD owner after a rehome). The owner is re-resolved
     * here via {@link #ownerExecutor} (rehoming-aware) and, when the task runs, {@link #runFlushOnCurrentOwner}
     * applies the same check-and-bounce as {@link #routeMessage}: a flush scheduled before a rehome lands on
     * the NEW owner, and never runs on an ambiguous owner mid-handoff. The flush ({@code RaftNode.flushDurable})
     * is owner-guarded, so any residual off-owner dispatch FIRES rather than silently racing.
     * <p>DORMANT in production (single group is never rehomed ⇒ the current owner is always the static
     * floorMod owner) and inert at N=1. If the owner pool is not set (legacy/test wiring), the flush runs
     * inline on the caller — exactly the pre-pool behaviour.
     *
     * @param groupId     the group whose buffered entries to flush
     * @param flush       the flush task (the group's {@code RaftNode::flushDurable} reference)
     * @param delayMicros linger delay before the flush (0 = ASAP)
     */
    public void dispatchFlush(int groupId, Runnable flush, long delayMicros) {
        ScheduledExecutorService owner;
        try {
            owner = ownerExecutor(groupId); // current owner (rehoming-aware via currentOwnerIndex)
        } catch (IllegalStateException noPool) {
            flush.run(); // legacy/test wiring without a pool — run inline on the caller, as before
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
     * The body of a dispatched flush, run ON an owner thread: check-and-bounce, then flush. If the group is
     * mid-handoff, or has been rehomed away from the executing owner (stale dispatch), RE-DISPATCH to the
     * current owner instead of flushing off-owner — mirroring {@link #routeMessage}. Otherwise run the flush
     * (on the current owner ⇒ {@code RaftNode.flushDurable}'s guard is silent). A removed group drops the
     * stale flush (nothing to sync).
     */
    private void runFlushOnCurrentOwner(int groupId, Runnable flush) {
        RaftNode node = groups.get(groupId);
        if (node == null) {
            return; // group removed — drop the stale flush
        }
        // Bounce while a handoff is in flight (migrating), or for a STALE dispatch to the old owner after a
        // SETTLED rehome (the node is owned by a different REAL owner — re-dispatch lands there). But do
        // NOT bounce a group WEDGED on the HANDOFF sentinel ({@code isDetached} yet not migrating — an
        // abandoned handoff / the rare double-fault): no real owner will ever run it, so re-dispatching
        // would LIVELOCK the owner thread (red-team Finding 2). Fall through instead so flushDurable's
        // guard FIRES once (loud) rather than spinning silently — consistent with "loudly wedged".
        if (migrating.contains(groupId)
                || (groupOwner.containsKey(groupId) && node.boundToAnotherThread() && !node.isDetached())) {
            ownerExecutor(groupId).execute(() -> runFlushOnCurrentOwner(groupId, flush));
            return;
        }
        flush.run(); // current owner ⇒ on-owner & silent; wedged HANDOFF node ⇒ guard FIRES (loud, no spin)
    }

    // ========================================================================
    // Query methods
    // ========================================================================

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

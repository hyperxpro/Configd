package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-tick buffer that coalesces Raft heartbeats (empty {@link AppendEntriesRequest}s) across the
 * multiple groups sharing one owner thread, so that one network message per peer carries every
 * group's liveness instead of one message per group per peer.
 * <p>
 * This is the CockroachDB/TiKV technique for removing heartbeat amplification with many
 * Raft groups. Without coalescing, N groups each send a heartbeat to every peer independently,
 * O(N * peers) messages per heartbeat interval; with coalescing the owner records each group's
 * heartbeat intent during its tick and drains them into one batched message per peer - O(peers),
 * flat in the group count.
 * <p>
 * <b>Threading.</b> Single-threaded, no synchronization - exactly ONE coalescer per owner thread, and
 * every method runs on that owner thread (records happen inside {@code node.tick()}; the window
 * open/drain happen in the owner's {@code tickOwner} task). It is never shared across owner threads
 * (that would race the {@link HashMap} and break the threading-contract section 2 per-owner isolation).
 */
public final class HeartbeatCoalescer {

    /**
     * Maps each peer to the latest heartbeat ({@link AppendEntriesRequest}) recorded for each group
     * this tick. Populated only while {@link #collecting}; cleared on {@link #drainAndEndTick()}.
     * {@link LinkedHashMap} so the drain replays heartbeats in record order (= the leader's {@code peersOf}
     * broadcast order). This preserves the per-tick send payloads and the heartbeat send order; note it is
     * NOT byte-identical to the un-coalesced path on a tick that mixes a buffered heartbeat with an
     * immediately-sent entry-carrying AppendEntries (the heartbeat now drains after it), so the sim
     * seed-sweep is a re-established baseline - green on the new trajectory.
     */
    private final Map<NodeId, Map<Integer, AppendEntriesRequest>> pending = new LinkedHashMap<>();

    /**
     * True while the owner is inside a tick window (between {@link #beginTick()} and
     * {@link #drainAndEndTick()}). Heartbeats are coalesced ONLY while collecting; a heartbeat emitted
     * outside the window (e.g. from an inbound or propose task) is not buffered  -  the caller sends it
     * immediately, so it is never delayed to the next tick.
     */
    private boolean collecting;

    /** Opens the tick window: subsequent {@link #recordIfCollecting} calls are coalesced. */
    public void beginTick() {
        this.collecting = true;
    }

    /** Whether a tick window is currently open. */
    public boolean isCollecting() {
        return collecting;
    }

    /**
     * Records {@code ae} as the heartbeat for {@code (peer, groupId)} this tick, IF a tick window is
     * open. The latest record per (peer, group) wins (so the most recent {@code leaderCommit} is the
     * one sent). Returns {@code true} if the heartbeat was buffered (the caller must NOT also send it),
     * {@code false} if no window is open (the caller sends it immediately).
     *
     * @param peer    the destination peer
     * @param groupId the Raft group this heartbeat belongs to
     * @param ae      the empty {@link AppendEntriesRequest} (the heartbeat) to coalesce
     * @return {@code true} if buffered; {@code false} if not collecting
     * @throws NullPointerException if {@code peer} or {@code ae} is null
     */
    public boolean recordIfCollecting(NodeId peer, int groupId, AppendEntriesRequest ae) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(ae, "ae");
        if (!collecting) {
            return false;
        }
        pending.computeIfAbsent(peer, k -> new LinkedHashMap<>()).put(groupId, ae);
        return true;
    }

    /**
     * Closes the tick window and returns every buffered heartbeat as {@code peer -> {group -> ae}},
     * then clears the buffer. The caller sends one message per returned peer (a single
     * {@link AppendEntriesRequest} when the peer has exactly one group; a coalesced message otherwise).
     *
     * @return an unmodifiable snapshot of the buffered heartbeats per peer; never null, possibly empty
     */
    public Map<NodeId, Map<Integer, AppendEntriesRequest>> drainAndEndTick() {
        collecting = false;
        if (pending.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<NodeId, Map<Integer, AppendEntriesRequest>> out = new LinkedHashMap<>(pending.size());
        for (var e : pending.entrySet()) {
            // preserve per-group record order too (matters only at N>1; harmless at N=1)
            out.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        pending.clear();
        return Collections.unmodifiableMap(out);
    }

    /** The peers that currently have at least one buffered heartbeat (diagnostic / test). */
    public Set<NodeId> pendingPeers() {
        return Collections.unmodifiableSet(pending.keySet());
    }

    /** Discards all buffered heartbeats and closes any open window. */
    public void reset() {
        pending.clear();
        collecting = false;
    }
}

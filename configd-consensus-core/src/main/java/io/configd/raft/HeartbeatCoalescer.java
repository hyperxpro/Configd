package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-tick buffer that coalesces Raft heartbeats across the groups sharing one owner thread, so one
 * message per peer carries every group's liveness. Without this, N groups each heartbeat every peer
 * independently: O(N * peers) messages per interval instead of O(peers), flat in the group count.
 * <p>
 * Exactly ONE coalescer per owner thread, and every method runs on that thread. Sharing one across
 * owner threads races the map and breaks per-owner isolation.
 */
public final class HeartbeatCoalescer {

    /**
     * {@link LinkedHashMap} so the drain replays heartbeats in record order, matching the leader's
     * broadcast order. Note this is not byte-identical to the un-coalesced path on a tick that mixes
     * a buffered heartbeat with an immediately-sent entry-carrying AppendEntries: the heartbeat now
     * drains after it.
     */
    private final Map<NodeId, Map<Integer, AppendEntriesRequest>> pending = new LinkedHashMap<>();

    private boolean collecting;

    public void beginTick() {
        this.collecting = true;
    }

    public boolean isCollecting() {
        return collecting;
    }

    /**
     * Records {@code ae} as the heartbeat for {@code (peer, groupId)}; the latest record per pair wins,
     * so the most recent {@code leaderCommit} is the one sent. Returns {@code true} if it was buffered
     * and the caller must NOT also send it, {@code false} if no tick window is open and the caller must
     * send it immediately rather than let it slip to the next tick.
     *
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
     * Closes the tick window and returns the buffered heartbeats, then clears the buffer. The caller
     * sends one message per returned peer.
     */
    public Map<NodeId, Map<Integer, AppendEntriesRequest>> drainAndEndTick() {
        collecting = false;
        if (pending.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<NodeId, Map<Integer, AppendEntriesRequest>> out = new LinkedHashMap<>(pending.size());
        for (var e : pending.entrySet()) {
            // LinkedHashMap copy so the defensive copy preserves per-group record order too
            out.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
        }
        pending.clear();
        return Collections.unmodifiableMap(out);
    }

    public Set<NodeId> pendingPeers() {
        return Collections.unmodifiableSet(pending.keySet());
    }

    public void reset() {
        pending.clear();
        collecting = false;
    }
}

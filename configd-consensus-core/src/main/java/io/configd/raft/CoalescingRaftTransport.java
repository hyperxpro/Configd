package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;

/**
 * A {@link RaftTransport} decorator that intercepts a group's <em>empty</em> {@link AppendEntriesRequest}s
 * (Raft heartbeats) and buffers them into its owner's {@link HeartbeatCoalescer} instead of sending one
 * per group; the owner drains the coalescer at the end of its tick into one message per peer (M3).
 * Everything else — entry-carrying AppendEntries (real replication), votes, snapshots, responses, and any
 * heartbeat emitted outside the owner's tick window — passes straight through to the delegate, unchanged.
 * <p>
 * {@link RaftNode} is unaware of coalescing: it still calls {@code transport.send(peer, req)} and still
 * increments its in-flight bookkeeping as if sent — the heartbeat <em>is</em> sent, at drain time, within
 * the same tick (~zero added latency). See {@code docs/phase0-B-stage2-m3/design.md} and D-020.
 * <p>
 * One decorator per group (it carries the group id, like {@code RaftTransportAdapter}); the
 * {@link HeartbeatCoalescer} it records into is the owner's, shared by the groups on that owner and
 * touched only on the owner thread. The coalescer is bound once at wiring (after the owner pool exists),
 * via {@link #bindCoalescer}; until bound — and in any legacy wiring that never binds one — this is an
 * exact pass-through, so coalescing is strictly additive.
 */
public final class CoalescingRaftTransport implements RaftTransport {

    private final RaftTransport delegate;
    private final int groupId;

    /**
     * The owner's coalescer, bound once at wiring before the group is ticked. Volatile: published by the
     * wiring thread, read on the owner thread. Null ⇒ pass-through (unbound / legacy).
     */
    private volatile HeartbeatCoalescer coalescer;

    /**
     * @param delegate the underlying transport (production: {@code RaftTransportAdapter}; sim: the
     *                 {@code SimulatedNetwork} lambda) — must not be null
     * @param groupId  the Raft group this transport serves
     */
    public CoalescingRaftTransport(RaftTransport delegate, int groupId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.groupId = groupId;
    }

    /**
     * Binds the owner's coalescer. Called once at wiring, before the group's owner is bound/ticked, so
     * the volatile write happens-before any record on the owner thread. Idempotent rebinding is allowed
     * but not expected.
     *
     * @param coalescer the owner's heartbeat coalescer (must not be null)
     */
    public void bindCoalescer(HeartbeatCoalescer coalescer) {
        this.coalescer = Objects.requireNonNull(coalescer, "coalescer");
    }

    /** The delegate transport this decorator wraps (for wiring that needs the underlying transport). */
    public RaftTransport delegate() {
        return delegate;
    }

    @Override
    public void send(NodeId target, RaftMessage message) {
        // Coalesce ONLY a genuinely-empty AppendEntries (a heartbeat), and ONLY while the owner's tick
        // window is open. recordIfCollecting returns false when there is no window (e.g. this send comes
        // from an inbound/propose task, not the heartbeat tick) — then we fall through and send now, so a
        // non-tick heartbeat is never delayed (H-1). Entry-carrying AppendEntries and all other message
        // types are never coalesced — real replication, votes and snapshots keep their exact timing.
        if (message instanceof AppendEntriesRequest ae && ae.entries().isEmpty()) {
            HeartbeatCoalescer hc = this.coalescer;
            if (hc != null && hc.recordIfCollecting(target, groupId, ae)) {
                return; // buffered — the owner drains it at tick end
            }
        }
        delegate.send(target, message);
    }
}

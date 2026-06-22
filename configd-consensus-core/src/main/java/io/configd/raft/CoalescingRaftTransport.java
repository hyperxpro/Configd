package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.function.Supplier;

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
 * One decorator per group (it carries the group id, like {@code RaftTransportAdapter}). It records into
 * the CURRENT owner's coalescer, resolved on each record via a {@link Supplier} bound at wiring — NOT a
 * fixed reference. Dynamic resolution is what keeps coalescing correct across a group rehoming: after a
 * group moves owners, its decorator records into the NEW owner's coalescer (the one that owner drains),
 * never the old owner's (which would be a cross-thread write on a non-synchronized map — D-020 review A2).
 * Until bound — and in any legacy wiring that never binds one — this is an exact pass-through, so
 * coalescing is strictly additive.
 */
public final class CoalescingRaftTransport implements RaftTransport {

    private final RaftTransport delegate;
    private final int groupId;

    /**
     * Resolves the CURRENT owner's coalescer (rehoming-aware) at record time. Bound once at wiring before
     * the group is ticked. Volatile: published by the wiring thread, read on the owner thread. Null ⇒
     * pass-through (unbound / legacy). The supplier MUST be cheap and side-effect-free; production resolves
     * {@code driver.heartbeatCoalescer(driver.currentOwnerIndex(groupId))}, the sim a constant.
     */
    private volatile Supplier<HeartbeatCoalescer> coalescerResolver;

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
     * Binds the resolver for this group's CURRENT owner coalescer. Called once at wiring, before the
     * group's owner is bound/ticked, so the volatile write happens-before any record on the owner thread.
     * The resolver is consulted on EVERY heartbeat record, so it always lands on the owner that will drain
     * (rehoming-safe).
     *
     * @param coalescerResolver supplies the current owner's coalescer (must not be null; may return null)
     */
    public void bindCoalescer(Supplier<HeartbeatCoalescer> coalescerResolver) {
        this.coalescerResolver = Objects.requireNonNull(coalescerResolver, "coalescerResolver");
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
            Supplier<HeartbeatCoalescer> resolver = this.coalescerResolver;
            if (resolver != null) {
                HeartbeatCoalescer hc = resolver.get(); // the CURRENT owner's coalescer (rehoming-aware)
                if (hc != null && hc.recordIfCollecting(target, groupId, ae)) {
                    return; // buffered — the owner drains it at tick end
                }
            }
        }
        delegate.send(target, message);
    }
}

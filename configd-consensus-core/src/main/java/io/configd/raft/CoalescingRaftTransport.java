package io.configd.raft;

import io.configd.common.NodeId;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Intercepts empty AppendEntries (heartbeats) and buffers into owner's HeartbeatCoalescer;
 * owner drains at tick end. Everything else (entry-carrying AE, votes, snapshots) passes through.
 * Dynamic resolver (not fixed reference) keeps coalescing correct across group rehoming:
 * after rehome, sends to NEW owner's coalescer, never old owner's.
 */
public final class CoalescingRaftTransport implements RaftTransport {

    private final RaftTransport delegate;
    private final int groupId;

    /**
     * Resolves CURRENT owner's coalescer at record time (rehoming-aware).
     * Volatile: wiring-thread writes happen-before owner-thread reads.
     * Null = pass-through (unbound).
     */
    private volatile Supplier<HeartbeatCoalescer> coalescerResolver;

    public CoalescingRaftTransport(RaftTransport delegate, int groupId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.groupId = groupId;
    }

    /**
     * Binds the coalescer resolver (called once at wiring, before group ticked).
     * Volatile write happens-before owner-thread reads.
     */
    public void bindCoalescer(Supplier<HeartbeatCoalescer> coalescerResolver) {
        this.coalescerResolver = Objects.requireNonNull(coalescerResolver, "coalescerResolver");
    }

    public RaftTransport delegate() {
        return delegate;
    }

    @Override
    public void send(NodeId target, RaftMessage message) {
        // Only coalesce empty AE (heartbeat), only during tick window. Non-tick heartbeats
        // send immediately (never delayed). Entry-carrying AE, votes, snapshots always pass through.
        if (message instanceof AppendEntriesRequest ae && ae.entries().isEmpty()) {
            Supplier<HeartbeatCoalescer> resolver = this.coalescerResolver;
            if (resolver != null) {
                HeartbeatCoalescer hc = resolver.get();
                if (hc != null && hc.recordIfCollecting(target, groupId, ae)) {
                    return;
                }
            }
        }
        delegate.send(target, message);
    }
}

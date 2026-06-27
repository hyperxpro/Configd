package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;
import io.configd.replication.MultiRaftDriver;
import io.configd.replication.OwnerExecutorPool;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-Raft Phase 1 (DL-P1-06) — the inbound DEMUX correctness test: a frame stamped {@code gid=k} must
 * reach group {@code k}, NOT the captured constant 0. This is the latent N&gt;1 correctness the
 * {@link ConfigdServer#raftDemuxInboundHandler} fixes; the pre-fix registration collapsed every inbound
 * frame onto group 0, so at N&gt;1-over-TCP all groups would have shared group 0's node.
 *
 * <p>Drives the REAL production demux helper. Each group is an independent single-node cluster that
 * self-elects to LEADER on its own owner thread (peers = {@code Set.of()} ⇒ no election sends); a stale
 * {@code AppendEntries(term=0)} injected at the demux makes the receiving group's leader emit exactly one
 * reject-reply send, captured by that group's recording transport — so the send tells us which group
 * handled the frame.
 */
class RaftInboundDemuxTest {

    private OwnerExecutorPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    void frameStampedGidKReachesGroupKNotZero() throws Exception {
        pool = new OwnerExecutorPool(2); // owner 0 -> gid 0, owner 1 -> gid 1 (floorMod(gid, 2))
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.setOwnerPool(pool);

        RecordingTransport t0 = new RecordingTransport();
        RecordingTransport t1 = new RecordingTransport();
        RaftNode node0 = buildLeaderOnOwner(driver.ownerExecutor(0), t0);
        RaftNode node1 = buildLeaderOnOwner(driver.ownerExecutor(1), t1);
        driver.addGroup(0, node0);
        driver.addGroup(1, node1);
        // Self-election produced no peer sends (single-node clusters).
        assertEquals(0, t0.sends.get(), "group 0 election must not have sent");
        assertEquals(0, t1.sends.get(), "group 1 election must not have sent");

        RaftTransportAdapter.InboundHandler demux = ConfigdServer.raftDemuxInboundHandler(driver, null);

        // Route a frame stamped gid=1 → ONLY group 1 must handle it (reply send on t1, none on t0).
        t1.arm();
        demux.accept(NodeId.of(2), 1, staleAppendEntries());
        assertTrue(t1.awaitSend(), "the gid=1 frame must be handled by group 1 (its leader replies)");
        fence(driver.ownerExecutor(0));
        fence(driver.ownerExecutor(1));
        assertEquals(1, t1.sends.get(), "group 1 handled exactly the one injected frame");
        assertEquals(0, t0.sends.get(),
                "group 0 must NOT have handled the gid=1 frame (pre-fix bug: everything routed to group 0)");

        // Route a frame stamped gid=0 → ONLY group 0 must handle it.
        t0.arm();
        demux.accept(NodeId.of(2), 0, staleAppendEntries());
        assertTrue(t0.awaitSend(), "the gid=0 frame must be handled by group 0");
        fence(driver.ownerExecutor(0));
        fence(driver.ownerExecutor(1));
        assertEquals(1, t0.sends.get(), "group 0 handled exactly the one gid=0 frame");
        assertEquals(1, t1.sends.get(), "group 1 saw no new frame from the gid=0 route");
    }

    @Test
    void frameForUnregisteredGroupIsDroppedSafely() throws Exception {
        pool = new OwnerExecutorPool(1);
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.setOwnerPool(pool);
        RecordingTransport t0 = new RecordingTransport();
        RaftNode node0 = buildLeaderOnOwner(driver.ownerExecutor(0), t0);
        driver.addGroup(0, node0);

        RaftTransportAdapter.InboundHandler demux = ConfigdServer.raftDemuxInboundHandler(driver, null);
        // gid 5 is not registered. routeMessage drops absent groups (no-op). Must not throw, must not send.
        demux.accept(NodeId.of(2), 5, staleAppendEntries());
        fence(driver.ownerExecutor(5)); // floorMod(5,1)=0 → drains the only owner
        assertEquals(0, t0.sends.get(), "a frame for an unregistered group must be dropped (no send, no crash)");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Builds a single-node cluster bound to {@code owner}, ticked there until it self-elects LEADER. */
    private static RaftNode buildLeaderOnOwner(ScheduledExecutorService owner, RaftTransport transport)
            throws Exception {
        RaftConfig config = RaftConfig.of(NodeId.of(1), Set.of()); // single-node cluster
        RaftNode node = new RaftNode(config, new RaftLog(), transport, new NoopStateMachine(),
                new java.util.Random(42));
        owner.submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400; i++) {
                node.tick();
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");
        return node;
    }

    /** A stale-term AppendEntries — any node (term >= 0) rejects it and replies, producing a send. */
    private static AppendEntriesRequest staleAppendEntries() {
        return new AppendEntriesRequest(0L, NodeId.of(2), 0L, 0L, List.of(), 0L);
    }

    /** Drains an owner executor so any marshalled routing task has completed. */
    private static void fence(ScheduledExecutorService owner) throws Exception {
        owner.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static final class RecordingTransport implements RaftTransport {
        final AtomicInteger sends = new AtomicInteger();
        private volatile CountDownLatch latch = new CountDownLatch(0);

        void arm() {
            latch = new CountDownLatch(1);
        }

        boolean awaitSend() throws InterruptedException {
            return latch.await(5, TimeUnit.SECONDS);
        }

        @Override
        public void send(NodeId target, RaftMessage message) {
            sends.incrementAndGet();
            latch.countDown();
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override
        public long apply(long index, long term, byte[] command) {
            return StateMachine.NON_MUTATING;
        }

        @Override
        public byte[] snapshot() {
            return new byte[0];
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
        }
    }
}

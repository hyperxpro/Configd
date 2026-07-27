package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescedHeartbeat;
import io.configd.raft.CoalescedHeartbeatTransport;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.HeartbeatCoalescer;
import io.configd.raft.LogEntry;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.RequestVoteRequest;
import io.configd.raft.StateMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatCoalescingTest {

    private static final NodeId LOCAL = NodeId.of(1);
    private static final NodeId PEER_A = NodeId.of(2);
    private static final NodeId PEER_B = NodeId.of(3);

    private static AppendEntriesRequest emptyHeartbeat(long term) {
        return new AppendEntriesRequest(term, LOCAL, 0L, 0L, List.of(), 0L);
    }

    private static AppendEntriesRequest entryCarrying(long term) {
        return new AppendEntriesRequest(term, LOCAL, 0L, 0L, List.of(LogEntry.noop(1L, term)), 0L);
    }

    private static final class CountingDrain implements CoalescedHeartbeatTransport {
        final Map<NodeId, Integer> calls = new LinkedHashMap<>();
        final Map<NodeId, Integer> lastGroupCount = new LinkedHashMap<>();

        @Override
        public void sendCoalesced(NodeId peer, Map<Integer, AppendEntriesRequest> groupHeartbeats) {
            calls.merge(peer, 1, Integer::sum);
            lastGroupCount.put(peer, groupHeartbeats.size());
        }

        int calls(NodeId peer) {
            return calls.getOrDefault(peer, 0);
        }
    }

    private static final class RecordingTransport implements RaftTransport {
        final AtomicInteger total = new AtomicInteger();
        final Map<NodeId, Integer> perPeer = new ConcurrentHashMap<>();

        @Override
        public void send(NodeId target, RaftMessage message) {
            total.incrementAndGet();
            perPeer.merge(target, 1, Integer::sum);
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static void drainLike(HeartbeatCoalescer hc, CoalescedHeartbeatTransport drain) {
        for (Map.Entry<NodeId, Map<Integer, AppendEntriesRequest>> e : hc.drainAndEndTick().entrySet()) {
            drain.sendCoalesced(e.getKey(), e.getValue());
        }
    }

    @Test
    void heartbeatCount_isFlatInGroupCount_throughDecorator() {
        for (int g = 1; g <= 256; g *= 16) { // G = 1, 16, 256
            HeartbeatCoalescer hc = new HeartbeatCoalescer();
            RecordingTransport delegate = new RecordingTransport();

            // G groups, each with its own decorator sharing this owner's coalescer, all heartbeating the
            // same two peers in one tick (what G co-tenant leaders on one owner do).
            List<CoalescingRaftTransport> groups = new ArrayList<>();
            for (int gid = 0; gid < g; gid++) {
                CoalescingRaftTransport dec = new CoalescingRaftTransport(delegate, gid);
                dec.bindCoalescer(() -> hc);
                groups.add(dec);
            }

            hc.beginTick();
            for (int gid = 0; gid < g; gid++) {
                groups.get(gid).send(PEER_A, emptyHeartbeat(1));
                groups.get(gid).send(PEER_B, emptyHeartbeat(1));
            }
            // Empties were buffered - nothing hit the delegate directly.
            assertEquals(0, delegate.total.get(), "in-window heartbeats must be buffered, not sent");

            CountingDrain drain = new CountingDrain();
            drainLike(hc, drain);

            // THE PROPERTY: exactly ONE coalesced message per peer per tick, independent of G.
            assertEquals(1, drain.calls(PEER_A), "one message to PEER_A regardless of G=" + g);
            assertEquals(1, drain.calls(PEER_B), "one message to PEER_B regardless of G=" + g);
            // And that one message carries every group's heartbeat.
            assertEquals(g, drain.lastGroupCount.get(PEER_A), "the coalesced message carries all G groups");
            assertEquals(g, drain.lastGroupCount.get(PEER_B));
        }
    }

    @Test
    void baseline_unCoalesced_scalesWithGroupCount() {
        // Test-the-tester: with coalescing OFF (no tick window open), G groups each send to two peers, so
        // traffic is 2*G messages per tick - the amplification this removes. Proves the reduction is real.
        for (int g = 1; g <= 256; g *= 16) {
            HeartbeatCoalescer hc = new HeartbeatCoalescer(); // never beginTick - not collecting
            RecordingTransport delegate = new RecordingTransport();
            for (int gid = 0; gid < g; gid++) {
                CoalescingRaftTransport dec = new CoalescingRaftTransport(delegate, gid);
                dec.bindCoalescer(() -> hc);
                dec.send(PEER_A, emptyHeartbeat(1)); // not collecting - straight through
                dec.send(PEER_B, emptyHeartbeat(1));
            }
            assertEquals(2 * g, delegate.total.get(),
                    "un-coalesced heartbeat traffic scales with group count G=" + g);
        }
    }

    @Test
    void decorator_coalescesOnlyEmptyAppendEntriesInsideTheWindow() {
        HeartbeatCoalescer hc = new HeartbeatCoalescer();
        RecordingTransport delegate = new RecordingTransport();
        CoalescingRaftTransport dec = new CoalescingRaftTransport(delegate, 0);
        dec.bindCoalescer(() -> hc);

        dec.send(PEER_A, emptyHeartbeat(1));
        assertEquals(1, delegate.total.get());

        hc.beginTick();
        dec.send(PEER_A, entryCarrying(1));
        assertEquals(2, delegate.total.get(), "entry-carrying AppendEntries must not be coalesced");
        dec.send(PEER_A, new RequestVoteRequest(1L, LOCAL, 0L, 0L, false));
        assertEquals(3, delegate.total.get(), "votes must not be coalesced");
        dec.send(PEER_A, emptyHeartbeat(1));
        assertEquals(3, delegate.total.get(), "in-window empty heartbeat is buffered");
        assertTrue(hc.pendingPeers().contains(PEER_A));
        CoalescingRaftTransport unbound = new CoalescingRaftTransport(delegate, 0);
        unbound.send(PEER_A, emptyHeartbeat(1));
        assertEquals(4, delegate.total.get());
    }

    @Test
    @Timeout(30)
    void routeCoalescedHeartbeat_demuxesToEveryGroup_andNeuterDropsOne() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        RaftNode g0 = newSingleNodeLeader(pool, 0);
        RaftNode g1 = newSingleNodeLeader(pool, 1);
        driver.addGroup(0, g0);
        driver.addGroup(1, g1);
        assertEquals(RaftRole.LEADER, g0.role());
        assertEquals(RaftRole.LEADER, g1.role());

        long higherTerm = Math.max(g0.currentTerm(), g1.currentTerm()) + 5;

        // (a) A coalesced heartbeat carrying a HIGHER-TERM empty AppendEntries for BOTH groups: the demux
        //     must deliver each to its group, and a higher-term AppendEntries makes a leader step down -
        //     an observable proof that each group received and processed ITS message.
        Map<Integer, AppendEntriesRequest> both = new LinkedHashMap<>();
        both.put(0, emptyHeartbeat(higherTerm));
        both.put(1, emptyHeartbeat(higherTerm));
        pool.ownerByIndex(0).submit(
                () -> driver.routeCoalescedHeartbeat(PEER_A, new CoalescedHeartbeat(PEER_A, both))).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.FOLLOWER, g0.role(), "group 0 must receive its demuxed heartbeat");
        assertEquals(RaftRole.FOLLOWER, g1.role(), "group 1 must receive its demuxed heartbeat");

        // (b) NEUTER (test-the-tester): re-elect, then send a coalesced heartbeat that OMITS group 1 - only
        //     group 0 steps down; group 1, not in the message, is untouched. Proves delivery is per-group,
        //     not broadcast.
        RaftNode h0 = newSingleNodeLeader(pool, 2);
        RaftNode h1 = newSingleNodeLeader(pool, 3);
        driver.addGroup(2, h0);
        driver.addGroup(3, h1);
        long ht = Math.max(h0.currentTerm(), h1.currentTerm()) + 5;
        Map<Integer, AppendEntriesRequest> onlyOne = new LinkedHashMap<>();
        onlyOne.put(2, emptyHeartbeat(ht));
        pool.ownerByIndex(0).submit(
                () -> driver.routeCoalescedHeartbeat(PEER_A, new CoalescedHeartbeat(PEER_A, onlyOne))).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.FOLLOWER, h0.role(), "group 2 (present) steps down");
        assertEquals(RaftRole.LEADER, h1.role(), "group 3 (omitted) must NOT be delivered to");

        pool.shutdown();
    }

    @Test
    @Timeout(30)
    void tickOwner_withCoalescingEnabled_isInertForPeerlessLeaderAndNeverFires() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(1);
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        CountingDrain drain = new CountingDrain();
        driver.enableHeartbeatCoalescing(drain);

        RaftNode g0 = newSingleNodeLeader(pool, 0);
        driver.addGroup(0, g0);
        // Many ticks on the owner: a single-node (peerless) leader has no peers to heartbeat, so the
        // coalescer stays empty and the drain is never called - coalescing is correctly inert, and the
        // begin/try/finally orchestration runs clean (no exception, no net fire, leader stays leader).
        pool.ownerByIndex(0).submit(() -> {
            for (int i = 0; i < 200; i++) {
                driver.tickOwner(0);
            }
        }).get(10, TimeUnit.SECONDS);

        assertEquals(RaftRole.LEADER, g0.role(), "peerless leader stays leader across coalescing ticks");
        assertTrue(drain.calls.isEmpty(), "no peers ⇒ no coalesced heartbeats drained");
        assertFalse(driver.heartbeatCoalescer(0).isCollecting(), "the tick window is closed after drain");

        pool.shutdown();
    }

    /** A single-node group that self-elects to LEADER, bound to its owner (the proven idiom). */
    private static RaftNode newSingleNodeLeader(OwnerExecutorPool pool, int gid) throws Exception {
        Storage storage = Storage.inMemory();
        RaftConfig config = RaftConfig.of(LOCAL, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(storage), new RecordingTransport(),
                new NoopStateMachine(), new java.util.Random(101L + gid), storage, null);
        pool.ownerExecutor(gid).submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400 && node.role() != RaftRole.LEADER; i++) {
                node.tick();
            }
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " self-elects");
        return node;
    }
}

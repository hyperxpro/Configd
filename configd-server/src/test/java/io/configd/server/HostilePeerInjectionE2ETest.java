package io.configd.server;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.netty.NettyRaftTransport;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.HeartbeatCoalescer;
import io.configd.raft.ProposalResult;
import io.configd.raft.ProposeOutcome;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.ReadResult;
import io.configd.store.VersionedConfigStore;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import io.configd.transport.RaftTransportMetrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate-4 scenario 5 (THE key new proof) — <b>a hostile peer injected into a LIVE consensus cluster over
 * the real Netty wire.</b> The Gate-1/2/3 work hardened every codec + reject path in isolation (unit +
 * fuzz); this proves the hardening HOLDS end-to-end: an external attacker that speaks the consensus wire
 * and blasts malformed / oversized / undecodable / dormant-type frames at a running follower is
 * REJECTED at the codec/transport boundary, does NOT crash or wedge the node, does NOT destabilize
 * consensus, and the cluster stays consistent — an honest write still commits + replicates on the very
 * node that was attacked.
 *
 * <p>Wiring mirrors {@code NettyConsensusLivenessTest.RealWireCluster}: three real {@link RaftNode}s, each
 * on its own owner thread behind a plaintext {@link NettyRaftTransport} + {@link RaftTransportAdapter}
 * (built with a counting {@link RaftTransportMetrics} sink so the WH-10 decode-drop is observable) +
 * {@link CoalescingRaftTransport}. After a stable leader is elected, a raw TCP socket connects to a
 * FOLLOWER's real listen port (the attacker never completed any consensus handshake — it just speaks the
 * {@code [4B senderId][FrameCodec frame]} wire) and injects the hostile-frame battery.
 *
 * <h2>The injected battery (each maps to a Gate-1 finding / reject path)</h2>
 * <ul>
 *   <li><b>Oversized length prefix</b> (declared &gt; 16 MiB) — {@code RaftFrameDecoder}'s
 *       length-before-allocation gate rejects it with {@code CorruptedFrameException}; no giant alloc.</li>
 *   <li><b>Corrupt CRC32C</b> — {@code FrameCodec.decode} fails the CRC before trusting version/type; the
 *       desynced connection is dropped.</li>
 *   <li><b>Dormant/undecodable type</b> (WH-10: a {@code HYPARVIEW_*} code with no consensus codec) — frames
 *       and CRC-verifies cleanly, then {@code RaftMessageCodec.decode} rejects it → a counted, rate-limited
 *       drop ({@code onInboundFrameDropped}), connection kept.</li>
 *   <li><b>Structurally-malformed {@code APPEND_ENTRIES}</b> (numEntries = {@code Integer.MAX_VALUE}) — the
 *       codec's bound-before-allocation gate rejects it → counted drop, no OOM.</li>
 *   <li><b>Truncated/partial frame</b> then abrupt close — the decoder never emits a half-frame; the socket
 *       drop is absorbed.</li>
 * </ul>
 *
 * <p>Note on scope: identity forgery (WH-08/09, forged {@code senderId} rejected by an enforced
 * {@link io.configd.transport.PeerIdentityPolicy}) is an mTLS property proven over real mTLS sockets by
 * {@code RaftPeerIdentityBindingTest} / {@code NettyRaftPeerIdentityBindingTest}; this plaintext live-cluster
 * test deliberately covers the codec/transport reject paths that do not need mTLS. The poison-pill committed
 * command (WH-01) is a state-machine apply property (deterministic non-mutating skip) covered by
 * {@code CommandCodecFuzzTest} + {@code ConfigStateMachine.apply}; it cannot be injected by a non-leader peer
 * without being rejected first at the Raft term/log layer, so it is not a live-injection vector.
 */
@Timeout(180) // hang detection on the throttled 2-vCPU box; every phase bounds itself with explicit deadlines
final class HostilePeerInjectionE2ETest {

    private static final int NODES = 3;
    private static final int GROUP = 0;
    private static final long BASE_SEED = 0xB01DL;
    private static final int HOSTILE_SENDER = 99; // not a cluster member — an external attacker's claimed id

    private static final int TICK_PERIOD_MS = 10;
    private static final int HEARTBEAT_MS = 50;
    private static final int ELECTION_MIN_MS = 1000;
    private static final int ELECTION_MAX_MS = 2000;

    private static final int POLL_MS = 25;
    private static final int STABLE_OBSERVATIONS = 40;
    private static final long STABILIZE_BUDGET_MS = 30_000;
    private static final long REPLICATE_BUDGET_MS = 20_000;
    private static final long STABILITY_WINDOW_MS = 3_000; // watch for spurious churn AFTER the barrage

    private Cluster cluster;
    private String savedWorkerThreads;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
        if (savedWorkerThreads == null) {
            System.clearProperty("configd.raft.netty.workerThreads");
        } else {
            System.setProperty("configd.raft.netty.workerThreads", savedWorkerThreads);
        }
    }

    @Test
    void hostilePeerBlastingMalformedFramesAtAFollowerCannotCrashOrDestabilizeTheCluster() throws Exception {
        savedWorkerThreads = System.getProperty("configd.raft.netty.workerThreads");
        System.setProperty("configd.raft.netty.workerThreads", "1");
        cluster = new Cluster();

        int leader = cluster.electStableLeader(STABILIZE_BUDGET_MS);
        assertTrue(leader >= 0, "a stable leader must be elected on the real Netty wire before the attack");
        long term0 = cluster.maxTerm();

        // A committed baseline the attacked follower already holds.
        cluster.commitAndAwaitReplication(leader, "before", "baseline");

        int follower = cluster.firstFollower(leader);
        assertTrue(follower >= 0, "the cluster must have a follower to attack");
        int droppedBefore = cluster.inboundDropped[follower].get();

        // --- INJECT the hostile-frame battery at the follower's real consensus listen port ---
        cluster.injectHostileBattery(follower);

        // --- 1) the follower COUNTED the decode-boundary rejects (WH-10 + malformed AppendEntries) ---
        assertTrue(cluster.awaitUntilMs(REPLICATE_BUDGET_MS,
                        () -> cluster.inboundDropped[follower].get() >= droppedBefore + 2),
                "the follower must have counted the undecodable + malformed frames as inbound drops (was "
                        + droppedBefore + ", now " + cluster.inboundDropped[follower].get() + ")");

        // --- 2) NO destabilization: the same leader holds the same term across a stability window ---
        long stabilityEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STABILITY_WINDOW_MS);
        long observations = 0;
        while (System.nanoTime() < stabilityEnd) {
            assertEquals(RaftRole.LEADER, cluster.role(leader),
                    "the hostile barrage must NOT unseat the leader (spurious election)");
            assertEquals(term0, cluster.maxTerm(),
                    "the hostile barrage must NOT advance the cluster term (no spurious election)");
            observations++;
            Thread.sleep(POLL_MS);
        }
        assertTrue(observations >= 20, "vacuity: the stability window must actually have polled");

        // --- 3) STILL CONSISTENT: an honest write commits + replicates on ALL nodes, incl. the attacked
        //         follower (its inbound pipeline + apply loop survived the barrage intact) ---
        long committed = cluster.commitAndAwaitReplication(leader, "after", "post-attack");
        assertTrue(committed > 0, "an honest write must still commit after the hostile barrage");
        assertValue(cluster.stores[follower], "before", "baseline",
                "the attacked follower must retain its pre-attack committed key");
        assertValue(cluster.stores[follower], "after", "post-attack",
                "the attacked follower must apply the post-attack write (pipeline survived)");

        System.out.println("[G4-HOSTILE] follower " + follower + " survived a 5-vector hostile-frame barrage: "
                + (cluster.inboundDropped[follower].get() - droppedBefore) + " decode-drops counted, leader "
                + leader + " held term " + term0 + " across " + observations
                + " obs, honest write replicated to the attacked node — no crash, no churn, still consistent");
    }

    private static void assertValue(VersionedConfigStore store, String key, String expected, String ctx) {
        ReadResult r = store.get(key);
        assertTrue(r.found(), ctx + ": key '" + key + "' must be present");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), r.value(),
                ctx + ": key '" + key + "' must hold its committed value");
    }

    // =======================================================================
    // hostile-frame construction (the [4B senderId][FrameCodec frame] consensus wire)
    // =======================================================================

    /** senderId prefix + a well-formed FrameCodec frame (valid CRC), exactly as a real peer would frame it. */
    private static byte[] raftWire(int senderId, MessageType type, int group, long term, byte[] payload) {
        byte[] encoded = FrameCodec.encode(type, group, term, payload);
        return prefix(senderId, encoded);
    }

    private static byte[] prefix(int senderId, byte[] frame) {
        byte[] wire = new byte[4 + frame.length];
        wire[0] = (byte) (senderId >>> 24);
        wire[1] = (byte) (senderId >>> 16);
        wire[2] = (byte) (senderId >>> 8);
        wire[3] = (byte) senderId;
        System.arraycopy(frame, 0, wire, 4, frame.length);
        return wire;
    }

    /** A structurally-malformed APPEND_ENTRIES: frames + CRCs cleanly, but numEntries = Integer.MAX_VALUE. */
    private static byte[] malformedAppendEntries() {
        byte[] payload = new byte[32]; // leaderId(4)+prevLogIndex(8)+prevLogTerm(8)+leaderCommit(8)+numEntries(4)
        // leave the header zero; set numEntries (last 4 bytes) to a value far past MAX_ENTRIES_PER_APPEND.
        int numEntries = Integer.MAX_VALUE;
        payload[28] = (byte) (numEntries >>> 24);
        payload[29] = (byte) (numEntries >>> 16);
        payload[30] = (byte) (numEntries >>> 8);
        payload[31] = (byte) numEntries;
        return raftWire(HOSTILE_SENDER, MessageType.APPEND_ENTRIES, GROUP, 0L, payload);
    }

    // =======================================================================
    // real-wire cluster (mirrors NettyConsensusLivenessTest.RealWireCluster; metrics sink + ports + inject added)
    // =======================================================================

    private final class Cluster {
        private final NodeId[] ids = new NodeId[NODES];
        private final RaftNode[] nodes = new RaftNode[NODES];
        private final VersionedConfigStore[] stores = new VersionedConfigStore[NODES];
        private final NettyRaftTransport[] transports = new NettyRaftTransport[NODES];
        private final RaftTransportAdapter[] adapters = new RaftTransportAdapter[NODES];
        private final HeartbeatCoalescer[] coalescers = new HeartbeatCoalescer[NODES];
        private final ScheduledExecutorService[] owners = new ScheduledExecutorService[NODES];
        private final ScheduledFuture<?>[] tickFutures = new ScheduledFuture<?>[NODES];
        private final AtomicInteger[] inboundDropped = new AtomicInteger[NODES];
        private int[] ports;
        private volatile boolean closed;

        Cluster() throws Exception {
            try {
                build();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private void build() throws Exception {
            ports = reserveDistinctPorts(NODES);
            for (int i = 0; i < NODES; i++) {
                ids[i] = NodeId.of(i);
                inboundDropped[i] = new AtomicInteger();
            }
            for (int i = 0; i < NODES; i++) {
                Map<NodeId, InetSocketAddress> peerAddrs = new HashMap<>();
                Set<NodeId> peerIds = new HashSet<>();
                for (int j = 0; j < NODES; j++) {
                    if (j != i) {
                        peerAddrs.put(ids[j], new InetSocketAddress("127.0.0.1", ports[j]));
                        peerIds.add(ids[j]);
                    }
                }
                transports[i] = new NettyRaftTransport(ids[i],
                        new InetSocketAddress("127.0.0.1", ports[i]), peerAddrs, null, null);
                final int idx = i;
                // Count the WH-10 decode-boundary drops so the reject is observable, not just behavioural.
                RaftTransportMetrics sink = new RaftTransportMetrics() {
                    @Override public void onInboundFrameDropped() {
                        inboundDropped[idx].incrementAndGet();
                    }
                };
                adapters[i] = new RaftTransportAdapter(transports[i], GROUP, /*enforceIdentity=*/false, sink);
                coalescers[i] = new HeartbeatCoalescer();
                CoalescingRaftTransport coalescing = new CoalescingRaftTransport(adapters[i], GROUP);
                coalescing.bindCoalescer(() -> coalescers[idx]);

                RaftConfig config = new RaftConfig(ids[i], peerIds,
                        ELECTION_MIN_MS, ELECTION_MAX_MS, HEARTBEAT_MS,
                        64, 256 * 1024, 1024, 10, TICK_PERIOD_MS);
                stores[i] = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(stores[i]);
                nodes[i] = new RaftNode(config, new RaftLog(), coalescing, sm,
                        new Random(BASE_SEED + i), Storage.inMemory(), RaftNode.InvariantChecker.NOOP);
                owners[i] = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "hostile-owner-" + idx);
                    t.setDaemon(true);
                    return t;
                });
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                owners[i].execute(() -> nodes[idx].bindOwnerThread());
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                adapters[i].registerInboundHandler((from, gid, msg) ->
                        owners[idx].execute(() -> nodes[idx].handleMessage(msg)));
            }
            for (int i = 0; i < NODES; i++) {
                transports[i].start();
            }
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                tickFutures[i] = owners[i].scheduleWithFixedDelay(
                        () -> tickOnce(idx), TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }

        /**
         * Opens a raw TCP socket to {@code follower}'s real consensus listen port and writes the hostile
         * battery. Best-effort writes: a rejecting server RST is a valid outcome (the survival + consistency
         * assertions in the test body are the authoritative checks), so IOExceptions are swallowed here.
         */
        void injectHostileBattery(int follower) {
            // 1) oversized length prefix: senderId + a frame-length field far past MAX_FRAME_SIZE.
            byte[] oversized = new byte[]{
                    0, 0, 0, (byte) HOSTILE_SENDER,          // senderId
                    (byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // frame length = 0x7FFFFFFF
                    0, 0, 0, 0                                // a little filler (never read — rejected first)
            };
            sendRaw(follower, oversized);

            // 2) corrupt CRC: a valid HEARTBEAT frame with one interior byte flipped (length stays valid).
            byte[] corrupt = raftWire(HOSTILE_SENDER, MessageType.APPEND_ENTRIES, GROUP, 7L, new byte[0]);
            corrupt[corrupt.length - 5] ^= (byte) 0xFF; // flip a byte before the CRC trailer => CRC mismatch
            sendRaw(follower, corrupt);

            // 3) dormant/undecodable type (WH-10): a HYPARVIEW code with no consensus codec, valid CRC.
            sendRaw(follower, raftWire(HOSTILE_SENDER, MessageType.HYPARVIEW_JOIN, GROUP, 0L, new byte[8]));

            // 4) structurally-malformed APPEND_ENTRIES (numEntries = Integer.MAX_VALUE), valid CRC.
            sendRaw(follower, malformedAppendEntries());

            // 5) truncated/partial frame then abrupt close: a sender id + a partial length prefix only.
            sendPartialThenClose(follower);
        }

        private void sendRaw(int follower, byte[] wire) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", ports[follower]), 2_000);
                s.setSoTimeout(1_000);
                OutputStream out = s.getOutputStream();
                out.write(wire);
                out.flush();
                // brief read to let the server process/close; ignore whatever (if anything) comes back.
                try {
                    s.getInputStream().read(new byte[64]);
                } catch (java.io.IOException ignored) {
                    // server closed the desynced connection — a valid rejection
                }
            } catch (java.io.IOException dropped) {
                // connect/write raced the server reset — the survival assertions are authoritative
            }
        }

        private void sendPartialThenClose(int follower) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", ports[follower]), 2_000);
                OutputStream out = s.getOutputStream();
                out.write(new byte[]{0, 0, 0, (byte) HOSTILE_SENDER, 0, 0}); // senderId + 2 of 4 length bytes
                out.flush();
                // abrupt close mid-frame; the decoder must never emit a half-frame.
            } catch (java.io.IOException ignored) {
                // benign
            }
        }

        private void tickOnce(int i) {
            HeartbeatCoalescer hc = coalescers[i];
            hc.beginTick();
            try {
                nodes[i].tick();
            } finally {
                Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = hc.drainAndEndTick();
                if (!drained.isEmpty()) {
                    for (Map.Entry<NodeId, Map<Integer, AppendEntriesRequest>> pe : drained.entrySet()) {
                        for (AppendEntriesRequest hb : pe.getValue().values()) {
                            adapters[i].send(pe.getKey(), hb);
                        }
                    }
                }
            }
        }

        long maxTerm() {
            long max = 0;
            for (RaftNode n : nodes) {
                max = Math.max(max, n.monitorView().currentTerm());
            }
            return max;
        }

        RaftRole role(int i) {
            return nodes[i].monitorView().role();
        }

        long lastApplied(int i) {
            return nodes[i].monitorView().lastApplied();
        }

        private int singleLeader() {
            int leader = -1;
            for (int i = 0; i < NODES; i++) {
                if (role(i) == RaftRole.LEADER) {
                    if (leader >= 0) {
                        return -1;
                    }
                    leader = i;
                }
            }
            return leader;
        }

        int firstFollower(int leader) {
            for (int i = 0; i < NODES; i++) {
                if (i != leader) {
                    return i;
                }
            }
            return -1;
        }

        int electStableLeader(long budgetMs) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
            int candidate = -1;
            int stable = 0;
            while (System.nanoTime() < end) {
                int leader = singleLeader();
                if (leader >= 0 && leader == candidate) {
                    if (++stable >= STABLE_OBSERVATIONS) {
                        return leader;
                    }
                } else {
                    candidate = leader;
                    stable = (leader >= 0) ? 1 : 0;
                }
                Thread.sleep(POLL_MS);
            }
            return -1;
        }

        long commitAndAwaitReplication(int leader, String key, String value) throws Exception {
            byte[] cmd = CommandCodec.encodePut(key, value.getBytes(StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REPLICATE_BUDGET_MS);
            long committed = -1;
            while (System.nanoTime() < deadline && committed < 0) {
                if (role(leader) != RaftRole.LEADER) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                long before = lastApplied(leader);
                ProposeOutcome outcome;
                try {
                    outcome = owners[leader].submit(() -> nodes[leader].propose(cmd)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                if (outcome.result() != ProposalResult.ACCEPTED) {
                    Thread.sleep(POLL_MS);
                    continue;
                }
                while (System.nanoTime() < deadline) {
                    long now = lastApplied(leader);
                    if (now > before) {
                        committed = now;
                        break;
                    }
                    Thread.sleep(POLL_MS);
                }
            }
            assertTrue(committed > 0, "leader " + leader + " did not commit '" + key + "' in time");
            final long target = committed;
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                assertTrue(awaitUntilNanos(deadline, () -> lastApplied(idx) >= target),
                        "node " + i + " did not replicate+apply '" + key + "' up to index " + target
                                + " (got " + lastApplied(i) + ")");
            }
            return committed;
        }

        boolean awaitUntilMs(long budgetMs, java.util.function.BooleanSupplier cond) throws InterruptedException {
            return awaitUntilNanos(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs), cond);
        }

        private boolean awaitUntilNanos(long deadlineNanos, java.util.function.BooleanSupplier cond)
                throws InterruptedException {
            while (System.nanoTime() < deadlineNanos) {
                if (cond.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(POLL_MS);
            }
            return cond.getAsBoolean();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (ScheduledFuture<?> f : tickFutures) {
                if (f != null) {
                    f.cancel(false);
                }
            }
            for (NettyRaftTransport t : transports) {
                if (t != null) {
                    t.close();
                }
            }
            for (ScheduledExecutorService o : owners) {
                if (o != null) {
                    o.shutdownNow();
                }
            }
            for (ScheduledExecutorService o : owners) {
                if (o != null) {
                    try {
                        o.awaitTermination(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static int[] reserveDistinctPorts(int n) throws Exception {
        ServerSocket[] socks = new ServerSocket[n];
        int[] ports = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                socks[i] = new ServerSocket(0);
                ports[i] = socks[i].getLocalPort();
            }
        } finally {
            for (ServerSocket s : socks) {
                if (s != null) {
                    s.close();
                }
            }
        }
        return ports;
    }
}

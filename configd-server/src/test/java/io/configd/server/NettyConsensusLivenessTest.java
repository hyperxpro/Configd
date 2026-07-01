package io.configd.server;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.netty.NettyRaftTransport;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.CoalescingRaftTransport;
import io.configd.raft.HeartbeatCoalescer;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigStateMachine;
import io.configd.store.VersionedConfigStore;
import io.configd.transport.FrameCodec;
import io.configd.transport.InboundMessage;
import io.configd.transport.MessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE load-bearing proof that the Netty consensus transport ({@link NettyRaftTransport})
 * preserves coalesced-heartbeat delivery <em>timing</em> faithfully enough that NO spurious leader
 * election occurs. This is the real-wire analog of the deterministic simulation proof
 * {@code io.configd.testkit.CoalescedHeartbeatLivenessTest#noSpuriousElectionUnderSustainedLoad}: where
 * that test routes the coalesce -> drain pipeline through an in-memory {@code SimulatedNetwork}, this one
 * routes it over real localhost TCP sockets, with each node on its own owner thread (genuinely
 * concurrent - not the single-threaded sim) and real wall-clock election timers.
 *
 * <h2>Why this is the #1 migration risk</h2>
 * The coalesced heartbeat is the ONLY liveness signal an idle follower receives. A
 * transport that drops, delays, reorders, or mistimes one heartbeat lets a follower's election timer
 * fire -> a spurious term bump -> leadership churn. So the proof
 * is: with the real Netty wire carrying coalesced heartbeats, a stable leader holds its term across a
 * sustained load window AND a sustained idle window (idle is where the coalesced heartbeat is the sole
 * liveness signal, so it bites hardest).
 *
 * <h2>Wiring (mirrors {@code ConsistencyPropertyTests.ClusterHarness} on the real wire)</h2>
 * Per node: {@link NettyRaftTransport} (plaintext - TLS delivery is separately proven by
 * {@code AbstractRaftTransportContract}; this proof isolates delivery TIMING) -> wrapped by a
 * {@link RaftTransportAdapter} (consensus-core {@code RaftTransport} seam, encode/decode) -> wrapped by a
 * {@link CoalescingRaftTransport} bound to a per-node {@link HeartbeatCoalescer} -> driving a
 * {@link RaftNode}. Each tick: {@code beginTick() -> node.tick() -> drain the coalescer and send each
 * drained heartbeat over the real adapter}. So COALESCING IS ACTIVE on the wire under test (the M3 path).
 *
 * <h2>Owner threading</h2>
 * Each node has its own single-thread {@link ScheduledExecutorService} (its owner). {@code bindOwnerThread()}
 * is the owner's FIRST task (before {@code transport.start()} publishes the inbound handler and before
 * ticks are scheduled). The inbound handler (arriving on a Netty event-loop thread) marshals
 * {@code handleMessage(...)} onto the owner; proposes and ticks run on the owner too. The ONLY off-owner
 * reads are via {@link RaftNode#monitorView()} - the volatile, never-torn {@code (role, currentTerm)}
 * snapshot published every tick ({@code currentTerm()} itself is owner-guarded and is never called here).
 *
 * <h2>Election budget (>=20x structural margin)</h2>
 * {@code tickPeriod=10ms}, {@code heartbeat=50ms (5 ticks)}, {@code election=1000-2000ms (100-200 ticks)}.
 * The derived election:heartbeat tick ratio is {@code 100/5 = 20} - a follower tolerates ~20-40 missed
 * heartbeats before timing out, so 2-vCPU scheduling jitter cannot trip a spurious election while the
 * transport is delivering. Loopback delivery itself is sub-millisecond to single-digit-ms (measured by
 * {@link #heartbeatFramesArriveInOrderWithinDeliveryBudget}), a tiny fraction of the 1000ms election floor.
 *
 * <p>Ticks are driven with {@link ScheduledExecutorService#scheduleWithFixedDelay} (not the production
 * {@code scheduleAtFixedRate}) deliberately: on a throttled box {@code scheduleAtFixedRate} fires
 * catch-up bursts of overdue ticks after a stall, and a {@code ScheduledThreadPoolExecutor} runs those
 * past-due ticks <em>ahead</em> of freshly-submitted inbound tasks - a HARNESS artifact (the election
 * counter advancing before queued heartbeats are processed) that has nothing to do with transport
 * fidelity. {@code scheduleWithFixedDelay} stretches the period under load instead of bursting, isolating
 * the variable under test (transport delivery). It does NOT mask a real defect: the non-vacuity leg below
 * proves a starved follower still elects under exactly this scheduler.
 */
@Timeout(240) // hang detection on the throttled 2-vCPU box; every test bounds itself with explicit deadlines
final class NettyConsensusLivenessTest {

    private static final int NODES = 3;            // a 3-node quorum is sufficient and leanest on 2 vCPUs
    private static final int GROUP = 0;
    private static final long BASE_SEED = 0xC0FFEEL;

    // ---- election budget (see class doc; ratio = electionMinTicks/heartbeatTicks = 100/5 = 20) ----
    private static final int TICK_PERIOD_MS = 10;
    private static final int HEARTBEAT_MS = 50;     // 5 ticks
    private static final int ELECTION_MIN_MS = 1000; // 100 ticks
    private static final int ELECTION_MAX_MS = 2000; // 200 ticks
    private static final int PROPOSE_PERIOD_MS = TICK_PERIOD_MS; // continuous load: ~one propose per tick

    // ---- observation cadence / budgets ----
    private static final int POLL_MS = 25;
    private static final int STABLE_OBSERVATIONS = 40;   // 40 x 25ms = 1s of stable single-leadership
    private static final long STABILIZE_BUDGET_MS = 30_000;
    private static final long CHURN_BUDGET_MS = 30_000;  // >> 2s election max; severing fires well inside this
    /** Each assertion phase (load, then idle) runs this long. Override with -Dconfigd.m4.windowMs. */
    private static final long WINDOW_MS = Long.getLong("configd.m4.windowMs", 6_000);

    private RealWireCluster cluster;
    private String savedWorkerThreads;

    /**
     * Pin one Netty worker thread per transport before any transport is constructed (the ctor reads the
     * property). Fewer runnable threads = less scheduler contention on the 2-vCPU box = less tick jitter;
     * loopback at heartbeat rates needs only one event loop. Restored in {@link #tearDown}.
     */
    private void pinWorkerThreads() {
        savedWorkerThreads = System.getProperty("configd.raft.netty.workerThreads");
        System.setProperty("configd.raft.netty.workerThreads", "1");
    }

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

    // =======================================================================
    // THE PROOF - no spurious election under sustained load AND while idle
    // =======================================================================

    @Test
    void noSpuriousElectionUnderSustainedLoadOnNettyWire() throws Exception {
        pinWorkerThreads();
        cluster = new RealWireCluster();

        int leader = cluster.electStableLeader(STABILIZE_BUDGET_MS);
        assertTrue(leader >= 0,
                "a stable leader must be elected on the real Netty wire within " + STABILIZE_BUDGET_MS + "ms");
        long term0 = cluster.maxTerm();

        // Phase A - SUSTAINED LOAD: propose every tick on the leader. Entry-carrying appends AND coalesced
        // heartbeats flow; the transport must deliver them faithfully enough that no follower times out.
        cluster.startLoad(leader);
        long loadObservations = assertNoChurn(leader, term0, WINDOW_MS,
                "under sustained load");
        cluster.stopLoad();

        // Phase B - SUSTAINED IDLE: no writes, so the COALESCED HEARTBEAT is the ONLY signal resetting a
        // follower's election timer. This is where a dropped/delayed/reordered coalesced heartbeat bites
        // hardest - the sharpest test of the M3 path on the real wire.
        long idleObservations = assertNoChurn(leader, term0, WINDOW_MS, "while idle");

        // Coherent final check after both phases.
        assertEquals(term0, cluster.maxTerm(),
                "term rose by the end of the proof (term0=" + term0 + ")");
        assertEquals(RaftRole.LEADER, cluster.role(leader),
                "leader " + leader + " did not hold leadership for the whole proof");
        assertTrue(loadObservations >= 50 && idleObservations >= 50,
                "vacuity: too few observations (load=" + loadObservations + ", idle=" + idleObservations
                        + ") — each phase must actually have polled the cluster many times");

        System.out.println("[M4-PROOF] no spurious election: leader=" + leader + " held term=" + term0
                + " across load(" + loadObservations + " obs)+idle(" + idleObservations + " obs) over the real Netty wire");
    }

    /**
     * Polls the cluster for {@code windowMs}, asserting on every poll that the cluster-wide max term stays
     * at {@code term0} and {@code leader} stays LEADER. Returns the number of polls performed.
     */
    private long assertNoChurn(int leader, long term0, long windowMs, String phase) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        long observations = 0;
        while (System.nanoTime() < end) {
            long mt = cluster.maxTerm();
            assertEquals(term0, mt,
                    "SPURIOUS ELECTION " + phase + " on the Netty wire: cluster max term rose " + term0
                            + " -> " + mt + " — a coalesced heartbeat was dropped, delayed, reordered, or "
                            + "mistimed by the transport.");
            assertEquals(RaftRole.LEADER, cluster.role(leader),
                    "leader " + leader + " stepped down " + phase + " on the Netty wire (term0=" + term0 + ")");
            observations++;
            Thread.sleep(POLL_MS);
        }
        return observations;
    }

    // =======================================================================
    // NON-VACUITY (test-the-tester) - severing heartbeats MUST cause churn
    // =======================================================================

    /**
     * Mirrors the intent of {@code CoalescedHeartbeatLivenessTest}'s DROP/DELAY legs on the real wire: if
     * severing the leader's coalesced heartbeats did NOT destabilize leadership, the no-spurious-election
     * proof above would be vacuous (it would pass even with a broken drain). Elect a stable leader, go
     * idle (so the heartbeat is the only liveness signal), then DROP the leader's drained heartbeats - they
     * are still drained from the coalescer every tick (closing the window) but never put on the wire. With
     * every follower starved, the PreVote shield opens for all of them, a follower wins a fresh election,
     * and the cluster term rises. We assert that genuine term advance - proving the harness DETECTS
     * heartbeat starvation, so the proof is non-vacuous.
     */
    @Test
    void severedHeartbeatsDoCauseElectionChurn() throws Exception {
        pinWorkerThreads();
        cluster = new RealWireCluster();

        int leader = cluster.electStableLeader(STABILIZE_BUDGET_MS);
        assertTrue(leader >= 0,
                "a stable leader must be elected before the non-vacuity check (within "
                        + STABILIZE_BUDGET_MS + "ms)");
        long term0 = cluster.maxTerm();

        // Idle + sever the leader's coalesced heartbeats. No proposes => no entry-carrying appends either,
        // so the leader goes completely silent toward its followers.
        cluster.severHeartbeats(leader);

        // 1) Detect destabilization (term rise OR the leader stepping down - CheckQuorum is invisible to
        //    maxTerm alone), then 2) confirm a NEW term is actually established (a real election completed).
        boolean destabilized = awaitUntil(CHURN_BUDGET_MS,
                () -> cluster.maxTerm() > term0 || cluster.role(leader) != RaftRole.LEADER);
        assertTrue(destabilized,
                "NON-VACUITY FAILURE: severing the leader's coalesced heartbeats did NOT destabilize "
                        + "leadership within " + CHURN_BUDGET_MS + "ms — the no-spurious-election proof would "
                        + "be vacuous. term0=" + term0 + ", maxTerm=" + cluster.maxTerm()
                        + ", leaderRole=" + cluster.role(leader));

        boolean termRose = awaitUntil(CHURN_BUDGET_MS, () -> cluster.maxTerm() > term0);
        long finalTerm = cluster.maxTerm();
        assertTrue(termRose,
                "severing heartbeats must drive a NEW election (the cluster term must rise past term0="
                        + term0 + "); observed finalTerm=" + finalTerm);

        System.out.println("[M4-NONVACUITY] severing the leader's coalesced heartbeats drove a term advance "
                + term0 + " -> " + finalTerm + " (delta=" + (finalTerm - term0)
                + ") — the harness detects heartbeat starvation, so the proof is non-vacuous");
    }

    // =======================================================================
    // COMPLEMENTARY - direct transport delivery fidelity (the empirical margin)
    // =======================================================================

    /**
     * The empirical basis for "loopback delivery << election budget": blast K heartbeat-sized frames over a
     * single established {@link NettyRaftTransport} connection and confirm they ALL arrive, IN ORDER
     * (per-peer FIFO), within a max latency that is a tiny fraction of the 1000ms election floor. The first
     * frame (which pays connect cost) is a warm-up; the measured K go over the live connection, reflecting
     * steady-state heartbeat delivery.
     */
    @Test
    void heartbeatFramesArriveInOrderWithinDeliveryBudget() throws Exception {
        // No worker-thread pinning here: this is not the election-timing test, and the default worker
        // count drains the back-to-back blast faster (less test-thread/event-loop contention) for a
        // cleaner steady-state number. The ceiling below is a generous non-flaky guardrail; the proof's
        // real margin is the OBSERVED value reported at the end.
        final int warmup = 1;
        final int k = 200;
        final int total = warmup + k;
        final long deliveryBudgetMs = 500; // non-flaky ceiling, still << ELECTION_MIN_MS (1000)

        int[] ports = reserveDistinctPorts(2);
        NodeId a = NodeId.of(1);
        NodeId b = NodeId.of(2);

        CountDownLatch warmedUp = new CountDownLatch(1);
        CountDownLatch allArrived = new CountDownLatch(k);
        List<Long> arrivalOrder = new ArrayList<>(); // frame.term() carries the monotonic sequence
        long[] sentAtNanos = new long[total];
        AtomicLong maxLatencyNanos = new AtomicLong();
        AtomicLong outOfOrder = new AtomicLong();
        AtomicLong nextExpected = new AtomicLong(0);

        NettyRaftTransport receiver = new NettyRaftTransport(
                b, new InetSocketAddress("127.0.0.1", ports[1]), Map.of(), null,
                (InboundMessage msg) -> {
                    long arrived = System.nanoTime();
                    long seq = msg.frame().term();
                    synchronized (arrivalOrder) {
                        arrivalOrder.add(seq);
                    }
                    if (seq != nextExpected.getAndIncrement()) {
                        outOfOrder.incrementAndGet();
                    }
                    if (seq < warmup) {
                        warmedUp.countDown();
                        return;
                    }
                    long lat = arrived - sentAtNanos[(int) seq];
                    maxLatencyNanos.accumulateAndGet(lat, Math::max);
                    allArrived.countDown();
                });
        NettyRaftTransport sender = new NettyRaftTransport(
                a, new InetSocketAddress("127.0.0.1", ports[0]),
                Map.of(b, new InetSocketAddress("127.0.0.1", ports[1])), null, msg -> { });
        try {
            receiver.start();
            sender.start();

            // Warm up: establish the connection and confirm the first frame round-trips before measuring.
            sentAtNanos[0] = System.nanoTime();
            sender.send(b, heartbeatFrame(0));
            assertTrue(warmedUp.await(10, TimeUnit.SECONDS),
                    "the warm-up frame must establish the connection within 10s");

            // Blast K heartbeat-sized frames back-to-back over the live connection.
            for (int seq = warmup; seq < total; seq++) {
                sentAtNanos[seq] = System.nanoTime();
                sender.send(b, heartbeatFrame(seq));
            }
            assertTrue(allArrived.await(30, TimeUnit.SECONDS),
                    "all " + k + " heartbeat-sized frames must arrive within 30s; arrived="
                            + (k - allArrived.getCount()));

            assertEquals(0, outOfOrder.get(),
                    "the transport must preserve per-peer FIFO order; out-of-order arrivals="
                            + outOfOrder.get() + " (a reorder can trip an election)");

            double maxLatencyMs = maxLatencyNanos.get() / 1_000_000.0;
            assertTrue(maxLatencyMs < deliveryBudgetMs,
                    "steady-state loopback delivery (" + String.format("%.3f", maxLatencyMs) + "ms) must be"
                            + " ≪ the election floor (" + ELECTION_MIN_MS + "ms); budget=" + deliveryBudgetMs + "ms");

            System.out.println("[M4-FIDELITY] " + k + " heartbeat-sized frames: all in order, max steady-state"
                    + " delivery=" + String.format("%.3f", maxLatencyMs) + "ms vs election floor "
                    + ELECTION_MIN_MS + "ms (margin ~" + (long) (ELECTION_MIN_MS / Math.max(maxLatencyMs, 0.001)) + "x)");
        } finally {
            sender.close();
            receiver.close();
        }
    }

    private static FrameCodec.Frame heartbeatFrame(long seq) {
        // An empty-payload AppendEntries == a Raft heartbeat on the wire; term carries the sequence marker.
        return new FrameCodec.Frame(MessageType.APPEND_ENTRIES, GROUP, seq, new byte[0]);
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private interface Condition {
        boolean met();
    }

    /** Polls {@code cond} every {@link #POLL_MS} until it is met or {@code budgetMs} elapses. */
    private static boolean awaitUntil(long budgetMs, Condition cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < end) {
            if (cond.met()) {
                return true;
            }
            Thread.sleep(POLL_MS);
        }
        return cond.met();
    }

    /**
     * Reserves {@code n} DISTINCT free localhost ports by opening {@code n} {@link ServerSocket}s at once
     * (so the OS hands out distinct ephemeral ports), reading their ports, then closing them all. The
     * close->bind window is tiny on a dedicated box; the {@link NettyRaftTransport} ctor needs the full peer
     * map up front, so per-node ephemeral-then-discover is not an option for a fully-connected cluster.
     */
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

    /**
     * A genuinely-concurrent real-wire Raft cluster: N nodes, each on its own owner thread, each behind its
     * own {@link NettyRaftTransport}, all wired through the {@link CoalescingRaftTransport} -> drain pipeline
     * (coalescing ACTIVE). Built and started in the constructor; {@link #close()} is idempotent and called
     * from {@code @AfterEach}.
     */
    private final class RealWireCluster {
        private final NodeId[] ids = new NodeId[NODES];
        private final RaftNode[] nodes = new RaftNode[NODES];
        private final NettyRaftTransport[] transports = new NettyRaftTransport[NODES];
        private final RaftTransportAdapter[] adapters = new RaftTransportAdapter[NODES]; // base (drain) seam
        private final HeartbeatCoalescer[] coalescers = new HeartbeatCoalescer[NODES];
        private final ScheduledExecutorService[] owners = new ScheduledExecutorService[NODES];
        private final ScheduledFuture<?>[] tickFutures = new ScheduledFuture<?>[NODES];
        /** When set, node i drains its coalescer every tick but does NOT put the heartbeats on the wire. */
        private final boolean[] severHeartbeats = new boolean[NODES];

        private volatile ScheduledFuture<?> loadFuture;
        private volatile boolean closed;

        RealWireCluster() throws Exception {
            try {
                build();
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private void build() throws Exception {
            int[] ports = reserveDistinctPorts(NODES);

            for (int i = 0; i < NODES; i++) {
                ids[i] = NodeId.of(i);
            }

            for (int i = 0; i < NODES; i++) {
                // Peer address map = every OTHER node (a node never sends to itself).
                Map<NodeId, InetSocketAddress> peerAddrs = new HashMap<>();
                Set<NodeId> peerIds = new HashSet<>();
                for (int j = 0; j < NODES; j++) {
                    if (j != i) {
                        peerAddrs.put(ids[j], new InetSocketAddress("127.0.0.1", ports[j]));
                        peerIds.add(ids[j]);
                    }
                }

                // Plaintext transport (tls=null) + null ctor inbound handler (the adapter registers its own).
                transports[i] = new NettyRaftTransport(ids[i],
                        new InetSocketAddress("127.0.0.1", ports[i]), peerAddrs, null, null);
                adapters[i] = new RaftTransportAdapter(transports[i], GROUP);

                coalescers[i] = new HeartbeatCoalescer();
                CoalescingRaftTransport coalescing = new CoalescingRaftTransport(adapters[i], GROUP);
                final int idx = i;
                coalescing.bindCoalescer(() -> coalescers[idx]); // one group per node => a constant resolver

                // Generous election budget: heartbeat 50ms (5 ticks) << election 1000-2000ms (100-200 ticks),
                // so the derived election:heartbeat tick ratio is 20 (well above the validated floor of 3).
                RaftConfig config = new RaftConfig(ids[i], peerIds,
                        ELECTION_MIN_MS, ELECTION_MAX_MS, HEARTBEAT_MS,
                        64, 256 * 1024, 1024, 10, TICK_PERIOD_MS);
                RaftLog log = new RaftLog();
                VersionedConfigStore store = new VersionedConfigStore();
                ConfigStateMachine sm = new ConfigStateMachine(store);
                nodes[i] = new RaftNode(config, log, coalescing, sm,
                        new Random(BASE_SEED + i), Storage.inMemory(), RaftNode.InvariantChecker.NOOP);

                owners[i] = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "m4-owner-" + idx);
                    t.setDaemon(true);
                    return t;
                });
            }

            // H-6: bindOwnerThread() is the owner's FIRST task - before start() (publishes the inbound
            // handler) and before ticks are scheduled. Single-thread FIFO then guarantees every later
            // tick/handleMessage/propose runs after the bind, so assertOwnerThread() never trips.
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                owners[i].execute(() -> nodes[idx].bindOwnerThread());
            }

            // Inbound (arrives on a Netty event-loop thread) - marshal handleMessage onto the node's owner.
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                adapters[i].registerInboundHandler((from, gid, msg) ->
                        owners[idx].execute(() -> nodes[idx].handleMessage(msg)));
            }

            // Start ALL transports before driving any ticks, so a leader's first heartbeats find listeners.
            for (int i = 0; i < NODES; i++) {
                transports[i].start();
            }

            // Drive real scheduled ticks per node on its own owner (fixed-delay - see class doc).
            for (int i = 0; i < NODES; i++) {
                final int idx = i;
                tickFutures[i] = owners[i].scheduleWithFixedDelay(
                        () -> tickOnce(idx), TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }

        /** One tick on node {@code i}'s owner: open the coalescing window, tick, drain + send heartbeats. */
        private void tickOnce(int i) {
            HeartbeatCoalescer hc = coalescers[i];
            hc.beginTick();
            try {
                nodes[i].tick();
            } finally {
                drainHeartbeats(i, hc);
            }
        }

        /**
         * Sends node {@code i}'s coalesced heartbeats over the REAL adapter (the base transport - NOT the
         * coalescing decorator, which would re-buffer them). Mirrors {@code ClusterHarness.drainHeartbeats}.
         * Always closes the window via {@code drainAndEndTick()}; when severed, the heartbeats are drained
         * but never put on the wire (the real-wire analog of the sim's DROP fault).
         */
        private void drainHeartbeats(int i, HeartbeatCoalescer hc) {
            Map<NodeId, Map<Integer, AppendEntriesRequest>> drained = hc.drainAndEndTick();
            if (severHeartbeats[i] || drained.isEmpty()) {
                return;
            }
            for (Map.Entry<NodeId, Map<Integer, AppendEntriesRequest>> peerEntry : drained.entrySet()) {
                NodeId peer = peerEntry.getKey();
                for (AppendEntriesRequest hb : peerEntry.getValue().values()) {
                    adapters[i].send(peer, hb); // encode -> netty.send (non-blocking offer); on the owner thread
                }
            }
        }

        // ---- off-owner observation (monitorView: volatile, never-torn (role, currentTerm) snapshot) ----

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

        /** A single node reporting LEADER, or -1 (none, or a transient two-leader split). */
        private int singleLeaderOrMinusOne() {
            int leader = -1;
            for (int i = 0; i < NODES; i++) {
                if (nodes[i].monitorView().role() == RaftRole.LEADER) {
                    if (leader >= 0) {
                        return -1; // two leaders observed (election transient) - not stable yet
                    }
                    leader = i;
                }
            }
            return leader;
        }

        /** Drive until one node is the sole LEADER for {@link #STABLE_OBSERVATIONS} consecutive polls. */
        int electStableLeader(long budgetMs) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
            int candidate = -1;
            int stable = 0;
            while (System.nanoTime() < end) {
                int leader = singleLeaderOrMinusOne();
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

        /** Continuous write load: propose a PUT on the leader's owner every {@link #PROPOSE_PERIOD_MS}. */
        void startLoad(int leader) {
            AtomicInteger n = new AtomicInteger();
            loadFuture = owners[leader].scheduleWithFixedDelay(() -> {
                try {
                    byte[] cmd = CommandCodec.encodePut("k",
                            ("v" + n.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
                    nodes[leader].propose(cmd); // on the owner thread; NOT_LEADER/OVERLOADED are returned, not thrown
                } catch (RuntimeException ignored) {
                    // never let a stray throw cancel the load task (which would silently end "sustained load")
                }
            }, 0, PROPOSE_PERIOD_MS, TimeUnit.MILLISECONDS);
        }

        void stopLoad() {
            ScheduledFuture<?> f = loadFuture;
            if (f != null) {
                f.cancel(false);
                loadFuture = null;
            }
        }

        void severHeartbeats(int i) {
            severHeartbeats[i] = true;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            stopLoad();
            for (ScheduledFuture<?> f : tickFutures) {
                if (f != null) {
                    f.cancel(false);
                }
            }
            for (NettyRaftTransport t : transports) {
                if (t != null) {
                    t.close(); // idempotent; stops inbound + outbound, awaits the listen-FD close
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
}

package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

/**
 * R-01 regression: the formally-verified, explicitly single-threaded {@link RaftNode}
 * ("No synchronization is used") must never have {@code tick()} and inbound
 * {@code handleMessage()}/{@code applyCommitted} run concurrently. The fix marshals inbound
 * routing onto the single tick executor (see {@link ConfigdServer#raftInboundHandler}).
 *
 * <p>These tests drive the REAL production handler so they discriminate: reverting the marshalling
 * (handler calling {@code driver.routeMessage(...)} directly instead of via
 * {@code raftExecutor.execute(...)}) makes both tests fail.
 *
 * <p>Setup uses a single-node cluster, which self-elects to LEADER purely by ticking past the
 * election timeout (no peer votes needed), keeping the harness deterministic.
 */
class RaftInboundMarshallingTest {

    private static final int GROUP = 0;
    private static final String RAFT_THREAD = "raft-test-exec";

    private static ScheduledExecutorService raftExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, RAFT_THREAD);
            t.setDaemon(true);
            return t;
        });
    }

    /** Builds a single-node RaftNode and self-elects it to LEADER on the raft executor thread. */
    private static RaftNode buildLeader(RaftTransport transport, StateMachine sm,
                                        ScheduledExecutorService raftExecutor) throws Exception {
        NodeId id = NodeId.of(1);
        RaftConfig config = RaftConfig.of(id, Set.of()); // single-node cluster
        RaftNode node = new RaftNode(config, new RaftLog(), transport, sm, new java.util.Random(42));
        // Drive the election on the raft thread so the node is only ever touched there.
        raftExecutor.submit(() -> {
            for (int i = 0; i < 400; i++) {
                node.tick();
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");
        return node;
    }

    /** A stale-term AppendEntries — a single-node leader (term >= 1) rejects it and replies, so
     *  feeding it always produces inbound work (a {@code transport.send}). */
    private static AppendEntriesRequest staleAppendEntries() {
        return new AppendEntriesRequest(0L, NodeId.of(2), 0L, 0L, List.of(), 0L);
    }

    /**
     * Deterministic discriminator: inbound routing MUST execute on the raft executor thread, not on
     * the caller's thread. Passes with the fix; fails deterministically if reverted.
     */
    @Test
    void inboundRoutingIsMarshalledOntoTheRaftExecutorThread() throws Exception {
        ScheduledExecutorService raftExecutor = raftExecutor();
        try {
            AtomicReference<String> sendThread = new AtomicReference<>();
            CountDownLatch sent = new CountDownLatch(1);
            RaftTransport transport = (target, message) -> {
                sendThread.compareAndSet(null, Thread.currentThread().getName());
                sent.countDown();
            };
            RaftNode node = buildLeader(transport, new NoopStateMachine(), raftExecutor);
            // Single-node election sends nothing (no peers): no send recorded yet.
            assertEquals(1, sent.getCount(), "election must not have produced a send");

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.addGroup(GROUP, node);
            var handler = ConfigdServer.raftInboundHandler(driver, GROUP, raftExecutor);

            String callerThread = Thread.currentThread().getName();
            handler.accept(NodeId.of(2), staleAppendEntries());

            assertTrue(sent.await(5, TimeUnit.SECONDS), "inbound message should produce a reply send");
            assertEquals(RAFT_THREAD, sendThread.get(),
                    "R-01: inbound routing must run on the raft executor thread, not the caller ("
                            + callerThread + ")");
            assertNotEquals(callerThread, sendThread.get(),
                    "R-01: inbound routing ran on the caller thread — the marshalling fix is missing");
        } finally {
            raftExecutor.shutdownNow();
        }
    }

    /**
     * Concurrency stress (the gate's named test): drive {@code tick()}+{@code propose()} on the raft
     * executor while a separate thread floods inbound messages through the production handler. A
     * shared sentinel (across {@code send} + {@code apply}) detects any concurrent RaftNode access.
     * With the fix everything serializes on the raft executor → clean; without it the flood thread
     * races the tick thread → the sentinel trips.
     */
    @Test
    void concurrentTickAndInboundDoNotRaceTheNode() throws Exception {
        ScheduledExecutorService raftExecutor = raftExecutor();
        try {
            Sentinel sentinel = new Sentinel();
            SentinelTransport transport = new SentinelTransport(sentinel);
            SentinelStateMachine sm = new SentinelStateMachine(sentinel);
            RaftNode node = buildLeader(transport, sm, raftExecutor);

            MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
            driver.addGroup(GROUP, node);
            var handler = ConfigdServer.raftInboundHandler(driver, GROUP, raftExecutor);

            // tick + propose on the raft executor — generates frequent applyCommitted activity.
            AtomicLong cmd = new AtomicLong();
            ScheduledFuture<?> ticker = raftExecutor.scheduleAtFixedRate(() -> {
                try {
                    node.propose(("c" + cmd.incrementAndGet()).getBytes(StandardCharsets.UTF_8));
                    node.tick();
                } catch (Throwable ignore) {
                    // best-effort load generator; assertions live in the sentinel
                }
            }, 0, 1, TimeUnit.MILLISECONDS);

            // Inbound flood from a distinct thread, through the PRODUCTION handler.
            final int floodIterations = 20_000;
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> floodError = new AtomicReference<>();
            Thread flood = new Thread(() -> {
                started.countDown();
                AppendEntriesRequest stale = staleAppendEntries();
                try {
                    for (int i = 0; i < floodIterations; i++) {
                        handler.accept(NodeId.of(2), stale);
                    }
                } catch (Throwable t) {
                    floodError.set(t);
                }
            }, "inbound-flood");
            flood.start();
            assertTrue(started.await(5, TimeUnit.SECONDS), "flood thread should start");
            flood.join(30_000);
            assertFalse(flood.isAlive(), "flood thread should finish");
            assertEquals(null, floodError.get(), "flood thread threw");

            ticker.cancel(false);
            // Drain any inbound tasks still queued on the raft executor (the fix path enqueues them).
            raftExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS);

            assertFalse(sentinel.raceDetected(),
                    "R-01: tick() and inbound handleMessage() accessed the RaftNode concurrently "
                            + "(observed " + sentinel.maxConcurrency() + " concurrent entries)");
            assertFalse(sm.doubleApplied(),
                    "R-01: stateMachine.apply double-entered (concurrent applyCommitted)");
        } finally {
            raftExecutor.shutdownNow();
        }
    }

    // ---- detectors -------------------------------------------------------------------------

    /** Shared concurrency sentinel: trips if more than one thread is inside an instrumented region. */
    private static final class Sentinel {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxObserved = new AtomicInteger();
        private final AtomicBoolean race = new AtomicBoolean(false);

        void enter() {
            int n = active.incrementAndGet();
            maxObserved.accumulateAndGet(n, Math::max);
            if (n > 1) {
                race.set(true);
            }
            LockSupport.parkNanos(20_000); // widen the window so a real race is reliably observed
        }

        void exit() {
            active.decrementAndGet();
        }

        boolean raceDetected() {
            return race.get();
        }

        int maxConcurrency() {
            return maxObserved.get();
        }
    }

    private static final class SentinelTransport implements RaftTransport {
        private final Sentinel sentinel;

        SentinelTransport(Sentinel sentinel) {
            this.sentinel = sentinel;
        }

        @Override
        public void send(NodeId target, RaftMessage message) {
            sentinel.enter();
            try {
                // no-op: we only care that send() is a node-access touchpoint
            } finally {
                sentinel.exit();
            }
        }
    }

    private static final class SentinelStateMachine implements StateMachine {
        private final Sentinel sentinel;
        private final java.util.Set<Long> applied = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicBoolean doubleApplied = new AtomicBoolean(false);

        SentinelStateMachine(Sentinel sentinel) {
            this.sentinel = sentinel;
        }

        @Override
        public void apply(long index, long term, byte[] command) {
            sentinel.enter();
            try {
                if (!applied.add(index)) {
                    doubleApplied.set(true);
                }
            } finally {
                sentinel.exit();
            }
        }

        @Override
        public byte[] snapshot() {
            return new byte[0];
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
        }

        boolean doubleApplied() {
            return doubleApplied.get();
        }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override
        public void apply(long index, long term, byte[] command) {
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

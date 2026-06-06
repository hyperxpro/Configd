package io.configd.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.configd.api.ConfigWriteService;
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
 * ("No synchronization is used") must only ever be touched by ONE thread. The server has three
 * node-access entry points that all run on different threads in the unfixed code:
 * <ul>
 *   <li>{@code tick()} — the "configd-tick" thread,</li>
 *   <li>inbound {@code handleMessage()} — per-connection virtual threads, and</li>
 *   <li>{@code propose()} (writes) — HTTP virtual threads.</li>
 * </ul>
 * The fix marshals BOTH inbound routing ({@link ConfigdServer#raftInboundHandler}) and proposals
 * ({@link ConfigdServer#raftProposer}) onto the single tick executor.
 *
 * <p>These tests drive the REAL production seams, so they discriminate: reverting either seam to a
 * direct {@code driver.routeMessage}/{@code driver.propose} call makes them fail. Setup uses a
 * single-node cluster, which self-elects to LEADER purely by ticking past the election timeout.
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
        raftExecutor.submit(() -> {
            for (int i = 0; i < 400; i++) {
                node.tick();
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "single-node cluster should self-elect to LEADER");
        return node;
    }

    private static MultiRaftDriver driverFor(RaftNode node) {
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(1), Clock.system());
        driver.addGroup(GROUP, node);
        return driver;
    }

    /** A stale-term AppendEntries — a leader (term >= 1) rejects it and replies, producing a send. */
    private static AppendEntriesRequest staleAppendEntries() {
        return new AppendEntriesRequest(0L, NodeId.of(2), 0L, 0L, List.of(), 0L);
    }

    private static byte[] cmd(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Concurrency stress: drive {@code tick()} on the raft executor while two other threads flood
     * inbound messages AND proposals through the production seams. A shared sentinel (across
     * {@code send} + {@code apply}) detects any concurrent RaftNode access. With the fix everything
     * serializes on the raft executor → clean; without it the flood threads race the tick thread.
     */
    @Test
    void concurrentTickInboundAndProposeAreSerializedOnTheRaftExecutor() throws Exception {
        ScheduledExecutorService raftExecutor = raftExecutor();
        try {
            Sentinel sentinel = new Sentinel();
            RaftNode node = buildLeader(new SentinelTransport(sentinel),
                    new SentinelStateMachine(sentinel), raftExecutor);
            MultiRaftDriver driver = driverFor(node);

            var inbound = ConfigdServer.raftInboundHandler(driver, GROUP, raftExecutor);
            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driver, GROUP, raftExecutor, 2000);

            // tick on the raft executor — the in-confinement entry point.
            ScheduledFuture<?> ticker = raftExecutor.scheduleAtFixedRate(() -> {
                try {
                    node.tick();
                } catch (Throwable ignore) {
                    // best-effort load generator; assertions live in the sentinel
                }
            }, 0, 1, TimeUnit.MILLISECONDS);

            AtomicReference<Throwable> floodError = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            Thread inboundFlood = new Thread(() -> {
                ready.countDown();
                await(go);
                AppendEntriesRequest stale = staleAppendEntries();
                try {
                    for (int i = 0; i < 12_000; i++) {
                        inbound.accept(NodeId.of(2), stale);
                    }
                } catch (Throwable t) {
                    floodError.compareAndSet(null, t);
                }
            }, "inbound-flood");

            Thread proposeFlood = new Thread(() -> {
                ready.countDown();
                await(go);
                try {
                    for (int i = 0; i < 3_000; i++) {
                        proposer.propose(null, cmd("w" + i)); // off-executor write
                    }
                } catch (Throwable t) {
                    floodError.compareAndSet(null, t);
                }
            }, "propose-flood");

            inboundFlood.start();
            proposeFlood.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS), "flood threads should start");
            go.countDown();
            inboundFlood.join(60_000);
            proposeFlood.join(60_000);
            assertFalse(inboundFlood.isAlive(), "inbound flood should finish");
            assertFalse(proposeFlood.isAlive(), "propose flood should finish");
            assertNull(floodError.get(), () -> "flood thread threw: " + floodError.get());

            ticker.cancel(false);
            raftExecutor.submit(() -> { }).get(30, TimeUnit.SECONDS); // drain queued tasks

            assertFalse(sentinel.raceDetected(),
                    "R-01: tick()/inbound/propose accessed the RaftNode concurrently (observed "
                            + sentinel.maxConcurrency() + " concurrent entries)");
        } finally {
            raftExecutor.shutdownNow();
        }
    }

    /** Deterministic discriminator for the INBOUND seam: routing must run on the raft executor. */
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
            assertEquals(1, sent.getCount(), "election must not have produced a send");

            var handler = ConfigdServer.raftInboundHandler(driverFor(node), GROUP, raftExecutor);
            String callerThread = Thread.currentThread().getName();
            handler.accept(NodeId.of(2), staleAppendEntries());

            assertTrue(sent.await(5, TimeUnit.SECONDS), "inbound message should produce a reply send");
            assertEquals(RAFT_THREAD, sendThread.get(),
                    "R-01: inbound routing must run on the raft executor thread, not the caller ("
                            + callerThread + ")");
            assertNotEquals(callerThread, sendThread.get());
        } finally {
            raftExecutor.shutdownNow();
        }
    }

    /** Deterministic discriminator for the PROPOSE seam: node.propose must run on the raft executor. */
    @Test
    void proposeIsMarshalledOntoTheRaftExecutorThread() throws Exception {
        ScheduledExecutorService raftExecutor = raftExecutor();
        try {
            ThreadRecordingStateMachine sm = new ThreadRecordingStateMachine();
            RaftNode node = buildLeader(new NoopTransport(), sm, raftExecutor);
            // The election's no-op commit applies on the raft executor; ignore it.
            sm.reset();

            ConfigWriteService.RaftProposer proposer =
                    ConfigdServer.raftProposer(driverFor(node), GROUP, raftExecutor, 5000);

            String callerThread = Thread.currentThread().getName();
            boolean accepted = proposer.propose(null, cmd("hello")); // single-node commits + applies
            assertTrue(accepted, "single-node leader should accept the proposal");

            String applyThread = sm.lastApplyThread();
            assertEquals(RAFT_THREAD, applyThread,
                    "R-01: node.propose()/apply must run on the raft executor thread, not the caller ("
                            + callerThread + ")");
            assertNotEquals(callerThread, applyThread);
        } finally {
            raftExecutor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                // node-access touchpoint
            } finally {
                sentinel.exit();
            }
        }
    }

    private static final class SentinelStateMachine implements StateMachine {
        private final Sentinel sentinel;

        SentinelStateMachine(Sentinel sentinel) {
            this.sentinel = sentinel;
        }

        @Override
        public void apply(long index, long term, byte[] command) {
            sentinel.enter();
            try {
                // node-access touchpoint
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
    }

    private static final class ThreadRecordingStateMachine implements StateMachine {
        private final AtomicReference<String> lastApplyThread = new AtomicReference<>();

        @Override
        public void apply(long index, long term, byte[] command) {
            lastApplyThread.set(Thread.currentThread().getName());
        }

        @Override
        public byte[] snapshot() {
            return new byte[0];
        }

        @Override
        public void restoreSnapshot(byte[] snapshot) {
        }

        void reset() {
            lastApplyThread.set(null);
        }

        String lastApplyThread() {
            return lastApplyThread.get();
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

    private static final class NoopTransport implements RaftTransport {
        @Override
        public void send(NodeId target, RaftMessage message) {
        }
    }
}

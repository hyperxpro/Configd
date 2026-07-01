package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.ProposalResult;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-executor stress test for the rehoming handoff mechanism. The deterministic single-drive-thread
 * sim cannot model true multi-owner concurrency; this test rehomes groups under adversarial schedules
 * WHILE a concurrent multi-owner workload (per-owner tick + propose + marshalled inbound + retargeted
 * group-commit flush) runs across N&gt;1 owner threads - the interaction of the handoff mechanism with
 * the live fault matrix, where a subtle handoff bug the unit/jcstress proofs missed would surface.
 *
 * <p>The invariants asserted hold under ALL interleavings if the mechanism is correct (so the test is
 * robust, not flaky):
 * <ul>
 *   <li><b>Owner isolation across rehomes</b> - ZERO {@code raft_owner_thread} fires: every
 *       {@code RaftNode} entry point ran on the group's CURRENT owner thread, even as groups
 *       move between owners.</li>
 *   <li><b>In-node safety invariants</b> - the {@code CountingThrowingChecker} throws on ANY
 *       invariant, so a violation anywhere aborts the run.</li>
 *   <li><b>Liveness / non-vacuity</b> - every group's commitIndex GROWS past its pre-sweep
 *       baseline: groups keep committing across the rehomes (a dead handoff that wedged a group
 *       would leave it at baseline or fire).</li>
 *   <li><b>No deadlock/livelock</b> - the sweep completes within the timeout.</li>
 * </ul>
 *
 * <p>Production stays single-group and never rehomes; this multi-group/rehoming surface is test-only.
 *
 * <p>This test deliberately runs REAL distinct owner threads - its core assertion is that
 * {@code raft_owner_thread} NEVER fires across rehomes, which is only meaningful with genuinely
 * separate OS owner threads. A single-thread / FIFO deterministic scheduler would bind every group
 * to one thread, making owner-isolation pass vacuously and destroying the exact coverage this test
 * exists for. The schedule therefore stays non-deterministic by design; what is made deterministic
 * is the verdict. The asserted invariants hold under ALL interleavings. The sole wall-clock deadline
 * is the method-level {@code @Timeout(600)} - a pure deadlock ceiling: a correct run finishes the
 * bounded workload in seconds, so 600s only fires on a genuine wedge.
 */
class RehomingInjectedSweepTest {

    private static final NodeId LOCAL = NodeId.of(1);
    private static final NodeId PHANTOM = NodeId.of(2);

    private static final class NoopTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    private static final class NoopStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static final class CountingThrowingChecker implements RaftNode.InvariantChecker {
        final AtomicInteger ownerFires = new AtomicInteger();
        final AtomicReference<String> firstViolation = new AtomicReference<>();
        @Override public void check(String name, boolean condition, String message) {
            if (!condition) {
                if ("raft_owner_thread".equals(name)) {
                    ownerFires.incrementAndGet();
                }
                firstViolation.compareAndSet(null, name + ": " + message);
                throw new AssertionError("INVARIANT '" + name + "' violated: " + message);
            }
        }
    }

    /** Builds a storage-backed single-node leader, bound + self-elected on its floorMod owner, wired with
     *  the PRODUCTION retargeted group-commit flush ({@code driver.dispatchFlush}) so the flush path is
     *  exercised across rehomes. */
    private static RaftNode buildLeader(OwnerExecutorPool pool, MultiRaftDriver driver, int gid,
                                        RaftNode.InvariantChecker checker) throws Exception {
        Storage storage = Storage.inMemory();
        RaftNode node = new RaftNode(RaftConfig.of(LOCAL, Set.of()), new RaftLog(storage),
                new NoopTransport(), new NoopStateMachine(), new java.util.Random(42L + gid), storage, checker);
        pool.ownerByIndex(pool.ownerIndexOf(gid)).submit(() -> {
            node.bindOwnerThread();
            // Production-style coalescing flush, dispatched onto the group's CURRENT owner (rehoming-aware).
            node.setGroupCommit((flush, delayMicros) -> driver.dispatchFlush(gid, flush, delayMicros), 4096, 0L);
            for (int i = 0; i < 400; i++) node.tick();
        }).get();
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " should self-elect");
        return node;
    }

    @Test
    @Timeout(600) // pure DEADLOCK ceiling (a correct run is ~seconds); see class doc - NOT a throughput budget
    void rehomingUnderConcurrentMultiOwnerWorkload_holdsInvariants_keepsCommitting_zeroFires() throws Exception {
        // Seed-sweep: each seed drives a different rehome SEQUENCE; real-executor scheduling adds
        // interleaving diversity on top. CI runs 1 sweep (fast smoke); the S3 verification runs many
        // (-Dconfigd.sweep.count=N). -Dconfigd.sweep.iters scales each sweep's per-producer workload.
        int sweeps = Integer.getInteger("configd.sweep.count", 1);
        long baseSeed = Long.getLong("configd.sweep.seed", 20260622L);
        for (int s = 0; s < sweeps; s++) {
            runOneSweep(baseSeed + s);
        }
    }

    private void runOneSweep(long seed) throws Exception {
        final int n = 3;
        final int[] gids = {0, 1, 2, 3, 4, 5}; // owner0={0,3}, owner1={1,4}, owner2={2,5} initially
        OwnerExecutorPool pool = new OwnerExecutorPool(n);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        Map<Integer, RaftNode> nodes = new LinkedHashMap<>();
        for (int gid : gids) {
            RaftNode node = buildLeader(pool, driver, gid, checker);
            driver.addGroup(gid, node);
            nodes.put(gid, node);
        }
        assertEquals(0, checker.ownerFires.get(), "bind/elect on the correct owners must not fire");

        // Non-vacuity baseline: post-election commitIndex per group (each has its no-op committed).
        long[] baseline = new long[gids.length];
        for (int k = 0; k < gids.length; k++) {
            baseline[k] = nodes.get(gids[k]).monitorView().commitIndex();
        }

        final int producers = 6;
        final int itersPer = Integer.getInteger("configd.sweep.iters", 800);
        ExecutorService work = Executors.newFixedThreadPool(producers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong accepted = new AtomicLong();
        AtomicLong rehomes = new AtomicLong();
        AtomicInteger keepInjecting = new AtomicInteger(1);

        // PRODUCERS - drive the workload, each unit marshalled onto the group's CURRENT owner (rehoming
        // aware). The .get() surfaces any owner-thread throwable (tripwire or in-node invariant).
        for (int p = 0; p < producers; p++) {
            final int pid = p;
            work.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < itersPer && failure.get() == null; i++) {
                        final int gid = gids[(pid + i) % gids.length];
                        // (a) per-owner tick - OWNER-indexed, on that owner's OWN executor (tickOwner self-
                        //     filters to the groups it currently owns, so it is safe under rehoming; coupling it
                        //     to a group's executor would let tickOwner(oldOwner) run on a new owner after a race).
                        final int oi = (pid + i) % n;
                        pool.ownerByIndex(oi).submit(() -> driver.tickOwner(oi)).get();
                        // (b) propose to a group - marshalled onto its CURRENT owner; driver.propose self-bounces
                        //     (rejects NOT_LEADER) if the group rehomed away from the resolved owner (no fire).
                        driver.ownerExecutor(gid).submit(() -> {
                            if (driver.propose(gid, ("v" + gid).getBytes()).result() == ProposalResult.ACCEPTED) {
                                accepted.incrementAndGet();
                            }
                        }).get();
                        // (c) marshalled inbound (production pattern) - benign low-term vote (rejected, no state
                        //     change), on the current owner; handleMessage runs on-owner, or bounces if rehomed.
                        driver.ownerExecutor(gid).execute(() ->
                                driver.routeMessage(gid, new RequestVoteRequest(0L, PHANTOM, 0L, 0L, true)));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    producersDone.countDown();
                }
            });
        }

        // REHOMING INJECTOR - move random groups to random target owners under adversarial timing, while
        // the workload runs. rehomeGroup quiesces -> publishes -> adopts (migrating gates tick + bounces work).
        work.submit(() -> {
            try {
                start.await();
                java.util.Random rnd = new java.util.Random(seed);
                while (keepInjecting.get() == 1 && failure.get() == null) {
                    int gid = gids[rnd.nextInt(gids.length)];
                    int target = rnd.nextInt(n);
                    if (driver.currentOwnerIndex(gid) == target) {
                        continue; // already there - pick again
                    }
                    try {
                        driver.rehomeGroup(gid, target);
                        rehomes.incrementAndGet();
                    } catch (IllegalArgumentException raced) {
                        // a concurrent state read raced (already-on-target / unknown) - benign, retry
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        // SAFE-RIDER - read the S-set + the owner-published monitorView off-owner, concurrently. These are
        // the only legal cross-owner reads and must NEVER trip the net.
        work.submit(() -> {
            try {
                start.await();
                while (producersDone.getCount() > 0 && failure.get() == null) {
                    for (RaftNode node : nodes.values()) {
                        node.role();
                        node.leaderId();
                        node.monitorView();
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        start.countDown();
        // No throughput budget: a correct run (even under 2-vCPU credit-exhaustion throttle) finishes the
        // bounded workload in well under a minute; a genuine handoff DEADLOCK is caught by @Timeout(600)
        // (with a thread dump). The former 90s budget was the throttle-sensitivity that made this flaky.
        producersDone.await();
        keepInjecting.set(0); // stop the injector
        work.shutdownNow();
        assertTrue(work.awaitTermination(60, TimeUnit.SECONDS), "workforce did not terminate");

        if (failure.get() != null) {
            throw new AssertionError("rehoming-injected sweep violated an invariant/tripwire (first: "
                    + checker.firstViolation.get() + ")", failure.get());
        }
        assertEquals(0, checker.ownerFires.get(),
                "OWNER ISOLATION VIOLATED under rehoming — a group entry point ran off its current owner; first: "
                        + checker.firstViolation.get());

        // PRE-DRAIN liveness (SELF-SUFFICIENT - does not rely on the drain): the injector is stopped and the
        // barriers are uninterruptible, so no group is left migrating. Tick each group ONCE on its current
        // owner (refresh monitorView; NO propose) and assert it committed PAST its baseline DURING the
        // concurrent rehoming phase - proving consensus progressed ACROSS the rehomes, not only in the drain.
        // A group wedged mid-sweep would either have FIRED the net (caught above) or show no pre-drain growth
        // here.
        long minPreDrain = Long.MAX_VALUE;
        for (int k = 0; k < gids.length; k++) {
            int gid = gids[k];
            final int owner = driver.currentOwnerIndex(gid);
            pool.ownerByIndex(owner).submit(() -> driver.tickOwner(owner)).get();
            long pre = nodes.get(gid).monitorView().commitIndex();
            assertTrue(pre > baseline[k],
                    "group " + gid + " did not commit across the concurrent rehoming phase (pre-drain commitIndex "
                            + baseline[k] + " -> " + pre + ") — it may have stalled/wedged mid-sweep");
            minPreDrain = Math.min(minPreDrain, pre - baseline[k]);
        }
        assertEquals(0, checker.ownerFires.get(), "the pre-drain liveness tick must not fire");

        // DRAIN - settle every group back onto its static owner, then propose + tick it there (clean shutdown +
        // a post-settle confirmation that the group still serves on its static owner after the sweep).
        for (int gid : gids) {
            int target = pool.ownerIndexOf(gid);
            if (driver.currentOwnerIndex(gid) != target) {
                driver.rehomeGroup(gid, target);
            }
            final int owner = driver.currentOwnerIndex(gid);
            pool.ownerByIndex(owner).submit(() -> {
                for (int j = 0; j < 10; j++) {
                    driver.propose(gid, "drain".getBytes());
                    driver.tickOwner(owner);
                }
            }).get();
        }
        assertEquals(0, checker.ownerFires.get(), "drain must not fire");
        assertTrue(accepted.get() > 0, "vacuous — no proposals were accepted");
        assertTrue(rehomes.get() > 0, "vacuous — the injector performed no rehomes");
        long minGrowth = Long.MAX_VALUE;
        long totalGrowth = 0;
        for (int k = 0; k < gids.length; k++) {
            long now = nodes.get(gids[k]).monitorView().commitIndex();
            assertTrue(now > baseline[k],
                    "group " + gids[k] + " did not keep committing across the rehomes (commitIndex "
                            + baseline[k] + " -> " + now + ")");
            minGrowth = Math.min(minGrowth, now - baseline[k]);
            totalGrowth += now - baseline[k];
        }
        System.out.println("[S3 sweep seed=" + seed + "] rehomes=" + rehomes.get() + " accepted=" + accepted.get()
                + " preDrainGrowth(min=" + minPreDrain + ") commitGrowth(min=" + minGrowth + " total=" + totalGrowth
                + " over " + gids.length + " groups) ownerFires=" + checker.ownerFires.get());

        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "owner pool did not terminate");
    }
}

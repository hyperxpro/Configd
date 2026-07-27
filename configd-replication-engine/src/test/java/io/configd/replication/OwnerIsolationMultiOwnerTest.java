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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner-isolation proof at N&gt;1.
 *
 * <p>The owner-executor pool generalizes the tick to per-owner ({@link MultiRaftDriver#tickOwner(int)}
 * scheduled on each owner) and this test exercises the race surface that N=1 never could: multiple
 * owner threads driving multiple groups. The property under test is OWNER ISOLATION:
 *
 * <blockquote>For each group g, every OWNER-ONLY entry point of its {@link RaftNode} executes on
 * {@code ownerExecutor(g)}'s thread and on no other - even when that "other" is itself a legitimate
 * owner thread for a different group.</blockquote>
 *
 * <p>The {@code assertOwnerThread()} net asserts this. The danger at N&gt;1 is a per-pool rather
 * than per-node guard: a naive guard that merely checked "am I on some owner thread?" would pass a
 * cross-group access. This test proves the guard is per-node by running group B's entry points on
 * group A's real owner thread and showing the net still fires.
 *
 * <ul>
 *   <li><b>{@link #perOwnerTick_cleanRun_zeroFires_nonVacuousAcrossAllOwners()}</b> - many producers
 *       drive {@code tickOwner(i)} + {@code maybeCompactOwner(i)} + {@code propose}, each marshalled
 *       onto the correct owner, across N=3 owners concurrently, while a foreign safe-rider reads the
 *       S-set ({@code role}/{@code leaderId}) and {@code monitorView()} off-owner. Zero fires, and
 *       every owner makes real consensus progress (non-vacuous on all three owners).</li>
 *   <li><b>{@link #crossGroupAccessOnARealOwnerTripsThePerNodeNet()}</b> - the injected violation:
 *       group 1's entry points (bound to owner[1]) are invoked on owner[0]'s thread (a real owner of
 *       groups 0,3). Each trips {@code raft_owner_thread}. A control shows the same entry points on
 *       group 1's correct owner do NOT fire - the guard is per-node, not "always-on".</li>
 * </ul>
 *
 * <p>Production stays single-group (group 0 on owner[0]); this multi-group surface is test-only.
 */
class OwnerIsolationMultiOwnerTest {

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

    /**
     * Counts {@code raft_owner_thread} fires AND throws on any violation (the sim/macro throwing
     * discipline). Shared across all groups: a cross-group leak shows up as a fire recorded against
     * whichever node was touched off its owner. The throw aborts the offending task; the count and
     * first-violation message survive for end-of-test assertions.
     */
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

    /**
     * Builds a storage-backed single-node group, binds its owner as the first task on
     * {@code ownerExecutor(gid)}, then self-elects it to LEADER - all on the group's owner, so
     * the bind/elect path is clean (no fire).
     */
    private static RaftNode newSingleNodeLeaderBoundToOwner(OwnerExecutorPool pool, int gid,
                                                            RaftNode.InvariantChecker checker) throws Exception {
        Storage storage = Storage.inMemory();
        RaftConfig config = RaftConfig.of(LOCAL, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(storage), new NoopTransport(),
                new NoopStateMachine(), new java.util.Random(42L + gid), storage, checker);
        pool.ownerExecutor(gid).submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400; i++) node.tick();
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " should self-elect to LEADER");
        return node;
    }

    @Test
    @Timeout(60)
    void perOwnerTick_cleanRun_zeroFires_nonVacuousAcrossAllOwners() throws Exception {
        final int n = 3;
        final int[] gids = {0, 1, 2, 3, 4, 5};
        OwnerExecutorPool pool = new OwnerExecutorPool(n);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        Map<Integer, RaftNode> nodes = new LinkedHashMap<>();
        for (int gid : gids) {
            RaftNode node = newSingleNodeLeaderBoundToOwner(pool, gid, checker);
            driver.addGroup(gid, node);
            nodes.put(gid, node);
        }
        assertEquals(0, checker.ownerFires.get(), "bind/elect on the correct owners must not fire");

        // Non-vacuity BASELINE. After self-election each group has already committed its leader no-op
        // (commitIndex == 1) - so "commitIndex > 0" alone would be satisfied by SETUP, not by the
        // per-owner tick (a dead tickOwner() would still pass it). Capture the post-setup commitIndex
        // here so the end assertion can require GROWTH driven by the per-owner tick during the run.
        long[] baselineCommit = new long[n];
        for (int owner = 0; owner < n; owner++) {
            baselineCommit[owner] = nodes.get(owner).monitorView().commitIndex();
        }

        final int producers = 6;
        final int itersPer = 600;
        ExecutorService producerPool = Executors.newFixedThreadPool(producers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong accepted = new AtomicLong();

        for (int p = 0; p < producers; p++) {
            final int pid = p;
            producerPool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < itersPer && failure.get() == null; i++) {
                        final int gid = gids[(pid + i) % gids.length];
                        final int owner = pool.ownerIndexOf(gid);
                        // The correct path: tick THIS owner's groups + propose to THIS group, all
                        // marshalled onto the group's owner. .get() surfaces any owner-thread
                        // throwable (tripwire or in-node invariant) to this producer thread.
                        pool.ownerExecutor(gid).submit(() -> {
                            driver.tickOwner(owner);
                            driver.maybeCompactOwner(owner, 64L);
                            if (driver.propose(gid, ("v-" + gid).getBytes()).result() == ProposalResult.ACCEPTED) {
                                accepted.incrementAndGet();
                            }
                        }).get();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        // Read the volatile S-set and the owner-published monitorView() from a foreign thread,
        // concurrently with N owners mutating their groups. These are the only legal cross-owner
        // reads (volatile / immutable snapshot) - they must NEVER trip the net.
        producerPool.submit(() -> {
            try {
                start.await();
                while (done.getCount() > 0 && failure.get() == null) {
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
        assertTrue(done.await(50, TimeUnit.SECONDS), "multi-owner workload did not finish in time");
        producerPool.shutdownNow();

        for (int i = 0; i < n; i++) {
            final int owner = i;
            pool.ownerByIndex(i).submit(() -> driver.tickOwner(owner)).get(5, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "owner pool did not terminate");

        if (failure.get() != null) {
            throw new AssertionError("clean multi-owner path violated an invariant/tripwire: "
                    + checker.firstViolation.get(), failure.get());
        }
        assertEquals(0, checker.ownerFires.get(),
                "OWNER ISOLATION VIOLATED: a group's entry point ran off its owner under the per-owner "
                        + "pool — first: " + checker.firstViolation.get());
        assertNull(checker.firstViolation.get(), "no invariant/tripwire fire expected on the clean path");

        // Non-vacuity: real consensus work happened, and the per-owner tick ADVANCED consensus on
        // EVERY owner during the run (not just owner[0], and not merely satisfied by setup). The
        // commitIndex must have GROWN past the post-setup baseline on a group bound to each owner -
        // a dead/mis-filtered tickOwner() would leave it at the baseline.
        assertTrue(accepted.get() > 0, "vacuous — no proposals were accepted across any owner");
        for (int owner = 0; owner < n; owner++) {
            int gid = owner; // group 'owner' is bound to owner[owner] (floorMod(owner, n) == owner for owner < n)
            long now = nodes.get(gid).monitorView().commitIndex();
            assertTrue(now > baselineCommit[owner],
                    "owner[" + owner + "] did not ADVANCE consensus during the run (group " + gid
                            + " commitIndex " + baselineCommit[owner] + " -> " + now + ") — the per-owner"
                            + " tick is not driving this owner (a dead tickOwner() would leave it at baseline)");
        }
    }

    @Test
    @Timeout(30)
    void crossGroupAccessOnARealOwnerTripsThePerNodeNet() throws Exception {
        final int n = 2;
        OwnerExecutorPool pool = new OwnerExecutorPool(n);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        RaftNode g0 = newSingleNodeLeaderBoundToOwner(pool, 0, checker);
        RaftNode g1 = newSingleNodeLeaderBoundToOwner(pool, 1, checker);
        driver.addGroup(0, g0);
        driver.addGroup(1, g1);
        assertEquals(0, checker.ownerFires.get(), "setup on correct owners must not fire");

        // THE CROSS-GROUP VIOLATION: invoke group 1's OWNER-ONLY entry points on owner[0]'s thread.
        // owner[0] is a REAL owner (it owns group 0), so this proves the guard is PER-NODE: g1 is bound
        // to owner[1], and assertOwnerThread() fires before any state is touched even though we run on a
        // legitimate (but wrong-for-g1) owner thread. A per-pool guard would miss this; the per-node
        // guard catches it.
        List<Runnable> crossGroup = List.<Runnable>of(
                g1::tick,
                () -> g1.propose(new byte[]{1}),
                () -> g1.handleMessage(new RequestVoteRequest(0L, PHANTOM, 0L, 0L, true)),
                g1::readIndex,
                g1::metrics,
                g1::triggerSnapshot,
                () -> g1.maybeCompact(16L)
        );
        for (Runnable op : crossGroup) {
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> pool.ownerByIndex(0).submit(op).get(5, TimeUnit.SECONDS),
                    "a group-1 entry point on owner[0] must trip the per-node owner net");
            Throwable cause = ee.getCause();
            assertNotNull(cause, "expected the tripwire AssertionError as the cause");
            assertTrue(cause.getMessage() != null && cause.getMessage().contains("raft_owner_thread"),
                    "expected raft_owner_thread, got: " + cause.getMessage());
        }
        assertTrue(checker.ownerFires.get() >= crossGroup.size(),
                "the per-node net must have fired once per cross-group access (>= " + crossGroup.size()
                        + "), was " + checker.ownerFires.get());

        // CONTROL (the catch is non-vacuous): the SAME entry points on group 1's CORRECT owner do NOT
        // fire - proving the guard discriminates by node, not "always throws".
        long before = checker.ownerFires.get();
        pool.ownerByIndex(1).submit(() -> {
            g1.tick();
            g1.readIndex();
            g1.metrics();
        }).get(5, TimeUnit.SECONDS);
        assertEquals(before, checker.ownerFires.get(),
                "on-owner access for group 1 must NOT fire — the guard is firing indiscriminately");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "owner pool did not terminate");
    }

    /**
     * Focused, deterministic proof that the per-owner tick filter is correct: tickOwner(i) /
     * maybeCompactOwner(i) act on EXACTLY the groups bound to owner[i] and no others. Owners are NOT
     * bound here (the net is inert), so the filter is driven directly from the test thread without
     * threading - isolating "does the filter select the right groups" from "does it run on the right
     * thread" (the concurrent test above proves the latter). A single-node group becomes LEADER only
     * if it is actually ticked, so {@code role()} is the observable: ticking owner i must elect i's
     * groups and leave the others FOLLOWER.
     */
    @Test
    @Timeout(30)
    void tickOwnerFiltersToExactlyItsOwnGroups() {
        final int n = 2;
        final int[] gids = {0, 1, 2, 3};
        OwnerExecutorPool pool = new OwnerExecutorPool(n);
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        Map<Integer, RaftNode> nodes = new LinkedHashMap<>();
        for (int gid : gids) {
            // Unbound: assertOwnerThread() is inert, so driving tickOwner from the test thread is legal.
            Storage storage = Storage.inMemory();
            RaftNode node = new RaftNode(RaftConfig.of(LOCAL, Set.of()), new RaftLog(storage),
                    new NoopTransport(), new NoopStateMachine(), new java.util.Random(42L + gid), storage);
            driver.addGroup(gid, node);
            nodes.put(gid, node);
        }
        try {
            for (int i = 0; i < 400; i++) driver.tickOwner(0);
            assertEquals(RaftRole.LEADER, nodes.get(0).role(), "group 0 (owner0) must be ticked by tickOwner(0)");
            assertEquals(RaftRole.LEADER, nodes.get(2).role(), "group 2 (owner0) must be ticked by tickOwner(0)");
            assertEquals(RaftRole.FOLLOWER, nodes.get(1).role(), "group 1 (owner1) must NOT be ticked by tickOwner(0)");
            assertEquals(RaftRole.FOLLOWER, nodes.get(3).role(), "group 3 (owner1) must NOT be ticked by tickOwner(0)");

            for (int i = 0; i < 400; i++) driver.tickOwner(1);
            for (int gid : gids) {
                assertEquals(RaftRole.LEADER, nodes.get(gid).role(),
                        "group " + gid + " should be LEADER after its owner ticked");
            }

            OwnerExecutorPool wide = new OwnerExecutorPool(8);
            MultiRaftDriver d2 = new MultiRaftDriver(LOCAL, Clock.system());
            d2.setOwnerPool(wide);
            assertDoesNotThrow(() -> { d2.tickOwner(5); d2.maybeCompactOwner(5, 16L); },
                    "tick/compact of an owner index with no groups must be a no-op");
            wide.shutdown();
        } finally {
            pool.shutdown();
        }
    }
}

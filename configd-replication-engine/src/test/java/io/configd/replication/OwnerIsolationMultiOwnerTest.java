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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 — Workstream B — Stage 2 — M1: the OWNER-ISOLATION proof at N&gt;1.
 *
 * <p>Stage 1 deleted R-01 and routed consensus through {@code ownerExecutor(gid) = pool[floorMod(gid, N)]}
 * at <b>N=1</b> — one owner thread, behaviourally exact-R-01, so multi-group / multi-owner could not be
 * exercised. M1 generalizes the tick to per-owner ({@link MultiRaftDriver#tickOwner(int)} scheduled on
 * each owner) and this test exercises the new race surface N=1 never could: <b>multiple owner threads
 * driving multiple groups</b>. The property under test is OWNER ISOLATION:
 *
 * <blockquote>For each group g, every OWNER-ONLY entry point of its {@link RaftNode} executes on
 * {@code ownerExecutor(g)}'s thread and on no other — even when that "other" is itself a legitimate
 * owner thread for a <i>different</i> group.</blockquote>
 *
 * <p>The {@code assertOwnerThread()} net asserts this. The danger at N&gt;1 (that N=1 hid) is a
 * <b>per-pool</b> rather than <b>per-node</b> guard: a naive guard that merely checked "am I on some
 * owner thread?" would pass a cross-group access. This test proves the guard is per-node by running
 * group B's entry points on group A's <i>real owner thread</i> and showing the net still fires.
 *
 * <ul>
 *   <li><b>{@link #perOwnerTick_cleanRun_zeroFires_nonVacuousAcrossAllOwners()}</b> — many producers
 *       drive {@code tickOwner(i)} + {@code maybeCompactOwner(i)} + {@code propose}, each marshalled
 *       onto the correct owner, across {@code N=3} owners concurrently, while a foreign safe-rider
 *       reads the S-set ({@code role}/{@code leaderId}) and {@code monitorView()} off-owner. Zero
 *       fires, and every owner makes real consensus progress (non-vacuous on all three owners).</li>
 *   <li><b>{@link #crossGroupAccessOnARealOwnerTripsThePerNodeNet()}</b> — the injected violation:
 *       group 1's entry points (bound to owner[1]) are invoked on owner[0]'s thread (a real owner of
 *       groups 0,3). Each trips {@code raft_owner_thread}. A CONTROL shows the same entry points on
 *       group 1's correct owner do NOT fire — the guard is per-node, not "always-on".</li>
 * </ul>
 *
 * <p>Production stays single-group (group 0 on owner[0]); this multi-group surface is test-only until
 * Phase 1 sharding. See {@code docs/phase0-B-stage2/} and threading-contract §2/§4.2.
 */
class OwnerIsolationMultiOwnerTest {

    private static final NodeId LOCAL = NodeId.of(1);
    private static final NodeId PHANTOM = NodeId.of(2); // benign foreign sender for handleMessage

    /** No peers — single-node groups self-elect; transport is unused. */
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
     * Builds a storage-backed single-node group, binds its owner as the FIRST task on
     * {@code ownerExecutor(gid)} (H-6), then self-elects it to LEADER — all on the group's owner, so
     * the bind/elect path is clean (no fire).
     */
    private static RaftNode newSingleNodeLeaderBoundToOwner(OwnerExecutorPool pool, int gid,
                                                            RaftNode.InvariantChecker checker) throws Exception {
        Storage storage = Storage.inMemory();
        RaftConfig config = RaftConfig.of(LOCAL, Set.of()); // single-node cluster self-elects
        RaftNode node = new RaftNode(config, new RaftLog(storage), new NoopTransport(),
                new NoopStateMachine(), new java.util.Random(42L + gid), storage, checker);
        pool.ownerExecutor(gid).submit(() -> {
            node.bindOwnerThread();                        // H-6: bind first, on the group's owner
            for (int i = 0; i < 400; i++) node.tick();      // self-elect (single-node), proven idiom
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " should self-elect to LEADER");
        return node;
    }

    @Test
    @Timeout(60)
    void perOwnerTick_cleanRun_zeroFires_nonVacuousAcrossAllOwners() throws Exception {
        final int n = 3;
        // owner0={0,3}, owner1={1,4}, owner2={2,5} (floorMod(gid, 3)).
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
        // Setup ran entirely on the correct owners — the guard must be silent so far.
        assertEquals(0, checker.ownerFires.get(), "bind/elect on the correct owners must not fire");

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
                        // The correct R-01' path: tick THIS owner's groups + propose to THIS group,
                        // all marshalled onto the group's owner. .get() surfaces any owner-thread
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

        // SAFE-RIDER (S-class + H-3): read the volatile S-set and the owner-published monitorView()
        // from a FOREIGN thread, concurrently with N owners mutating their groups. These are the only
        // legal cross-owner reads (volatile / immutable snapshot) — they must NEVER trip the net.
        producerPool.submit(() -> {
            try {
                start.await();
                while (done.getCount() > 0 && failure.get() == null) {
                    for (RaftNode node : nodes.values()) {
                        node.role();
                        node.leaderId();
                        node.monitorView(); // one volatile load of the immutable snapshot
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        start.countDown();
        assertTrue(done.await(50, TimeUnit.SECONDS), "multi-owner workload did not finish in time");
        producerPool.shutdownNow();

        // Drain each owner with a final tick so the last proposals commit, then shut the pool down.
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

        // Non-vacuity: real consensus work happened, and on EVERY owner (not just owner[0]).
        assertTrue(accepted.get() > 0, "vacuous — no proposals were accepted across any owner");
        for (int owner = 0; owner < n; owner++) {
            int gid = owner; // group 'owner' is bound to owner[owner] (floorMod(owner, n) == owner for owner < n)
            assertTrue(nodes.get(gid).monitorView().commitIndex() > 0,
                    "owner[" + owner + "] made no consensus progress (group " + gid
                            + " commitIndex==0) — the per-owner tick is not driving this owner");
        }
    }

    @Test
    @Timeout(30)
    void crossGroupAccessOnARealOwnerTripsThePerNodeNet() throws Exception {
        final int n = 2; // owner0={0}, owner1={1}
        OwnerExecutorPool pool = new OwnerExecutorPool(n);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        RaftNode g0 = newSingleNodeLeaderBoundToOwner(pool, 0, checker); // bound to owner[0]
        RaftNode g1 = newSingleNodeLeaderBoundToOwner(pool, 1, checker); // bound to owner[1]
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
        // fire — proving the guard discriminates by node, not "always throws".
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
}

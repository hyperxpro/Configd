package io.configd.replication;

import io.configd.common.Clock;
import io.configd.common.NodeId;
import io.configd.common.Storage;
import io.configd.raft.RaftConfig;
import io.configd.raft.RaftLog;
import io.configd.raft.RaftMessage;
import io.configd.raft.RaftNode;
import io.configd.raft.RaftRole;
import io.configd.raft.RaftTransport;
import io.configd.raft.StateMachine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the three deferred rehoming sub-mechanisms that the handoff safety model depends on.
 * The core handoff mechanism (quiesce - publish - adopt) was built first; this covers:
 *
 * <ul>
 *   <li><b>quiesce</b> - {@link RaftNode#quiesceForHandoff()} force-syncs buffered entries on the
 *       LOSING owner BEFORE the routing flip and detach, so the gaining owner adopts a clean,
 *       durable state
 *       ({@link #quiesce_flushesBufferedEntriesDurableAcrossRehome()}).</li>
 *   <li><b>FlushScheduler retarget</b> - {@link MultiRaftDriver#dispatchFlush} re-resolves the
 *       group's CURRENT owner (rehoming-aware), and {@link RaftNode#flushDurable()} is owner-guarded,
 *       so a stale flush dispatched onto the OLD owner after a rehome FIRES the net instead of
 *       silently racing
 *       ({@link #flushRetarget_dispatchAfterRehome_runsOnNewOwner_noFire()},
 *       {@link #flushRetarget_offOwnerFlush_firesGuard()}).</li>
 *   <li><b>abortHandoff</b> - if the gaining owner cannot adopt after the losing owner detached,
 *       {@link MultiRaftDriver#rehomeGroup} rolls the handoff back to the losing owner with no torn
 *       state ({@link #abortHandoff_gainingOwnerUnavailable_rollsBackToLosingOwner()}).</li>
 * </ul>
 *
 * <p>Production stays single-group and never rehomes; this surface is test-only.
 */
class RehomingSubMechanismsTest {

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

    /** Counts raft_owner_thread fires AND throws on any violation (sim/macro discipline). */
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

    /** Builds a storage-backed single-node leader, bound + self-elected on its floorMod owner (INLINE flush). */
    private static RaftNode buildLeaderBoundTo(OwnerExecutorPool pool, int gid,
                                               RaftNode.InvariantChecker checker) throws Exception {
        Storage storage = Storage.inMemory();
        RaftNode node = new RaftNode(RaftConfig.of(LOCAL, java.util.Set.of()), new RaftLog(storage),
                new NoopTransport(), new NoopStateMachine(), new java.util.Random(42L + gid), storage, checker);
        pool.ownerByIndex(pool.ownerIndexOf(gid)).submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400; i++) node.tick();
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " should self-elect");
        return node;
    }

    /** Runs {@code task} on the given owner and waits for it. */
    private static void onOwner(OwnerExecutorPool pool, int ownerIndex, RunnableEx task) throws Exception {
        pool.ownerByIndex(ownerIndex).submit(() -> {
            try { task.run(); } catch (Exception e) { throw new RuntimeException(e); }
        }).get(5, TimeUnit.SECONDS);
    }

    @FunctionalInterface private interface RunnableEx { void run() throws Exception; }

    // (1) quiesce - force-sync buffered entries on the losing owner before the handoff point.

    @Test
    @Timeout(30)
    void quiesce_flushesBufferedEntriesDurableAcrossRehome() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2); // group 0 -> owner0 by floorMod
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker); // elected + no-op committed (INLINE flush)
        driver.addGroup(0, g);

        // Switch to a DEFERRED flush scheduler: from now on, scheduled flushes are PARKED in `pending`
        // and never auto-run, so a freshly-proposed entry stays BUFFERED (durableIndex behind lastIndex)
        // and therefore cannot commit (the leader may not count a not-yet-fsynced self-copy - the
        // group-commit durability gate). The only thing that can make it durable is a DIRECT flushDurable
        // - which is exactly what quiesceForHandoff() does during the rehome.
        Deque<Runnable> pending = new ArrayDeque<>();
        g.setGroupCommit((flush, delayMicros) -> pending.add(flush), 4096, 0);

        long base = commitIndexVia(pool, driver, g, 0); // committed no-op (everything durable from INLINE era)
        assertTrue(base >= 1, "precondition: the election no-op committed before we deferred flushes");

        // Propose a NEW entry on the current owner (owner0). It is appended-no-sync and its flush is parked.
        onOwner(pool, 0, () -> driver.propose(0, "buffered".getBytes()));
        assertTrue(pending.size() >= 1, "the buffered propose must have PARKED a flush");
        int parkedBefore = pending.size();
        assertEquals(base, commitIndexVia(pool, driver, g, 0),
                "the buffered entry must NOT commit while its flush is parked (durableIndex gate)");

        // REHOME 0 -> 1. The handoff's quiesce step force-syncs the buffered entry on the LOSING owner
        // (owner0) BEFORE detaching, so it becomes durable and commits - carried across to owner1.
        driver.rehomeGroup(0, 1);
        assertEquals(1, driver.currentOwnerIndex(0), "rehomed to owner1");

        long afterRehome = commitIndexVia(pool, driver, g, 1);
        assertTrue(afterRehome > base,
                "quiesce must have force-synced the buffered entry across the rehome (commitIndex " + base
                        + " -> " + afterRehome + "); it stayed buffered ⇒ quiesce did NOT flush");
        assertEquals(parkedBefore, pending.size(),
                "the parked flush must still be un-run — the commit came from quiesce's DIRECT flushDurable, "
                        + "not from a scheduled flush");
        assertEquals(0, checker.ownerFires.get(),
                "the quiesce path runs entirely on the losing owner — zero fires; first: " + checker.firstViolation.get());

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    /** Ticks the group on {@code ownerIndex} to republish its monitor view, then reads commitIndex (S-set). */
    private static long commitIndexVia(OwnerExecutorPool pool, MultiRaftDriver driver, RaftNode g, int ownerIndex)
            throws Exception {
        onOwner(pool, ownerIndex, () -> driver.tickOwner(ownerIndex));
        return g.monitorView().commitIndex();
    }

    // (2) FlushScheduler retarget - the dispatched flush targets the CURRENT owner; flushDurable guarded.

    @Test
    @Timeout(30)
    void flushRetarget_dispatchAfterRehome_runsOnNewOwner_noFire() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        // Capture the node's real flush task (node::flushDurable) via a deferred scheduler + a propose.
        AtomicReference<Runnable> captured = new AtomicReference<>();
        g.setGroupCommit((flush, delayMicros) -> captured.set(flush), 4096, 0);
        onOwner(pool, 0, () -> driver.propose(0, "x".getBytes()));
        Runnable flushTask = captured.get();
        assertNotNull(flushTask, "propose must schedule a flush we can capture");

        // REHOME 0 -> 1. dispatchFlush must re-resolve the CURRENT owner (owner1) and run the flush there
        // WITHOUT firing - a flush scheduled before/after a rehome lands on the new owner.
        driver.rehomeGroup(0, 1);
        driver.dispatchFlush(0, flushTask, 0);
        onOwner(pool, 1, () -> { }); // drain owner1 so the dispatched flush completed

        assertEquals(0, checker.ownerFires.get(),
                "dispatchFlush must target the current owner (owner1) — zero fires; first: " + checker.firstViolation.get());

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void flushRetarget_offOwnerFlush_firesGuard() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        AtomicReference<Runnable> captured = new AtomicReference<>();
        g.setGroupCommit((flush, delayMicros) -> captured.set(flush), 4096, 0);
        onOwner(pool, 0, () -> driver.propose(0, "x".getBytes()));
        Runnable flushTask = captured.get();
        assertNotNull(flushTask);

        driver.rehomeGroup(0, 1); // group now owned by owner1

        // The hazard the retarget closes: a flush that runs on the OLD owner (owner0) after the rehome -
        // exactly what a closure that CAPTURED owner0 would do. flushDurable is now owner-guarded, so this
        // off-owner flush FIRES the net (throw in test/sim, metric in prod) instead of silently racing the
        // unsynchronised log. This is why dispatchFlush re-resolving the owner is load-bearing.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pool.ownerByIndex(0).submit(flushTask).get(5, TimeUnit.SECONDS),
                "a flush run on the OLD owner after a rehome must trip raft_owner_thread");
        Throwable cause = ee.getCause();
        assertNotNull(cause);
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("raft_owner_thread"),
                "expected raft_owner_thread, got: " + cause.getMessage());
        assertTrue(checker.ownerFires.get() >= 1, "the guard must have fired on the off-owner flush");

        // CONTROL: the SAME flush task on the CURRENT owner (owner1) does not fire.
        long firesAfterOffOwner = checker.ownerFires.get();
        onOwner(pool, 1, flushTask::run);
        assertEquals(firesAfterOffOwner, checker.ownerFires.get(),
                "the flush on the current owner (owner1) must not fire");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    // (3) abortHandoff - roll a partial handoff back to the losing owner if the gaining owner is dead.

    @Test
    @Timeout(30)
    void abortHandoff_gainingOwnerUnavailable_rollsBackToLosingOwner() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker); // bound to owner0
        driver.addGroup(0, g);
        long base = commitIndexVia(pool, driver, g, 0);

        // Make the GAINING owner (owner1) unavailable: its executor rejects new tasks. The handoff will
        // quiesce + publish + DETACH on owner0, then fail to ADOPT on owner1 - and must roll back to owner0.
        pool.ownerByIndex(1).shutdownNow();

        assertThrows(RejectedExecutionException.class, () -> driver.rehomeGroup(0, 1),
                "rehoming to a dead owner must surface the failure (after rolling back)");

        // ROLLBACK: routing restored to owner0 (no leaked override), and owner0 re-adopted - the group is
        // back on its original owner, not wedged on the HANDOFF sentinel.
        assertEquals(0, driver.currentOwnerIndex(0), "abort must restore routing to the losing owner (owner0)");
        assertEquals(RaftRole.LEADER, g.role(), "rollback preserves group state (still LEADER, no torn state)");

        // The losing owner (owner0) still owns it: an on-owner tick does NOT fire, and the group keeps
        // committing on owner0 (consensus resumed cleanly after the aborted handoff).
        long afterAbort = commitIndexVia(pool, driver, g, 0);
        for (int i = 0; i < 10; i++) {
            onOwner(pool, 0, () -> { driver.propose(0, "y".getBytes()); driver.tickOwner(0); });
        }
        long afterCommits = g.monitorView().commitIndex();
        assertTrue(afterCommits > afterAbort,
                "the group must keep committing on the losing owner after the aborted handoff ("
                        + afterAbort + " -> " + afterCommits + ")");
        assertTrue(afterAbort >= base, "no commit lost across the abort");
        assertEquals(0, checker.ownerFires.get(),
                "the abort/rollback path runs entirely on the losing owner — zero fires; first: "
                        + checker.firstViolation.get());

        // A message routed to the (live) owner0 is handled, not bounced into the void.
        onOwner(pool, 0, () -> driver.routeMessage(0,
                new io.configd.raft.RequestVoteRequest(0L, PHANTOM, 0L, 0L, true)));

        pool.ownerByIndex(0).shutdown();
        assertTrue(pool.ownerByIndex(0).awaitTermination(10, TimeUnit.SECONDS));
    }
}

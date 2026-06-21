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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 — Workstream B — Stage 2 — M2: the H-4 group-REHOMING handoff proof.
 *
 * <p>M1 generalized the tick to per-owner with STATIC assignment (a group's owner never changed). M2
 * makes a group able to MOVE between owner threads (rehoming) via a quiesce→publish→adopt handoff
 * ({@link MultiRaftDriver#rehomeGroup}), which makes {@code RaftNode.ownerThread} re-bindable
 * ({@code beginHandoff}→HANDOFF sentinel, {@code adoptOwnerThread}). This is the Stage-2 analogue of
 * H-3 — a new cross-thread-handoff race class the N=1 net never exercised. This test proves:
 *
 * <ul>
 *   <li><b>{@link #cleanRehome_preservesState_keepsCommitting_zeroFires()}</b> — a group rehomed
 *       A→B keeps its state (still LEADER) and keeps COMMITTING on the new owner (commitIndex grows
 *       past the pre-rehome baseline — non-vacuous, on B's epoch), with ZERO net fires; and a stale
 *       message routed to the OLD owner bounces to the new owner (check-and-bounce) without firing.</li>
 *   <li><b>{@link #accessOnLosingOwnerAfterHandoff_trips()}</b> — accessing the group on the LOSING
 *       owner after {@code beginHandoff()} trips {@code raft_owner_thread} (the window is net-covered
 *       by the HANDOFF sentinel).</li>
 *   <li><b>{@link #accessOnGainingOwnerBeforeAdopt_trips()}</b> — accessing it on the GAINING owner
 *       BEFORE {@code adoptOwnerThread()} trips {@code raft_owner_thread}.</li>
 *   <li><b>{@link #afterRehome_oldOwnerLockedOut_newOwnerOwns()}</b> — NO DOUBLE-OWNERSHIP: after the
 *       handoff completes, the OLD owner accessing the group trips, while the NEW owner does not.</li>
 *   <li><b>{@link #adoptOnNonMigratingNode_trips()}</b> — adopting a node that is not mid-handoff trips
 *       {@code raft_owner_adopt} (a double-adopt / wrong-state guard).</li>
 * </ul>
 *
 * <p>Production stays single-group and NEVER rehomes (the mechanism is dormant until Phase-1 placement);
 * this surface is test-only. See docs/phase0-B-stage2/m2-rehoming-handoff-design.md.
 */
class RehomingHandoffTest {

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

    /** Builds a storage-backed single-node leader, bound + self-elected on its floorMod owner. */
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

    /** Proposes + ticks {@code rounds} times on the group's CURRENT owner (commit advances). */
    private static void driveCommits(OwnerExecutorPool pool, MultiRaftDriver driver, int gid, int rounds)
            throws Exception {
        final int owner = driver.currentOwnerIndex(gid);
        for (int i = 0; i < rounds; i++) {
            pool.ownerByIndex(owner).submit(() -> {
                driver.propose(gid, "x".getBytes());
                driver.tickOwner(owner);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(30)
    void cleanRehome_preservesState_keepsCommitting_zeroFires() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2); // group 0 → owner0 by floorMod
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);
        assertEquals(0, driver.currentOwnerIndex(0), "group 0 starts on owner0 (floorMod)");

        // Commit on the ORIGINAL owner (owner0), then record the baseline.
        driveCommits(pool, driver, 0, 20);
        long baseline = g.monitorView().commitIndex();
        assertTrue(baseline > 0, "precondition: committed on the original owner");

        // REHOME 0: owner0 → owner1.
        driver.rehomeGroup(0, 1);
        assertEquals(1, driver.currentOwnerIndex(0), "after rehome, group 0 is owned by owner1");
        assertEquals(RaftRole.LEADER, g.role(), "rehome must preserve group state (still LEADER, no torn state)");

        // Keep COMMITTING on the NEW owner (owner1) — non-vacuous: commitIndex grows past the baseline.
        driveCommits(pool, driver, 0, 20);
        long afterRehome = g.monitorView().commitIndex();
        assertTrue(afterRehome > baseline,
                "group did not keep committing on the new owner (commitIndex " + baseline + " -> "
                        + afterRehome + ") — the rehome did not actually hand off live consensus");

        // Stale routing: a message dispatched to the OLD owner (owner0) must BOUNCE to owner1, not fire.
        pool.ownerByIndex(0).submit(() ->
                driver.routeMessage(0, new RequestVoteRequest(0L, PHANTOM, 0L, 0L, true))).get(5, TimeUnit.SECONDS);
        // Drain owner1 so any bounced task completed.
        pool.ownerByIndex(1).submit(() -> { }).get(5, TimeUnit.SECONDS);

        assertEquals(0, checker.ownerFires.get(),
                "clean rehome path must not fire raft_owner_thread — first: " + checker.firstViolation.get());

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void accessOnLosingOwnerAfterHandoff_trips() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        // Partial handoff: detach on the losing owner (owner0). ownerThread → HANDOFF.
        pool.ownerByIndex(0).submit(g::beginHandoff).get(5, TimeUnit.SECONDS);

        // INJECTED RACE (i): touch the group on the LOSING owner AFTER handoff — must trip.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pool.ownerByIndex(0).submit((Runnable) g::tick).get(5, TimeUnit.SECONDS),
                "access on the losing owner after beginHandoff must trip the net");
        assertOwnerThreadCause(ee);
        assertTrue(checker.ownerFires.get() >= 1, "the net must have fired");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void accessOnGainingOwnerBeforeAdopt_trips() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        // Detach on the losing owner; do NOT adopt yet.
        pool.ownerByIndex(0).submit(g::beginHandoff).get(5, TimeUnit.SECONDS);

        // INJECTED RACE (ii): touch the group on the GAINING owner (owner1) BEFORE adopt — must trip.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pool.ownerByIndex(1).submit((Runnable) g::tick).get(5, TimeUnit.SECONDS),
                "access on the gaining owner before adoptOwnerThread must trip the net");
        assertOwnerThreadCause(ee);
        assertTrue(checker.ownerFires.get() >= 1, "the net must have fired");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void afterRehome_oldOwnerLockedOut_newOwnerOwns() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        driver.rehomeGroup(0, 1); // owner0 → owner1, fully (detach + adopt)

        // NO DOUBLE-OWNERSHIP: the OLD owner (owner0) accessing the group must trip — it no longer owns it.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pool.ownerByIndex(0).submit((Runnable) g::tick).get(5, TimeUnit.SECONDS),
                "after rehome the OLD owner must be locked out (no double-ownership)");
        assertOwnerThreadCause(ee);
        long firesAfterOldOwner = checker.ownerFires.get();
        assertTrue(firesAfterOldOwner >= 1, "old-owner access must have fired");

        // CONTROL: the NEW owner (owner1) accessing the group does NOT fire.
        pool.ownerByIndex(1).submit((Runnable) g::tick).get(5, TimeUnit.SECONDS);
        assertEquals(firesAfterOldOwner, checker.ownerFires.get(),
                "the new owner must own the group — on-owner access must not fire");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void adoptOnNonMigratingNode_trips() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker); // bound to owner0, NOT mid-handoff
        driver.addGroup(0, g);

        // Adopting a node that is not mid-handoff (ownerThread != HANDOFF) must trip raft_owner_adopt.
        ExecutionException ee = assertThrows(ExecutionException.class,
                () -> pool.ownerByIndex(1).submit(g::adoptOwnerThread).get(5, TimeUnit.SECONDS),
                "adoptOwnerThread on a non-migrating node must trip");
        Throwable cause = ee.getCause();
        assertNotNull(cause);
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("raft_owner_adopt"),
                "expected raft_owner_adopt, got: " + cause.getMessage());

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static void assertOwnerThreadCause(ExecutionException ee) {
        Throwable cause = ee.getCause();
        assertNotNull(cause, "expected the tripwire AssertionError as the cause");
        assertTrue(cause.getMessage() != null && cause.getMessage().contains("raft_owner_thread"),
                "expected raft_owner_thread, got: " + cause.getMessage());
    }
}

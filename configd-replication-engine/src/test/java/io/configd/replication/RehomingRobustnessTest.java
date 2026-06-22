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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 — Workstream B — Stage 2 — M2b S1: ROBUSTNESS regressions for the two defects the adversarial
 * red-team found in the first S1 cut (commit {@code 3a44cf0}) and which the four-way fixes commit closed.
 * Each test ENCODES the contract that was broken; it goes RED on the pre-fix code and GREEN on the fix.
 *
 * <ul>
 *   <li><b>Finding 1 (P1) — {@link #interruptDuringHandoff_completesAtomically_reassertsInterrupt_notWedged()}:</b>
 *       a {@code Future.get()} interrupt does NOT cancel an already-submitted owner task, so the pre-fix
 *       interruptible barrier abandoned the wait while the queued publish/detach task ran later — wedging
 *       the group on the HANDOFF sentinel with both owners alive. Fix: the barriers are UNINTERRUPTIBLE
 *       (complete atomically, re-assert the interrupt afterward).</li>
 *   <li><b>Finding 2 (P2) — {@link #flushDispatch_onWedgedGroup_doesNotLivelock()}:</b> a dispatched flush
 *       on a group wedged on HANDOFF re-dispatched FOREVER (no real owner ever runs it). Fix:
 *       {@code runFlushOnCurrentOwner} only bounces a TRANSIENT mismatch (migrating, or a different REAL
 *       owner); a wedged ({@code isDetached}, not migrating) node falls through so flushDurable's guard
 *       FIRES once (loud) instead of spinning silently.</li>
 *   <li><b>{@link #quiesceThrowsMidRehome_leavesGroupCleanOnLosingOwner()}</b> — a confirmation (GREEN on
 *       both pre- and post-fix): quiesce runs BEFORE publish+detach, so an fsync failure there leaves the
 *       group clean on the losing owner (no override published, not detached).</li>
 * </ul>
 *
 * <p>Credit: the attack harness here is the red-team's, adapted to assert the fixed contract. The
 * mechanism is dormant in production; this is N&gt;1 test-only surface. See docs/phase0-B-stage2-m2b/.
 */
class RehomingRobustnessTest {

    private static final NodeId LOCAL = NodeId.of(1);

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

    private static void onOwner(OwnerExecutorPool pool, int ownerIndex, RunnableEx task) throws Exception {
        pool.ownerByIndex(ownerIndex).submit(() -> {
            try { task.run(); } catch (Exception e) { throw new RuntimeException(e); }
        }).get(5, TimeUnit.SECONDS);
    }

    @FunctionalInterface private interface RunnableEx { void run() throws Exception; }

    // =============================================================================================
    // FINDING 1 — interrupt during a handoff barrier must NOT wedge the group. The fix makes the
    // barriers uninterruptible: the handoff completes ATOMICALLY to the gaining owner (or rolls back),
    // and the interrupt is re-asserted to the coordinator afterward (never lost). We run the rehome on a
    // dedicated coordinator thread (the test thread must stay free to release the owner0 blocker; the
    // pre-fix interruptible version would instead throw + leave the group wedged, and a same-thread
    // harness would deadlock under the fix).
    // =============================================================================================
    @Test
    @Timeout(30)
    void interruptDuringHandoff_completesAtomically_reassertsInterrupt_notWedged() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker); // bound to owner0, LEADER
        driver.addGroup(0, g);

        // Occupy owner0 so the rehome's FIRST barrier task QUEUES behind a blocker.
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        pool.ownerByIndex(0).submit(() -> {
            blockerEntered.countDown();
            try { releaseBlocker.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS), "blocker must occupy owner0");

        AtomicReference<Throwable> rehomeError = new AtomicReference<>();
        AtomicBoolean interruptReasserted = new AtomicBoolean();
        CountDownLatch coordStarted = new CountDownLatch(1);
        Thread coordinator = new Thread(() -> {
            coordStarted.countDown();
            try {
                driver.rehomeGroup(0, 1);                                       // uninterruptible: completes
                interruptReasserted.set(Thread.currentThread().isInterrupted()); // deferred interrupt honoured
            } catch (Throwable t) {
                rehomeError.set(t);
            }
        }, "rehome-coordinator");
        coordinator.start();
        assertTrue(coordStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(150);          // let the coordinator reach the first barrier .get() (owner0 blocked)
        coordinator.interrupt();    // interrupt while it waits on that barrier
        Thread.sleep(50);
        releaseBlocker.countDown();  // owner0 runs the queued handoff task; the barrier completes
        coordinator.join(10_000);
        assertFalse(coordinator.isAlive(), "coordinator must finish (no deadlock under the uninterruptible fix)");

        // FIXED contract: the rehome completed ATOMICALLY to owner1, and the interrupt was re-asserted.
        assertNull(rehomeError.get(), "uninterruptible rehome must complete, not throw: " + rehomeError.get());
        assertTrue(interruptReasserted.get(), "the deferred interrupt must be re-asserted on the coordinator");
        assertEquals(1, driver.currentOwnerIndex(0), "rehome completed to owner1 despite the interrupt");

        // NOT wedged: the node is owned by exactly owner1 (both owners observing boundToAnotherThread would
        // mean it is on the HANDOFF sentinel — owned by nobody).
        boolean wedged = pool.ownerByIndex(0).submit(g::boundToAnotherThread).get(5, TimeUnit.SECONDS)
                && pool.ownerByIndex(1).submit(g::boundToAnotherThread).get(5, TimeUnit.SECONDS);
        assertFalse(wedged, "group must not be wedged on HANDOFF (owned by nobody) after the interrupt");
        onOwner(pool, 1, () -> driver.tickOwner(1)); // tick on the new owner — must not fire
        assertEquals(0, checker.ownerFires.get(),
                "the completed handoff must leave the group serviceable on owner1 — zero fires: "
                        + checker.firstViolation.get());

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // =============================================================================================
    // FINDING 2 — a dispatched flush on a HANDOFF-wedged group must NOT livelock. Pre-fix,
    // runFlushOnCurrentOwner re-dispatched forever (groupOwner override + boundToAnotherThread, never
    // migrating, no real owner). The fix runs the flush on a wedged node (its guard fires loud, single
    // shot) instead of spinning. We construct the wedge deterministically and prove the flush body runs
    // (lands) exactly once rather than being bounced indefinitely.
    // =============================================================================================
    @Test
    @Timeout(30)
    void flushDispatch_onWedgedGroup_doesNotLivelock() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);
        RaftNode g = buildLeaderBoundTo(pool, 0, checker);
        driver.addGroup(0, g);

        // Deterministic wedge: complete a real rehome 0->1, then detach on owner1 (ownerThread=HANDOFF)
        // WITHOUT adopting. Now groupOwner.containsKey(0)==true, node.boundToAnotherThread()==true (HANDOFF),
        // migrating empty, node.isDetached()==true — an abandoned-handoff terminal state.
        driver.rehomeGroup(0, 1);
        assertEquals(1, driver.currentOwnerIndex(0), "rehomed to owner1");
        onOwner(pool, 1, g::beginHandoff); // detach: ownerThread -> HANDOFF, never adopt

        // Drive the REAL production path. The flush body (a counter, not flushDurable, so no fire here)
        // must LAND once — the dispatch must terminate, not re-dispatch forever.
        AtomicInteger flushBodyRuns = new AtomicInteger();
        driver.dispatchFlush(0, flushBodyRuns::incrementAndGet, 0);

        // Give a livelock ample time to manifest (a healthy dispatch lands in microseconds), then assert
        // it landed exactly once and is not still re-dispatching.
        Thread.sleep(300);
        int landed = flushBodyRuns.get();
        Thread.sleep(200);
        assertEquals(landed, flushBodyRuns.get(), "the dispatch must have terminated (no live re-dispatch loop)");
        assertEquals(1, landed,
                "the dispatched flush on a wedged group must LAND exactly once (not livelock, not run repeatedly)");

        // owner1 must be idle/responsive (not pegged by a self-replenishing queue).
        AtomicInteger probe = new AtomicInteger();
        pool.ownerByIndex(1).submit(probe::incrementAndGet).get(5, TimeUnit.SECONDS);
        assertEquals(1, probe.get(), "owner1 must service new work promptly (not livelocked)");

        onOwner(pool, 1, g::adoptOwnerThread); // clear the wedge for a clean shutdown
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // =============================================================================================
    // CONFIRMATION (GREEN pre- and post-fix) — if QUIESCE throws (fsync error) the group is left CLEAN:
    // quiesce runs BEFORE publish+detach, so a throw leaves groupOwner unwritten and ownerThread on owner0.
    // =============================================================================================
    @Test
    @Timeout(30)
    void quiesceThrowsMidRehome_leavesGroupCleanOnLosingOwner() throws Exception {
        OwnerExecutorPool pool = new OwnerExecutorPool(2);
        CountingThrowingChecker checker = new CountingThrowingChecker();
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        AtomicReference<RuntimeException> syncFault = new AtomicReference<>();
        Storage storage = new FaultingStorage(Storage.inMemory(), syncFault);
        RaftNode g = new RaftNode(RaftConfig.of(LOCAL, java.util.Set.of()), new RaftLog(storage),
                new NoopTransport(), new NoopStateMachine(), new java.util.Random(7L), storage, checker);
        pool.ownerByIndex(0).submit(() -> {
            g.bindOwnerThread();
            for (int i = 0; i < 400; i++) g.tick();
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, g.role());
        driver.addGroup(0, g);

        // Buffer an entry so quiesce has something to sync, then arm the fsync fault.
        onOwner(pool, 0, () -> g.setGroupCommit((flush, d) -> { /* park */ }, 4096, 0));
        onOwner(pool, 0, () -> driver.propose(0, "x".getBytes()));
        syncFault.set(new RuntimeException("induced fsync failure"));

        // Rehome 0->1: quiesce throws on owner0 BEFORE publish+detach → surfaced as IllegalStateException.
        assertThrows(IllegalStateException.class, () -> driver.rehomeGroup(0, 1),
                "rehome must surface the quiesce failure");

        // CLEAN: still routed to owner0 (no override published), owner0 still owns it (not the HANDOFF sentinel).
        assertEquals(0, driver.currentOwnerIndex(0), "no override should have been published (quiesce failed first)");
        boolean offOwner0 = pool.ownerByIndex(0).submit(g::boundToAnotherThread).get(5, TimeUnit.SECONDS);
        assertFalse(offOwner0, "owner0 must still own the group (not detached to HANDOFF)");

        // Disarm; the group keeps working on owner0 with zero net fires.
        syncFault.set(null);
        onOwner(pool, 0, () -> { driver.propose(0, "y".getBytes()); driver.tickOwner(0); });
        assertEquals(0, checker.ownerFires.get(),
                "the failed-quiesce path runs entirely on owner0 — zero fires: " + checker.firstViolation.get());

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    /** Delegating Storage whose {@code syncLog} throws when the fault is armed. */
    private static final class FaultingStorage implements Storage {
        private final Storage delegate;
        private final AtomicReference<RuntimeException> fault;
        FaultingStorage(Storage delegate, AtomicReference<RuntimeException> fault) {
            this.delegate = delegate; this.fault = fault;
        }
        @Override public void put(String key, byte[] value) { delegate.put(key, value); }
        @Override public byte[] get(String key) { return delegate.get(key); }
        @Override public void appendToLog(String logName, byte[] data) { delegate.appendToLog(logName, data); }
        @Override public java.util.List<byte[]> readLog(String logName) { return delegate.readLog(logName); }
        @Override public void truncateLog(String logName) { delegate.truncateLog(logName); }
        @Override public void renameLog(String from, String to) { delegate.renameLog(from, to); }
        @Override public void sync() { delegate.sync(); }
        @Override public void appendToLogNoSync(String logName, byte[] data) { delegate.appendToLogNoSync(logName, data); }
        @Override public void syncLog(String logName) {
            RuntimeException f = fault.get();
            if (f != null) throw f;
            delegate.syncLog(logName);
        }
    }
}

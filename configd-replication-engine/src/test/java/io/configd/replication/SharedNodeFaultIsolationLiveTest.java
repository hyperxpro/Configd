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
import io.configd.raft.StateMachine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-Raft Phase 1 — Seam G2: the LIVE shared-node isolation sim (C3a / SF1 mandate). Runs N groups on
 * a P&lt;N owner pool (the production shape — groups SHARE owner threads) on the REAL
 * {@link MultiRaftDriver} + {@link OwnerExecutorPool}, and proves the isolation the independent-harness V
 * sim could not: a real coupling leak is INJECTED and a per-group liveness witness goes RED, while a
 * non-thread-blocking fault leaves siblings unharmed.
 *
 * <p>The {@code OwnerIsolationMultiOwnerTest} already proves the <em>missed-hop</em> isolation class (a
 * group's entry point run on the wrong owner trips {@code assertOwnerThread}). This test adds the
 * <em>starvation</em> class, which {@code assertOwnerThread} CANNOT catch (no thread violation — the owner
 * is simply blocked) and which therefore needs an explicit per-group LIVENESS witness:
 *
 * <ul>
 *   <li><b>{@link #couplingLeakRed_stuckApplyStarvesCoOwnedSibling_otherOwnerUnaffected_thenRecovers()}</b>
 *       — a STUCK apply on group 0 blocks owner0's single thread; its co-owned sibling group 2 is STARVED
 *       (witness RED — the genuinely non-vacuous coupling-leak proof at the shared-node fidelity), while
 *       groups on owner1 keep committing (cross-owner isolation GREEN — the fault is owner-confined, not
 *       node-wide). Releasing the apply RECOVERS group 0 and group 2 (the stall was transient, not
 *       corruption).</li>
 *   <li><b>{@link #perShardSafetyHoldsUnderSharedOwnerConcurrency()}</b> — under a concurrent multi-owner
 *       workload on shared owners, each group's applied-mutation sequence is strictly monotone and contains
 *       ONLY its own shard's commands (S2/S4 per-shard safety + isolation; no cross-shard leak, no
 *       corruption) even while a sibling's apply is transiently slow.</li>
 * </ul>
 *
 * <p>Production stays single-group (N&gt;1 is boot-refused until Seam G4); this multi-group fault surface
 * is the proof that N&gt;1 is isolation-safe BEFORE the guard is lifted. See
 * {@code docs/multiraft/phase1/c3-multigroup-wiring.md} (SF1) and {@code seam-g2-live-isolation.md}.
 */
class SharedNodeFaultIsolationLiveTest {

    private static final NodeId LOCAL = NodeId.of(1);

    /** No peers — single-node groups self-elect; transport is unused. */
    private static final class NoopTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    /**
     * A real-ish state machine that (a) records the per-shard applied-mutation sequence so per-shard
     * monotonicity + isolation can be asserted, and (b) can be ARMED to block its mutating apply on a
     * latch — the "stuck/slow apply" fault that, running on the owner thread, starves co-owned siblings.
     * The first byte of every command is the owning gid; a cross-shard leak would surface as a foreign gid.
     */
    private static final class BlockableTrackingStateMachine implements StateMachine {
        private final int gid;
        final List<Long> appliedSeqs = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong mutatingCount = new AtomicLong();
        volatile long lastAppliedIndex;
        /** When non-null, a mutating apply blocks here (the stuck-apply fault). */
        volatile CountDownLatch gate;
        volatile boolean sawForeignGid;

        BlockableTrackingStateMachine(int gid) {
            this.gid = gid;
        }

        @Override
        public long apply(long index, long term, byte[] command) {
            if (command == null || command.length == 0) {
                return StateMachine.NON_MUTATING; // leader no-op / election entry — never blocks
            }
            if ((command[0] & 0xFF) != (gid & 0xFF)) {
                sawForeignGid = true; // cross-shard leak — group applied another shard's command
            }
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(); // BLOCK the owner thread (the injected coupling leak)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long seq = mutatingCount.incrementAndGet();
            appliedSeqs.add(seq);
            lastAppliedIndex = index;
            return seq;
        }

        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    private static byte[] cmd(int gid, int n) {
        return new byte[]{(byte) gid, (byte) (n & 0xFF), (byte) (n >>> 8)};
    }

    /** Builds a storage-backed single-node group with the tracking SM, owner-binds it (H-6), self-elects. */
    private static RaftNode newTrackingLeader(OwnerExecutorPool pool, int gid,
            BlockableTrackingStateMachine sm) throws Exception {
        Storage storage = Storage.inMemory();
        RaftConfig config = RaftConfig.of(LOCAL, Set.of()); // single-node cluster self-elects
        RaftNode node = new RaftNode(config, new RaftLog(storage), new NoopTransport(),
                sm, new java.util.Random(42L + gid), storage);
        pool.ownerExecutor(gid).submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 400; i++) node.tick(); // self-elect (single-node)
        }).get(10, TimeUnit.SECONDS);
        assertEquals(RaftRole.LEADER, node.role(), "group " + gid + " should self-elect to LEADER");
        return node;
    }

    /**
     * The per-group LIVENESS WITNESS. Submits a propose + repeated ticks for {@code gid} onto its owner
     * (fire-and-forget, so a STUCK owner cannot block the witness thread) and polls the shard's applied
     * count. Returns true iff the shard makes apply progress within the budget. A stuck owner ⇒ the tasks
     * queue and never run ⇒ no progress ⇒ false (RED). A free owner ⇒ progress ⇒ true (GREEN).
     */
    private static boolean witnessProgresses(MultiRaftDriver driver, OwnerExecutorPool pool,
            BlockableTrackingStateMachine sm, int gid, int n, long budgetMs) throws Exception {
        int owner = pool.ownerIndexOf(gid);
        long before = sm.appliedSeqs.size();
        pool.ownerExecutor(gid).execute(() -> driver.propose(gid, cmd(gid, n)));
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            pool.ownerExecutor(gid).execute(() -> driver.tickOwner(owner));
            if (sm.appliedSeqs.size() > before) {
                return true;
            }
            Thread.sleep(20);
        }
        return sm.appliedSeqs.size() > before;
    }

    @Test
    @Timeout(60)
    void couplingLeakRed_stuckApplyStarvesCoOwnedSibling_otherOwnerUnaffected_thenRecovers()
            throws Exception {
        final int p = 2;                 // owner0 = {0,2}, owner1 = {1,3}
        final int[] gids = {0, 1, 2, 3}; // P<N: groups 0,2 SHARE owner0; 1,3 SHARE owner1
        OwnerExecutorPool pool = new OwnerExecutorPool(p);
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        Map<Integer, BlockableTrackingStateMachine> sms = new LinkedHashMap<>();
        for (int gid : gids) {
            BlockableTrackingStateMachine sm = new BlockableTrackingStateMachine(gid);
            RaftNode node = newTrackingLeader(pool, gid, sm);
            driver.addGroup(gid, node);
            sms.put(gid, sm);
        }
        // BASELINE: every group is live before the fault (witness GREEN for all four).
        for (int gid : gids) {
            assertTrue(witnessProgresses(driver, pool, sms.get(gid), gid, 1, 5_000),
                    "baseline: group " + gid + " must make apply progress before the fault");
        }

        // INJECT the coupling leak: arm group 0's apply to block, then drive a mutating apply on owner0.
        // On the single-node INLINE group-commit path, driver.propose(0,...) itself runs append -> flush
        // -> advanceCommit -> applyCommitted -> stateMachine.apply ON owner0's single thread, so the
        // BLOCK happens inside propose() here (the trailing tickOwner(0) never gets to run — owner0 is
        // already stuck). The effect is owner0 STUCK in group 0's apply; fire-and-forget so the test
        // thread is not blocked.
        CountDownLatch stuck = new CountDownLatch(1);
        sms.get(0).gate = stuck;
        pool.ownerExecutor(0).execute(() -> {
            driver.propose(0, cmd(0, 99)); // blocks inside group 0's apply on `stuck` (inline flush path)
            driver.tickOwner(0);           // queued behind the block; never reached until release
        });
        // Give owner0 a moment to enter the blocking apply.
        Thread.sleep(200);

        // WITNESS RED (the SF1 mandate): group 2 SHARES owner0 with the stuck group 0, so it is STARVED —
        // its propose/tick tasks queue behind the blocked apply and never run. No assertOwnerThread fire
        // could catch this (no thread violation); only a liveness witness can.
        assertFalse(witnessProgresses(driver, pool, sms.get(2), 2, 1, 1_500),
                "COUPLING LEAK NOT DETECTED: co-owned sibling group 2 made progress while its owner thread "
                        + "was stuck in group 0's apply — the starvation witness is vacuous");

        // CROSS-OWNER ISOLATION GREEN: groups 1 and 3 are on owner1 (a DIFFERENT thread), so the fault is
        // owner-confined — they keep committing. This is the property that makes shared-node co-tenancy
        // safe: a stuck owner stalls only ITS groups, never the whole node.
        for (int gid : new int[]{1, 3}) {
            assertTrue(witnessProgresses(driver, pool, sms.get(gid), gid, 2, 5_000),
                    "cross-owner isolation: group " + gid + " (owner1) must stay live while owner0 is stuck");
        }

        // RECOVER: release the stuck apply. owner0 drains the queued work; group 0 and the starved group 2
        // both resume — proving the stall was transient back-pressure, not corruption or deadlock.
        sms.get(0).gate = null;
        stuck.countDown();
        assertTrue(witnessProgresses(driver, pool, sms.get(0), 0, 100, 5_000),
                "recovery: group 0 must resume after the stuck apply is released");
        assertTrue(witnessProgresses(driver, pool, sms.get(2), 2, 100, 5_000),
                "recovery: starved sibling group 2 must resume after owner0 is unblocked");

        // Per-shard SAFETY survived the fault: every shard applied a strictly monotone sequence and never
        // a foreign shard's command (no cross-shard leak, no corruption).
        for (int gid : gids) {
            assertMonotoneAndIsolated(sms.get(gid), gid);
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "owner pool did not terminate");
    }

    @Test
    @Timeout(60)
    void perShardSafetyHoldsUnderSharedOwnerConcurrency() throws Exception {
        final int p = 2;                 // owner0 = {0,2}, owner1 = {1,3}
        final int[] gids = {0, 1, 2, 3};
        OwnerExecutorPool pool = new OwnerExecutorPool(p);
        MultiRaftDriver driver = new MultiRaftDriver(LOCAL, Clock.system());
        driver.setOwnerPool(pool);

        Map<Integer, BlockableTrackingStateMachine> sms = new LinkedHashMap<>();
        for (int gid : gids) {
            BlockableTrackingStateMachine sm = new BlockableTrackingStateMachine(gid);
            RaftNode node = newTrackingLeader(pool, gid, sm);
            driver.addGroup(gid, node);
            sms.put(gid, sm);
        }

        // Drive a concurrent workload: one producer per group, each proposing + ticking its OWN group on
        // the group's owner (so co-owned groups genuinely contend for one owner thread).
        final int writesPerGroup = 200;
        ExecutorService producers = Executors.newFixedThreadPool(gids.length);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(gids.length);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int gid : gids) {
            final int g = gid;
            producers.submit(() -> {
                try {
                    start.await();
                    int owner = pool.ownerIndexOf(g);
                    for (int i = 1; i <= writesPerGroup && failure.get() == null; i++) {
                        final int n = i;
                        pool.ownerExecutor(g).submit(() -> {
                            if (driver.propose(g, cmd(g, n)).result() == ProposalResult.ACCEPTED) {
                                driver.tickOwner(owner); // commit + apply on the owner thread
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
        start.countDown();
        assertTrue(done.await(50, TimeUnit.SECONDS), "concurrent shared-owner workload did not finish");
        producers.shutdownNow();

        // Final drain so every accepted proposal applies.
        for (int owner = 0; owner < p; owner++) {
            final int o = owner;
            for (int i = 0; i < 5; i++) {
                pool.ownerByIndex(o).submit(() -> driver.tickOwner(o)).get(5, TimeUnit.SECONDS);
            }
        }

        assertNull(failure.get(), "no producer threw under shared-owner concurrency");
        // S2/S4 per shard: strictly monotone applied sequence, only this shard's commands, real progress.
        for (int gid : gids) {
            BlockableTrackingStateMachine sm = sms.get(gid);
            assertMonotoneAndIsolated(sm, gid);
            assertTrue(sm.appliedSeqs.size() > 0, "group " + gid + " made no progress (vacuous)");
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "owner pool did not terminate");
    }

    /** S2/S4: the shard's applied-mutation sequence is strictly 1,2,3,… and it never applied a foreign gid. */
    private static void assertMonotoneAndIsolated(BlockableTrackingStateMachine sm, int gid) {
        assertFalse(sm.sawForeignGid, "group " + gid + " applied a FOREIGN shard's command (cross-shard leak)");
        List<Long> seqs;
        synchronized (sm.appliedSeqs) {
            seqs = new ArrayList<>(sm.appliedSeqs);
        }
        // Non-vacuity: a shard with no applied commands cannot witness safety (both callers also drive
        // progress, but guard the helper itself so it can never pass trivially).
        assertFalse(seqs.isEmpty(), "group " + gid + " applied nothing — safety assertion would be vacuous");
        for (int i = 0; i < seqs.size(); i++) {
            assertEquals(i + 1L, seqs.get(i),
                    "group " + gid + " per-shard sequence must be contiguous + monotone at position " + i);
        }
    }
}

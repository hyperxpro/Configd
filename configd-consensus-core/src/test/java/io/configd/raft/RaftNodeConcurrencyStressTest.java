package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent consensus stress harness, and proof that it actually catches an injected
 * off-owner-thread access. This is the verification machinery that must exist and be shown to
 * catch a race before the per-owner re-threading model can be trusted
 * (see {@code docs/architecture/raft-threading-contract.md}).
 *
 * <h2>What it models</h2>
 * The threading contract gives each group a single owner thread; every owner-only entry point of
 * its {@link RaftNode} must execute on that thread, enforced by the {@code assertOwnerThread()}
 * tripwire. Here a single-thread {@code owner} executor stands in for the future
 * {@code ownerExecutor(groupId)}; {@link RaftNode#bindOwnerThread()} binds it.
 *
 * <ul>
 *   <li><b>{@link #concurrentOwnerMarshalledAccessStaysGreen()}</b> - many producer threads marshal
 *       the guarded entry points (tick / propose / maybeCompact / readIndex / metrics /
 *       handleMessage) onto the owner, while a foreign "safe-rider" thread reads the volatile
 *       fields ({@code role()}, {@code leaderId()}) off-owner. The marshalled path and the volatile
 *       reads must not trip the tripwire or any in-node invariant; the riders that touch only
 *       volatile/own state are safe to run off-owner, and the harness proves it rather than just
 *       asserting it.</li>
 *   <li><b>{@link #offOwnerAccessTripsTheGuard_provesHarnessCatchesARace()}</b> - the
 *       "test the tester": owner-only entry points are called directly from a foreign thread (the
 *       injected race). Each must trip the {@code raft_owner_thread} tripwire before touching
 *       state. A harness that has not been shown to catch a real race is unproven.</li>
 * </ul>
 *
 * <p>Single-node by design: the threading contract is per-node, so one node is the right unit to
 * prove the tripwire and the marshalling discipline. Multi-node concurrent re-runs are a follow-up
 * for multi-node scenarios.
 */
class RaftNodeConcurrencyStressTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId PHANTOM = NodeId.of(2); // not in the single-node config; benign sender

    static final class NoOpTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    static final class NoOpStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    /**
     * Throws on ANY in-node safety-invariant violation OR the owner-thread tripwire - the same
     * throwing-checker discipline the deterministic sim uses (SimInvariants), so a violation is a
     * hard failure, not a swallowed metric.
     */
    static final class ThrowingChecker implements RaftNode.InvariantChecker {
        final AtomicReference<String> firstViolation = new AtomicReference<>();
        @Override public void check(String name, boolean condition, String message) {
            if (!condition) {
                firstViolation.compareAndSet(null, name + ": " + message);
                throw new AssertionError("INVARIANT '" + name + "' violated: " + message);
            }
        }
    }

    /** Constructs a single-node node, binds the owner thread, and drives it to LEADER - all on the owner. */
    private static RaftNode newSingleNodeLeaderBoundTo(ExecutorService owner, RaftNode.InvariantChecker checker)
            throws Exception {
        RaftConfig config = RaftConfig.of(N1, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(), new NoOpTransport(),
                new NoOpStateMachine(), new java.util.Random(42), Storage.inMemory(), checker);
        owner.submit(() -> {
            node.bindOwnerThread();                       // bind rule: first task on the owner executor
            for (int i = 0; i < 301; i++) node.tick();
            assertEquals(RaftRole.LEADER, node.role());
        }).get();
        return node;
    }

    @Test
    void concurrentOwnerMarshalledAccessStaysGreen() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "raft-owner"));
        ThrowingChecker checker = new ThrowingChecker();
        RaftNode node = newSingleNodeLeaderBoundTo(owner, checker);

        final int producers = 6;
        final int itersPerProducer = 1500;
        ExecutorService pool = Executors.newFixedThreadPool(producers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong proposeAccepted = new AtomicLong();

        for (int p = 0; p < producers; p++) {
            final int kind = p % 6;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < itersPerProducer && failure.get() == null; i++) {
                        // Marshal every OWNER-ONLY op onto the owner - the correct owner-thread model path.
                        // .get() surfaces any owner-thread exception (invariant or tripwire) here.
                        owner.submit(() -> {
                            switch (kind) {
                                case 0 -> node.tick();
                                case 1 -> {
                                    if (node.propose(new byte[]{1, 2, 3}).result() == ProposalResult.ACCEPTED) {
                                        proposeAccepted.incrementAndGet();
                                    }
                                }
                                case 2 -> node.maybeCompact(16);
                                case 3 -> node.readIndex();
                                case 4 -> node.metrics();
                                // benign PreVote from a phantom peer (term 0 < currentTerm - rejected, no step-down)
                                case 5 -> node.handleMessage(new RequestVoteRequest(0L, PHANTOM, 0L, 0L, true));
                                default -> { }
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

        // SAFE-RIDER (S-class): read the volatile fields from a FOREIGN thread, concurrently with the
        // owner mutating them. role()/leaderId() are volatile and UNGUARDED - this must never trip.
        pool.submit(() -> {
            try {
                start.await();
                while (done.getCount() > 0 && failure.get() == null) {
                    node.role();
                    node.leaderId();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "stress workload did not finish in time");
        pool.shutdownNow();
        owner.shutdown();
        assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS), "owner executor did not terminate");

        if (failure.get() != null) {
            throw new AssertionError("marshalled concurrent access violated an invariant or the owner-thread "
                    + "guard: " + checker.firstViolation.get(), failure.get());
        }
        assertNull(checker.firstViolation.get(),
                "no invariant/tripwire violation expected on the correctly-marshalled path");
        // Vacuity defence ("test the tester"): prove the workload actually did consensus work.
        assertTrue(proposeAccepted.get() > 0, "workload was vacuous — no proposals were accepted/committed");
    }

    @Test
    void offOwnerAccessTripsTheGuard_provesHarnessCatchesARace() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "raft-owner"));
        try {
            ThrowingChecker checker = new ThrowingChecker();
            RaftNode node = newSingleNodeLeaderBoundTo(owner, checker);

            // The injected race: invoke all 14 guarded owner-only entry points directly from this
            // (foreign) thread. assertOwnerThread() is the first statement of each, so the tripwire
            // fires before any state is touched - a deterministic catch, not a hoped-for corruption.
            // Covering every guarded entry point proves each guard actually fires, not merely that
            // it is present: the core 7 (tick / handleMessage / propose / maybeCompact / readIndex /
            // whenCommitOutcome / metrics) plus the 7 mutators re-threading would touch
            // (transferLeadership / triggerSnapshot / isReadReady / completeRead / whenReadReady /
            // cancelCommitOutcome / proposeConfigChange) - the complete mutator/callback entry-point
            // surface of the threading contract.
            List<Runnable> offOwnerCalls = List.<Runnable>of(
                    () -> node.tick(),
                    () -> node.handleMessage(new RequestVoteRequest(0L, PHANTOM, 0L, 0L, true)),
                    () -> node.propose(new byte[]{1}),
                    () -> node.maybeCompact(16),
                    () -> node.readIndex(),
                    () -> node.whenCommitOutcome(1L, 1L, o -> { }),
                    () -> node.metrics(),
                    () -> node.transferLeadership(PHANTOM),
                    () -> node.triggerSnapshot(),
                    () -> node.isReadReady(1L),
                    () -> node.completeRead(1L),
                    () -> node.whenReadReady(1L, () -> { }),
                    () -> node.cancelCommitOutcome(1L),
                    () -> node.proposeConfigChange(Set.of(N1))
            );
            for (Runnable offOwner : offOwnerCalls) {
                AssertionError err = assertThrows(AssertionError.class, offOwner::run,
                        "an OWNER-ONLY entry point invoked off-owner must trip the tripwire");
                assertTrue(err.getMessage().contains("raft_owner_thread"),
                        "expected the owner-thread tripwire, got: " + err.getMessage());
            }
            assertNotNull(checker.firstViolation.get(), "the tripwire must have recorded a violation");
            assertTrue(checker.firstViolation.get().startsWith("raft_owner_thread"),
                    "first violation should be the owner-thread tripwire, was: " + checker.firstViolation.get());
        } finally {
            owner.shutdownNow();
        }
    }
}

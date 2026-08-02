package io.configd.raft;

import io.configd.common.NodeId;
import io.configd.common.Storage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftMonitorViewConcurrencyTest {

    private static final NodeId N1 = NodeId.of(1);

    static final class NoOpTransport implements RaftTransport {
        @Override public void send(NodeId target, RaftMessage message) { }
    }

    static final class NoOpStateMachine implements StateMachine {
        @Override public long apply(long index, long term, byte[] command) { return StateMachine.NON_MUTATING; }
        @Override public byte[] snapshot() { return new byte[0]; }
        @Override public void restoreSnapshot(byte[] snapshot) { }
    }

    static final class ThrowingChecker implements RaftNode.InvariantChecker {
        final AtomicReference<String> firstViolation = new AtomicReference<>();
        @Override public void check(String name, boolean condition, String message) {
            if (!condition) {
                firstViolation.compareAndSet(null, name + ": " + message);
                throw new AssertionError("INVARIANT '" + name + "' violated: " + message);
            }
        }
    }

    private static RaftNode newSingleNodeLeaderBoundTo(ExecutorService owner, RaftNode.InvariantChecker checker)
            throws Exception {
        RaftConfig config = RaftConfig.of(N1, Set.of());
        RaftNode node = new RaftNode(config, new RaftLog(), new NoOpTransport(),
                new NoOpStateMachine(), new java.util.Random(42), Storage.inMemory(), checker);
        owner.submit(() -> {
            node.bindOwnerThread();
            for (int i = 0; i < 301; i++) node.tick();
            assertEquals(RaftRole.LEADER, node.role());
        }).get();
        return node;
    }

    @Test
    void monitorViewIsCoherentAndNeverBlocksUnderConcurrentPublish() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "raft-owner"));
        ThrowingChecker checker = new ThrowingChecker();
        RaftNode node = newSingleNodeLeaderBoundTo(owner, checker);

        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong observed = new AtomicLong();
        final AtomicLong distinctCommits = new AtomicLong();

        Thread reader = new Thread(() -> {
            RaftMetrics prev = null;
            long lastCommit = -1;
            try {
                while (running.get() && failure.get() == null) {
                    RaftMetrics v = node.monitorView();
                    assertNotNull(v, "monitorView() must never be null (seeded in the constructor)");
                    assertNotNull(v.role(), "snapshot role must be non-null");
                    assertEquals(N1, v.nodeId(), "snapshot nodeId must be stable");
                    // Structural coherence of a single immutable snapshot - can only fail on a torn read.
                    assertTrue(v.snapshotIndex() <= v.lastApplied(),
                            "snapshotIndex>" + v.snapshotIndex() + " > lastApplied=" + v.lastApplied());
                    assertTrue(v.lastApplied() <= v.commitIndex(),
                            "lastApplied=" + v.lastApplied() + " > commitIndex=" + v.commitIndex());
                    assertTrue(v.commitIndex() <= v.lastLogIndex(),
                            "commitIndex=" + v.commitIndex() + " > lastLogIndex=" + v.lastLogIndex());
                    if (prev != null) {
                        assertTrue(v.currentTerm() >= prev.currentTerm(),
                                "currentTerm regressed " + prev.currentTerm() + " -> " + v.currentTerm());
                        assertTrue(v.commitIndex() >= prev.commitIndex(),
                                "commitIndex regressed " + prev.commitIndex() + " -> " + v.commitIndex());
                    }
                    if (v.commitIndex() != lastCommit) {
                        lastCommit = v.commitIndex();
                        distinctCommits.incrementAndGet();
                    }
                    prev = v;
                    observed.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "monitor-reader");
        reader.start();

        // OWNER: advance consensus state and republish the view every tick (publishMonitorView() at end of tick()).
        final int cycles = 4000;
        owner.submit(() -> {
            for (int i = 0; i < cycles && failure.get() == null; i++) {
                node.propose(new byte[]{(byte) i, (byte) (i >>> 8)});
                node.tick();
            }
        }).get();

        running.set(false);
        reader.join(TimeUnit.SECONDS.toMillis(30));

        owner.shutdown();
        assertTrue(owner.awaitTermination(10, TimeUnit.SECONDS), "owner executor did not terminate");

        if (failure.get() != null) {
            throw new AssertionError("monitorView() observed an incoherent/torn snapshot or threw off-owner: "
                    + (checker.firstViolation.get() == null ? "" : checker.firstViolation.get()), failure.get());
        }
        assertNull(checker.firstViolation.get(), "no tripwire/invariant should fire on the monitorView() path");
        // Non-vacuity: the owner did real work AND the reader actually raced concurrent publication.
        assertTrue(node.monitorView().commitIndex() > 0, "vacuous: commitIndex never advanced (no consensus work)");
        assertTrue(observed.get() > 1000, "vacuous: foreign reader barely sampled (" + observed.get() + ")");
        assertTrue(distinctCommits.get() > 1, "vacuous: reader did not observe the view changing under it ("
                + distinctCommits.get() + " distinct commit values)");
    }

    @Test
    void h3AccessorsTripOffOwnerWhileMonitorViewAndSSetStaySafe() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor(r -> new Thread(r, "raft-owner"));
        try {
            ThrowingChecker checker = new ThrowingChecker();
            RaftNode node = newSingleNodeLeaderBoundTo(owner, checker);

            assertTripsOffOwner(() -> node.currentTerm(), "currentTerm");
            assertTripsOffOwner(() -> node.votedFor(), "votedFor");
            assertTripsOffOwner(() -> node.log(), "log");
            assertTripsOffOwner(() -> node.transferTarget(), "transferTarget");
            assertTripsOffOwner(() -> node.clusterConfig(), "clusterConfig");

            assertNotNull(node.monitorView(), "monitorView() is the safe cross-thread path — must not trip");
            assertNotNull(node.role(), "role() is S-class volatile — must not trip");
            node.leaderId();
            assertEquals(N1, node.nodeId(), "nodeId() is immutable — must not trip");

            assertTrue(checker.firstViolation.get() != null
                            && checker.firstViolation.get().startsWith("raft_owner_thread"),
                    "the off-owner accessor reads must have tripped raft_owner_thread; was: "
                            + checker.firstViolation.get());
        } finally {
            owner.shutdownNow();
        }
    }

    /**
     * Test-durability guard. The no-tear proof rests on TWO structural
     * facts the concurrency/jcstress proofs cannot themselves protect (a single owner builds each
     * snapshot atomically, so they pass even if these regress): {@code monitorView} is a {@code volatile}
     * reference, and {@link RaftMetrics} is a deeply-immutable carrier. These assertions fail the instant
     * either is lost - e.g. a refactor that drops {@code volatile} (an off-owner reader could then miss
     * the publication) or adds a mutable/aliasable field to the snapshot (a monitor could observe
     * post-publish mutation).
     */
    @Test
    void monitorViewFieldStaysVolatile_elseTheNoTearProofIsVoid() throws Exception {
        Field f = RaftNode.class.getDeclaredField("monitorView");
        assertTrue(Modifier.isVolatile(f.getModifiers()),
                "RaftNode.monitorView MUST stay volatile — without it an off-owner reader can miss the "
                        + "publication and the H-3 visibility/no-tear proof is void.");
        assertEquals(RaftMetrics.class, f.getType(),
                "monitorView must publish the immutable RaftMetrics snapshot");
    }

    @Test
    void raftMetricsStaysDeeplyImmutable_elseAPublishedSnapshotCanMutate() {
        assertTrue(RaftMetrics.class.isRecord(), "RaftMetrics must stay a record (immutable carrier)");
        for (RecordComponent rc : RaftMetrics.class.getRecordComponents()) {
            Class<?> t = rc.getType();
            boolean immutable = t.isPrimitive() || t.isEnum() || t == String.class || t == NodeId.class;
            assertTrue(immutable, "RaftMetrics component '" + rc.getName() + "' is of type " + t.getName()
                    + " — a mutable/aliasable type in the published snapshot would let a monitor observe "
                    + "post-publish mutation (an H-3 tear). Only add genuinely-immutable types to the allowlist.");
        }
    }

    private static void assertTripsOffOwner(Runnable offOwnerRead, String which) {
        AssertionError err = assertThrows(AssertionError.class, offOwnerRead::run,
                "H-3 accessor " + which + "() read off-owner must trip the owner-thread tripwire");
        assertTrue(err.getMessage().contains("raft_owner_thread"),
                "expected raft_owner_thread from " + which + "(), got: " + err.getMessage());
    }
}

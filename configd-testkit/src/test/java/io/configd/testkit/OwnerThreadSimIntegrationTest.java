package io.configd.testkit;

import io.configd.raft.RaftNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner-thread tripwire integrated into the deterministic
 * simulation. The macro stress harness ({@code RaftNodeConcurrencyStressTest}) and the jcstress
 * micro-race prove the guard on a single node in isolation; this proves it on the <em>full sim
 * surface</em> - many nodes driven through a randomized schedule with the tripwire wired into the
 * same throwing {@link RaftNode.InvariantChecker} that already carries the in-node safety invariants.
 *
 * <p>Two halves, mirroring the macro harness:
 * <ul>
 *   <li><b>{@link #boundSimRunsGreenAndNonVacuous()}</b> - with owners bound to the single drive
 *       thread, a real cluster elects a leader and commits (non-vacuous) while {@code
 *       raft_owner_thread} is continuously asserted alongside the in-node checks, and nothing trips.
 *       This is the "the correctly-marshalled path stays clean" half: binding does not cause a
 *       spurious fire on the legitimate single-thread access pattern.</li>
 *   <li><b>{@link #offDriveThreadAccessFailsTheSeed()}</b> - the injected race: once the sim has
 *       bound its owners, a {@code RaftNode} touched from a FOREIGN thread trips the tripwire, which
 *       the sim's throwing checker turns into a hard failure. This proves the sim's net actually
 *       catches an off-owner access; a net never shown to catch one is unproven.</li>
 * </ul>
 */
class OwnerThreadSimIntegrationTest {

    private static RaftNode.InvariantChecker throwingChecker(AtomicInteger fireCount) {
        return (name, condition, message) -> {
            if (!condition) {
                fireCount.incrementAndGet();
                throw new AssertionError(name + ": " + message);
            }
        };
    }

    @Test
    void boundSimRunsGreenAndNonVacuous() {
        AtomicInteger fires = new AtomicInteger();
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(20260621L, 3, throwingChecker(fires));

        // Driving the cluster binds each node's owner to THIS (the drive) thread on the first tick,
        // then exercises tick / handleMessage / propose entirely on it - the correct owner-thread path.
        int leader = cluster.electLeader(5_000);
        assertTrue(leader >= 0, "expected a stable leader (non-vacuous: the bound sim made progress)");

        long version = cluster.proposeAndCommit(leader, "k", "v", 5_000);
        assertTrue(version > 0, "expected a committed proposal (non-vacuous consensus work)");

        assertTrue(fires.get() == 0, "owner-thread tripwire (or an in-node invariant) fired on the correct path");
    }

    @Test
    void offDriveThreadAccessFailsTheSeed() throws Exception {
        AtomicInteger fires = new AtomicInteger();
        ConsistencyPropertyTests.ClusterHarness cluster =
                new ConsistencyPropertyTests.ClusterHarness(20260621L, 3, throwingChecker(fires));

        int leader = cluster.electLeader(5_000);
        assertTrue(leader >= 0, "precondition: a leader was elected (owners now bound to the drive thread)");

        // THE INJECTED RACE: touch a node from a FOREIGN thread. The owner is the drive thread, so
        // assertOwnerThread() fires before any state is touched and the throwing checker fails it.
        ExecutorService foreign = Executors.newSingleThreadExecutor(r -> new Thread(r, "foreign-not-owner"));
        try {
            Future<?> f = foreign.submit(() -> cluster.node(0).tick());
            ExecutionException ee = assertThrows(ExecutionException.class, f::get,
                    "an off-drive-thread RaftNode access must trip the owner-thread tripwire");
            Throwable cause = ee.getCause();
            assertNotNull(cause, "expected the tripwire AssertionError as the cause");
            assertTrue(cause.getMessage() != null && cause.getMessage().contains("raft_owner_thread"),
                    "expected the raft_owner_thread tripwire, got: " + cause.getMessage());
            assertTrue(fires.get() >= 1, "the sim's throwing checker must have recorded the violation");
        } finally {
            foreign.shutdownNow();
            foreign.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}

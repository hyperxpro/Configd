package io.configd.server;

import io.configd.api.ConfigWriteService;
import io.configd.common.Clock;
import io.configd.common.ConfigScope;
import io.configd.common.NodeId;
import io.configd.observability.ConfigdMetrics;
import io.configd.observability.MetricsRegistry;
import io.configd.replication.MultiRaftDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 (Class-B): write-admission control is ON by default at a conservative tuned value, so the store
 * protects itself from a write flood out of the box. Two proofs:
 * <ol>
 *   <li>the default is on (a positive, tuned cap), and</li>
 *   <li>with the DEFAULT config (no {@code -D} override) a full cap of concurrent in-flight proposals is
 *       admitted WITHOUT shedding, and one more beyond the cap is shed as {@code Overloaded} (HTTP 429).</li>
 * </ol>
 * "Normal load unaffected" is also proven for free by the rest of the reactor: every existing write-path
 * test runs under this default cap and stays green (no legitimate write sheds - the cap is ~1000x the
 * measured steady in-flight count).
 *
 * <p>The mechanism is driven through the real {@link ConfigdServer#raftProposer} seam with a holding
 * executor: it accepts (never runs) each admitted proposal task, so the proposal's outcome future never
 * completes and the HTTP-thread permit is held for the whole (long) commit deadline - a deterministic way
 * to keep the cap full without a live cluster. The permits are released by interrupting the blocked
 * occupier threads at teardown.
 */
@Timeout(60)
class WriteAdmissionDefaultTest {

    private static final String OVERRIDE = "configd.write.maxInflightProposals";

    @Test
    void admissionIsOnByDefault() {
        assertTrue(ConfigdServer.DEFAULT_MAX_INFLIGHT_PROPOSALS > 0,
                "write admission must be ON by default (a positive in-flight cap), not 0/off");
    }

    @Test
    void defaultCapAdmitsAFullCapThenShedsBeyondIt() throws Exception {
        String saved = System.getProperty(OVERRIDE);
        System.clearProperty(OVERRIDE); // exercise the DEFAULT cap, not an override

        final int cap = ConfigdServer.DEFAULT_MAX_INFLIGHT_PROPOSALS;
        // Signalled once per ADMITTED proposal (the holding executor receives the task only after a permit
        // is acquired). Reaching zero means all `cap` permits are held.
        CountDownLatch admitted = new CountDownLatch(cap);
        Executor holding = task -> admitted.countDown(); // acquire signalled; task DROPPED -> future never completes
        // Never actually invoked: the holding executor drops the task, so propose()/getGroup() are unreached.
        MultiRaftDriver driver = new MultiRaftDriver(NodeId.of(0), Clock.system());
        ConfigdMetrics metrics = new ConfigdMetrics(new MetricsRegistry(), () -> 0L);
        // Long deadline so no held permit is released via timeout during the test window.
        long longCommitTimeoutMs = 60_000L;
        ConfigWriteService.RaftProposer proposer =
                ConfigdServer.raftProposer(driver, 0, holding, longCommitTimeoutMs, metrics);

        ExecutorService occupiers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // Fill the cap: exactly `cap` concurrent writes, each acquires a permit and then blocks on its
            // outcome future (the task was dropped). None of these should shed - there are exactly `cap`
            // permits - so all `cap` reach the executor and count down.
            for (int i = 0; i < cap; i++) {
                occupiers.submit(() ->
                        proposer.propose(ConfigScope.GLOBAL, List.of("k"), new byte[]{1}));
            }
            assertTrue(admitted.await(30, TimeUnit.SECONDS),
                    "a full cap (" + cap + ") of concurrent writes must be admitted WITHOUT shedding");

            // The cap is now full and no permit is released (tasks dropped, long deadline). The next write
            // must shed as Overloaded (-> HTTP 429) on the calling thread, before any executor task.
            ConfigWriteService.ProposeCommitResult shed =
                    proposer.propose(ConfigScope.GLOBAL, List.of("k"), new byte[]{1});
            assertInstanceOf(ConfigWriteService.ProposeCommitResult.Overloaded.class, shed,
                    "beyond the default cap the write must shed as Overloaded (the store protects itself)");
        } finally {
            // Interrupt the blocked occupiers so their outcome-future waits return and permits release,
            // instead of parking for the full 60s deadline.
            occupiers.shutdownNow();
            occupiers.awaitTermination(15, TimeUnit.SECONDS);
            if (saved == null) {
                System.clearProperty(OVERRIDE);
            } else {
                System.setProperty(OVERRIDE, saved);
            }
        }
    }
}

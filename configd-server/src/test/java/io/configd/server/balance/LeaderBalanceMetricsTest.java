package io.configd.server.balance;

import io.configd.observability.MetricsRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the registry-backed metrics sink: the counters and per-reason skip series are eager-created so
 * they emit {@code _total 0} from the first scrape (the anti-blind-dashboard property), and the gauges
 * reflect the loop's last cadence.
 */
class LeaderBalanceMetricsTest {

    @Test
    void eagerlyRegistersAllSeries() {
        MetricsRegistry registry = new MetricsRegistry();
        LeaderBalanceMetrics.forRegistry(registry);
        var metrics = registry.snapshot().metrics();

        assertNotNull(metrics.get("configd.raft.autobalance.leader_spread"));
        assertNotNull(metrics.get("configd.raft.autobalance.cooldown_active"));
        assertNotNull(metrics.get("configd.raft.autobalance.transfers_initiated"));
        assertNotNull(metrics.get("configd.raft.autobalance.transfer_refused"));
        assertNotNull(metrics.get("configd.raft.autobalance.would_transfer"));
        assertNotNull(metrics.get("configd.raft.autobalance.skipped_unstable.unknown_leader"));
        assertNotNull(metrics.get("configd.raft.autobalance.skipped_unstable.term_churn"));
        assertNotNull(metrics.get("configd.raft.autobalance.skipped_unstable.cooldown"));

        assertEquals(0L, metrics.get("configd.raft.autobalance.transfers_initiated").value());
        assertEquals(0L, metrics.get("configd.raft.autobalance.skipped_unstable.term_churn").value());
    }

    @Test
    void gaugesAndCountersReflectActivity() {
        MetricsRegistry registry = new MetricsRegistry();
        LeaderBalanceMetrics sink = LeaderBalanceMetrics.forRegistry(registry);

        sink.leaderSpread(4);
        sink.cooldownActive(true);
        sink.transferInitiated();
        sink.transferInitiated();
        sink.transferRefused();
        sink.wouldTransfer();
        sink.skippedUnstable(LeaderBalancePlanner.REASON_TERM_CHURN);

        var metrics = registry.snapshot().metrics();
        assertEquals(4L, metrics.get("configd.raft.autobalance.leader_spread").value());
        assertEquals(1L, metrics.get("configd.raft.autobalance.cooldown_active").value());
        assertEquals(2L, metrics.get("configd.raft.autobalance.transfers_initiated").value());
        assertEquals(1L, metrics.get("configd.raft.autobalance.transfer_refused").value());
        assertEquals(1L, metrics.get("configd.raft.autobalance.would_transfer").value());
        assertEquals(1L, metrics.get("configd.raft.autobalance.skipped_unstable.term_churn").value());

        sink.cooldownActive(false);
        assertEquals(0L, registry.snapshot().metrics().get("configd.raft.autobalance.cooldown_active").value());
        assertTrue(true);
    }
}

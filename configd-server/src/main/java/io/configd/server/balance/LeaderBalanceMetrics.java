package io.configd.server.balance;

import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability sink for the leadership balancer. Kept as an interface so the loop is testable without a
 * live registry ({@link #NOOP}) and the production series live in one place ({@link #forRegistry}).
 *
 * <p>Series (all under {@code configd.raft.autobalance.*}):
 * <ul>
 *   <li>{@code leader_spread} gauge - the {@code max-min} leader count from this node's local view, as of
 *       the last cadence.</li>
 *   <li>{@code cooldown_active} gauge - {@code 1} while this node is inside its post-transfer cooldown.</li>
 *   <li>{@code transfers_initiated} counter - real transfers this node drove.</li>
 *   <li>{@code transfer_refused} counter - transfers the primitive declined (no longer leader, or a
 *       config change pending on the group - the hard floor folding into a no-op).</li>
 *   <li>{@code would_transfer} counter - dry-run moves that were computed but not executed.</li>
 *   <li>{@code skipped_unstable.<reason>} counters - cadences skipped by the instability gate, one series
 *       per reason ({@code unknown_leader} / {@code term_churn} / {@code cooldown}).</li>
 * </ul>
 */
public interface LeaderBalanceMetrics {

    void leaderSpread(int spread);

    void cooldownActive(boolean active);

    void transferInitiated();

    void transferRefused();

    void wouldTransfer();

    /** {@code reason} is one of the {@code LeaderBalancePlanner.REASON_*} constants (bounded set). */
    void skippedUnstable(String reason);

    /**
     * A balance cadence threw from {@code runOnce()} (a transient read/transfer fault). The loop swallows
     * it to stay alive; this counter makes a PERSISTENTLY-throwing loop metric-visible (and alertable)
     * rather than only stderr-visible.
     */
    void cycleError();

    /** A sink that records nothing - for tests and for the loop when metrics are not wired. */
    LeaderBalanceMetrics NOOP = new LeaderBalanceMetrics() {
        @Override public void leaderSpread(int spread) { }
        @Override public void cooldownActive(boolean active) { }
        @Override public void transferInitiated() { }
        @Override public void transferRefused() { }
        @Override public void wouldTransfer() { }
        @Override public void skippedUnstable(String reason) { }
        @Override public void cycleError() { }
    };

    /**
     * A sink backed by the server's {@link MetricsRegistry}. Counters and the per-reason skip series are
     * eager-created so they emit {@code _total 0} from the first scrape rather than only appearing once
     * the first event fires. The two gauges read {@link AtomicLong}s the loop updates each cadence.
     */
    static LeaderBalanceMetrics forRegistry(MetricsRegistry registry) {
        return new RegistryLeaderBalanceMetrics(registry);
    }

    final class RegistryLeaderBalanceMetrics implements LeaderBalanceMetrics {

        static final String NAME_LEADER_SPREAD = "configd.raft.autobalance.leader_spread";
        static final String NAME_COOLDOWN_ACTIVE = "configd.raft.autobalance.cooldown_active";
        static final String NAME_TRANSFERS_INITIATED = "configd.raft.autobalance.transfers_initiated";
        static final String NAME_TRANSFER_REFUSED = "configd.raft.autobalance.transfer_refused";
        static final String NAME_WOULD_TRANSFER = "configd.raft.autobalance.would_transfer";
        static final String NAME_CYCLE_ERRORS = "configd.raft.autobalance.cycle_errors";
        static final String NAME_SKIPPED_UNSTABLE_BASE = "configd.raft.autobalance.skipped_unstable";

        private final MetricsRegistry registry;
        private final AtomicLong leaderSpread = new AtomicLong();
        private final AtomicLong cooldownActive = new AtomicLong();
        private final MetricsRegistry.Counter transfersInitiated;
        private final MetricsRegistry.Counter transferRefused;
        private final MetricsRegistry.Counter wouldTransfer;
        private final MetricsRegistry.Counter cycleErrors;

        RegistryLeaderBalanceMetrics(MetricsRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            registry.gauge(NAME_LEADER_SPREAD, leaderSpread::get);
            registry.gauge(NAME_COOLDOWN_ACTIVE, cooldownActive::get);
            this.transfersInitiated = registry.counter(NAME_TRANSFERS_INITIATED);
            this.transferRefused = registry.counter(NAME_TRANSFER_REFUSED);
            this.wouldTransfer = registry.counter(NAME_WOULD_TRANSFER);
            this.cycleErrors = registry.counter(NAME_CYCLE_ERRORS);
            // Eager-create the bounded reason series so they exist from the first scrape.
            registry.counter(skipSeries(LeaderBalancePlanner.REASON_UNKNOWN_LEADER));
            registry.counter(skipSeries(LeaderBalancePlanner.REASON_TERM_CHURN));
            registry.counter(skipSeries(LeaderBalancePlanner.REASON_COOLDOWN));
        }

        private static String skipSeries(String reason) {
            return NAME_SKIPPED_UNSTABLE_BASE + "." + reason;
        }

        @Override
        public void leaderSpread(int spread) {
            leaderSpread.set(spread);
        }

        @Override
        public void cooldownActive(boolean active) {
            cooldownActive.set(active ? 1L : 0L);
        }

        @Override
        public void transferInitiated() {
            transfersInitiated.increment();
        }

        @Override
        public void transferRefused() {
            transferRefused.increment();
        }

        @Override
        public void wouldTransfer() {
            wouldTransfer.increment();
        }

        @Override
        public void cycleError() {
            cycleErrors.increment();
        }

        @Override
        public void skippedUnstable(String reason) {
            // Reasons come from the planner's fixed constant set, so this is a bounded series count.
            registry.counter(skipSeries(reason)).increment();
        }
    }
}

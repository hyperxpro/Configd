package io.configd.server.balance;

import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;


public interface LeaderBalanceMetrics {

    void leaderSpread(int spread);

    void cooldownActive(boolean active);

    void transferInitiated();

    void transferRefused();

    void wouldTransfer();

    
    void skippedUnstable(String reason);

    
    void cycleError();

    
    LeaderBalanceMetrics NOOP = new LeaderBalanceMetrics() {
        @Override public void leaderSpread(int spread) { }
        @Override public void cooldownActive(boolean active) { }
        @Override public void transferInitiated() { }
        @Override public void transferRefused() { }
        @Override public void wouldTransfer() { }
        @Override public void skippedUnstable(String reason) { }
        @Override public void cycleError() { }
    };

    
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

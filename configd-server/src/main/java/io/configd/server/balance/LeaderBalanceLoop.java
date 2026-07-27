package io.configd.server.balance;

import io.configd.common.Clock;
import io.configd.common.NodeId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class LeaderBalanceLoop implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(LeaderBalanceLoop.class.getName());

    
    @FunctionalInterface
    public interface LeadershipTransfer {
        boolean transfer(int groupId, NodeId target);
    }

    private static final String THREAD_NAME = "configd-leader-balance";

    private final LeaderView view;
    private final LeadershipTransfer transfer;
    private final LeaderBalanceConfig config;
    private final Clock clock;
    private final Random jitter;
    private final LeaderBalanceMetrics metrics;
    private final ScheduledExecutorService executor;

    // Cross-cadence state, confined to the executor thread.
    private final Map<Integer, Long> lastTerm = new HashMap<>();
    private final Map<Integer, Long> lastTermChangeMillis = new HashMap<>();
    private long cooldownUntilMillis = Long.MIN_VALUE;

    private volatile boolean closed;

    
    public LeaderBalanceLoop(LeaderView view, LeadershipTransfer transfer, LeaderBalanceConfig config,
                             Clock clock, Random jitter, LeaderBalanceMetrics metrics) {
        this.view = Objects.requireNonNull(view, "view");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
    }

    
    public void start() {
        scheduleNext();
    }

    private void scheduleNext() {
        if (closed) {
            return;
        }
        try {
            executor.schedule(this::cycle, jitteredDelayMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            // Executor is draining on close - stop rescheduling; nothing further to do.
        }
    }

    private void cycle() {
        try {
            runOnce();
        } catch (Throwable t) {
            // A transient read/transfer failure must never kill the loop (which would leave leadership
            // permanently unbalanced). Count it (so a PERSISTENTLY-throwing loop is alertable, not just
            // stderr-visible) and continue; the next cadence re-observes cleanly.
            metrics.cycleError();
            LOG.log(Level.WARNING, "leadership auto-balance cycle failed (continuing)", t);
        } finally {
            scheduleNext();
        }
    }

    
    long jitteredDelayMillis() {
        double fraction = config.jitterPct() / 100.0;
        double factor = 1.0 + fraction * (jitter.nextDouble() * 2.0 - 1.0);
        return Math.max(1L, Math.round(config.intervalMs() * factor));
    }

    
    void runOnce() {
        long now = clock.currentTimeMillis();
        LeaderView.Snapshot snapshot = view.snapshot();
        updateTermTracking(snapshot, now);

        boolean inCooldown = now < cooldownUntilMillis;
        metrics.cooldownActive(inCooldown);
        LeaderBalancePlanner.Gate gate =
                new LeaderBalancePlanner.Gate(recentTermChurn(now), inCooldown);

        LeaderBalancePlanner.Plan plan =
                LeaderBalancePlanner.plan(snapshot, gate, config.imbalanceThreshold(), jitter);
        metrics.leaderSpread(plan.leaderSpread());

        if (plan.backedOff()) {
            metrics.skippedUnstable(plan.backoffReason());
            return;
        }
        if (!plan.actionable()) {
            return;
        }

        LeaderBalancePlanner.Move move = plan.move();
        if (config.dryRun()) {
            // Observe-only: emit the would-be move but do not execute it. Still enter cooldown so the
            // preview shows the same one-per-cooldown cadence the live loop would exhibit.
            metrics.wouldTransfer();
            LOG.log(Level.INFO, "leadership auto-balance (dry-run): would transfer group {0} to {1} (spread {2})",
                    new Object[]{move.groupId(), move.target(), plan.leaderSpread()});
            cooldownUntilMillis = now + config.cooldownMs();
            return;
        }

        boolean initiated = transfer.transfer(move.groupId(), move.target());
        if (initiated) {
            // Count only ACTUAL initiations: transfers_initiated must mean "transfers this node drove",
            // not "attempts" (a declined transfer increments transfer_refused, so attempts = initiated +
            // refused). Incrementing before the refusal check would inflate the success series.
            metrics.transferInitiated();
            LOG.log(Level.INFO, "leadership auto-balance: transferring group {0} to {1} (spread {2})",
                    new Object[]{move.groupId(), move.target(), plan.leaderSpread()});
        } else {
            metrics.transferRefused();
            LOG.log(Level.INFO, "leadership auto-balance: transfer of group {0} to {1} was declined "
                    + "(no longer leader or change pending)", new Object[]{move.groupId(), move.target()});
        }
        // Cooldown regardless of the immediate result - a declined transfer is treated as one attempt,
        // so a group mid-membership-change is not retried every cadence.
        cooldownUntilMillis = now + config.cooldownMs();
    }

    
    private void updateTermTracking(LeaderView.Snapshot snapshot, long now) {
        for (LeaderView.GroupLeader group : snapshot.groups()) {
            int gid = group.groupId();
            long term = group.term();
            Long prev = lastTerm.get(gid);
            if (prev == null) {
                lastTerm.put(gid, term);
            } else if (term > prev) {
                lastTerm.put(gid, term);
                lastTermChangeMillis.put(gid, now);
            }
        }
    }

    
    private boolean recentTermChurn(long now) {
        long window = config.instabilityWindowMs();
        for (long changedAt : lastTermChangeMillis.values()) {
            if (now - changedAt < window) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }
}

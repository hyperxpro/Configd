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

/**
 * The decentralized leadership auto-balance loop: one per node, on its own dedicated single-thread
 * scheduled executor (modeled on the server's {@code tlsReloadExecutor}) so it never runs on, or delays,
 * the 10ms consensus tick. Each cadence it observes the cluster's leader distribution
 * ({@link LeaderView}), decides at most one leadership shed via the pure {@link LeaderBalancePlanner},
 * and drives it through the wired, owner-thread-confined transfer path ({@link LeadershipTransfer}).
 *
 * <p>Dampening, so a rebalance never becomes churn:
 * <ul>
 *   <li><b>Jittered cadence.</b> The next run is scheduled at {@code intervalMs +/- jitterPct}, so nodes'
 *       loops desynchronize instead of firing in lockstep.</li>
 *   <li><b>One shed per cadence.</b> The planner returns at most one move, and the loop is a single thread
 *       that runs a cadence to completion (the transfer is bounded), so a node never has two transfers in
 *       flight.</li>
 *   <li><b>Post-transfer cooldown.</b> After initiating (or, in dry-run, computing) a move, the node stays
 *       quiet for {@code cooldownMs}, letting the moved leadership settle before the next shed.</li>
 *   <li><b>Instability back-off.</b> The cycle does nothing while any group's leader is unknown, a group's
 *       term bumped within {@code instabilityWindowMs} (election storm), or the node is in cooldown. The
 *       transfer primitive is itself the hard floor for an in-flight membership change (it refuses while a
 *       config change is pending); a refused transfer folds into a no-op plus cooldown.</li>
 * </ul>
 *
 * <p><b>Threading.</b> All mutable state ({@code lastTerm}, {@code lastTermChangeMillis},
 * {@code cooldownUntilMillis}) is confined to the single executor thread; {@link #runOnce()} is only ever
 * invoked there (or directly by a single-threaded test). {@code closed} is the one cross-thread field.
 */
public final class LeaderBalanceLoop implements AutoCloseable {

    /**
     * Drives one leadership transfer through the server's wired, owner-thread-confined admin path.
     * Returns {@code true} if the transfer was initiated, {@code false} if the primitive declined it
     * (this node is no longer the group's leader, a config change is pending, or the owner did not
     * confirm within the bound). Either way the loop treats it as one attempt and enters cooldown.
     */
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

    /**
     * @param view     the local cluster-leader observation source
     * @param transfer the owner-confined transfer driver
     * @param config   the tunables (already validated)
     * @param clock    the time source for cadence/cooldown/instability timekeeping (injectable for tests)
     * @param jitter   randomness for cadence jitter and target tie-breaking (seed for reproducible tests)
     * @param metrics  the observability sink ({@link LeaderBalanceMetrics#NOOP} if unwired)
     */
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

    /** Schedules the first cadence. Idempotent-safe to call once at boot. */
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
            // permanently unbalanced). Log and continue; the next cadence re-observes cleanly.
            System.err.println("WARNING: leadership auto-balance cycle failed (continuing): " + t);
        } finally {
            scheduleNext();
        }
    }

    /** The base cadence perturbed by {@code +/- jitterPct}, floored at 1ms. */
    long jitteredDelayMillis() {
        double fraction = config.jitterPct() / 100.0;
        double factor = 1.0 + fraction * (jitter.nextDouble() * 2.0 - 1.0);
        return Math.max(1L, Math.round(config.intervalMs() * factor));
    }

    /**
     * Runs exactly one balance cadence. Package-visible so tests drive it deterministically without the
     * scheduler: observe, update churn tracking, gate, plan, act, record cooldown.
     */
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
            System.out.println("leadership auto-balance (dry-run): would transfer group "
                    + move.groupId() + " to " + move.target() + " (spread " + plan.leaderSpread() + ")");
            cooldownUntilMillis = now + config.cooldownMs();
            return;
        }

        boolean initiated = transfer.transfer(move.groupId(), move.target());
        metrics.transferInitiated();
        if (initiated) {
            System.out.println("leadership auto-balance: transferring group " + move.groupId()
                    + " to " + move.target() + " (spread " + plan.leaderSpread() + ")");
        } else {
            metrics.transferRefused();
            System.out.println("leadership auto-balance: transfer of group " + move.groupId()
                    + " to " + move.target() + " was declined (no longer leader or change pending)");
        }
        // Cooldown regardless of the immediate result - a declined transfer is treated as one attempt,
        // so a group mid-membership-change is not retried every cadence.
        cooldownUntilMillis = now + config.cooldownMs();
    }

    /**
     * Records each group's last-seen term and the wall-clock time it last increased. First observation of
     * a group seeds its term WITHOUT counting as a change (so a fresh boot is not treated as churn); only
     * a strict term increase from a known prior value marks churn.
     */
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

    /** True if any group's term changed within {@code instabilityWindowMs} of {@code now}. */
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

package io.configd.edge;

import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Poison-pill policy: bounded retries → quarantine+snapshot re-bootstrap → terminal fail-loud.
 * Single-writer from session thread. Counters lock-free; local longs for deterministic reads.
 */
public final class PoisonPillPolicy {

    private static final Logger LOG = Logger.getLogger(PoisonPillPolicy.class.getName());

    public static final int DEFAULT_MAX_RETRIES = 3;

    public static final String RETRIES_METRIC = "edge.poison_retries";
    public static final String POISON_PILL_METRIC = "configd.edge.poison_pill";
    public static final String TERMINAL_METRIC = "configd.edge.poison_pill_terminal";

    public enum Action {
        RESUBSCRIBE,
        REBOOTSTRAP,
        TERMINAL
    }

    private final PoisonPillDetector detector;
    private final int maxRetries;
    private final MetricsRegistry.Counter retriesCounter;
    private final MetricsRegistry.Counter poisonPillCounter;
    private final MetricsRegistry.Counter terminalCounter;

    private volatile long retries;
    private volatile long quarantines;
    private volatile long terminals;

    private volatile long quarantinedSeq = -1L;
    private long lastFailingSeq = -1L;
    private volatile boolean terminal;

    public PoisonPillPolicy() {
        this(DEFAULT_MAX_RETRIES, null, null, null);
    }

    public PoisonPillPolicy(int maxRetries, MetricsRegistry.Counter retriesCounter,
                            MetricsRegistry.Counter poisonPillCounter,
                            MetricsRegistry.Counter terminalCounter) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException(
                    "edge.poisonpill.maxRetries must be > 0: " + maxRetries);
        }
        this.maxRetries = maxRetries;
        this.detector = new PoisonPillDetector(maxRetries);
        this.retriesCounter = retriesCounter;
        this.poisonPillCounter = poisonPillCounter;
        this.terminalCounter = terminalCounter;
    }

    public Action onApplyFailure(long seq, RuntimeException cause) {
        if (terminal) {
            return Action.TERMINAL;
        }
        if (quarantinedSeq >= 0) {
            if (seq == quarantinedSeq) {
                // The forced re-bootstrap redelivered the poison as a DELTA (the server
                // chose TAIL -- young ring, nothing evicted) and it threw again: the edge
                // cannot advance and cannot be given a snapshot. Die visibly.
                return decideTerminal(seq, "quarantined seq redelivered as a delta and "
                        + "threw again after the forced re-bootstrap (server chose TAIL)", cause);
            }
            // A DIFFERENT seq failing means the re-bootstrap advanced past the old poison
            // (seqs apply in order). The old quarantine is moot; treat this as a fresh
            // failure on the new seq.
            clearQuarantine();
        }
        return recordFailure(seq, "apply", cause);
    }

    public Action onSnapshotApplyFailure(long seq, RuntimeException cause) {
        if (terminal) {
            return Action.TERMINAL;
        }
        if (quarantinedSeq >= 0) {
            return decideTerminal(seq,
                    "snapshot failed to apply during the forced re-bootstrap", cause);
        }
        return recordFailure(seq, "snapshot", cause);
    }

    public void onProgress(long cursor) {
        if (terminal) {
            return;
        }
        if (lastFailingSeq >= 0 && cursor >= lastFailingSeq) {
            detector.recordSuccess(key(lastFailingSeq));
            lastFailingSeq = -1L;
        }
        if (quarantinedSeq >= 0 && cursor >= quarantinedSeq) {
            LOG.info("poison pill recovered: snapshot re-bootstrap covered seq "
                    + quarantinedSeq + " (cursor now " + cursor + ")");
            clearQuarantine();
        }
    }

    private Action recordFailure(long seq, String kind, RuntimeException cause) {
        retries++;
        increment(retriesCounter);
        lastFailingSeq = seq;
        boolean quarantined = detector.recordFailure(key(seq),
                kind + " failure: " + cause.getClass().getName());
        if (!quarantined) {
            LOG.log(Level.WARNING, "edge " + kind + " failure at seq " + seq
                    + " (retry via resubscribe-at-cursor; "
                    + "max " + maxRetries + " before quarantine)", cause);
            return Action.RESUBSCRIBE;
        }
        // Quarantine: emit the metric + structured log, then direct the forced snapshot
        // re-bootstrap. Skipping the seq is forbidden (chain break).
        quarantines++;
        increment(poisonPillCounter);
        quarantinedSeq = seq;
        LOG.severe("POISON PILL: seq " + seq + " " + kind + " failed " + maxRetries
                + "x — quarantined; forcing snapshot re-bootstrap (re-subscribe at cursor 0); "
                + "last cause: " + cause);
        return Action.REBOOTSTRAP;
    }

    private Action decideTerminal(long seq, String why, RuntimeException cause) {
        terminal = true;
        terminals++;
        increment(terminalCounter);
        // Emit the structured fail-loud event BEFORE the process exit the caller performs.
        // An edge that cannot advance and cannot re-bootstrap must die visibly, never serve
        // an ever-staler cache behind a green health check.
        LOG.log(Level.SEVERE, "POISON PILL TERMINAL: quarantinedSeq=" + quarantinedSeq
                + " failingSeq=" + seq + " — " + why
                + "; the edge cannot advance and cannot re-bootstrap; the process must "
                + "exit non-zero (ADR-0040)", cause);
        return Action.TERMINAL;
    }

    private void clearQuarantine() {
        detector.release(key(quarantinedSeq));
        quarantinedSeq = -1L;
    }

    private static String key(long seq) {
        return "seq:" + seq;
    }

    private static void increment(MetricsRegistry.Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    public long retries() {
        return retries;
    }

    public long quarantines() {
        return quarantines;
    }

    public long terminals() {
        return terminals;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public long quarantinedSeq() {
        return quarantinedSeq;
    }

    public int maxRetries() {
        return maxRetries;
    }
}

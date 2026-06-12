package io.configd.edge;

import io.configd.observability.MetricsRegistry;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The ADR-0040 narrow poison-pill policy (C3; contract row CT-33). Decides what the edge
 * does when a frame that passed signature verification <b>throws during apply</b> — the one
 * genuinely real poison hazard in a system that stores opaque bytes (architecture §8's
 * schema-validation circuit breaker is descoped by ADR-0040; an invalid-<i>signature</i>
 * delta is NOT a poison pill — it is rejected fail-closed upstream in {@link DeltaApplier}
 * and never reaches this policy).
 *
 * <h2>The ladder (ADR-0040 §Decision 1)</h2>
 * <ol>
 *   <li><b>Bounded retries per seq</b> — failures are counted by the existing
 *       {@link PoisonPillDetector}, re-pointed at apply exceptions keyed by the
 *       applied-mutation seq (named config {@code edge.poisonpill.maxRetries}, default
 *       {@value #DEFAULT_MAX_RETRIES}; metric {@code edge_poison_retries_total}). Each
 *       retry is a {@link Action#RESUBSCRIBE resubscribe-at-cursor} — the server
 *       redelivers the same seq, healing a transient failure.</li>
 *   <li><b>Quarantine → forced snapshot re-bootstrap.</b> Skipping the bad seq is
 *       FORBIDDEN (a skipped seq is a silent chain break — divergence). On quarantine the
 *       policy emits {@code configd.edge.poison_pill} + a structured log and directs a
 *       {@link Action#REBOOTSTRAP re-subscribe at cursor 0}: the server's TAIL /
 *       SNAPSHOT_FIRST decision then sends a snapshot whose cumulative state already
 *       contains the poison seq's effect, so the bad delta is never re-applied.</li>
 *   <li><b>Terminal fail-loud.</b> If, after the forced re-bootstrap, the snapshot itself
 *       fails to apply — or the quarantined seq is redelivered as a delta and throws again
 *       (defense-in-depth: since C3's decideMode rule a cursor-0 subscriber always gets a
 *       snapshot when data exists, so a TAIL redelivery here means a server not honoring
 *       that rule — still a wedge, still loud) — the edge can neither advance nor
 *       re-bootstrap: {@link Action#TERMINAL}. The process must exit non-zero
 *       ({@code configd.edge.poison_pill_terminal} emitted first), never serve an
 *       ever-staler cache behind a green health check, and never hot-loop.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * Single-writer, like its caller {@link EdgeClientCore}: all methods must be invoked from
 * the session thread. Counters are lock-free; the local longs are for deterministic test /
 * sim reads.
 */
public final class PoisonPillPolicy {

    private static final Logger LOG = Logger.getLogger(PoisonPillPolicy.class.getName());

    /** Default bounded-retry count (named config {@code edge.poisonpill.maxRetries}). */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** Metric: apply-failure retries ({@code edge_poison_retries_total}). */
    public static final String RETRIES_METRIC = "edge.poison_retries";
    /** Metric: quarantines — the §8 name, kept by ADR-0040 ({@code configd_edge_poison_pill_total}). */
    public static final String POISON_PILL_METRIC = "configd.edge.poison_pill";
    /** Metric: terminal fail-loud, emitted before the process exits non-zero. */
    public static final String TERMINAL_METRIC = "configd.edge.poison_pill_terminal";

    /** What the caller must do after a reported failure. */
    public enum Action {
        /** Bounded retry: re-subscribe at the CURRENT cursor; the failing seq is redelivered. */
        RESUBSCRIBE,
        /**
         * Quarantined: force a snapshot re-bootstrap — re-subscribe at cursor 0 (ADR-0040;
         * cursor 0 is reserved for suspected local poison, never for ordinary recovery).
         */
        REBOOTSTRAP,
        /** Cannot advance, cannot re-bootstrap: the process must exit non-zero, loudly. */
        TERMINAL
    }

    private final PoisonPillDetector detector;
    private final int maxRetries;

    // Optional registry counters (null in sim/unit wiring); local longs are authoritative
    // for deterministic reads (the StalenessTracker implausibleCounter pattern).
    private final MetricsRegistry.Counter retriesCounter;
    private final MetricsRegistry.Counter poisonPillCounter;
    private final MetricsRegistry.Counter terminalCounter;

    // Single-writer (the session thread) per the class contract; volatile so process
    // tests and the metrics surface can poll cross-thread (safe publication, no locks).
    private volatile long retries;
    private volatile long quarantines;
    private volatile long terminals;

    /** Non-negative while a forced re-bootstrap is in flight; -1 otherwise. */
    private volatile long quarantinedSeq = -1L;

    /** The seq of the most recent failure (for clearing the consecutive count on progress). */
    private long lastFailingSeq = -1L;

    /** Latched on the first TERMINAL decision — the policy never un-decides death. */
    private volatile boolean terminal;

    /** Policy with default retries and no registry counters (sim / unit wiring). */
    public PoisonPillPolicy() {
        this(DEFAULT_MAX_RETRIES, null, null, null);
    }

    /**
     * @param maxRetries        bounded retries per seq before quarantine
     *                          ({@code edge.poisonpill.maxRetries}, &gt; 0)
     * @param retriesCounter    optional {@value #RETRIES_METRIC} counter (may be null)
     * @param poisonPillCounter optional {@value #POISON_PILL_METRIC} counter (may be null)
     * @param terminalCounter   optional {@value #TERMINAL_METRIC} counter (may be null)
     */
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

    /**
     * Reports an apply-time failure of the delta at {@code seq} (a {@link RuntimeException}
     * out of the verified apply path — NOT a signature rejection, which is handled
     * fail-closed upstream and is deliberately invisible here).
     *
     * @param seq   the applied-mutation seq of the failing delta
     * @param cause the apply failure
     * @return the action the caller must take
     */
    public Action onApplyFailure(long seq, RuntimeException cause) {
        if (terminal) {
            return Action.TERMINAL;
        }
        if (quarantinedSeq >= 0) {
            if (seq == quarantinedSeq) {
                // The forced re-bootstrap redelivered the poison as a DELTA (the server
                // chose TAIL — young ring, nothing evicted) and it threw again: the edge
                // cannot advance and cannot be given a snapshot. Die visibly (ADR-0040 §1.3).
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

    /**
     * Reports a snapshot reassembly/cutover failure at snapshot seq {@code seq}.
     * During a forced re-bootstrap this is the ADR-0040 §1.3 terminal condition verbatim
     * ("if the snapshot itself fails to apply"); outside one it gets the same bounded
     * retry ladder (the server re-sends a lost/corrupt snapshot — C1's self-healing).
     *
     * @param seq   the failing snapshot's seq
     * @param cause the reassembly/cutover failure
     * @return the action the caller must take
     */
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

    /**
     * Reports apply progress (the cursor advanced — a delta applied or a snapshot cut
     * over). Clears the in-flight failure count once the failing seq is passed, and ends a
     * forced re-bootstrap once the quarantined seq is covered (recovery: the snapshot's
     * cumulative state contains the poison seq's effect).
     *
     * @param cursor the edge's applied-mutation cursor after the advance
     */
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
        // Quarantine: the §8 metric name (kept by ADR-0040) + structured log, then the
        // forced snapshot re-bootstrap. Skipping the seq is forbidden (chain break).
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
        // The structured fail-loud event (ADR-0040 §1.3): emitted BEFORE the process exit
        // the caller performs. An edge that cannot advance and cannot re-bootstrap must
        // die visibly, never serve an ever-staler cache behind a green health check.
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

    // --- deterministic reads (tests / sim / the metrics pump) ---

    /** Total apply/snapshot failure retries observed. */
    public long retries() {
        return retries;
    }

    /** Total quarantines ({@code configd.edge.poison_pill}). */
    public long quarantines() {
        return quarantines;
    }

    /** Total terminal decisions (0 or 1 — latched). */
    public long terminals() {
        return terminals;
    }

    /** True once a TERMINAL decision was made (latched forever). */
    public boolean isTerminal() {
        return terminal;
    }

    /** The quarantined seq while a forced re-bootstrap is in flight, else -1. */
    public long quarantinedSeq() {
        return quarantinedSeq;
    }

    /** The configured bounded-retry count ({@code edge.poisonpill.maxRetries}). */
    public int maxRetries() {
        return maxRetries;
    }
}

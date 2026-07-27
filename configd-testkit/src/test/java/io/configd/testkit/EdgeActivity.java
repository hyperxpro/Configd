package io.configd.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-seed edge-activity accumulator for {@link EdgeFanOutSim}, extending the
 * {@link Activity} liveness-vs-safety discipline to the edge data plane.
 * <p>
 * As with {@link Activity}, the split is deliberate:
 * <ul>
 *   <li><b>Safety</b> edge invariants (per-edge monotonicity, no-stale-overwrite,
 *       convergence) FAIL the seed by throwing {@link SimInvariants.SafetyViolation}
 *       in {@link EdgeInvariants} - they never land here.</li>
 *   <li><b>Liveness</b> goals (a published notification is observed by a
 *       live+connected+non-lagging edge within {@code BOUND_MS}) are
 *       <em>recorded, never thrown</em>. A miss is an
 *       {@link DeliveryViolation} appended here, capped so a pathological seed
 *       cannot OOM the recorder.</li>
 * </ul>
 * With {@link StreamDriver#NONE} every published notification is a delivery
 * violation - that is the executable backlog.
 * <p>
 * Not thread-safe; one instance per seed, mutated on the single sim thread.
 */
final class EdgeActivity {

    /** Bound on the recorded violation list so a pathological seed cannot OOM. */
    static final int MAX_RECORDED_VIOLATIONS = 256;

    record DeliveryViolation(long seq, int edgeId, long publishedAtMs, long latenessMs) {}

    private long deliveredCount;
    private final List<DeliveryViolation> deliveryViolations = new ArrayList<>();
    private boolean deliveryViolationsTruncated;
    private int edgeCrashes;
    private int edgeRestarts;
    private int gapsDetected;
    private int snapshotsApplied;

    private long excusedAtDeadline;

    private final Map<Integer, Long> perEdgeMaxLatenessMs = new TreeMap<>();

    void recordDelivered() {
        deliveredCount++;
    }

    void recordDelivered(long n) {
        deliveredCount += n;
    }

    void recordDeliveryViolation(long seq, int edgeId, long publishedAtMs, long latenessMs) {
        if (deliveryViolations.size() < MAX_RECORDED_VIOLATIONS) {
            deliveryViolations.add(new DeliveryViolation(seq, edgeId, publishedAtMs, latenessMs));
        } else {
            deliveryViolationsTruncated = true;
        }
        perEdgeMaxLatenessMs.merge(edgeId, latenessMs, Math::max);
    }

    void recordEdgeCrash() {
        edgeCrashes++;
    }

    void recordEdgeRestart() {
        edgeRestarts++;
    }

    void recordGapDetected() {
        gapsDetected++;
    }

    void recordGapsDetected(int n) {
        gapsDetected += n;
    }

    void recordSnapshotApplied() {
        snapshotsApplied++;
    }

    void recordSnapshotsApplied(int n) {
        snapshotsApplied += n;
    }

    void recordExcusedAtDeadline() {
        excusedAtDeadline++;
    }

    long deliveredCount() { return deliveredCount; }

    List<DeliveryViolation> deliveryViolations() {
        return List.copyOf(deliveryViolations);
    }

    int deliveryViolationCount() {
        return deliveryViolations.size();
    }

    boolean deliveryViolationsTruncated() {
        return deliveryViolationsTruncated;
    }

    int edgeCrashes() { return edgeCrashes; }

    int edgeRestarts() { return edgeRestarts; }

    int gapsDetected() { return gapsDetected; }

    int snapshotsApplied() { return snapshotsApplied; }

    long excusedAtDeadline() { return excusedAtDeadline; }

    Map<Integer, Long> perEdgeMaxLatenessMs() {
        return Map.copyOf(perEdgeMaxLatenessMs);
    }

    @Override
    public String toString() {
        return "EdgeActivity[delivered=" + deliveredCount
                + ", deliveryViolations=" + deliveryViolations.size()
                + (deliveryViolationsTruncated ? "(+truncated)" : "")
                + ", edgeCrashes=" + edgeCrashes
                + ", edgeRestarts=" + edgeRestarts
                + ", gapsDetected=" + gapsDetected
                + ", snapshotsApplied=" + snapshotsApplied
                + ", excusedAtDeadline=" + excusedAtDeadline
                + ", maxLateness=" + perEdgeMaxLatenessMs + "]";
    }
}

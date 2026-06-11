package io.configd.testkit;

/**
 * OBSERVER-ONLY seam (Phase V2 — charter §3 V2) fired by an {@link EdgeActor} at the
 * exact moment a {@link io.configd.distribution.CommitNotification} is <b>applied</b>
 * (its {@link io.configd.edge.DeltaApplier.ApplyResult} is {@code APPLIED} and the
 * cursor advances). It exists so the {@link io.configd.probe.PropagationProbe} can
 * sample the logical visibility time of each committed seq at each edge.
 *
 * <p><b>Strictly observer-only.</b> An implementation MUST NOT mutate any edge, sim,
 * or invariant state — it only reads the already-computed {@code (seq, commitTs, nowMs)}
 * and records them. This guarantees attaching a probe does not perturb the
 * {@link EdgeSimDeterminismTest} digest (proven by {@code ProbeMechanismTest}).
 *
 * <p>The default {@link #NONE} does nothing, so V1's behavior (no probe attached) is
 * byte-identical to the unseamed path.
 */
@FunctionalInterface
interface EdgeApplyObserver {

    /**
     * Notifies that {@code edgeId} applied the notification with applied-mutation
     * sequence {@code seq} (leader commit timestamp {@code commitTsMillis}, ADR-0035 §2)
     * at logical sim time {@code visibleTsMillis}.
     *
     * @param edgeId          the applying edge's id
     * @param seq             the applied-mutation sequence S (ADR-0033)
     * @param commitTsMillis  the leader-assigned commit timestamp of the notification
     * @param visibleTsMillis the edge's logical clock at apply (the visibility moment)
     */
    void onApplied(int edgeId, long seq, long commitTsMillis, long visibleTsMillis);

    /** No-op observer — the V1 default; attaching it changes nothing. */
    EdgeApplyObserver NONE = (edgeId, seq, commitTsMillis, visibleTsMillis) -> { };
}

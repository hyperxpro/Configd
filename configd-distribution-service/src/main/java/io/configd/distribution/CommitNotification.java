package io.configd.distribution;

import io.configd.store.ConfigDelta;

import java.util.Objects;

/**
 * One committed-mutation notification on the §4.6 commit-notification boundary
 * (ADR-0034). This is the unit Session 3's data-plane fan-out consumes; it is
 * <b>transport-agnostic</b> — no wire encoding lives here.
 *
 * <p>A notification corresponds to exactly one mutating apply on the leader
 * (PUT / DELETE / BATCH); non-mutating committed entries (leader no-ops, RCFG)
 * produce no notification, matching the applied-mutation sequence semantics of
 * ADR-0033 / ADR-0035.
 *
 * <h2>Field justification (ADR-0034 §schema)</h2>
 * <ul>
 *   <li><b>{@code seq}</b> — the ADR-0033 applied-mutation sequence S the state
 *       machine assigned to this apply. It is the client-visible commit sequence
 *       AND the consumer's cursor: a consumer holds the last S it applied and
 *       calls {@link CommitNotificationSource#readSince(long)} with it. Equal to
 *       {@code delta.toVersion()} for a single-mutation delta, but carried
 *       explicitly so the cursor contract never depends on parsing the delta.</li>
 *   <li><b>{@code commitTimestampMillis}</b> — the <b>leader-assigned commit
 *       timestamp</b>: the leader's wall clock captured at apply time (ADR-0035
 *       §2). This is the §2 staleness clock — S3's edge computes
 *       {@code staleness = edge_wall_now − commitTimestampMillis}. It is a single
 *       authoritative reference clock (the leader's), NOT a per-entry HLC carried
 *       in the Raft log.</li>
 *   <li><b>{@code delta}</b> — the existing {@link ConfigDelta}, which already
 *       carries the mutation list (keys + payloads), {@code fromVersion}/
 *       {@code toVersion}, and the F-0052 signature/epoch/nonce the edge needs to
 *       verify authenticity and reject replays. Carrying the whole delta avoids
 *       duplicating key/payload state and lets S3 forward the signed payload
 *       unchanged.</li>
 * </ul>
 *
 * @param seq                   applied-mutation sequence S (ADR-0033); also the cursor key
 * @param commitTimestampMillis leader wall-clock at apply time, ms (ADR-0035 §2 staleness clock)
 * @param delta                 the committed delta (keys, payloads, signature, epoch, nonce)
 */
public record CommitNotification(
        long seq,
        long commitTimestampMillis,
        ConfigDelta delta
) {

    public CommitNotification {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be non-negative: " + seq);
        }
        if (commitTimestampMillis < 0) {
            throw new IllegalArgumentException(
                    "commitTimestampMillis must be non-negative: " + commitTimestampMillis);
        }
        Objects.requireNonNull(delta, "delta must not be null");
    }
}

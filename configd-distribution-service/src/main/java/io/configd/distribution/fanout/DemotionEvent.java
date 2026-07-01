package io.configd.distribution.fanout;

/**
 * The structured event a {@link FanOutSessionCore} records on every streaming-to-catch-up
 * demotion. Every demotion emits a structured log event and metric with
 * (session, cursor, lastAckedSeq, reason) - never a silent drop.
 * The slow-consumer governor layers disconnect and quarantine policy on top of these events.
 *
 * @param cursor       the session cursor at the moment of demotion (the highest seq the
 *                     server had streamed) - the cursor evidence
 * @param lastAckedSeq the highest seq the edge had acknowledged at demotion time
 * @param reason       the demotion reason (one of the {@code REASON_*} labels)
 */
public record DemotionEvent(long cursor, long lastAckedSeq, String reason) {

    /** Unacked NOTIFY frames reached {@code queueFrames} (the bounded outbound queue is full). */
    public static final String REASON_QUEUE_OVERFLOW = "queue_overflow";

    /** Ack lag ({@code cursor - lastAckedSeq}) exceeded {@code ackLagDemoteSeqs}. */
    public static final String REASON_ACK_LAG = "ack_lag";

    /** {@code readSince(cursor)} returned a GAP - the tail no longer covers the cursor. */
    public static final String REASON_GAP = "gap";

    /** The transport sink rejected an offered frame (would-block / transport queue full). */
    public static final String REASON_TRANSPORT_BLOCK = "transport_block";

    public DemotionEvent {
        java.util.Objects.requireNonNull(reason, "reason must not be null");
    }
}

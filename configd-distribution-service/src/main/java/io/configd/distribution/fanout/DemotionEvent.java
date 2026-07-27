package io.configd.distribution.fanout;

public record DemotionEvent(long cursor, long lastAckedSeq, String reason) {

    public static final String REASON_QUEUE_OVERFLOW = "queue_overflow";
    public static final String REASON_ACK_LAG = "ack_lag";
    public static final String REASON_GAP = "gap";
    public static final String REASON_TRANSPORT_BLOCK = "transport_block";

    public DemotionEvent {
        java.util.Objects.requireNonNull(reason, "reason must not be null");
    }
}

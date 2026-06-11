package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;

import java.util.List;
import java.util.Objects;

/**
 * The protocol-v1 frame model for the edge streaming path (C1 design §3; ADR-0037
 * wire discipline, ADR-0038 verbatim-signed-chain delivery). A sealed family of
 * immutable records — one per frame type in the §3 table — that {@link EdgeFrameCodec}
 * encodes to / decodes from the length-prefixed CRC32C-checked wire format.
 *
 * <p><b>Transport-free by construction.</b> No {@code java.net}, socket, or TLS type
 * appears anywhere in this hierarchy or in {@link EdgeFrameCodec}; the only boundary
 * to the transport is the session core's {@code TransportSink} (ADR-0037
 * TransportSink-seam contingency). This keeps the protocol model fully unit- and
 * golden-fixture-testable without a network.
 *
 * <p><b>Direction</b> (per §3): {@link Subscribe} and {@link CursorAck} are edge→server;
 * {@link SubscribeOk}, {@link Notify}, {@link SnapshotBegin}, {@link SnapshotChunk},
 * {@link SnapshotEnd}, {@link Heartbeat} are server→edge; {@link ErrorClose} is either.
 */
public sealed interface EdgeFrame
        permits EdgeFrame.Subscribe, EdgeFrame.SubscribeOk, EdgeFrame.Notify,
        EdgeFrame.SnapshotBegin, EdgeFrame.SnapshotChunk, EdgeFrame.SnapshotEnd,
        EdgeFrame.CursorAck, EdgeFrame.Heartbeat, EdgeFrame.ErrorClose {

    /** The wire type code carried in the frame header. */
    FrameType type();

    /**
     * Edge→server subscription request (one per connection, §3). The subscription is
     * either prefix-scoped or full-store; per ADR-0038 the prefix set is echoed to the
     * edge as a storage/serving filter only — the server always streams the full signed
     * chain regardless of prefixes.
     *
     * @param fullStore            true ⇒ subscribe to the whole store; when true
     *                             {@code prefixes} must be empty
     * @param prefixes             the subscribed key prefixes (empty when {@code fullStore})
     * @param resumeCursor         the applied-mutation seq S the edge has already applied
     *                             (0 = fresh subscriber)
     * @param failoverResumeCursor RESERVED (§3): the cursor obtained from a PREVIOUS
     *                             fan-out endpoint, for the contract §3 edge-failover
     *                             clause. C2 populates it; v1 servers treat it as the
     *                             resume cursor when it exceeds {@code resumeCursor}.
     *                             {@code -1} means "not present".
     * @param edgeId               the edge identity (bound to the mTLS cert identity at C2)
     */
    record Subscribe(
            boolean fullStore,
            List<String> prefixes,
            long resumeCursor,
            long failoverResumeCursor,
            String edgeId
    ) implements EdgeFrame {

        public Subscribe {
            Objects.requireNonNull(prefixes, "prefixes must not be null");
            prefixes = List.copyOf(prefixes);
            if (fullStore && !prefixes.isEmpty()) {
                throw new IllegalArgumentException(
                        "full-store subscription must carry no prefixes: " + prefixes);
            }
            if (resumeCursor < 0) {
                throw new IllegalArgumentException("resumeCursor must be non-negative: " + resumeCursor);
            }
            if (failoverResumeCursor < -1) {
                throw new IllegalArgumentException(
                        "failoverResumeCursor must be >= -1 (-1 = absent): " + failoverResumeCursor);
            }
            Objects.requireNonNull(edgeId, "edgeId must not be null");
        }

        /**
         * The effective resume cursor a v1 server tails from: the larger of
         * {@code resumeCursor} and {@code failoverResumeCursor} (the latter ignored
         * when absent, i.e. {@code -1}). See the §3 failover-resume reserved field note.
         */
        public long effectiveResumeCursor() {
            return Math.max(resumeCursor, failoverResumeCursor);
        }

        @Override
        public FrameType type() {
            return FrameType.SUBSCRIBE;
        }
    }

    /** The subscribe-time mode the server chose for a session (§3). */
    enum Mode {
        /** The edge's cursor is recoverable from the tail; stream forward immediately. */
        TAIL,
        /** The edge needs a snapshot first (cursor behind the cache, or a fresh bootstrap). */
        SNAPSHOT_FIRST
    }

    /**
     * Server→edge subscription acknowledgement (§3).
     *
     * @param latestSeq the highest applied-mutation seq S the server currently holds
     * @param mode      {@link Mode#TAIL} or {@link Mode#SNAPSHOT_FIRST}
     */
    record SubscribeOk(long latestSeq, Mode mode) implements EdgeFrame {

        public SubscribeOk {
            Objects.requireNonNull(mode, "mode must not be null");
        }

        @Override
        public FrameType type() {
            return FrameType.SUBSCRIBE_OK;
        }
    }

    /**
     * Server→edge notification batch (§3; ADR-0038). One {@code NOTIFY} frame carries
     * N <b>consecutive, verbatim</b> {@link CommitNotification}s — the leader-signed
     * delta chain, never merged or coalesced. The batch is bounded at encode by
     * {@code batchMaxNotifications} / {@code batchMaxBytes} (CT-17).
     *
     * @param notifications the consecutive notifications, in ascending seq order (non-empty
     *                      in practice; the empty batch is a valid encoded edge case the
     *                      golden fixture pins)
     */
    record Notify(List<CommitNotification> notifications) implements EdgeFrame {

        public Notify {
            Objects.requireNonNull(notifications, "notifications must not be null");
            notifications = List.copyOf(notifications);
        }

        @Override
        public FrameType type() {
            return FrameType.NOTIFY;
        }
    }

    /**
     * Server→edge snapshot-transfer header (§3): a chunked snapshot follows
     * (RR-019 lesson — chunked from day one).
     *
     * @param snapshotSeq the applied-mutation seq S the snapshot encodes
     * @param chunkCount  the number of {@link SnapshotChunk} frames that follow
     * @param totalBytes  the total snapshot byte length (sum of all chunk payloads)
     */
    record SnapshotBegin(long snapshotSeq, int chunkCount, long totalBytes) implements EdgeFrame {

        public SnapshotBegin {
            if (snapshotSeq < 0) {
                throw new IllegalArgumentException("snapshotSeq must be non-negative: " + snapshotSeq);
            }
            if (chunkCount < 0) {
                throw new IllegalArgumentException("chunkCount must be non-negative: " + chunkCount);
            }
            if (totalBytes < 0) {
                throw new IllegalArgumentException("totalBytes must be non-negative: " + totalBytes);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.SNAPSHOT_BEGIN;
        }
    }

    /**
     * Server→edge snapshot chunk (§3). Each chunk's payload is bounded at
     * {@code MAX_SNAPSHOT_CHUNK_BYTES} (1 MiB) and CRC-protected by the frame trailer.
     *
     * @param index the 0-based chunk index
     * @param bytes the chunk payload (a slice of the ADR-0028 snapshot bytes)
     */
    record SnapshotChunk(int index, byte[] bytes) implements EdgeFrame {

        public SnapshotChunk {
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative: " + index);
            }
            Objects.requireNonNull(bytes, "bytes must not be null");
            bytes = bytes.clone();
        }

        /** Returns a defensive copy of the chunk bytes. */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** Internal zero-copy accessor for the codec (callers MUST NOT mutate). */
        byte[] bytesUnsafe() {
            return bytes;
        }

        @Override
        public FrameType type() {
            return FrameType.SNAPSHOT_CHUNK;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SnapshotChunk that
                    && this.index == that.index
                    && java.util.Arrays.equals(this.bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * index + java.util.Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "SnapshotChunk[index=" + index + ", len=" + bytes.length + "]";
        }
    }

    /**
     * Server→edge snapshot-transfer trailer (§3): the edge sets cursor =
     * {@code snapshotSeq} and resumes tailing.
     *
     * @param snapshotSeq the applied-mutation seq S the completed snapshot encodes
     */
    record SnapshotEnd(long snapshotSeq) implements EdgeFrame {

        public SnapshotEnd {
            if (snapshotSeq < 0) {
                throw new IllegalArgumentException("snapshotSeq must be non-negative: " + snapshotSeq);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.SNAPSHOT_END;
        }
    }

    /**
     * Edge→server cursor acknowledgement (§3): the highest applied-mutation seq S the
     * edge has applied. Drives outbound flow-control / ack-lag accounting (CT-26) and is
     * the C4 slow-consumer signal.
     *
     * @param seq the highest applied seq S
     */
    record CursorAck(long seq) implements EdgeFrame {

        public CursorAck {
            if (seq < 0) {
                throw new IllegalArgumentException("seq must be non-negative: " + seq);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.CURSOR_ACK;
        }
    }

    /**
     * Server→edge heartbeat (§3). C1 ships this as a <b>carrier only</b> — the
     * idle-staleness frontier measure it feeds is deferred to C2 behind ADR-0039 (design
     * review B-1). It carries the server's latest seq and wall clock so the edge can
     * later compute a covered-frontier staleness.
     *
     * @param latestSeq        the server's highest applied-mutation seq S at emit time
     * @param serverNowMillis  the server's wall clock at emit time
     */
    record Heartbeat(long latestSeq, long serverNowMillis) implements EdgeFrame {

        public Heartbeat {
            if (serverNowMillis < 0) {
                throw new IllegalArgumentException("serverNowMillis must be non-negative: " + serverNowMillis);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.HEARTBEAT;
        }
    }

    /**
     * Either-direction error / close frame (§3). The {@code code} is the fixed
     * {@link ErrorCode} taxonomy; {@code message} is diagnostic only (never a structured
     * cause).
     *
     * @param code    the taxonomy code
     * @param message a human-readable diagnostic (never null; may be empty)
     */
    record ErrorClose(ErrorCode code, String message) implements EdgeFrame {

        public ErrorClose {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }

        @Override
        public FrameType type() {
            return FrameType.ERROR_CLOSE;
        }
    }
}

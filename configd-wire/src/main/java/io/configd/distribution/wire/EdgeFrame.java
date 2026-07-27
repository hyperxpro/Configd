package io.configd.distribution.wire;

import io.configd.common.auth.Credential;
import io.configd.distribution.CommitNotification;

import java.util.List;
import java.util.Objects;

/**
 * The frame model for the edge streaming path. A sealed family of immutable records - one
 * per frame type - that {@link EdgeFrameCodec} encodes to / decodes from the
 * length-prefixed CRC32C-checked wire format.
 *
 * <p><b>Transport-free by construction.</b> No {@code java.net}, socket, or TLS type
 * appears anywhere in this hierarchy or in {@link EdgeFrameCodec}; the only boundary to
 * the transport is the session core's {@code TransportSink}. This keeps the protocol model
 * fully unit- and golden-fixture-testable without a network.
 *
 * <p><b>Direction:</b> {@link Subscribe} and {@link CursorAck} are edge->server;
 * {@link SubscribeOk}, {@link Notify}, {@link SnapshotBegin}, {@link SnapshotChunk},
 * {@link SnapshotEnd}, {@link Heartbeat} are server->edge; {@link ErrorClose} is either.
 *
 * <p><b>Watch frames (0x02 only).</b> {@link WatchCreate} and {@link WatchCancel} are
 * client->server; {@link WatchCreated}, {@link WatchEvent}, {@link WatchProgress},
 * {@link WatchCanceled}, {@link WatchSnapshotBegin}, {@link WatchSnapshotChunk}, and
 * {@link WatchSnapshotEnd} are server->client. They form the client-facing watch surface
 * (multiplex / filter veneer over the connection-level fan-out) and are encodable/decodable
 * <b>only</b> under {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V2} (W1-3 / W5-11). They carry
 * the per-shard {@link WatchCursor} vector (never a scalar, even at {@code N = 1}; W1-1).
 */
public sealed interface EdgeFrame
        permits EdgeFrame.Subscribe, EdgeFrame.SubscribeOk, EdgeFrame.Notify,
        EdgeFrame.SnapshotBegin, EdgeFrame.SnapshotChunk, EdgeFrame.SnapshotEnd,
        EdgeFrame.CursorAck, EdgeFrame.Heartbeat, EdgeFrame.ErrorClose,
        EdgeFrame.WatchCreate, EdgeFrame.WatchCancel, EdgeFrame.WatchCreated,
        EdgeFrame.WatchEvent, EdgeFrame.WatchProgress, EdgeFrame.WatchCanceled,
        EdgeFrame.WatchSnapshotBegin, EdgeFrame.WatchSnapshotChunk, EdgeFrame.WatchSnapshotEnd,
        EdgeFrame.Auth, EdgeFrame.RefreshAuth {

    /** The wire type code carried in the frame header. */
    FrameType type();

    // Watch-frame constants (W5-2 / W5-4a). The wire carries raw u8 fields with these
    // named values; the semantic enums (target kind, scope, mutation kind) live in the
    // veneer layer, keeping this wire model dependency-free.

    /** {@link WatchCreate} flag bit0: full_chain_verify (untrusted-edge verbatim mode; W8-4). */
    int WATCH_FLAG_FULL_CHAIN_VERIFY = 0x01;
    /** {@link WatchCreate} flag bit1: prev_value (pre-image; the server MAY leave it unsupported; W5-4a). */
    int WATCH_FLAG_PREV_VALUE = 0x02;
    /** {@link WatchCreate} flag bit2: with_initial_snapshot (request existing state; W5-4a). */
    int WATCH_FLAG_WITH_INITIAL_SNAPSHOT = 0x04;

    /** {@link WatchCreate} target_kind: a concrete key (one shard). */
    int WATCH_TARGET_KEY = 0;
    /** {@link WatchCreate} target_kind: a subtree prefix (scatter-gather). */
    int WATCH_TARGET_PREFIX = 1;
    /** {@link WatchCreate} target_kind: the whole scope (root); path MUST be empty. */
    int WATCH_TARGET_FULL = 2;

    /** {@link WatchChange} kind: PUT (a value is present; val_len &ge; 0). */
    int CHANGE_KIND_PUT = 0;
    /** {@link WatchChange} kind: DELETE (no value; val_len == -1). */
    int CHANGE_KIND_DELETE = 1;

    /**
     * Edge->server subscription request (one per connection). The subscription is either
     * prefix-scoped or full-store; the prefix set is echoed to the edge as a storage/serving
     * filter only - the server always streams the full signed chain regardless of prefixes.
     *
     * @param fullStore            true means subscribe to the whole store; when true
     *                             {@code prefixes} must be empty
     * @param prefixes             the subscribed key prefixes (empty when {@code fullStore})
     * @param resumeCursor         the applied-mutation seq S the edge has already applied
     *                             (0 = fresh subscriber)
     * @param failoverResumeCursor RESERVED: the cursor obtained from a PREVIOUS fan-out
     *                             endpoint, for the edge-failover clause. The edge populates
     *                             it; the server treats it as the resume cursor when it exceeds
     *                             {@code resumeCursor}. {@code -1} means "not present".
     * @param edgeId               the edge identity (bound to the mTLS cert identity)
     * @param acceptsFiltered      the edge advertises it understands the server-side-filtered
     *                             stream semantics (a dense covered-seq cursor advanced on the
     *                             HEARTBEAT, a forward-only version chain). Encoded ONLY under
     *                             {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V3}; a {@code 0x01}/
     *                             {@code 0x02} decode always yields {@code false}. A full-store
     *                             subscription MUST set it {@code false} (a root edge wants the
     *                             whole chain). See ADR-0045.
     */
    record Subscribe(
            boolean fullStore,
            List<String> prefixes,
            long topologyEpoch,
            long resumeCursor,
            long failoverResumeCursor,
            String edgeId,
            boolean acceptsFiltered
    ) implements EdgeFrame {

        public Subscribe {
            Objects.requireNonNull(prefixes, "prefixes must not be null");
            prefixes = List.copyOf(prefixes);
            if (fullStore && !prefixes.isEmpty()) {
                throw new IllegalArgumentException(
                        "full-store subscription must carry no prefixes: " + prefixes);
            }
            if (fullStore && acceptsFiltered) {
                throw new IllegalArgumentException(
                        "full-store subscription must not accept server-side filtering");
            }
            // The resume token binds the topology epoch: 0 is reserved-illegal (pre-epoch).
            if (topologyEpoch <= WatchCursor.EPOCH_UNSET) {
                throw new IllegalArgumentException(
                        "topologyEpoch must be in [1, 2^63) (0 is reserved-illegal): " + topologyEpoch);
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
         * A subscription at the static topology epoch ({@link WatchCursor#INITIAL_TOPOLOGY_EPOCH})
         * that does not opt into server-side filtering - the convenience shape for a single-epoch
         * deployment. A caller tracking multiple topology epochs MUST use the canonical constructor
         * and pass the live epoch.
         */
        public Subscribe(boolean fullStore, List<String> prefixes, long resumeCursor,
                         long failoverResumeCursor, String edgeId) {
            this(fullStore, prefixes, WatchCursor.INITIAL_TOPOLOGY_EPOCH, resumeCursor,
                    failoverResumeCursor, edgeId, false);
        }

        /**
         * A subscription at the static topology epoch ({@link WatchCursor#INITIAL_TOPOLOGY_EPOCH})
         * with an explicit {@code acceptsFiltered} opt-in. A caller tracking multiple topology epochs
         * MUST use the canonical constructor and pass the live epoch.
         */
        public Subscribe(boolean fullStore, List<String> prefixes, long resumeCursor,
                         long failoverResumeCursor, String edgeId, boolean acceptsFiltered) {
            this(fullStore, prefixes, WatchCursor.INITIAL_TOPOLOGY_EPOCH, resumeCursor,
                    failoverResumeCursor, edgeId, acceptsFiltered);
        }

        /**
         * The effective resume cursor the server tails from: the larger of
         * {@code resumeCursor} and {@code failoverResumeCursor} (the latter ignored when
         * absent, i.e. {@code -1}).
         */
        public long effectiveResumeCursor() {
            return Math.max(resumeCursor, failoverResumeCursor);
        }

        @Override
        public FrameType type() {
            return FrameType.SUBSCRIBE;
        }
    }

    /** The subscribe-time mode the server chose for a session. */
    enum Mode {
        /** The edge's cursor is recoverable from the tail; stream forward immediately. */
        TAIL,
        /** The edge needs a snapshot first (cursor behind the cache, or a fresh bootstrap). */
        SNAPSHOT_FIRST
    }

    /**
     * Server->edge subscription acknowledgement.
     *
     * @param latestSeq the highest applied-mutation seq S the server currently holds
     * @param mode      {@link Mode#TAIL} or {@link Mode#SNAPSHOT_FIRST}
     * @param filtered  the server confirms it is filtering this session server-side (the edge
     *                  then selects the filtered-stream apply mode). Encoded ONLY under
     *                  {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V3}; a {@code 0x01}/{@code 0x02}
     *                  decode always yields {@code false} = the byte-identical full-chain
     *                  session. See ADR-0045.
     */
    record SubscribeOk(long latestSeq, Mode mode, boolean filtered) implements EdgeFrame {

        public SubscribeOk {
            Objects.requireNonNull(mode, "mode must not be null");
        }

        /**
         * An unfiltered (full-chain) acknowledgement - the byte-identical legacy shape.
         * Existing callers use this arity unchanged.
         */
        public SubscribeOk(long latestSeq, Mode mode) {
            this(latestSeq, mode, false);
        }

        @Override
        public FrameType type() {
            return FrameType.SUBSCRIBE_OK;
        }
    }

    /**
     * Server->edge notification batch. One {@code NOTIFY} frame carries N
     * <b>consecutive, verbatim</b> {@link CommitNotification}s - the leader-signed delta
     * chain, never merged or coalesced. The batch is bounded at encode by
     * {@code batchMaxNotifications} / {@code batchMaxBytes}.
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
     * Server->edge snapshot-transfer header: a chunked snapshot follows. Chunked from
     * day one to bound per-frame memory allocation.
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
     * Server->edge snapshot chunk. Each chunk's payload is bounded at
     * {@code MAX_SNAPSHOT_CHUNK_BYTES} (1 MiB) and CRC-protected by the frame trailer.
     *
     * @param index the 0-based chunk index
     * @param bytes the chunk payload (a slice of the snapshot bytes)
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

        /** The chunk payload length in bytes (no defensive copy - for accumulation accounting). */
        public int length() {
            return bytes.length;
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
     * Server->edge snapshot-transfer trailer: the edge sets its cursor to
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
     * Edge->server cursor acknowledgement: the highest applied-mutation seq S the edge has
     * applied. Drives outbound flow-control / ack-lag accounting and is the slow-consumer
     * signal.
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
     * Server->edge heartbeat. Carries the server's latest seq and wall clock so the edge
     * can compute a covered-frontier staleness.
     *
     * @param latestSeq       the server's highest applied-mutation seq S at emit time
     * @param serverNowMillis the server's wall clock at emit time
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
     * Either-direction error / close frame. The {@code code} is the fixed
     * {@link ErrorCode} taxonomy; {@code message} is diagnostic only (never a structured
     * cause on the wire).
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

    // Auth-phase frames (0x04 only, version-pin-exempt; AU3-3).

    /**
     * Client->server auth-phase frame presenting a credential to authenticate the connection (AU3-3).
     * Encodable/decodable ONLY under {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V4} and version-pin-exempt.
     * The {@link Credential} is constrained to the two frame-carriable shapes - a bearer token or a basic
     * user+password; a client certificate is an mTLS handshake artifact (never sent in a frame), so it is
     * rejected here. The credential's {@code toString} is redacted, so the frame never logs the secret.
     *
     * @param credential a {@link Credential.BearerToken} or {@link Credential.BasicCredential}
     */
    record Auth(Credential credential) implements EdgeFrame {

        public Auth {
            Objects.requireNonNull(credential, "credential must not be null");
            requireFrameCarriable(credential);
        }

        @Override
        public FrameType type() {
            return FrameType.AUTH;
        }
    }

    /**
     * Client->server refresh of an already-authenticated connection: presents a fresh credential to
     * extend the session (AU3-3). Identical payload shape to {@link Auth}; a distinct type so the intent
     * is self-describing on the wire.
     *
     * @param credential a {@link Credential.BearerToken} or {@link Credential.BasicCredential}
     */
    record RefreshAuth(Credential credential) implements EdgeFrame {

        public RefreshAuth {
            Objects.requireNonNull(credential, "credential must not be null");
            requireFrameCarriable(credential);
        }

        @Override
        public FrameType type() {
            return FrameType.REFRESH_AUTH;
        }
    }

    /**
     * A credential that can ride an AUTH/REFRESH_AUTH frame is a bearer token or a basic user+password.
     * A {@link Credential.ClientCertificate} is an mTLS handshake artifact and is never framed.
     */
    private static void requireFrameCarriable(Credential c) {
        if (!(c instanceof Credential.BearerToken || c instanceof Credential.BasicCredential)) {
            throw new IllegalArgumentException("an AUTH/REFRESH_AUTH frame credential must be a BearerToken"
                    + " or BasicCredential, not " + c.getClass().getSimpleName());
        }
    }

    // Watch frames (0x02 only). Payload byte layouts are normative (sections
    // 5.2-5.8 of the RFC); see EdgeFrameCodec for the encode/decode discipline.

    /**
     * Client->server create/resume of a watch. Wire payload:
     * {@code [watchId u64][scope u8][targetKind u8][pathLen u32][path][cursor][flags u8]}.
     *
     * <p>A resume is just a {@code WatchCreate} carrying the saved {@link WatchCursor}
     * (W5-4); there is no separate resume frame. {@code path} is the UTF-8 canonical path
     * for a KEY/PREFIX target and MUST be empty for a {@link #WATCH_TARGET_FULL} target
     * (W5-4 - the load-bearing structural invariant enforced here, mirroring
     * {@link Subscribe}'s full-store/prefixes rule; KEY/PREFIX path-grammar validation is a
     * session-layer concern surfaced as {@code BAD_SUBSCRIBE}, not a codec concern).
     *
     * @param watchId    client-assigned multiplex id, unique per connection (W2-8); an
     *                   opaque {@code uint64} (no sign constraint)
     * @param scope      the {@code ConfigScope} as a {@code u8} (0..255)
     * @param targetKind {@link #WATCH_TARGET_KEY}/{@link #WATCH_TARGET_PREFIX}/{@link #WATCH_TARGET_FULL}
     *                   as a {@code u8}
     * @param path       UTF-8 canonical path bytes (empty iff {@code targetKind == FULL})
     * @param cursor     the resume {@link WatchCursor} vector (empty means "from now per shard")
     * @param flags      the {@code u8} flag bits ({@link #WATCH_FLAG_FULL_CHAIN_VERIFY} etc.)
     */
    record WatchCreate(long watchId, int scope, int targetKind, byte[] path,
                       WatchCursor cursor, int flags) implements EdgeFrame {

        public WatchCreate {
            if (scope < 0 || scope > 0xFF) {
                throw new IllegalArgumentException("scope must fit a u8 (0..255): " + scope);
            }
            if (targetKind < 0 || targetKind > 0xFF) {
                throw new IllegalArgumentException("targetKind must fit a u8 (0..255): " + targetKind);
            }
            if (flags < 0 || flags > 0xFF) {
                throw new IllegalArgumentException("flags must fit a u8 (0..255): " + flags);
            }
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(cursor, "cursor must not be null");
            if (targetKind == WATCH_TARGET_FULL && path.length != 0) {
                throw new IllegalArgumentException(
                        "FULL target must carry an empty path, got " + path.length + " bytes");
            }
            path = path.clone();
        }

        /** Returns a defensive copy of the path bytes. */
        @Override
        public byte[] path() {
            return path.clone();
        }

        /** Internal zero-copy accessor for the codec (callers MUST NOT mutate). */
        byte[] pathUnsafe() {
            return path;
        }

        /** True iff flag bit0 (full_chain_verify) is set (W8-4). */
        public boolean fullChainVerify() {
            return (flags & WATCH_FLAG_FULL_CHAIN_VERIFY) != 0;
        }

        /** True iff flag bit1 (prev_value) is set (W5-4a). */
        public boolean prevValue() {
            return (flags & WATCH_FLAG_PREV_VALUE) != 0;
        }

        /** True iff flag bit2 (with_initial_snapshot) is set (W5-4a). */
        public boolean withInitialSnapshot() {
            return (flags & WATCH_FLAG_WITH_INITIAL_SNAPSHOT) != 0;
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_CREATE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WatchCreate that
                    && this.watchId == that.watchId
                    && this.scope == that.scope
                    && this.targetKind == that.targetKind
                    && this.flags == that.flags
                    && this.cursor.equals(that.cursor)
                    && java.util.Arrays.equals(this.path, that.path);
        }

        @Override
        public int hashCode() {
            int h = Long.hashCode(watchId);
            h = 31 * h + scope;
            h = 31 * h + targetKind;
            h = 31 * h + flags;
            h = 31 * h + cursor.hashCode();
            h = 31 * h + java.util.Arrays.hashCode(path);
            return h;
        }

        @Override
        public String toString() {
            return "WatchCreate[watchId=" + watchId + ", scope=" + scope + ", targetKind="
                    + targetKind + ", pathLen=" + path.length + ", cursor=" + cursor
                    + ", flags=0x" + Integer.toHexString(flags) + "]";
        }
    }

    /**
     * Client->server cancel of a watch by {@code watch_id}. The id is NOT reused (W2-8).
     * Wire payload: {@code [watchId u64]}.
     *
     * @param watchId the watch to cancel (opaque {@code uint64})
     */
    record WatchCancel(long watchId) implements EdgeFrame {

        @Override
        public FrameType type() {
            return FrameType.WATCH_CANCEL;
        }
    }

    /**
     * Server->client acknowledgement of an authorized watch - the FIRST frame for a
     * {@code watch_id} (W5-5). Wire payload:
     * {@code [watchId u64][shardCount u32]( gid u32  latestSeq u64  mode u8 )*shardCount}.
     *
     * @param watchId the acknowledged watch
     * @param shards  the per-shard initial mode vector (one {@link ShardMode} per covered
     *                shard; exactly one element {@code gid=0} at {@code N = 1})
     */
    record WatchCreated(long watchId, List<ShardMode> shards) implements EdgeFrame {

        public WatchCreated {
            Objects.requireNonNull(shards, "shards must not be null");
            shards = List.copyOf(shards);
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_CREATED;
        }
    }

    /**
     * Server->client per-shard change batch, tagged {@code (gid, S)}. Carries the matching
     * changes of <b>exactly one shard-commit</b> (W5-6 - never split, never coalesced).
     * Wire payload:
     * {@code [watchId u64][gid u32][S u64][commitTs u64][changeCount u32]( change )*}.
     *
     * @param watchId  the watch this event belongs to
     * @param gid      the shard group id (a {@code uint32}; raw bits in this {@code int})
     * @param s        the shard's applied-mutation seq for this commit (the cursor advance;
     *                 non-negative)
     * @param commitTs the leader commit wall-clock millis - freshness only, NOT a cursor
     *                 (W3-3); non-negative
     * @param changes  the matching {@link WatchChange}s of this commit (PUT/DELETE)
     */
    record WatchEvent(long watchId, int gid, long s, long commitTs,
                      List<WatchChange> changes) implements EdgeFrame {

        public WatchEvent {
            if (s < 0) {
                throw new IllegalArgumentException("S must be non-negative: " + s);
            }
            if (commitTs < 0) {
                throw new IllegalArgumentException("commitTs must be non-negative: " + commitTs);
            }
            Objects.requireNonNull(changes, "changes must not be null");
            changes = List.copyOf(changes);
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_EVENT;
        }
    }

    /**
     * Server->client bookmark - advances idle cursor components with no events (W5-7; the
     * etcd {@code progress_notify} analog). Wire payload:
     * {@code [watchId u64][cursor][serverNowMillis u64]}.
     *
     * @param watchId         the watch
     * @param cursor          the per-covered-shard current {@code S} (the bookmark; W4-4)
     * @param serverNowMillis the server wall-clock for the staleness frontier (non-negative)
     */
    record WatchProgress(long watchId, WatchCursor cursor, long serverNowMillis) implements EdgeFrame {

        public WatchProgress {
            Objects.requireNonNull(cursor, "cursor must not be null");
            if (serverNowMillis < 0) {
                throw new IllegalArgumentException("serverNowMillis must be non-negative: " + serverNowMillis);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_PROGRESS;
        }
    }

    /**
     * Server->client terminal per-watch close - terminates ONE watch, not the connection
     * (W5-9). Wire payload:
     * {@code [watchId u64][code u8][hasOldest u8][cursor (iff hasOldest==1)][msgLen u32][msg]}.
     *
     * @param watchId the closed watch
     * @param code    the {@link ErrorCode} (e.g. {@link ErrorCode#NOT_AUTHORIZED} for an
     *                authz reject, {@link ErrorCode#GAP_UNRECOVERABLE} for a too-old resume)
     * @param oldest  the per-shard {@code oldestRetainedSeq} vector for the GAP case, or
     *                {@code null} when absent (encodes {@code hasOldest})
     * @param message a human-readable diagnostic (never null; may be empty)
     */
    record WatchCanceled(long watchId, ErrorCode code, WatchCursor oldest, String message) implements EdgeFrame {

        public WatchCanceled {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
            // oldest is nullable: present iff has_oldest == 1 on the wire.
        }

        /** True iff a per-shard {@code oldestRetainedSeq} vector is present (the GAP case). */
        public boolean hasOldest() {
            return oldest != null;
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_CANCELED;
        }
    }

    /**
     * Server->client per-{@code (watch_id, gid)} catch-up snapshot header. Uses the same
     * chunked, backpressure-paced, cutover-after-END mechanism as the connection-level
     * snapshot. Wire payload:
     * {@code [watchId u64][gid u32][snapshotSeq u64][chunkCount u32][totalBytes u64]}.
     *
     * @param watchId     the watch
     * @param gid         the lagging shard's group id
     * @param snapshotSeq the applied-mutation seq the snapshot encodes (non-negative)
     * @param chunkCount  the number of {@link WatchSnapshotChunk} frames that follow (&ge; 0)
     * @param totalBytes  the total snapshot byte length (non-negative)
     */
    record WatchSnapshotBegin(long watchId, int gid, long snapshotSeq, int chunkCount,
                              long totalBytes) implements EdgeFrame {

        public WatchSnapshotBegin {
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
            return FrameType.WATCH_SNAPSHOT_BEGIN;
        }
    }

    /**
     * Server->client per-{@code (watch_id, gid)} catch-up snapshot chunk. Wire payload:
     * {@code [watchId u64][gid u32][index u32][bytes]}, where {@code bytes} is the rest of
     * the frame, capped at {@link EdgeFrameCodec#MAX_SNAPSHOT_CHUNK_BYTES} (enforced by the
     * codec, mirroring {@link SnapshotChunk}).
     *
     * @param watchId the watch
     * @param gid     the shard group id
     * @param index   the 0-based chunk index
     * @param bytes   the chunk payload (a slice of the snapshot bytes)
     */
    record WatchSnapshotChunk(long watchId, int gid, int index, byte[] bytes) implements EdgeFrame {

        public WatchSnapshotChunk {
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
            return FrameType.WATCH_SNAPSHOT_CHUNK;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WatchSnapshotChunk that
                    && this.watchId == that.watchId
                    && this.gid == that.gid
                    && this.index == that.index
                    && java.util.Arrays.equals(this.bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            int h = Long.hashCode(watchId);
            h = 31 * h + gid;
            h = 31 * h + index;
            h = 31 * h + java.util.Arrays.hashCode(bytes);
            return h;
        }

        @Override
        public String toString() {
            return "WatchSnapshotChunk[watchId=" + watchId + ", gid=" + gid + ", index="
                    + index + ", len=" + bytes.length + "]";
        }
    }

    /**
     * Server->client per-{@code (watch_id, gid)} catch-up snapshot trailer; on receipt the
     * driver sets {@code cursor[gid] = snapshotSeq} and resumes tailing (W5-10). Wire
     * payload: {@code [watchId u64][gid u32][snapshotSeq u64]}.
     *
     * @param watchId     the watch
     * @param gid         the shard group id
     * @param snapshotSeq the applied-mutation seq the completed snapshot encodes (non-negative)
     */
    record WatchSnapshotEnd(long watchId, int gid, long snapshotSeq) implements EdgeFrame {

        public WatchSnapshotEnd {
            if (snapshotSeq < 0) {
                throw new IllegalArgumentException("snapshotSeq must be non-negative: " + snapshotSeq);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.WATCH_SNAPSHOT_END;
        }
    }

    /**
     * One per-shard entry in a {@link WatchCreated} initial-mode vector (W5-5).
     *
     * @param gid       the shard group id (a {@code uint32}; raw bits in this {@code int})
     * @param latestSeq the shard's current applied-mutation seq (non-negative)
     * @param mode      {@link Mode#TAIL} (cursor recoverable) or {@link Mode#SNAPSHOT_FIRST}
     *                  (a catch-up snapshot follows for this {@code (watchId, gid)})
     */
    record ShardMode(int gid, long latestSeq, Mode mode) {

        public ShardMode {
            if (latestSeq < 0) {
                throw new IllegalArgumentException("latestSeq must be non-negative: " + latestSeq);
            }
            Objects.requireNonNull(mode, "mode must not be null");
        }
    }

    /**
     * One change entry in a {@link WatchEvent}. A PUT carries a (possibly empty) value; a
     * DELETE carries no value. On the wire the entry is
     * {@code [keyLen u32][key][kind u8][valLen i32][val]}, where {@code valLen} is the
     * <b>sole signed</b> length width among the watch frames (W5-6):
     * {@code valLen >= 0} means value present ({@code 0} = empty value present);
     * {@code valLen == -1} means no value (a DELETE). This record couples {@code kind} and
     * {@code value} so the two are always consistent: a PUT MUST carry a non-null value, a
     * DELETE MUST carry a null value - a mismatched wire combination decodes as
     * {@link ErrorCode#FRAME_CORRUPT}.
     *
     * @param key   the changed key (UTF-8 on the wire; never null)
     * @param kind  {@link #CHANGE_KIND_PUT} or {@link #CHANGE_KIND_DELETE}
     * @param value the value bytes for a PUT (non-null, may be empty); {@code null} for a
     *              DELETE
     */
    record WatchChange(String key, int kind, byte[] value) {

        public WatchChange {
            Objects.requireNonNull(key, "key must not be null");
            if (kind != CHANGE_KIND_PUT && kind != CHANGE_KIND_DELETE) {
                throw new IllegalArgumentException("kind must be 0 (PUT) or 1 (DELETE): " + kind);
            }
            if (kind == CHANGE_KIND_DELETE) {
                if (value != null) {
                    throw new IllegalArgumentException("DELETE change must carry no value (val_len == -1)");
                }
            } else { // PUT
                if (value == null) {
                    throw new IllegalArgumentException(
                            "PUT change must carry a value (use an empty array for an empty value)");
                }
                value = value.clone();
            }
        }

        public static WatchChange put(String key, byte[] value) {
            return new WatchChange(key, CHANGE_KIND_PUT, value);
        }

        public static WatchChange delete(String key) {
            return new WatchChange(key, CHANGE_KIND_DELETE, null);
        }

        /** Returns defensive copy, or null for DELETE. */
        @Override
        public byte[] value() {
            return value == null ? null : value.clone();
        }

        /** Unsafe zero-copy accessor for codec use; callers MUST NOT mutate. */
        byte[] valueUnsafe() {
            return value;
        }

        /** True iff DELETE (val_len == -1 on wire). */
        public boolean isDelete() {
            return kind == CHANGE_KIND_DELETE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WatchChange that
                    && this.kind == that.kind
                    && this.key.equals(that.key)
                    && java.util.Arrays.equals(this.value, that.value);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * key.hashCode() + kind) + java.util.Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "WatchChange[key=" + key + ", kind=" + (isDelete() ? "DELETE" : "PUT")
                    + ", valLen=" + (value == null ? -1 : value.length) + "]";
        }
    }
}

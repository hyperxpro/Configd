package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.AppendEntriesResponse;
import io.configd.raft.InstallSnapshotRequest;
import io.configd.raft.InstallSnapshotResponse;
import io.configd.raft.LogEntry;
import io.configd.raft.RaftMessage;
import io.configd.raft.RequestVoteRequest;
import io.configd.raft.RequestVoteResponse;
import io.configd.raft.TimeoutNowRequest;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec that serializes {@link RaftMessage} to {@link FrameCodec.Frame}
 * and deserializes back. Bridges the consensus-core sealed message types
 * with the transport wire protocol.
 * <p>
 * Payload formats (all big-endian):
 * <ul>
 *   <li>AppendEntriesRequest: [4B leaderId][8B prevLogIndex][8B prevLogTerm]
 *       [8B leaderCommit][4B numEntries][entries...] where each entry is
 *       [8B index][8B term][4B cmdLen][cmdBytes]</li>
 *   <li>AppendEntriesResponse: [1B success][8B matchIndex][4B from]</li>
 *   <li>RequestVoteRequest: [4B candidateId][8B lastLogIndex][8B lastLogTerm]</li>
 *   <li>RequestVoteResponse: [1B voteGranted][4B from]</li>
 *   <li>InstallSnapshotRequest: [4B leaderId][8B lastIncludedIndex][8B lastIncludedTerm]
 *       [4B offset][1B done][4B dataLen][data][4B configLen][configData]</li>
 *   <li>InstallSnapshotResponse: [1B success][4B from][8B lastIncludedIndex]</li>
 *   <li>TimeoutNowRequest: [4B leaderId]</li>
 * </ul>
 */
public final class RaftMessageCodec {

    /**
     * Hard upper bound on the number of entries in a single
     * AppendEntries RPC. Anything beyond this is taken as
     * adversarial / corruption and rejected without allocation —
     * blocks a "32-byte attacker frame, multi-GB heap" amplification.
     * The legitimate Raft path batches at most a few hundred entries.
     */
    public static final int MAX_ENTRIES_PER_APPEND = 10_000;

    /**
     * Hard upper bound on a single LogEntry command in bytes
     * (1 MiB). The configd write path commits configs that fit
     * comfortably in a single TCP segment; anything past this is
     * rejected.
     */
    public static final int MAX_COMMAND_LEN = 1 * 1024 * 1024;

    /**
     * Hard upper bound on a single snapshot data OR cluster-config blob
     * in an {@code InstallSnapshot} RPC.
     *
     * <p>Per-blob ceiling alone is insufficient: an InstallSnapshot
     * carries TWO blobs (data + clusterConfigData). The encoder
     * additionally enforces the combined-fits-in-frame constraint
     * via {@link #checkInstallSnapshotFitsFrame} so that a maximally-
     * sized request is provably encodable AND decodable.
     *
     * <p>4 MiB per blob leaves headroom for both blobs (= 8 MiB) plus
     * the InstallSnapshot fixed header (33 B) plus the FrameCodec
     * header+trailer (22 B), all comfortably under
     * {@link FrameCodec#MAX_FRAME_SIZE} = 16 MiB. Larger snapshots
     * must chunk via the {@code offset}/{@code done} fields.
     */
    public static final int MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024;

    /** Fixed-size portion of an InstallSnapshot payload (in bytes). */
    private static final int INSTALL_SNAPSHOT_FIXED_HEADER =
            4 + 8 + 8 + 4 + 1 + 4 + 4; // leaderId+lastIdx+lastTerm+offset+done+dataLen+configLen

    private RaftMessageCodec() {}

    private static void checkRemaining(ByteBuffer buf, int needed, String field) {
        if (buf.remaining() < needed) {
            throw new IllegalArgumentException(
                    "Truncated " + field + ": need " + needed
                            + " bytes but " + buf.remaining() + " remain");
        }
    }

    private static void checkBlobLen(int declared, int max, ByteBuffer buf, String field) {
        if (declared < 0) {
            throw new IllegalArgumentException(
                    "Negative " + field + " length: " + declared);
        }
        if (declared > max) {
            throw new IllegalArgumentException(
                    field + " length " + declared + " exceeds max " + max);
        }
        if (declared > buf.remaining()) {
            throw new IllegalArgumentException(
                    field + " length " + declared
                            + " exceeds remaining bytes " + buf.remaining());
        }
    }

    private static void checkInstallSnapshotFitsFrame(int dataLen, int configLen) {
        if (dataLen < 0 || configLen < 0) {
            throw new IllegalArgumentException(
                    "Negative blob length: data=" + dataLen + ", config=" + configLen);
        }
        if (dataLen > MAX_SNAPSHOT_BLOB_LEN) {
            throw new IllegalArgumentException(
                    "InstallSnapshot data length " + dataLen
                            + " exceeds max " + MAX_SNAPSHOT_BLOB_LEN);
        }
        if (configLen > MAX_SNAPSHOT_BLOB_LEN) {
            throw new IllegalArgumentException(
                    "InstallSnapshot configData length " + configLen
                            + " exceeds max " + MAX_SNAPSHOT_BLOB_LEN);
        }
        // Combined: payload + FrameCodec header + trailer must fit
        // in MAX_FRAME_SIZE. Use long arithmetic against 32-bit overflow.
        long total = (long) INSTALL_SNAPSHOT_FIXED_HEADER + dataLen + configLen
                + FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE;
        if (total > FrameCodec.MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "InstallSnapshot total " + total
                            + " bytes (data=" + dataLen + ", config=" + configLen
                            + ") exceeds MAX_FRAME_SIZE=" + FrameCodec.MAX_FRAME_SIZE);
        }
    }

    private static void checkAppendEntriesFitsFrame(int payloadSize) {
        long total = (long) payloadSize
                + FrameCodec.HEADER_SIZE + FrameCodec.TRAILER_SIZE;
        if (total > FrameCodec.MAX_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "AppendEntries total " + total + " bytes exceeds MAX_FRAME_SIZE="
                            + FrameCodec.MAX_FRAME_SIZE);
        }
    }

    /**
     * Encodes a RaftMessage into a FrameCodec.Frame.
     *
     * @param message the Raft message to encode
     * @param groupId the Raft group this message belongs to
     * @return the encoded frame
     */
    public static FrameCodec.Frame encode(RaftMessage message, int groupId) {
        return switch (message) {
            case AppendEntriesRequest req -> encodeAppendEntries(req, groupId);
            case AppendEntriesResponse resp -> encodeAppendEntriesResponse(resp, groupId);
            case RequestVoteRequest req -> encodeRequestVote(req, groupId);
            case RequestVoteResponse resp -> encodeRequestVoteResponse(resp, groupId);
            case InstallSnapshotRequest req -> encodeInstallSnapshot(req, groupId);
            case InstallSnapshotResponse resp -> encodeInstallSnapshotResponse(resp, groupId);
            case TimeoutNowRequest req -> encodeTimeoutNow(req, groupId);
        };
    }

    /**
     * Decodes a FrameCodec.Frame into a RaftMessage.
     *
     * @param frame the frame to decode
     * @return the decoded Raft message
     * @throws IllegalArgumentException if the message type is not a Raft message
     */
    public static RaftMessage decode(FrameCodec.Frame frame) {
        return switch (frame.messageType()) {
            case APPEND_ENTRIES -> decodeAppendEntries(frame);
            case APPEND_ENTRIES_RESPONSE -> decodeAppendEntriesResponse(frame);
            case REQUEST_VOTE -> decodeRequestVote(frame, false);
            case REQUEST_VOTE_RESPONSE -> decodeRequestVoteResponse(frame, false);
            case PRE_VOTE -> decodeRequestVote(frame, true);
            case PRE_VOTE_RESPONSE -> decodeRequestVoteResponse(frame, true);
            case INSTALL_SNAPSHOT -> decodeInstallSnapshot(frame);
            case INSTALL_SNAPSHOT_RESPONSE -> decodeInstallSnapshotResponse(frame);
            case TIMEOUT_NOW -> decodeTimeoutNow(frame);
            default -> throw new IllegalArgumentException(
                    "Not a Raft message type: " + frame.messageType());
        };
    }

    // ---- AppendEntries ----

    private static FrameCodec.Frame encodeAppendEntries(AppendEntriesRequest req, int groupId) {
        if (req.entries().size() > MAX_ENTRIES_PER_APPEND) {
            throw new IllegalArgumentException(
                    "AppendEntries entry count " + req.entries().size()
                            + " exceeds max " + MAX_ENTRIES_PER_APPEND);
        }
        int payloadSize = 4 + 8 + 8 + 8 + 4; // leaderId + prevLogIndex + prevLogTerm + leaderCommit + numEntries
        for (LogEntry entry : req.entries()) {
            int cmdLen = entry.command().length;
            if (cmdLen > MAX_COMMAND_LEN) {
                throw new IllegalArgumentException(
                        "LogEntry command length " + cmdLen + " exceeds max " + MAX_COMMAND_LEN);
            }
            payloadSize += 8 + 8 + 4 + cmdLen; // index + term + cmdLen + cmd
        }
        checkAppendEntriesFitsFrame(payloadSize);
        ByteBuffer buf = ByteBuffer.allocate(payloadSize);
        buf.putInt(req.leaderId().id());
        buf.putLong(req.prevLogIndex());
        buf.putLong(req.prevLogTerm());
        buf.putLong(req.leaderCommit());
        buf.putInt(req.entries().size());
        for (LogEntry entry : req.entries()) {
            buf.putLong(entry.index());
            buf.putLong(entry.term());
            buf.putInt(entry.command().length);
            buf.put(entry.command());
        }
        return new FrameCodec.Frame(MessageType.APPEND_ENTRIES, groupId, req.term(), buf.array());
    }

    private static RaftMessage decodeAppendEntries(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        // Fixed header: leaderId(4) + prevLogIndex(8) + prevLogTerm(8)
        //             + leaderCommit(8) + numEntries(4) = 32 bytes.
        checkRemaining(buf, 4 + 8 + 8 + 8 + 4, "AppendEntries header");
        NodeId leaderId = NodeId.of(buf.getInt());
        long prevLogIndex = buf.getLong();
        long prevLogTerm = buf.getLong();
        long leaderCommit = buf.getLong();
        int numEntries = buf.getInt();
        if (numEntries < 0) {
            throw new IllegalArgumentException(
                    "Negative AppendEntries entry count: " + numEntries);
        }
        if (numEntries > MAX_ENTRIES_PER_APPEND) {
            throw new IllegalArgumentException(
                    "AppendEntries entry count " + numEntries
                            + " exceeds max " + MAX_ENTRIES_PER_APPEND);
        }
        // Each entry has a per-entry header of 8+8+4=20 bytes plus a
        // variable command. Reject up front if the declared count
        // cannot possibly fit in the remaining buffer — blocks a
        // tiny adversary frame from triggering a large ArrayList alloc.
        if ((long) numEntries * (8 + 8 + 4) > buf.remaining()) {
            throw new IllegalArgumentException(
                    "AppendEntries declares " + numEntries
                            + " entries but only " + buf.remaining()
                            + " bytes remain");
        }
        List<LogEntry> entries = new ArrayList<>(numEntries);
        for (int i = 0; i < numEntries; i++) {
            checkRemaining(buf, 8 + 8 + 4, "AppendEntries entry header[" + i + "]");
            long index = buf.getLong();
            long term = buf.getLong();
            int cmdLen = buf.getInt();
            checkBlobLen(cmdLen, MAX_COMMAND_LEN, buf, "AppendEntries entry[" + i + "] command");
            byte[] cmd = new byte[cmdLen];
            buf.get(cmd);
            entries.add(new LogEntry(index, term, cmd));
        }
        return new AppendEntriesRequest(frame.term(), leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
    }

    // ---- AppendEntriesResponse ----

    private static FrameCodec.Frame encodeAppendEntriesResponse(AppendEntriesResponse resp, int groupId) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 + 4);
        buf.put((byte) (resp.success() ? 1 : 0));
        buf.putLong(resp.matchIndex());
        buf.putInt(resp.from().id());
        return new FrameCodec.Frame(MessageType.APPEND_ENTRIES_RESPONSE, groupId, resp.term(), buf.array());
    }

    private static RaftMessage decodeAppendEntriesResponse(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 1 + 8 + 4, "AppendEntriesResponse payload");
        boolean success = buf.get() != 0;
        long matchIndex = buf.getLong();
        NodeId from = NodeId.of(buf.getInt());
        return new AppendEntriesResponse(frame.term(), success, matchIndex, from);
    }

    // ---- RequestVote ----

    private static FrameCodec.Frame encodeRequestVote(RequestVoteRequest req, int groupId) {
        ByteBuffer buf = ByteBuffer.allocate(4 + 8 + 8);
        buf.putInt(req.candidateId().id());
        buf.putLong(req.lastLogIndex());
        buf.putLong(req.lastLogTerm());
        MessageType type = req.preVote() ? MessageType.PRE_VOTE : MessageType.REQUEST_VOTE;
        return new FrameCodec.Frame(type, groupId, req.term(), buf.array());
    }

    private static RaftMessage decodeRequestVote(FrameCodec.Frame frame, boolean preVote) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 4 + 8 + 8, "RequestVote payload");
        NodeId candidateId = NodeId.of(buf.getInt());
        long lastLogIndex = buf.getLong();
        long lastLogTerm = buf.getLong();
        return new RequestVoteRequest(frame.term(), candidateId, lastLogIndex, lastLogTerm, preVote);
    }

    // ---- RequestVoteResponse ----

    private static FrameCodec.Frame encodeRequestVoteResponse(RequestVoteResponse resp, int groupId) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 4);
        buf.put((byte) (resp.voteGranted() ? 1 : 0));
        buf.putInt(resp.from().id());
        MessageType type = resp.preVote() ? MessageType.PRE_VOTE_RESPONSE : MessageType.REQUEST_VOTE_RESPONSE;
        return new FrameCodec.Frame(type, groupId, resp.term(), buf.array());
    }

    private static RaftMessage decodeRequestVoteResponse(FrameCodec.Frame frame, boolean preVote) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 1 + 4, "RequestVoteResponse payload");
        boolean voteGranted = buf.get() != 0;
        NodeId from = NodeId.of(buf.getInt());
        return new RequestVoteResponse(frame.term(), voteGranted, from, preVote);
    }

    // ---- InstallSnapshot ----

    private static FrameCodec.Frame encodeInstallSnapshot(InstallSnapshotRequest req, int groupId) {
        byte[] configData = req.clusterConfigData() != null ? req.clusterConfigData() : new byte[0];
        checkInstallSnapshotFitsFrame(req.data().length, configData.length);
        int payloadSize = 4 + 8 + 8 + 4 + 1 + 4 + req.data().length + 4 + configData.length;
        ByteBuffer buf = ByteBuffer.allocate(payloadSize);
        buf.putInt(req.leaderId().id());
        buf.putLong(req.lastIncludedIndex());
        buf.putLong(req.lastIncludedTerm());
        buf.putInt(req.offset());
        buf.put((byte) (req.done() ? 1 : 0));
        buf.putInt(req.data().length);
        buf.put(req.data());
        buf.putInt(configData.length);
        buf.put(configData);
        return new FrameCodec.Frame(MessageType.INSTALL_SNAPSHOT, groupId, req.term(), buf.array());
    }

    private static RaftMessage decodeInstallSnapshot(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        // Fixed header: leaderId(4)+lastIncludedIndex(8)+lastIncludedTerm(8)
        //             +offset(4)+done(1)+dataLen(4) = 29 bytes.
        checkRemaining(buf, 4 + 8 + 8 + 4 + 1 + 4, "InstallSnapshot header");
        NodeId leaderId = NodeId.of(buf.getInt());
        long lastIncludedIndex = buf.getLong();
        long lastIncludedTerm = buf.getLong();
        int offset = buf.getInt();
        boolean done = buf.get() != 0;
        int dataLen = buf.getInt();
        checkBlobLen(dataLen, MAX_SNAPSHOT_BLOB_LEN, buf, "InstallSnapshot data");
        byte[] data = new byte[dataLen];
        buf.get(data);
        byte[] configData = null;
        if (buf.hasRemaining()) {
            checkRemaining(buf, 4, "InstallSnapshot configData length");
            int configLen = buf.getInt();
            if (configLen < 0) {
                // Reject explicitly — `configLen > 0` would silently
                // accept a negative as "no config" and discard the
                // hostile peer's wire violation.
                throw new IllegalArgumentException(
                        "Negative InstallSnapshot configData length: " + configLen);
            }
            if (configLen > 0) {
                checkBlobLen(configLen, MAX_SNAPSHOT_BLOB_LEN, buf, "InstallSnapshot configData");
                configData = new byte[configLen];
                buf.get(configData);
            }
        }
        return new InstallSnapshotRequest(frame.term(), leaderId, lastIncludedIndex, lastIncludedTerm,
                offset, data, done, configData);
    }

    // ---- InstallSnapshotResponse ----

    private static FrameCodec.Frame encodeInstallSnapshotResponse(InstallSnapshotResponse resp, int groupId) {
        // [1B success][4B from][8B lastIncludedIndex]
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 8);
        buf.put((byte) (resp.success() ? 1 : 0));
        buf.putInt(resp.from().id());
        buf.putLong(resp.lastIncludedIndex());
        return new FrameCodec.Frame(MessageType.INSTALL_SNAPSHOT_RESPONSE, groupId, resp.term(), buf.array());
    }

    private static RaftMessage decodeInstallSnapshotResponse(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 1 + 4 + 8, "InstallSnapshotResponse payload");
        boolean success = buf.get() != 0;
        NodeId from = NodeId.of(buf.getInt());
        long lastIncludedIndex = buf.getLong();
        if (lastIncludedIndex < 0) {
            // Reject malformed wire input here so a peer cannot send us
            // a sentinel/garbage value and trigger the record's compact
            // constructor. Wire-validation lives in the codec; the
            // record's invariant is the in-process safety net.
            throw new IllegalArgumentException(
                    "Negative InstallSnapshotResponse lastIncludedIndex: "
                            + lastIncludedIndex);
        }
        return new InstallSnapshotResponse(frame.term(), success, from, lastIncludedIndex);
    }

    // ---- TimeoutNow ----

    private static FrameCodec.Frame encodeTimeoutNow(TimeoutNowRequest req, int groupId) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(req.leaderId().id());
        return new FrameCodec.Frame(MessageType.TIMEOUT_NOW, groupId, req.term(), buf.array());
    }

    private static RaftMessage decodeTimeoutNow(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 4, "TimeoutNow payload");
        NodeId leaderId = NodeId.of(buf.getInt());
        return new TimeoutNowRequest(frame.term(), leaderId);
    }
}

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
import io.configd.raft.WitnessMessage;
import io.configd.raft.WitnessReply;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class RaftMessageCodec {

    
    public static final int MAX_ENTRIES_PER_APPEND = 10_000;

    
    public static final int MAX_COMMAND_LEN = 1 * 1024 * 1024;

    
    public static final int MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024;

    
    public static final int MAX_COALESCED_GROUPS = 1024;

    
    private static final int COALESCED_GROUP_RECORD = 4 + 8 + 4 + 8 + 8 + 8;

    
    private static final int INSTALL_SNAPSHOT_FIXED_HEADER =
            4 + 8 + 8 + 4 + 1 + 4 + 4; // leaderId+lastIdx+lastTerm+offset+done+dataLen+configLen

    
    private static final int WITNESS_BODY_LEN = 8 + 8 + 4 + 8 + 1;

    
    private static final int WITNESS_FLAG_QUERY = 0x01;

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

    
    private static void rejectTrailingBytes(ByteBuffer buf, String field) {
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException(
                    field + " has " + buf.remaining() + " trailing bytes after a fixed-size payload");
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

    
    public static FrameCodec.Frame encode(RaftMessage message, int groupId) {
        return switch (message) {
            case AppendEntriesRequest req -> encodeAppendEntries(req, groupId);
            case AppendEntriesResponse resp -> encodeAppendEntriesResponse(resp, groupId);
            case RequestVoteRequest req -> encodeRequestVote(req, groupId);
            case RequestVoteResponse resp -> encodeRequestVoteResponse(resp, groupId);
            case InstallSnapshotRequest req -> encodeInstallSnapshot(req, groupId);
            case InstallSnapshotResponse resp -> encodeInstallSnapshotResponse(resp, groupId);
            case TimeoutNowRequest req -> encodeTimeoutNow(req, groupId);
            case WitnessMessage m -> encodeWitness(m, groupId);
            case WitnessReply m -> encodeWitnessReply(m, groupId);
        };
    }

    
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
            // A coalesced heartbeat is NOT a RaftMessage (it bundles many groups) - it must be
            // decoded via decodeCoalescedHeartbeat() and demuxed per group. Surface a directional
            // error rather than the generic default so a misroute is loud, not silent.
            case RAFT_COALESCED_HEARTBEAT -> throw new IllegalArgumentException(
                    "CoalescedHeartbeat must be decoded via decodeCoalescedHeartbeat(), not decode()");
            // A witness frame's sender is the transport prefix ({@code InboundMessage.from}), which
            // decode(frame) does not carry - it must be decoded via decodeWitness(frame, from). Surface a
            // directional error rather than the generic default so a misroute is loud, not silent.
            case RAFT_WITNESS, RAFT_WITNESS_REPLY -> throw new IllegalArgumentException(
                    "Witness frames must be decoded via decodeWitness(frame, from), not decode()");
            default -> throw new IllegalArgumentException(
                    "Not a Raft message type: " + frame.messageType());
        };
    }

    
    public static FrameCodec.Frame encodeCoalescedHeartbeat(Map<Integer, AppendEntriesRequest> groupHeartbeats) {
        int n = groupHeartbeats.size();
        if (n == 0) {
            throw new IllegalArgumentException("CoalescedHeartbeat must carry at least one group");
        }
        if (n > MAX_COALESCED_GROUPS) {
            throw new IllegalArgumentException(
                    "CoalescedHeartbeat group count " + n + " exceeds max " + MAX_COALESCED_GROUPS);
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + n * COALESCED_GROUP_RECORD);
        buf.putInt(n);
        for (Map.Entry<Integer, AppendEntriesRequest> e : groupHeartbeats.entrySet()) {
            AppendEntriesRequest ae = e.getValue();
            if (!ae.entries().isEmpty()) {
                // Only heartbeats (empty AppendEntries) are ever coalesced (M3 contract). Reject a
                // non-empty AE rather than silently dropping its entries on the wire.
                throw new IllegalArgumentException(
                        "CoalescedHeartbeat group " + e.getKey() + " carries " + ae.entries().size()
                                + " entries; only empty heartbeats may be coalesced");
            }
            buf.putInt(e.getKey());
            buf.putLong(ae.term());
            buf.putInt(ae.leaderId().id());
            buf.putLong(ae.prevLogIndex());
            buf.putLong(ae.prevLogTerm());
            buf.putLong(ae.leaderCommit());
        }
        return new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, buf.array());
    }

    
    public static Map<Integer, AppendEntriesRequest> decodeCoalescedHeartbeat(FrameCodec.Frame frame) {
        // Defense-in-depth: the sole production caller routes by type, but a type guard here makes the
        // method fail-closed against any future second caller (mirrors the directional throw in decode()).
        if (frame.messageType() != MessageType.RAFT_COALESCED_HEARTBEAT) {
            throw new IllegalArgumentException(
                    "decodeCoalescedHeartbeat called on a " + frame.messageType()
                            + " frame; expected RAFT_COALESCED_HEARTBEAT");
        }
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 4, "CoalescedHeartbeat count");
        int n = buf.getInt();
        if (n < 0) {
            throw new IllegalArgumentException("Negative CoalescedHeartbeat group count: " + n);
        }
        if (n > MAX_COALESCED_GROUPS) {
            throw new IllegalArgumentException(
                    "CoalescedHeartbeat group count " + n + " exceeds max " + MAX_COALESCED_GROUPS);
        }
        // Reject up front if the declared count cannot fit in the remaining buffer - blocks a tiny
        // adversary frame from triggering a large map allocation (mirrors decodeAppendEntries).
        if ((long) n * COALESCED_GROUP_RECORD > buf.remaining()) {
            throw new IllegalArgumentException(
                    "CoalescedHeartbeat declares " + n + " groups but only "
                            + buf.remaining() + " bytes remain");
        }
        Map<Integer, AppendEntriesRequest> out = new LinkedHashMap<>(Math.max(4, n * 2));
        for (int i = 0; i < n; i++) {
            checkRemaining(buf, COALESCED_GROUP_RECORD, "CoalescedHeartbeat group[" + i + "]");
            int groupId = buf.getInt();
            long term = buf.getLong();
            NodeId leaderId = NodeId.of(buf.getInt());
            long prevLogIndex = buf.getLong();
            long prevLogTerm = buf.getLong();
            long leaderCommit = buf.getLong();
            AppendEntriesRequest ae = new AppendEntriesRequest(
                    term, leaderId, prevLogIndex, prevLogTerm, List.of(), leaderCommit);
            if (out.putIfAbsent(groupId, ae) != null) {
                throw new IllegalArgumentException(
                        "CoalescedHeartbeat has duplicate group id: " + groupId);
            }
        }
        if (buf.hasRemaining()) {
            // Strict: a well-formed coalesced heartbeat is exactly count + n records, no padding.
            throw new IllegalArgumentException(
                    "CoalescedHeartbeat has " + buf.remaining() + " trailing bytes after " + n + " groups");
        }
        return out;
    }

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
        // cannot possibly fit in the remaining buffer - blocks a
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
        if (buf.hasRemaining()) {
            // Strict-end: a well-formed AppendEntries is exactly the fixed header plus its
            // declared entries, with no padding. Reject trailing bytes (uniform with the coalesced
            // heartbeat and the edge plane) so a hostile peer cannot smuggle bytes past the grammar.
            throw new IllegalArgumentException(
                    "AppendEntries has " + buf.remaining() + " trailing bytes after " + numEntries + " entries");
        }
        return new AppendEntriesRequest(frame.term(), leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
    }

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
        rejectTrailingBytes(buf, "AppendEntriesResponse");
        return new AppendEntriesResponse(frame.term(), success, matchIndex, from);
    }

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
        rejectTrailingBytes(buf, "RequestVote");
        return new RequestVoteRequest(frame.term(), candidateId, lastLogIndex, lastLogTerm, preVote);
    }

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
        rejectTrailingBytes(buf, "RequestVoteResponse");
        return new RequestVoteResponse(frame.term(), voteGranted, from, preVote);
    }

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
        if (offset < 0) {
            // Reject a negative chunk offset at decode, matching the check on the response's
            // nextExpectedOffset below. Downstream reassembly is a guarded contiguous-prefix, but
            // the wire violation is rejected here where it enters.
            throw new IllegalArgumentException(
                    "Negative InstallSnapshot offset: " + offset);
        }
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
                // Reject explicitly - `configLen > 0` would silently
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
        if (buf.hasRemaining()) {
            // Strict-end: the request is a fixed-shape header + data blob + optional configData
            // blob, nothing more. A frame with configData absent decodes with nothing remaining; a
            // frame that carried configData consumes it exactly. Any bytes past that are padding a
            // hostile peer appended - reject them (uniform with decodeAppendEntries / coalesced HB).
            throw new IllegalArgumentException(
                    "InstallSnapshot has " + buf.remaining() + " trailing bytes after payload");
        }
        return new InstallSnapshotRequest(frame.term(), leaderId, lastIncludedIndex, lastIncludedTerm,
                offset, data, done, configData);
    }

    private static FrameCodec.Frame encodeInstallSnapshotResponse(InstallSnapshotResponse resp, int groupId) {
        // [1B success][4B from][8B lastIncludedIndex][4B nextExpectedOffset]
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + 8 + 4);
        buf.put((byte) (resp.success() ? 1 : 0));
        buf.putInt(resp.from().id());
        buf.putLong(resp.lastIncludedIndex());
        buf.putInt(resp.nextExpectedOffset());
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
        // nextExpectedOffset (chunked-transfer position) is an optional trailing field: decode it
        // when present, default 0 otherwise, so a peer speaking an older wire version that omits it
        // still decodes cleanly - the sender safely re-syncs from offset 0.
        int nextExpectedOffset = 0;
        if (buf.hasRemaining()) {
            checkRemaining(buf, 4, "InstallSnapshotResponse nextExpectedOffset");
            nextExpectedOffset = buf.getInt();
            if (nextExpectedOffset < 0) {
                throw new IllegalArgumentException(
                        "Negative InstallSnapshotResponse nextExpectedOffset: " + nextExpectedOffset);
            }
        }
        // Strict-end AFTER the optional nextExpectedOffset: the absence of the optional field is
        // legitimate (checked by the hasRemaining() gate above), but bytes past a present-or-absent
        // field are padding a hostile peer appended - reject them (matching the strict-end enforced
        // elsewhere in this codec).
        rejectTrailingBytes(buf, "InstallSnapshotResponse");
        return new InstallSnapshotResponse(frame.term(), success, from, lastIncludedIndex, nextExpectedOffset);
    }

    private static FrameCodec.Frame encodeTimeoutNow(TimeoutNowRequest req, int groupId) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(req.leaderId().id());
        return new FrameCodec.Frame(MessageType.TIMEOUT_NOW, groupId, req.term(), buf.array());
    }

    private static RaftMessage decodeTimeoutNow(FrameCodec.Frame frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, 4, "TimeoutNow payload");
        NodeId leaderId = NodeId.of(buf.getInt());
        rejectTrailingBytes(buf, "TimeoutNow");
        return new TimeoutNowRequest(frame.term(), leaderId);
    }

    private static FrameCodec.Frame encodeWitness(WitnessMessage m, int groupId) {
        return witnessFrame(MessageType.RAFT_WITNESS, groupId,
                m.selfAnchorSeq(), m.selfTerm(), m.selfVotedFor(), m.seenOfYouSeq(), m.query());
    }

    private static FrameCodec.Frame encodeWitnessReply(WitnessReply m, int groupId) {
        // A reply never carries the QUERY flag (it IS the answer to one).
        return witnessFrame(MessageType.RAFT_WITNESS_REPLY, groupId,
                m.selfAnchorSeq(), m.selfTerm(), m.selfVotedFor(), m.seenOfYouSeq(), false);
    }

    private static FrameCodec.Frame witnessFrame(MessageType type, int groupId,
            long selfAnchorSeq, long selfTerm, int selfVotedFor, long seenOfYouSeq, boolean query) {
        ByteBuffer buf = ByteBuffer.allocate(WITNESS_BODY_LEN);
        buf.putLong(selfAnchorSeq);
        buf.putLong(selfTerm);
        buf.putInt(selfVotedFor);
        buf.putLong(seenOfYouSeq);
        buf.put((byte) (query ? WITNESS_FLAG_QUERY : 0));
        // The frame-header term mirrors the sender's currentTerm, as every other Raft frame does; the
        // body also carries selfTerm (the authoritative field the decoder reads) as part of the fixed
        // 29-byte body. The sender id is not written - the transport prefix carries it.
        return new FrameCodec.Frame(type, groupId, selfTerm, buf.array());
    }

    
    public static RaftMessage decodeWitness(FrameCodec.Frame frame, NodeId from) {
        MessageType type = frame.messageType();
        if (type != MessageType.RAFT_WITNESS && type != MessageType.RAFT_WITNESS_REPLY) {
            throw new IllegalArgumentException(
                    "decodeWitness called on a " + type + " frame; expected a RAFT_WITNESS[_REPLY]");
        }
        Objects.requireNonNull(from, "from (authenticated witness sender) must not be null");
        ByteBuffer buf = ByteBuffer.wrap(frame.payload());
        checkRemaining(buf, WITNESS_BODY_LEN, "Witness payload");
        long selfAnchorSeq = buf.getLong();
        long selfTerm = buf.getLong();
        int selfVotedFor = buf.getInt();
        long seenOfYouSeq = buf.getLong();
        int flags = buf.get() & 0xFF;
        boolean query = (flags & WITNESS_FLAG_QUERY) != 0;
        // Strict-end: the witness body is exactly WITNESS_BODY_LEN; reject any surplus, matching
        // every other fixed-shape Raft decoder in this codec.
        rejectTrailingBytes(buf, "Witness");
        if (type == MessageType.RAFT_WITNESS) {
            return new WitnessMessage(from, selfAnchorSeq, selfTerm, selfVotedFor, seenOfYouSeq, query);
        }
        return new WitnessReply(from, selfAnchorSeq, selfTerm, selfVotedFor, seenOfYouSeq);
    }
}

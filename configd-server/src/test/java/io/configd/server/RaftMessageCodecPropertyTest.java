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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * jqwik property-based fuzz suite for {@link RaftMessageCodec}.
 *
 * <p>Closes the codec-bounds requirement of Phase 4 (gap-closure §6,
 * row "jqwik for codec bounds") for the consensus wire path. Each
 * Raft RPC type has a roundtrip property; non-Raft frame types and
 * truncated payloads are explicitly rejected.
 */
class RaftMessageCodecPropertyTest {

    @Property(tries = 200)
    void appendEntriesRoundtrip(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long prevLogIndex,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long prevLogTerm,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long leaderCommit,
            @ForAll int groupId,
            @ForAll("entryLists") List<LogEntry> entries) {

        var msg = new AppendEntriesRequest(term, NodeId.of(leaderId),
                prevLogIndex, prevLogTerm, entries, leaderCommit);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        assertEquals(MessageType.APPEND_ENTRIES, frame.messageType());

        var decoded = assertInstanceOf(AppendEntriesRequest.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(leaderId, decoded.leaderId().id());
        assertEquals(prevLogIndex, decoded.prevLogIndex());
        assertEquals(prevLogTerm, decoded.prevLogTerm());
        assertEquals(leaderCommit, decoded.leaderCommit());
        assertEquals(entries.size(), decoded.entries().size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(entries.get(i).index(), decoded.entries().get(i).index());
            assertEquals(entries.get(i).term(), decoded.entries().get(i).term());
            assertArrayEquals(entries.get(i).command(), decoded.entries().get(i).command());
        }
    }

    @Property(tries = 200)
    void appendEntriesResponseRoundtrip(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll boolean success,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long matchIndex,
            @ForAll int from,
            @ForAll int groupId) {

        var msg = new AppendEntriesResponse(term, success, matchIndex, NodeId.of(from));
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        var decoded = assertInstanceOf(AppendEntriesResponse.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(success, decoded.success());
        assertEquals(matchIndex, decoded.matchIndex());
        assertEquals(from, decoded.from().id());
    }

    @Property(tries = 200)
    void requestVoteRoundtripPreservesPreVoteFlag(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int candidateId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long lastLogIndex,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long lastLogTerm,
            @ForAll boolean preVote,
            @ForAll int groupId) {

        var msg = new RequestVoteRequest(term, NodeId.of(candidateId),
                lastLogIndex, lastLogTerm, preVote);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        assertEquals(preVote ? MessageType.PRE_VOTE : MessageType.REQUEST_VOTE,
                frame.messageType());

        var decoded = assertInstanceOf(RequestVoteRequest.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(candidateId, decoded.candidateId().id());
        assertEquals(lastLogIndex, decoded.lastLogIndex());
        assertEquals(lastLogTerm, decoded.lastLogTerm());
        assertEquals(preVote, decoded.preVote());
    }

    @Property(tries = 200)
    void requestVoteResponseRoundtripPreservesPreVoteFlag(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll boolean voteGranted,
            @ForAll int from,
            @ForAll boolean preVote,
            @ForAll int groupId) {

        var msg = new RequestVoteResponse(term, voteGranted, NodeId.of(from), preVote);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        assertEquals(preVote ? MessageType.PRE_VOTE_RESPONSE : MessageType.REQUEST_VOTE_RESPONSE,
                frame.messageType());

        var decoded = assertInstanceOf(RequestVoteResponse.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(voteGranted, decoded.voteGranted());
        assertEquals(from, decoded.from().id());
        assertEquals(preVote, decoded.preVote());
    }

    @Property(tries = 200)
    void installSnapshotRoundtripWithAndWithoutClusterConfig(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long lastIncludedIndex,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long lastIncludedTerm,
            @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int offset,
            @ForAll boolean done,
            @ForAll @Size(max = 1024) byte[] data,
            @ForAll @Size(max = 256) byte[] configData,
            @ForAll boolean withConfig,
            @ForAll int groupId) {

        byte[] config = withConfig ? configData : null;
        var msg = new InstallSnapshotRequest(term, NodeId.of(leaderId),
                lastIncludedIndex, lastIncludedTerm, offset, data, done, config);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);

        var decoded = assertInstanceOf(InstallSnapshotRequest.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(leaderId, decoded.leaderId().id());
        assertEquals(lastIncludedIndex, decoded.lastIncludedIndex());
        assertEquals(lastIncludedTerm, decoded.lastIncludedTerm());
        assertEquals(offset, decoded.offset());
        assertEquals(done, decoded.done());
        assertArrayEquals(data, decoded.data());
        if (withConfig && configData.length > 0) {
            assertArrayEquals(configData, decoded.clusterConfigData());
        }
    }

    @Property(tries = 200)
    void installSnapshotResponseRoundtrip(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll boolean success,
            @ForAll int from,
            @ForAll int groupId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long lastIncludedIndex) {

        var msg = new InstallSnapshotResponse(term, success, NodeId.of(from), lastIncludedIndex);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        var decoded = assertInstanceOf(InstallSnapshotResponse.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(success, decoded.success());
        assertEquals(from, decoded.from().id());
        assertEquals(lastIncludedIndex, decoded.lastIncludedIndex());
    }

    @Property(tries = 100)
    void timeoutNowRoundtrip(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll int groupId) {

        var msg = new TimeoutNowRequest(term, NodeId.of(leaderId));
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        assertEquals(MessageType.TIMEOUT_NOW, frame.messageType());

        var decoded = assertInstanceOf(TimeoutNowRequest.class,
                RaftMessageCodec.decode(frame));
        assertEquals(term, decoded.term());
        assertEquals(leaderId, decoded.leaderId().id());
    }

    /**
     * Frames carrying a non-Raft message type are rejected by the dispatcher,
     * preventing accidental cross-protocol decoding.
     */
    @Property(tries = 50)
    void nonRaftMessageTypesAreRejected(
            @ForAll("nonRaftType") MessageType type,
            @ForAll int groupId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll @Size(max = 64) byte[] payload) {

        FrameCodec.Frame frame = new FrameCodec.Frame(type, groupId, term, payload);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(frame));
    }

    /**
     * Encoded AppendEntries with an entry-count past
     * {@code MAX_ENTRIES_PER_APPEND} is rejected before any list allocation.
     * This is the amplification guard: a 32-byte adversary frame must not
     * be able to provoke a multi-GB heap allocation.
     */
    @Property(tries = 100)
    void appendEntriesWithBogusEntryCountFails(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll @IntRange(min = 10_001, max = Integer.MAX_VALUE) int oversizeCount,
            @ForAll int groupId) {

        var msg = new AppendEntriesRequest(term, NodeId.of(leaderId), 0L, 0L,
                List.of(new LogEntry(1L, 1L, new byte[]{1, 2, 3})), 0L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        byte[] payload = frame.payload().clone();

        // entry count lives at offset 4+8+8+8 = 28
        payload[28] = (byte) ((oversizeCount >>> 24) & 0xFF);
        payload[29] = (byte) ((oversizeCount >>> 16) & 0xFF);
        payload[30] = (byte) ((oversizeCount >>> 8) & 0xFF);
        payload[31] = (byte) (oversizeCount & 0xFF);
        FrameCodec.Frame corrupted = new FrameCodec.Frame(
                frame.messageType(), groupId, term, payload);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(corrupted));
    }

    /**
     * Same guard, but for the per-entry command-length field — a single
     * entry with an oversized cmdLen must not allocate.
     */
    @Property(tries = 100)
    void appendEntriesWithBogusCmdLenFails(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll @IntRange(min = 1_048_577, max = Integer.MAX_VALUE) int oversizeCmdLen,
            @ForAll int groupId) {

        var msg = new AppendEntriesRequest(term, NodeId.of(leaderId), 0L, 0L,
                List.of(new LogEntry(1L, 1L, new byte[]{1, 2, 3})), 0L);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        byte[] payload = frame.payload().clone();

        // After header (4+8+8+8+4=32) and per-entry index+term (8+8=16), cmdLen is at 48
        payload[48] = (byte) ((oversizeCmdLen >>> 24) & 0xFF);
        payload[49] = (byte) ((oversizeCmdLen >>> 16) & 0xFF);
        payload[50] = (byte) ((oversizeCmdLen >>> 8) & 0xFF);
        payload[51] = (byte) (oversizeCmdLen & 0xFF);
        FrameCodec.Frame corrupted = new FrameCodec.Frame(
                frame.messageType(), groupId, term, payload);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(corrupted));
    }

    /**
     * InstallSnapshot decoder must reject any oversized data-length field.
     */
    @Property(tries = 100)
    void installSnapshotWithBogusDataLenFails(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long term,
            @ForAll int leaderId,
            @ForAll @IntRange(min = 16 * 1_048_576 + 1, max = Integer.MAX_VALUE) int oversizeDataLen,
            @ForAll int groupId) {

        var msg = new InstallSnapshotRequest(term, NodeId.of(leaderId), 1L, 1L,
                0, new byte[]{1, 2, 3}, true, null);
        FrameCodec.Frame frame = RaftMessageCodec.encode(msg, groupId);
        byte[] payload = frame.payload().clone();

        // dataLen lives at offset 4+8+8+4+1 = 25
        payload[25] = (byte) ((oversizeDataLen >>> 24) & 0xFF);
        payload[26] = (byte) ((oversizeDataLen >>> 16) & 0xFF);
        payload[27] = (byte) ((oversizeDataLen >>> 8) & 0xFF);
        payload[28] = (byte) (oversizeDataLen & 0xFF);
        FrameCodec.Frame corrupted = new FrameCodec.Frame(
                frame.messageType(), groupId, term, payload);
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decode(corrupted));
    }

    // ---- Arbitraries ----

    @Provide
    Arbitrary<List<LogEntry>> entryLists() {
        Arbitrary<LogEntry> entry = Combinators.combine(
                Arbitraries.longs().between(1L, Long.MAX_VALUE),
                Arbitraries.longs().between(0L, Long.MAX_VALUE),
                Arbitraries.bytes().array(byte[].class).ofMaxSize(64))
                .as(LogEntry::new);
        return entry.list().ofMaxSize(8);
    }

    @Provide
    Arbitrary<MessageType> nonRaftType() {
        return Arbitraries.of(MessageType.values()).filter(t -> switch (t) {
            case APPEND_ENTRIES, APPEND_ENTRIES_RESPONSE,
                 REQUEST_VOTE, REQUEST_VOTE_RESPONSE,
                 PRE_VOTE, PRE_VOTE_RESPONSE,
                 INSTALL_SNAPSHOT, INSTALL_SNAPSHOT_RESPONSE,
                 TIMEOUT_NOW -> false;
            default -> true;
        });
    }

    /** Demonstrates the dispatcher accepts every Raft type via roundtrip. */
    @Property(tries = 1)
    @SuppressWarnings("unused")
    void dispatcherAcceptsEveryRaftType() {
        RaftMessage[] messages = new RaftMessage[]{
                new AppendEntriesRequest(1L, NodeId.of(1), 0L, 0L, List.of(), 0L),
                new AppendEntriesResponse(1L, true, 0L, NodeId.of(1)),
                new RequestVoteRequest(1L, NodeId.of(1), 0L, 0L, false),
                new RequestVoteRequest(1L, NodeId.of(1), 0L, 0L, true),
                new RequestVoteResponse(1L, true, NodeId.of(1), false),
                new RequestVoteResponse(1L, true, NodeId.of(1), true),
                new InstallSnapshotRequest(1L, NodeId.of(1), 1L, 1L, 0, new byte[]{}, true, null),
                new InstallSnapshotResponse(1L, true, NodeId.of(1), 0L),
                new TimeoutNowRequest(1L, NodeId.of(1)),
        };
        for (RaftMessage msg : messages) {
            FrameCodec.Frame frame = RaftMessageCodec.encode(msg, 0);
            RaftMessage round = RaftMessageCodec.decode(frame);
            assertEquals(msg.getClass(), round.getClass());
        }
    }
}

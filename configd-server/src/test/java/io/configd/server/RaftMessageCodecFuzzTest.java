package io.configd.server;

import io.configd.common.NodeId;
import io.configd.raft.AppendEntriesRequest;
import io.configd.raft.InstallSnapshotRequest;
import io.configd.raft.LogEntry;
import io.configd.raft.RaftMessage;
import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Adversarial byte-level fuzz suite for {@link RaftMessageCodec} - the consensus-plane codec that
 * turns a cert-valid (but possibly Byzantine) peer's frame payload into an in-process
 * {@link RaftMessage}.
 *
 * <p>Complements - does NOT duplicate - {@link RaftMessageCodecPropertyTest}, which proves the
 * <em>structural</em> round-trip and a handful of hand-aimed bound checks. This suite adds the
 * security <b>resource oracle</b>: for EVERY {@link MessageType} the codec decodes and for wholly
 * arbitrary / adversarially-mutated payload bytes, the decode entry point must EITHER return a
 * well-formed message/map OR throw exactly {@link IllegalArgumentException} - and must NEVER:
 * <ul>
 *   <li>throw {@link OutOfMemoryError} (unbounded allocation from a hostile count/length),</li>
 *   <li>throw {@link BufferUnderflowException} (an <em>unguarded</em> {@code ByteBuffer} read -
 *       every length in this codec is {@code checkRemaining}/{@code checkBlobLen}-gated BEFORE the
 *       read, so an underflow escaping here is a real hole),</li>
 *   <li>throw {@link NullPointerException}, {@link NegativeArraySizeException},
 *       {@link ArrayIndexOutOfBoundsException} (un-validated index / size math),</li>
 *   <li>hang (caught by {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}).</li>
 * </ul>
 *
 * <p><b>The Raft oracle is strictly narrower than the FrameCodec one.</b> {@code FrameCodecFuzzTest}
 * admits a {@link BufferUnderflowException} defensively; here it is <em>forbidden</em>, because
 * every payload read in {@link RaftMessageCodec} is preceded by an explicit remaining-bytes guard
 * and every record it constructs ({@code LogEntry}, {@code AppendEntriesRequest},
 * {@code InstallSnapshotRequest}, ...) is fed non-null, codec-produced arguments - so the ONLY
 * legal escape for a hostile payload is the codec's own {@link IllegalArgumentException}.
 *
 * <p>Every raft decode surface is covered: {@link RaftMessageCodec#decode} (AppendEntries,
 * AppendEntriesResponse, RequestVote/PreVote (+Response), InstallSnapshot (+Response), TimeoutNow),
 * {@link RaftMessageCodec#decodeCoalescedHeartbeat}, and
 * {@link RaftMessageCodec#decodeWitness} (Witness / WitnessReply).
 *
 * <p><b>Tries budget.</b> Sized for a 2-vCPU CI box: 2000 tries on the broad arbitrary-payload
 * oracle, 800 on the per-type oracle, a few hundred on the mutation properties. Decode is
 * microseconds, so the whole class is well under a second of CPU. Each {@code @Property} pins a
 * fixed {@code seed} so a failing input is reproducible and lands in the committed corpus, matching
 * the golden-fixture discipline. The hardcoded {@code @Property(tries = 1)} cases at the bottom are
 * the permanent crash/regression corpus of hand-picked hostile byte shapes.
 */
class RaftMessageCodecFuzzTest {

    /** Bounded per-decode timeout. Decode is microseconds; 2 s catches a true hang. */
    private static final Duration DECODE_BUDGET = Duration.ofSeconds(2);

    /** The authenticated transport sender injected into a witness decode. */
    private static final NodeId FROM = NodeId.of(0x7F7F7F7F);

    /** Every raft MessageType that has a decode path (the frame types this codec owns). */
    private static final MessageType[] RAFT_TYPES = {
            MessageType.APPEND_ENTRIES,
            MessageType.APPEND_ENTRIES_RESPONSE,
            MessageType.REQUEST_VOTE,
            MessageType.REQUEST_VOTE_RESPONSE,
            MessageType.PRE_VOTE,
            MessageType.PRE_VOTE_RESPONSE,
            MessageType.INSTALL_SNAPSHOT,
            MessageType.INSTALL_SNAPSHOT_RESPONSE,
            MessageType.TIMEOUT_NOW,
            MessageType.RAFT_COALESCED_HEARTBEAT,
            MessageType.RAFT_WITNESS,
            MessageType.RAFT_WITNESS_REPLY,
    };

    /**
     * The core oracle: an arbitrary raft type stamped on a wholly arbitrary, adversarially-sized
     * payload. The matching decode entry point (see {@link #decodeByType}) must satisfy the resource
     * oracle for every one of the 12 raft frame types.
     */
    @Property(tries = 2000, seed = "424242")
    void arbitraryPayloadOnAnyRaftTypeNeverViolatesTheOracle(
            @ForAll("raftType") MessageType type,
            @ForAll("adversarialSized") byte[] payload,
            @ForAll int groupId,
            @ForAll long term) {
        assertOracleHolds(type, groupId, term, payload);
    }

    /**
     * Same oracle, but the payload size is pinned at each fixed-header boundary (0, header-1, header,
     * header+1, ...) where off-by-one index math is most likely to misbehave. Covers the exact
     * truncation frontier of every fixed-shape decoder in one property.
     */
    @Property(tries = 1500, seed = "20260706")
    void boundarySizedPayloadOnAnyRaftTypeNeverViolatesTheOracle(
            @ForAll("raftType") MessageType type,
            @ForAll("boundarySized") byte[] payload,
            @ForAll int groupId,
            @ForAll long term) {
        assertOracleHolds(type, groupId, term, payload);
    }

    /**
     * Encode a valid message of every type, then overwrite a random 4-byte window of its payload with
     * a hostile int (negative, huge count, Integer.MAX/MIN). A mutated frame must decode to SOME valid
     * message or throw {@link IllegalArgumentException} - never a forbidden throwable. This is the
     * amplification / count-field guard exercised by the fuzzer rather than by hand.
     */
    @Property(tries = 800, seed = "1001")
    void innerIntLieOnValidFrameNeverViolatesTheOracle(
            @ForAll("validFrames") FrameCodec.Frame valid,
            @ForAll @IntRange(min = 0, max = 4095) int offsetSeed,
            @ForAll("hostileInts") int hostile) {
        byte[] payload = valid.payload().clone();
        if (payload.length < 4) {
            return; // nothing to overwrite
        }
        int at = offsetSeed % (payload.length - 3);
        ByteBuffer.wrap(payload).putInt(at, hostile);
        assertOracleHolds(valid.messageType(), valid.groupId(), valid.term(), payload);
    }

    /**
     * Append trailing garbage to a valid frame's payload. The fixed-shape request decoders are
     * strict-end, so this is either accepted (types that legitimately tolerate a trailing optional
     * field) or rejected as {@link IllegalArgumentException} - never a forbidden throwable.
     */
    @Property(tries = 500, seed = "1002")
    void trailingGarbageOnValidFrameNeverViolatesTheOracle(
            @ForAll("validFrames") FrameCodec.Frame valid,
            @ForAll @IntRange(min = 1, max = 64) int extra) {
        byte[] payload = new byte[valid.payload().length + extra];
        System.arraycopy(valid.payload(), 0, payload, 0, valid.payload().length);
        assertOracleHolds(valid.messageType(), valid.groupId(), valid.term(), payload);
    }

    /**
     * Truncate a valid frame's payload at EVERY offset. Each prefix must decode-or-reject cleanly - a
     * partial payload must never crash and never leak a {@link BufferUnderflowException} (proving each
     * fixed-shape decoder's leading {@code checkRemaining} gate actually fires).
     */
    @Property(tries = 60, seed = "1003")
    void truncateValidFrameAtEveryOffsetNeverViolatesTheOracle(
            @ForAll("validFrames") FrameCodec.Frame valid) {
        byte[] full = valid.payload();
        for (int cut = 0; cut < full.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(full, cut);
            assertOracleHolds(valid.messageType(), valid.groupId(), valid.term(), truncated);
        }
    }

    // Bounded allocation: a tiny frame declaring a huge count is rejected BEFORE the list/map
    // allocation (no multi-GB heap from 32 attacker bytes).

    /**
     * A minimal AppendEntries header that lies about {@code numEntries} = a near-2^31 value must be
     * rejected with {@link IllegalArgumentException} pre-allocation - the frame is only 32 bytes, so a
     * successful huge {@code ArrayList} alloc would OOM the box; a clean reject proves the
     * {@code numEntries * 20 > remaining} pre-check fires first. (Matches
     * {@code RaftMessageCodecPropertyTest.appendEntriesWithBogusEntryCountFails} but asserts it on a
     * tiny frame, so the bounded-allocation property is explicit.)
     */
    @Property(tries = 1, seed = "1004")
    void tinyAppendEntriesWithHugeEntryCountRejectedPreAllocation() {
        for (int count : new int[]{Integer.MAX_VALUE, 2_000_000_000, 100_000_000}) {
            ByteBuffer p = ByteBuffer.allocate(32);
            p.putInt(1);          // leaderId
            p.putLong(0L);        // prevLogIndex
            p.putLong(0L);        // prevLogTerm
            p.putLong(0L);        // leaderCommit
            p.putInt(count);      // numEntries (hostile)
            FrameCodec.Frame frame = new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 0, 0L, p.array());
            assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(frame), "huge entry count " + count + " must reject pre-alloc"));
        }
    }

    /**
     * The coalesced-heartbeat analogue: a 4-byte count that lies about the group count must reject
     * before {@code new LinkedHashMap<>(n*2)}.
     */
    @Property(tries = 1, seed = "1005")
    void tinyCoalescedHeartbeatWithHugeGroupCountRejectedPreAllocation() {
        for (int count : new int[]{Integer.MAX_VALUE, 2_000_000_000, 50_000_000}) {
            ByteBuffer p = ByteBuffer.allocate(4);
            p.putInt(count);
            FrameCodec.Frame frame =
                    new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, p.array());
            assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decodeCoalescedHeartbeat(frame),
                    "huge group count " + count + " must reject pre-alloc"));
        }
    }

    /**
     * The InstallSnapshot per-blob analogue: a huge {@code dataLen} on a tiny frame must reject before
     * {@code new byte[dataLen]}.
     */
    @Property(tries = 1, seed = "1006")
    void tinyInstallSnapshotWithHugeDataLenRejectedPreAllocation() {
        for (int dataLen : new int[]{Integer.MAX_VALUE, 1 << 30, 100_000_000}) {
            ByteBuffer p = ByteBuffer.allocate(29);
            p.putInt(1);          // leaderId
            p.putLong(1L);        // lastIncludedIndex
            p.putLong(1L);        // lastIncludedTerm
            p.putInt(0);          // offset
            p.put((byte) 1);      // done
            p.putInt(dataLen);    // dataLen (hostile)
            FrameCodec.Frame frame = new FrameCodec.Frame(MessageType.INSTALL_SNAPSHOT, 0, 0L, p.array());
            assertTimeoutPreemptively(DECODE_BUDGET, () -> assertThrows(IllegalArgumentException.class,
                    () -> RaftMessageCodec.decode(frame), "huge dataLen " + dataLen + " must reject pre-alloc"));
        }
    }

    // Permanent regression corpus of hand-picked hostile byte shapes. Each is a hardcoded byte[]
    // case; if any codec change reintroduces a forbidden throwable here, CI fails loud on the exact
    // byte pattern.

    /** A negative InstallSnapshot chunk offset is a clean IllegalArgumentException. */
    @Property(tries = 1, seed = "2001")
    void corpusNegativeInstallSnapshotOffset() {
        ByteBuffer p = ByteBuffer.allocate(29);
        p.putInt(1);
        p.putLong(1L);
        p.putLong(1L);
        p.putInt(-1);         // offset < 0
        p.put((byte) 0);
        p.putInt(0);          // dataLen
        FrameCodec.Frame frame = new FrameCodec.Frame(MessageType.INSTALL_SNAPSHOT, 0, 0L, p.array());
        assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(frame));
    }

    /** Trailing bytes past a fully-parsed AppendEntries heartbeat are rejected strict-end. */
    @Property(tries = 1, seed = "2002")
    void corpusAppendEntriesTrailingBytes() {
        ByteBuffer p = ByteBuffer.allocate(33);
        p.putInt(1);          // leaderId
        p.putLong(0L);        // prevLogIndex
        p.putLong(0L);        // prevLogTerm
        p.putLong(0L);        // leaderCommit
        p.putInt(0);          // numEntries = 0 (heartbeat)
        p.put((byte) 0xAB);   // one trailing byte
        FrameCodec.Frame frame = new FrameCodec.Frame(MessageType.APPEND_ENTRIES, 0, 5L, p.array());
        assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decode(frame));
    }

    /** A coalesced heartbeat declaring a duplicate group id is rejected (not a silent overwrite). */
    @Property(tries = 1, seed = "2003")
    void corpusCoalescedDuplicateGroupId() {
        int record = 4 + 8 + 4 + 8 + 8 + 8; // 40
        ByteBuffer p = ByteBuffer.allocate(4 + 2 * record);
        p.putInt(2);
        for (int i = 0; i < 2; i++) {
            p.putInt(7);      // SAME groupId both records -> duplicate
            p.putLong(1L);    // term
            p.putInt(1);      // leaderId
            p.putLong(0L);    // prevLogIndex
            p.putLong(0L);    // prevLogTerm
            p.putLong(0L);    // leaderCommit
        }
        FrameCodec.Frame frame =
                new FrameCodec.Frame(MessageType.RAFT_COALESCED_HEARTBEAT, 0, 0L, p.array());
        assertThrows(IllegalArgumentException.class,
                () -> RaftMessageCodec.decodeCoalescedHeartbeat(frame));
    }

    /** A witness frame with a body one byte short is a clean reject, never a BufferUnderflow. */
    @Property(tries = 1, seed = "2004")
    void corpusTruncatedWitnessBody() {
        byte[] shortBody = new byte[28]; // WITNESS_BODY_LEN is 29
        FrameCodec.Frame frame = new FrameCodec.Frame(MessageType.RAFT_WITNESS, 0, 0L, shortBody);
        assertThrows(IllegalArgumentException.class, () -> RaftMessageCodec.decodeWitness(frame, FROM));
    }

    /** An empty payload on every raft type is a clean reject (or, for the count-led types, needs 4B). */
    @Property(tries = 1, seed = "2005")
    void corpusEmptyPayloadOnEveryType() {
        for (MessageType type : RAFT_TYPES) {
            assertOracleHolds(type, 0, 0L, new byte[0]);
        }
    }

    /**
     * Routes a frame to the correct decode entry point for its type and asserts the resource oracle:
     * a valid message/map OR an {@link IllegalArgumentException}, within the time budget, never a
     * forbidden throwable.
     */
    private static void assertOracleHolds(MessageType type, int groupId, long term, byte[] payload) {
        FrameCodec.Frame frame = new FrameCodec.Frame(type, groupId, term, payload);
        assertTimeoutPreemptively(DECODE_BUDGET, () -> {
            try {
                Object decoded = decodeByType(frame);
                assertNotNull(decoded, "decode returned null for a " + type + " frame");
            } catch (IllegalArgumentException expected) {
                // The single documented rejection type - correct. (MalformedCommandException does not
                // arise: the raft codec stores raw command bytes, it does not decode them here.)
            } catch (Throwable t) {
                failForbidden(type, payload, t);
            }
        });
    }

    /** Dispatches to the decode surface that owns {@code type} (mirrors the production inbound demux). */
    private static Object decodeByType(FrameCodec.Frame frame) {
        return switch (frame.messageType()) {
            case RAFT_COALESCED_HEARTBEAT -> RaftMessageCodec.decodeCoalescedHeartbeat(frame);
            case RAFT_WITNESS, RAFT_WITNESS_REPLY -> RaftMessageCodec.decodeWitness(frame, FROM);
            default -> RaftMessageCodec.decode(frame);
        };
    }

    private static void failForbidden(MessageType type, byte[] payload, Throwable t) {
        if (t instanceof AssertionError ae) {
            throw ae; // an assertNotNull failure - propagate verbatim
        }
        fail("decode of " + type + " produced FORBIDDEN throwable " + t.getClass().getName()
                + " on payload " + describe(payload) + ": " + t.getMessage(), t);
    }

    private static String describe(byte[] data) {
        String hex = HexFormat.of().formatHex(data, 0, Math.min(data.length, 48));
        return "len=" + data.length + " hex=" + hex + (data.length > 48 ? "..." : "");
    }

    @Provide
    Arbitrary<MessageType> raftType() {
        return Arbitraries.of(RAFT_TYPES);
    }

    /**
     * Arbitrary payload bytes whose SIZE distribution is weighted toward the adversarial zone: empty,
     * tiny, and around each fixed-header size (RequestVoteResponse 5, TimeoutNow 4, AE header 32,
     * InstallSnapshot header 29, witness body 29, coalesced record 40), plus small random sizes.
     */
    @Provide
    Arbitrary<byte[]> adversarialSized() {
        Arbitrary<Integer> sizes = Arbitraries.frequency(
                Tuple.of(4, 0),
                Tuple.of(4, 1),
                Tuple.of(2, 4),
                Tuple.of(2, 5),
                Tuple.of(2, 12),
                Tuple.of(2, 13),
                Tuple.of(3, 28),
                Tuple.of(3, 29),
                Tuple.of(3, 32),
                Tuple.of(3, 33),
                Tuple.of(2, 40),
                Tuple.of(3, 44),
                Tuple.of(3, 64),
                Tuple.of(2, 256))
                .flatMap(max -> Arbitraries.integers().between(0, Math.max(0, max)));
        return sizes.flatMap(this::randomBytesOfSize);
    }

    @Provide
    Arbitrary<byte[]> boundarySized() {
        return Arbitraries.of(0, 1, 3, 4, 5, 12, 13, 20, 28, 29, 31, 32, 33, 39, 40, 41)
                .flatMap(this::randomBytesOfSize);
    }

    private Arbitrary<byte[]> randomBytesOfSize(int size) {
        if (size <= 0) {
            return Arbitraries.just(new byte[0]);
        }
        return Arbitraries.bytes().array(byte[].class).ofSize(size);
    }

    /**
     * A small set of well-formed frames covering every raft type + a couple of payload shapes, so the
     * mutation properties always start from a real, decodable frame.
     */
    @Provide
    Arbitrary<FrameCodec.Frame> validFrames() {
        AppendEntriesRequest heartbeat =
                new AppendEntriesRequest(3L, NodeId.of(1), 4L, 2L, List.of(), 4L);
        AppendEntriesRequest withEntries = new AppendEntriesRequest(3L, NodeId.of(1), 4L, 2L,
                List.of(new LogEntry(5L, 3L, new byte[]{1, 2, 3}),
                        new LogEntry(6L, 3L, new byte[]{4, 5})), 4L);
        InstallSnapshotRequest snapNoCfg = new InstallSnapshotRequest(
                7L, NodeId.of(2), 10L, 3L, 0, new byte[]{9, 8, 7}, true, null);
        InstallSnapshotRequest snapWithCfg = new InstallSnapshotRequest(
                7L, NodeId.of(2), 10L, 3L, 4096, new byte[]{9, 8, 7}, false, new byte[]{1, 1});

        RaftMessage[] singles = {
                heartbeat,
                withEntries,
                new io.configd.raft.AppendEntriesResponse(3L, true, 5L, NodeId.of(4)),
                new io.configd.raft.RequestVoteRequest(9L, NodeId.of(1), 5L, 3L, false),
                new io.configd.raft.RequestVoteRequest(9L, NodeId.of(1), 5L, 3L, true),
                new io.configd.raft.RequestVoteResponse(9L, true, NodeId.of(1), false),
                new io.configd.raft.RequestVoteResponse(9L, false, NodeId.of(1), true),
                snapNoCfg,
                snapWithCfg,
                new io.configd.raft.InstallSnapshotResponse(7L, true, NodeId.of(2), 10L, 4096),
                new io.configd.raft.TimeoutNowRequest(9L, NodeId.of(1)),
                new io.configd.raft.WitnessMessage(FROM, 11L, 9L, 3, 4L, true),
                new io.configd.raft.WitnessReply(FROM, 11L, 9L, 3, 4L),
        };

        // The coalesced heartbeat has no encode-via-RaftMessage path; build it directly.
        FrameCodec.Frame coalesced = RaftMessageCodec.encodeCoalescedHeartbeat(
                Map.of(0, heartbeat, 1, new AppendEntriesRequest(3L, NodeId.of(1), 0L, 0L, List.of(), 0L)));

        Arbitrary<FrameCodec.Frame> encoded = Arbitraries.of(singles).map(m -> RaftMessageCodec.encode(m, 2));
        return Arbitraries.oneOf(encoded, Arbitraries.of(coalesced));
    }

    /** Hostile 4-byte int values to splat over a valid payload (count / length / id fields). */
    @Provide
    Arbitrary<Integer> hostileInts() {
        return Arbitraries.of(
                Integer.MIN_VALUE, -1, 0, 1,
                10_001, 1_048_577, 4 * 1024 * 1024 + 1,
                1_000_000, 2_000_000_000, Integer.MAX_VALUE);
    }

    /** Sanity anchor: a known-good frame of each surface still decodes (guards the mutation base). */
    @Property(tries = 1, seed = "3000")
    void knownGoodFramesStillDecode() {
        AppendEntriesRequest hb = new AppendEntriesRequest(3L, NodeId.of(1), 4L, 2L, List.of(), 4L);
        assertNotNull(RaftMessageCodec.decode(RaftMessageCodec.encode(hb, 2)));
        FrameCodec.Frame co = RaftMessageCodec.encodeCoalescedHeartbeat(Map.of(0, hb));
        assertNotNull(RaftMessageCodec.decodeCoalescedHeartbeat(co));
        FrameCodec.Frame wf = RaftMessageCodec.encode(
                new io.configd.raft.WitnessMessage(FROM, 1L, 1L, 0, 0L, true), 2);
        assertNotNull(RaftMessageCodec.decodeWitness(wf, FROM));
    }
}

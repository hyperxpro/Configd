package io.configd.raft;

import io.configd.common.NodeId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for chunked/streaming InstallSnapshot transfer (lifting the single-frame total-state
 * ceiling). A snapshot larger than one chunk is sent as an ordered stream of chunks driven off
 * the follower's per-chunk acks; the follower reassembles a contiguous prefix and installs only
 * once the final chunk completes it.
 *
 * <p>Reuses the transport/state-machine harness from {@link InstallSnapshotTest}. Three properties
 * matter most and each has a dedicated group:
 * <ul>
 *   <li><b>Install-identical</b> - a multi-chunk transfer installs byte-for-byte the same state a
 *       single blob would have.</li>
 *   <li><b>No premature progress</b> - an intermediate-chunk ack never advances matchIndex; the
 *       follower is counted as holding the snapshot only after it installs.</li>
 *   <li><b>Fail-closed</b> - a truncated, out-of-order, duplicate, or cross-snapshot stream never
 *       produces a torn install.</li>
 * </ul>
 */
class ChunkedInstallSnapshotTest {

    private static final NodeId N1 = NodeId.of(1);
    private static final NodeId N2 = NodeId.of(2);

    /** A deterministic snapshot blob whose every byte is a function of its index. */
    private static byte[] blob(int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return b;
    }

    /** Builds a fresh follower node (empty log, term 0) wired to a capture transport. */
    private static RaftNode newFollower(InstallSnapshotTest.TestTransport transport,
                                        InstallSnapshotTest.TestStateMachine sm) {
        RaftConfig config = RaftConfig.of(N2, Set.of(N1));
        RaftLog log = new RaftLog();
        RandomGenerator rng = new java.util.Random(42);
        return new RaftNode(config, log, transport, sm, rng);
    }

    private static InstallSnapshotRequest chunk(long idx, long term, int offset,
                                                byte[] full, int end, boolean done, byte[] config) {
        return new InstallSnapshotRequest(1, N1, idx, term, offset,
                Arrays.copyOfRange(full, offset, end), done, done ? config : null);
    }

    // Receiver-side reassembly, driven with hand-built chunks for full control.

    @Nested
    class ReassemblyTests {

        @Test
        void reassemblesMultiChunkSnapshotIntoIdenticalState() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);
            RaftLog log = follower.log();

            byte[] full = blob(10);
            byte[] cfg = "cluster-config".getBytes();

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, cfg));
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, cfg));
            follower.handleMessage(chunk(10, 1, 8, full, 10, true, cfg));

            assertArrayEquals(full, sm.restoredFrom);
            assertEquals(10, log.snapshotIndex());
            assertEquals(1, log.snapshotTerm());
            assertEquals(10, log.lastApplied());

            List<InstallSnapshotResponse> responses =
                    transport.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(3, responses.size());
            assertTrue(responses.stream().allMatch(InstallSnapshotResponse::success));
            // Intermediate chunks ack the UNCHANGED (pre-install) position, below the snapshot;
            // only the final ack echoes the installed index.
            assertEquals(0, responses.get(0).lastIncludedIndex());
            assertEquals(0, responses.get(1).lastIncludedIndex());
            assertEquals(10, responses.get(2).lastIncludedIndex());
        }

        @Test
        void truncatedStreamDoesNotInstall() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);
            RaftLog log = follower.log();

            byte[] full = blob(10);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            // Final (done) chunk never arrives.

            assertNull(sm.restoredFrom, "a truncated transfer must not touch the state machine");
            assertEquals(0, log.snapshotIndex(), "follower stays on its prior state");
            assertEquals(0, log.lastApplied());
        }

        @Test
        void gapChunkIsFailClosedThenRecovers() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] full = blob(12);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));
            // Gap: offset 8 skips [4,8). Must NOT be appended, even though it is not the final chunk.
            follower.handleMessage(chunk(10, 1, 8, full, 12, true, null));
            assertNull(sm.restoredFrom, "a gap chunk must never be spliced into the buffer");

            // The missing chunk arrives in order, then the final chunk completes the transfer.
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            follower.handleMessage(chunk(10, 1, 8, full, 12, true, null));
            assertArrayEquals(full, sm.restoredFrom);
            assertEquals(10, follower.log().snapshotIndex());
        }

        @Test
        void duplicateChunkIsIgnoredNotDoubleApplied() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] full = blob(12);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            // Retransmit of an already-buffered chunk (offset 4 < accumulated 8): must be ignored,
            // not appended again (which would corrupt the buffer length).
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            follower.handleMessage(chunk(10, 1, 8, full, 12, true, null));

            // If the duplicate had been appended the reassembled blob would be 16 bytes, not 12.
            assertArrayEquals(full, sm.restoredFrom);
        }

        @Test
        void offsetZeroRestartsReassembly() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] full = blob(12);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            // Leader restarts from the beginning (offset 0 with a non-empty partial): the follower
            // discards the stale partial and re-accepts from zero, then completes cleanly.
            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));
            follower.handleMessage(chunk(10, 1, 8, full, 12, true, null));

            assertArrayEquals(full, sm.restoredFrom);
        }

        @Test
        void chunkForDifferentSnapshotDiscardsPartial() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] snapA = blob(12);
            byte[] snapB = blob(6);

            follower.handleMessage(chunk(10, 1, 0, snapA, 4, false, null));
            // A newer snapshot B (index 20) supersedes it; its single chunk installs cleanly and
            // the stale partial for A is discarded rather than spliced in.
            follower.handleMessage(chunk(20, 2, 0, snapB, 6, true, null));

            assertArrayEquals(snapB, sm.restoredFrom);
            assertEquals(20, follower.log().snapshotIndex());
            assertEquals(2, follower.log().snapshotTerm());
        }

        @Test
        void reportsAccumulatedAsNextExpectedOffset() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] full = blob(12);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null));   // -> accumulated 4
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));   // -> accumulated 8
            follower.handleMessage(chunk(10, 1, 10, full, 12, false, null)); // gap (10 > 8), no append
            follower.handleMessage(chunk(10, 1, 8, full, 10, false, null));  // -> accumulated 10
            follower.handleMessage(chunk(10, 1, 10, full, 12, true, null));  // done -> install

            List<InstallSnapshotResponse> r = transport.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(5, r.size());
            // The follower reports its true contiguous position on every ack - the leader's ground
            // truth for where to resume.
            assertEquals(4, r.get(0).nextExpectedOffset());
            assertEquals(8, r.get(1).nextExpectedOffset());
            assertEquals(8, r.get(2).nextExpectedOffset(), "a gap chunk leaves the reported position unchanged");
            assertEquals(10, r.get(3).nextExpectedOffset());
            assertEquals(0, r.get(4).nextExpectedOffset(), "the install ack carries no in-progress position");
            assertArrayEquals(full, sm.restoredFrom);
        }

        @Test
        void reassemblyExceedingCapFailsClosed() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);
            follower.setMaxReassembledSnapshotBytesForTest(6); // tiny heap cap

            byte[] full = blob(10);

            follower.handleMessage(chunk(10, 1, 0, full, 4, false, null)); // accumulated 4 (<= cap)
            // Next in-order chunk would push accumulated to 8 > cap 6: refused, partial dropped, no install.
            follower.handleMessage(chunk(10, 1, 4, full, 8, false, null));

            assertNull(sm.restoredFrom, "an over-cap reassembly must never install (fail closed, no OOM)");
            List<InstallSnapshotResponse> r = transport.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(0, r.getLast().nextExpectedOffset(), "the dropped partial is reported as position 0");

            // The refusal tally makes this wedge observable: it surfaces in the RaftMetrics snapshot
            // the server's per-shard gauge reads.
            assertEquals(1, follower.snapshotReassemblyRefused(),
                    "the reassembly refusal must increment the snapshot-reassembly-refused tally");
            assertEquals(1, follower.metrics().snapshotReassemblyRefused(),
                    "the tally must surface in the RaftMetrics snapshot");

            // The follower is not bricked: a snapshot that fits the cap still installs.
            byte[] small = blob(5);
            follower.handleMessage(chunk(11, 1, 0, small, 5, true, null));
            assertArrayEquals(small, sm.restoredFrom);
            assertEquals(11, follower.log().snapshotIndex());
        }
    }

    // Sender-side chunk emission and ack-driven advancement.

    @Nested
    class SenderChunkingTests {

        @Test
        void firstChunkIsBoundedOffsetZeroAndCarriesNoConfig() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            leader.setSnapshotChunkBytesForTest(2); // force several chunks over the small test snapshot

            // Commit some entries with only node 2, then compact so node 3 needs a snapshot.
            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> active = Set.of(N1, NodeId.of(2));
            for (int r = 0; r < 10; r++) {
                cluster.deliverMessagesTo(active);
            }
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int r = 0; r < 5; r++) {
                cluster.deliverMessagesTo(active);
            }
            assertTrue(leader.triggerSnapshot());

            cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

            // The leader emits only the first chunk here (subsequent chunks are driven by acks,
            // which we do not deliver in this test). It starts at offset 0, is bounded by the
            // configured chunk size, is not the final chunk, and carries no cluster config - the
            // config rides the last chunk only.
            List<InstallSnapshotRequest> chunks = cluster.transports.get(N1)
                    .messagesTo(lagging, InstallSnapshotRequest.class);
            assertFalse(chunks.isEmpty(), "leader must send the first snapshot chunk to node 3");

            InstallSnapshotRequest first = chunks.getFirst();
            assertEquals(0, first.offset());
            assertEquals(2, first.data().length, "chunk must be bounded by the configured chunk size");
            assertFalse(first.done(), "a multi-chunk transfer's first chunk is not the final one");
            assertNull(first.clusterConfigData(), "an intermediate chunk carries no cluster config");
        }

        @Test
        void intermediateAckDoesNotAdvanceMatchIndexButFinalAckDoes() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            leader.setSnapshotChunkBytesForTest(2);

            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> active = Set.of(N1, NodeId.of(2));
            for (int r = 0; r < 10; r++) {
                cluster.deliverMessagesTo(active);
            }
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int r = 0; r < 5; r++) {
                cluster.deliverMessagesTo(active);
            }
            assertTrue(leader.triggerSnapshot());
            long snapIndex = leader.log().snapshotIndex();
            assertTrue(snapIndex > 0);

            cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            List<InstallSnapshotRequest> chunks = cluster.transports.get(N1)
                    .messagesTo(lagging, InstallSnapshotRequest.class);
            assertFalse(chunks.isEmpty());
            boolean multiChunk = !chunks.getFirst().done();
            assertTrue(multiChunk, "chunk size 2 over the test snapshot must produce >1 chunk");

            long leaderTerm = leader.currentTerm();
            long matchBefore = leader.matchIndexForTest(lagging);
            assertTrue(matchBefore < snapIndex, "lagging peer has not caught up to the snapshot yet");

            // Intermediate-chunk ack: the follower reports a below-snapshot index and a partial
            // accumulated offset (2 bytes). matchIndex must NOT advance - the follower has not
            // installed the snapshot yet.
            leader.handleMessage(new InstallSnapshotResponse(leaderTerm, true, lagging, matchBefore, 2));
            assertEquals(matchBefore, leader.matchIndexForTest(lagging),
                    "an intermediate-chunk ack must not advance matchIndex");

            // Final (install) ack: the follower reports the installed index. matchIndex advances.
            leader.handleMessage(new InstallSnapshotResponse(leaderTerm, true, lagging, snapIndex));
            assertTrue(leader.matchIndexForTest(lagging) >= snapIndex,
                    "the final install ack advances matchIndex to the snapshot index");
        }

        @Test
        void senderReSyncsToFollowerReportedOffsetNotAckCount() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            setUpLaggingSnapshot(cluster, 2);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);

            leaderTransport.clear();
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            long term = leader.currentTerm();

            // The follower keeps reporting the SAME accumulated offset (2) - e.g. it is stuck at a
            // gap and rejects each retransmit. The leader must keep re-sending chunk@2 and must NOT
            // climb to 4, 6, ... A counting sender would advance one chunk per ack and wedge; the
            // offset-echo sender pins to the follower's reported position.
            for (int round = 0; round < 4; round++) {
                leaderTransport.clear();
                leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 2));
                List<InstallSnapshotRequest> sent = leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class);
                assertFalse(sent.isEmpty(), "an intermediate ack drives the next chunk send");
                assertEquals(2, sent.getFirst().offset(),
                        "sender must re-send the follower's reported offset, not count acks forward");
            }

            // When the follower finally reports forward progress (offset 4), the sender advances.
            leaderTransport.clear();
            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 4));
            List<InstallSnapshotRequest> advanced = leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class);
            assertFalse(advanced.isEmpty());
            assertEquals(4, advanced.getFirst().offset(), "sender advances to the follower's new reported offset");
        }

        @Test
        void aliveRestartedFollowerReSyncsToZero() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            setUpLaggingSnapshot(cluster, 2);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);

            leaderTransport.clear();
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            long term = leader.currentTerm();

            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 2));
            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 4));

            // The follower restarts mid-transfer: it is alive and acking, but its in-memory partial
            // is gone, so it now reports accumulated 0. The leader must immediately re-sync to 0 and
            // re-prime from the beginning, not keep sending high offsets forever - a plain
            // ack-counting sender would let an alive-but-rejecting follower keep the stall backstop
            // from ever firing.
            leaderTransport.clear();
            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 0));
            List<InstallSnapshotRequest> resent = leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class);
            assertFalse(resent.isEmpty(), "the restart ack must drive a re-send");
            assertEquals(0, resent.getFirst().offset(),
                    "an alive follower reporting offset 0 must make the leader restart from offset 0");
        }

        @Test
        void stalledTransferRestartsFromOffsetZeroAsBackstop() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            setUpLaggingSnapshot(cluster, 2);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId n2 = NodeId.of(2);
            NodeId lagging = NodeId.of(3);

            // Emit the first chunk, then advance to a non-zero offset with a REAL-progress ack so
            // ackedOffset > 0 and the stall counter has been reset by genuine progress.
            InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);
            leaderTransport.clear();
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            long term = leader.currentTerm();
            long leaderLast = leader.log().lastIndex();
            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 2)); // progress to offset 2
            leaderTransport.clear();

            // The follower now goes fully silent (no acks at all - e.g. crashed). Nothing resets the
            // stall counter, so after the threshold the backstop restarts the transfer from offset 0.
            boolean sawRestartFromZero = false;
            for (int hb = 0; hb <= RaftNode.SNAPSHOT_TRANSFER_STALL_HEARTBEATS + 2; hb++) {
                // Keep the leader in power: a live node-2 heartbeat ack each interval satisfies
                // CheckQuorum (self + node 2 = quorum in a 3-node cluster).
                leader.handleMessage(new AppendEntriesResponse(term, true, leaderLast, n2));
                for (int t = 0; t < 60; t++) {
                    leader.tick();
                }
                for (InstallSnapshotRequest chunk :
                        leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class)) {
                    if (chunk.offset() == 0) {
                        sawRestartFromZero = true;
                    }
                }
                leaderTransport.clear();
            }
            assertTrue(sawRestartFromZero,
                    "a silent-stalled transfer past the threshold must restart from offset 0");
        }

        @Test
        void slowButAckingFollowerIsNotResetToZero() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            setUpLaggingSnapshot(cluster, 2);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId n2 = NodeId.of(2);
            NodeId lagging = NodeId.of(3);

            InstallSnapshotTest.TestTransport leaderTransport = cluster.transports.get(N1);
            leaderTransport.clear();
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            long term = leader.currentTerm();
            long leaderLast = leader.log().lastIndex();
            leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 2)); // advance to offset 2

            // The follower is SLOW but ALIVE: it acks the SAME offset (2) every heartbeat - e.g. its
            // chunk round-trip exceeds the heartbeat interval, or it is stuck at a gap it keeps
            // reporting. Because it is acking at all, the ground-truth echo owns recovery and the
            // silence backstop must NOT fire: the leader keeps sending chunk@2 and never resets the
            // follower's partial back to offset 0. (Under a no-progress-gated backstop this WOULD
            // reset and livelock the transfer.)
            boolean sawRestartFromZero = false;
            for (int hb = 0; hb <= RaftNode.SNAPSHOT_TRANSFER_STALL_HEARTBEATS + 3; hb++) {
                leader.handleMessage(new AppendEntriesResponse(term, true, leaderLast, n2)); // keep leader alive
                leader.handleMessage(new InstallSnapshotResponse(term, true, lagging, 1L, 2)); // slow ack, same pos
                leaderTransport.clear();
                for (int t = 0; t < 60; t++) {
                    leader.tick();
                }
                for (InstallSnapshotRequest chunk :
                        leaderTransport.messagesTo(lagging, InstallSnapshotRequest.class)) {
                    if (chunk.offset() == 0) {
                        sawRestartFromZero = true;
                    }
                }
            }
            assertFalse(sawRestartFromZero,
                    "a slow-but-acking follower must never be reset to offset 0 - its partial must survive");
        }
    }

    /** Elects N1, commits+compacts so node 3 lags behind a snapshot, sets the chunk size, and
     * returns the snapshot index. Leaves the leader in {@code cluster.nodes.get(N1)}. Package-visible
     * so {@code RaftNodeDropMetricsTest} can reuse this sender setup. */
    static long setUpLaggingSnapshot(InstallSnapshotTest.TestCluster cluster, int chunkBytes) {
        cluster.electLeader(N1);
        RaftNode leader = cluster.nodes.get(N1);
        leader.setSnapshotChunkBytesForTest(chunkBytes);
        Set<NodeId> active = Set.of(N1, NodeId.of(2));
        for (int i = 0; i < 5; i++) {
            leader.propose(new byte[]{(byte) i});
        }
        for (int r = 0; r < 10; r++) {
            cluster.deliverMessagesTo(active);
        }
        for (int i = 0; i < 51; i++) {
            leader.tick();
        }
        for (int r = 0; r < 5; r++) {
            cluster.deliverMessagesTo(active);
        }
        if (!leader.triggerSnapshot()) {
            throw new IllegalStateException("expected the leader to take a snapshot");
        }
        long snapIndex = leader.log().snapshotIndex();
        if (snapIndex <= 0) {
            throw new IllegalStateException("expected a positive snapshot index");
        }
        return snapIndex;
    }

    // Full leader->follower loop through the cluster harness.

    @Nested
    class EndToEndTests {

        @Test
        void laggingFollowerReceivesMultiChunkSnapshot() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            leader.setSnapshotChunkBytesForTest(2); // multi-chunk over the small test snapshot

            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> active = Set.of(N1, NodeId.of(2));
            for (int r = 0; r < 10; r++) {
                cluster.deliverMessagesTo(active);
            }
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int r = 0; r < 5; r++) {
                cluster.deliverMessagesTo(active);
            }
            assertTrue(leader.triggerSnapshot());
            // The leader's snapshot bytes: the state machine is quiescent after triggerSnapshot,
            // so snapshot() returns exactly what was captured.
            byte[] leaderSnapshot = cluster.stateMachines.get(N1).snapshot();

            cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            // Deliver the whole chunked transfer: each ack drives the next chunk, so the transfer
            // completes within the delivery rounds.
            cluster.deliverAllMessages(200);

            RaftLog log3 = cluster.logs.get(lagging);
            assertTrue(log3.snapshotIndex() > 0, "lagging follower installs the snapshot");

            InstallSnapshotTest.TestStateMachine sm3 = cluster.stateMachines.get(lagging);
            assertNotNull(sm3.restoredFrom);
            assertArrayEquals(leaderSnapshot, sm3.restoredFrom,
                    "installed state must be byte-identical to the leader's snapshot");
        }

        @Test
        void multiChunkTransferSurvivesMiddleChunkLoss() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            setUpLaggingSnapshot(cluster, 2); // multi-chunk over the small test snapshot
            RaftNode leader = cluster.nodes.get(N1);
            NodeId n3 = NodeId.of(3);
            InstallSnapshotTest.TestStateMachine sm3 = cluster.stateMachines.get(n3);
            byte[] leaderSnapshot = cluster.stateMachines.get(N1).snapshot();

            cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);

            // Drop the FIRST middle chunk (offset 2) to node 3 exactly once, simulating the lossy
            // production transport (NettyRaftTransport drops frames on a full per-peer queue). The
            // transfer must still complete: the follower's echoed position stalls at the gap and the
            // leader retransmits from there. A counting sender would wedge here.
            boolean[] droppedMiddleChunk = {false};
            for (int round = 0; round < 500 && sm3.restoredFrom == null; round++) {
                for (int t = 0; t < 55; t++) {
                    leader.tick();
                }
                for (int hop = 0; hop < 6; hop++) {
                    lossyDeliver(cluster, n3, 2, droppedMiddleChunk);
                }
            }

            assertTrue(droppedMiddleChunk[0], "the test must actually have dropped a middle chunk");
            assertNotNull(sm3.restoredFrom, "transfer must complete despite one dropped middle chunk");
            assertArrayEquals(leaderSnapshot, sm3.restoredFrom);
            assertEquals(leader.log().snapshotIndex(), cluster.logs.get(n3).snapshotIndex());
        }
    }

    /**
     * Delivers all currently-queued messages to their targets, but drops the FIRST
     * InstallSnapshotRequest to {@code dropTarget} whose offset equals {@code dropOffset} (once),
     * simulating a single lost chunk. All other traffic (including node-2 heartbeats that keep the
     * leader in power) is delivered normally.
     */
    private static void lossyDeliver(InstallSnapshotTest.TestCluster cluster, NodeId dropTarget,
                                     int dropOffset, boolean[] droppedOnce) {
        Map<NodeId, List<RaftMessage>> toDeliver = new HashMap<>();
        for (var entry : cluster.transports.entrySet()) {
            for (var sent : entry.getValue().messages()) {
                if (!droppedOnce[0] && sent.target().equals(dropTarget)
                        && sent.message() instanceof InstallSnapshotRequest isr
                        && isr.offset() == dropOffset) {
                    droppedOnce[0] = true;
                    continue;
                }
                toDeliver.computeIfAbsent(sent.target(), k -> new ArrayList<>()).add(sent.message());
            }
            entry.getValue().clear();
        }
        for (var entry : toDeliver.entrySet()) {
            RaftNode target = cluster.nodes.get(entry.getKey());
            if (target != null) {
                for (RaftMessage m : entry.getValue()) {
                    target.handleMessage(m);
                }
            }
        }
    }

    // The <=1-chunk path must be byte-identical to a pre-chunking single-blob transfer.

    @Nested
    class SingleChunkByteIdentityTests {

        @Test
        void smallSnapshotSendsExactlyOneUnchunkedRequest() {
            InstallSnapshotTest.TestCluster cluster = new InstallSnapshotTest.TestCluster(3);
            cluster.electLeader(N1);
            RaftNode leader = cluster.nodes.get(N1);
            NodeId lagging = NodeId.of(3);
            // Default chunk size (1 MiB) dwarfs the tiny test snapshot -> exactly one chunk.

            for (int i = 0; i < 5; i++) {
                leader.propose(new byte[]{(byte) i});
            }
            Set<NodeId> active = Set.of(N1, NodeId.of(2));
            for (int r = 0; r < 10; r++) {
                cluster.deliverMessagesTo(active);
            }
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }
            for (int r = 0; r < 5; r++) {
                cluster.deliverMessagesTo(active);
            }
            assertTrue(leader.triggerSnapshot());

            cluster.transports.values().forEach(InstallSnapshotTest.TestTransport::clear);
            for (int i = 0; i < 51; i++) {
                leader.tick();
            }

            List<InstallSnapshotRequest> reqs = cluster.transports.get(N1)
                    .messagesTo(lagging, InstallSnapshotRequest.class);
            assertFalse(reqs.isEmpty());
            InstallSnapshotRequest req = reqs.getFirst();
            // Matches the unchunked single-blob wire: offset 0, done true, full data, config.
            assertEquals(0, req.offset());
            assertTrue(req.done());
            assertTrue(req.data().length > 0);
            assertEquals(leader.log().snapshotIndex(), req.lastIncludedIndex());
        }

        @Test
        void singleChunkInstallMatchesUnchunkedInstall() {
            var transport = new InstallSnapshotTest.TestTransport();
            var sm = new InstallSnapshotTest.TestStateMachine();
            RaftNode follower = newFollower(transport, sm);

            byte[] full = "the-whole-snapshot".getBytes();
            // A single done chunk at offset 0 - exactly what an unchunked InstallSnapshot is.
            follower.handleMessage(new InstallSnapshotRequest(1, N1, 7, 1, 0, full, true, null));

            assertArrayEquals(full, sm.restoredFrom);
            assertEquals(7, follower.log().snapshotIndex());
            List<InstallSnapshotResponse> responses =
                    transport.messagesOfType(InstallSnapshotResponse.class);
            assertEquals(1, responses.size());
            assertTrue(responses.getFirst().success());
            assertEquals(7, responses.getFirst().lastIncludedIndex());
        }
    }
}

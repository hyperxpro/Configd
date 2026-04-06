package io.configd.replication;

import io.configd.replication.SnapshotTransfer.SnapshotChunk;
import io.configd.replication.SnapshotTransfer.SnapshotReceiveState;
import io.configd.replication.SnapshotTransfer.SnapshotSendState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SnapshotTransfer} — chunked send/receive/assemble
 * roundtrip, edge cases, and determinism.
 */
class SnapshotTransferTest {

    private SnapshotTransfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new SnapshotTransfer();
    }

    // ========================================================================
    // Sender tests
    // ========================================================================

    @Nested
    class SenderTests {

        @Test
        void smallSnapshotProducesSingleChunk() {
            byte[] data = "small-snapshot".getBytes();
            SnapshotSendState state = transfer.startSend(data, 10, 2);

            SnapshotChunk chunk = transfer.nextChunk(state);
            assertNotNull(chunk);
            assertEquals(0, chunk.offset());
            assertArrayEquals(data, chunk.data());
            assertTrue(chunk.done());
            assertEquals(10, chunk.lastIncludedIndex());
            assertEquals(2, chunk.lastIncludedTerm());

            // No more chunks
            assertNull(transfer.nextChunk(state));
            assertTrue(state.isComplete());
        }

        @Test
        void largeSnapshotProducesMultipleChunks() {
            byte[] data = new byte[SnapshotTransfer.CHUNK_SIZE * 3 + 100];
            Arrays.fill(data, (byte) 0xAB);
            SnapshotSendState state = transfer.startSend(data, 50, 5);

            List<SnapshotChunk> chunks = new ArrayList<>();
            SnapshotChunk chunk;
            while ((chunk = transfer.nextChunk(state)) != null) {
                chunks.add(chunk);
            }

            // 3 full chunks + 1 partial = 4 chunks
            assertEquals(4, chunks.size());

            // Verify offsets
            assertEquals(0, chunks.get(0).offset());
            assertEquals(SnapshotTransfer.CHUNK_SIZE, chunks.get(1).offset());
            assertEquals(SnapshotTransfer.CHUNK_SIZE * 2, chunks.get(2).offset());
            assertEquals(SnapshotTransfer.CHUNK_SIZE * 3, chunks.get(3).offset());

            // Verify sizes
            assertEquals(SnapshotTransfer.CHUNK_SIZE, chunks.get(0).data().length);
            assertEquals(SnapshotTransfer.CHUNK_SIZE, chunks.get(1).data().length);
            assertEquals(SnapshotTransfer.CHUNK_SIZE, chunks.get(2).data().length);
            assertEquals(100, chunks.get(3).data().length);

            // Only last chunk is marked done
            assertFalse(chunks.get(0).done());
            assertFalse(chunks.get(1).done());
            assertFalse(chunks.get(2).done());
            assertTrue(chunks.get(3).done());

            // All chunks have correct metadata
            for (SnapshotChunk c : chunks) {
                assertEquals(50, c.lastIncludedIndex());
                assertEquals(5, c.lastIncludedTerm());
            }
        }

        @Test
        void exactlyChunkSizedSnapshot() {
            byte[] data = new byte[SnapshotTransfer.CHUNK_SIZE];
            Arrays.fill(data, (byte) 0xFF);
            SnapshotSendState state = transfer.startSend(data, 1, 1);

            SnapshotChunk chunk = transfer.nextChunk(state);
            assertNotNull(chunk);
            assertEquals(SnapshotTransfer.CHUNK_SIZE, chunk.data().length);
            assertTrue(chunk.done());
            assertNull(transfer.nextChunk(state));
        }

        @Test
        void emptySnapshotProducesNoChunks() {
            SnapshotSendState state = transfer.startSend(new byte[0], 0, 0);
            assertTrue(state.isComplete());
            assertNull(transfer.nextChunk(state));
        }

        @Test
        void sendStateTracksProgress() {
            byte[] data = new byte[SnapshotTransfer.CHUNK_SIZE * 2];
            SnapshotSendState state = transfer.startSend(data, 1, 1);

            assertEquals(0, state.currentOffset());
            assertEquals(SnapshotTransfer.CHUNK_SIZE * 2, state.totalSize());
            assertFalse(state.isComplete());

            transfer.nextChunk(state);
            assertEquals(SnapshotTransfer.CHUNK_SIZE, state.currentOffset());
            assertFalse(state.isComplete());

            transfer.nextChunk(state);
            assertEquals(SnapshotTransfer.CHUNK_SIZE * 2, state.currentOffset());
            assertTrue(state.isComplete());
        }

        @Test
        void startSendRejectsNullData() {
            assertThrows(NullPointerException.class,
                    () -> transfer.startSend(null, 0, 0));
        }

        @Test
        void startSendRejectsNegativeIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> transfer.startSend(new byte[0], -1, 0));
        }

        @Test
        void startSendRejectsNegativeTerm() {
            assertThrows(IllegalArgumentException.class,
                    () -> transfer.startSend(new byte[0], 0, -1));
        }
    }

    // ========================================================================
    // Receiver tests
    // ========================================================================

    @Nested
    class ReceiverTests {

        @Test
        void receiveSingleChunk() {
            byte[] data = "single-chunk".getBytes();
            SnapshotReceiveState state = transfer.startReceive(10, 2);

            boolean accepted = transfer.acceptChunk(state, 0, data, true);
            assertTrue(accepted);
            assertTrue(transfer.isComplete(state));

            byte[] assembled = transfer.assemble(state);
            assertArrayEquals(data, assembled);
        }

        @Test
        void receiveMultipleChunks() {
            SnapshotReceiveState state = transfer.startReceive(50, 5);

            byte[] chunk1 = new byte[]{1, 2, 3, 4};
            byte[] chunk2 = new byte[]{5, 6, 7};
            byte[] chunk3 = new byte[]{8, 9};

            assertTrue(transfer.acceptChunk(state, 0, chunk1, false));
            assertFalse(transfer.isComplete(state));

            assertTrue(transfer.acceptChunk(state, 4, chunk2, false));
            assertFalse(transfer.isComplete(state));

            assertTrue(transfer.acceptChunk(state, 7, chunk3, true));
            assertTrue(transfer.isComplete(state));

            byte[] assembled = transfer.assemble(state);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, assembled);
        }

        @Test
        void rejectOutOfOrderChunk() {
            SnapshotReceiveState state = transfer.startReceive(1, 1);

            assertTrue(transfer.acceptChunk(state, 0, new byte[]{1, 2}, false));
            // Skip expected offset 2, try offset 5
            assertFalse(transfer.acceptChunk(state, 5, new byte[]{3}, true));
            assertFalse(transfer.isComplete(state));
        }

        @Test
        void rejectChunkAfterComplete() {
            SnapshotReceiveState state = transfer.startReceive(1, 1);
            assertTrue(transfer.acceptChunk(state, 0, new byte[]{1}, true));
            assertTrue(transfer.isComplete(state));

            // Should reject further chunks
            assertFalse(transfer.acceptChunk(state, 1, new byte[]{2}, true));
        }

        @Test
        void assembleBeforeCompleteThrows() {
            SnapshotReceiveState state = transfer.startReceive(1, 1);
            transfer.acceptChunk(state, 0, new byte[]{1}, false);
            assertThrows(IllegalStateException.class,
                    () -> transfer.assemble(state));
        }

        @Test
        void receiveStateTracksMetadata() {
            SnapshotReceiveState state = transfer.startReceive(100, 7);
            assertEquals(100, state.lastIncludedIndex());
            assertEquals(7, state.lastIncludedTerm());
            assertFalse(state.isComplete());
        }

        @Test
        void startReceiveRejectsNegativeIndex() {
            assertThrows(IllegalArgumentException.class,
                    () -> transfer.startReceive(-1, 0));
        }

        @Test
        void startReceiveRejectsNegativeTerm() {
            assertThrows(IllegalArgumentException.class,
                    () -> transfer.startReceive(0, -1));
        }

        @Test
        void acceptChunkRejectsNullData() {
            SnapshotReceiveState state = transfer.startReceive(1, 1);
            assertThrows(NullPointerException.class,
                    () -> transfer.acceptChunk(state, 0, null, true));
        }
    }

    // ========================================================================
    // Full roundtrip tests
    // ========================================================================

    @Nested
    class Roundtrip {

        @Test
        void fullRoundtripSmallSnapshot() {
            byte[] original = "config-snapshot-data".getBytes();
            verifyRoundtrip(original, 10, 3);
        }

        @Test
        void fullRoundtripLargeSnapshot() {
            // Generate a large snapshot spanning multiple chunks
            byte[] original = new byte[SnapshotTransfer.CHUNK_SIZE * 5 + 1234];
            new Random(42).nextBytes(original);
            verifyRoundtrip(original, 500, 12);
        }

        @Test
        void fullRoundtripExactMultipleOfChunkSize() {
            byte[] original = new byte[SnapshotTransfer.CHUNK_SIZE * 3];
            new Random(99).nextBytes(original);
            verifyRoundtrip(original, 300, 8);
        }

        private void verifyRoundtrip(byte[] original, long lastIndex, long lastTerm) {
            // Sender side
            SnapshotSendState sendState = transfer.startSend(original, lastIndex, lastTerm);

            // Receiver side
            SnapshotReceiveState receiveState = transfer.startReceive(lastIndex, lastTerm);

            // Transfer chunks
            SnapshotChunk chunk;
            while ((chunk = transfer.nextChunk(sendState)) != null) {
                boolean accepted = transfer.acceptChunk(
                        receiveState, chunk.offset(), chunk.data(), chunk.done());
                assertTrue(accepted, "Chunk at offset " + chunk.offset() + " was rejected");
            }

            assertTrue(transfer.isComplete(receiveState));
            byte[] assembled = transfer.assemble(receiveState);
            assertArrayEquals(original, assembled);
        }
    }

    // ========================================================================
    // Determinism tests
    // ========================================================================

    @Nested
    class Determinism {

        @Test
        void sameDataProducesSameChunks() {
            byte[] data = new byte[SnapshotTransfer.CHUNK_SIZE * 2 + 500];
            new Random(42).nextBytes(data);

            List<SnapshotChunk> chunks1 = allChunks(data, 10, 2);
            List<SnapshotChunk> chunks2 = allChunks(data, 10, 2);

            assertEquals(chunks1.size(), chunks2.size());
            for (int i = 0; i < chunks1.size(); i++) {
                assertEquals(chunks1.get(i), chunks2.get(i));
            }
        }

        private List<SnapshotChunk> allChunks(byte[] data, long index, long term) {
            SnapshotSendState state = transfer.startSend(data, index, term);
            List<SnapshotChunk> chunks = new ArrayList<>();
            SnapshotChunk chunk;
            while ((chunk = transfer.nextChunk(state)) != null) {
                chunks.add(chunk);
            }
            return chunks;
        }
    }
}

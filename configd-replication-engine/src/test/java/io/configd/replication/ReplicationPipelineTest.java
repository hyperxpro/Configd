package io.configd.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationPipelineTest {

    private static final int MAX_BATCH_SIZE = 4;
    private static final int MAX_BATCH_BYTES = 256;
    private static final long MAX_BATCH_DELAY_NANOS = 200_000L; // 200us

    private ReplicationPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new ReplicationPipeline(MAX_BATCH_SIZE, MAX_BATCH_BYTES, MAX_BATCH_DELAY_NANOS);
    }

    @Nested
    class ConstructorValidation {

        @Test
        void rejectsZeroBatchSize() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReplicationPipeline(0, 256, 200_000L));
        }

        @Test
        void rejectsNegativeBatchSize() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReplicationPipeline(-1, 256, 200_000L));
        }

        @Test
        void rejectsZeroBatchBytes() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReplicationPipeline(4, 0, 200_000L));
        }

        @Test
        void rejectsZeroBatchDelay() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReplicationPipeline(4, 256, 0));
        }

        @Test
        void rejectsNegativeBatchDelay() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ReplicationPipeline(4, 256, -1));
        }
    }

    @Nested
    class EmptyPipeline {

        @Test
        void pendingCountIsZero() {
            assertEquals(0, pipeline.pendingCount());
        }

        @Test
        void pendingBytesIsZero() {
            assertEquals(0L, pipeline.pendingBytes());
        }

        @Test
        void shouldFlushReturnsFalse() {
            assertFalse(pipeline.shouldFlush(1_000_000L));
        }

        @Test
        void flushReturnsEmptyList() {
            List<byte[]> batch = pipeline.flush();
            assertTrue(batch.isEmpty());
        }
    }

    @Nested
    class OfferAndPending {

        @Test
        void offerIncrementsPendingCount() {
            pipeline.offer(new byte[]{1, 2, 3});
            assertEquals(1, pipeline.pendingCount());

            pipeline.offer(new byte[]{4, 5});
            assertEquals(2, pipeline.pendingCount());
        }

        @Test
        void offerTracksPendingBytes() {
            pipeline.offer(new byte[10]);
            assertEquals(10L, pipeline.pendingBytes());

            pipeline.offer(new byte[20]);
            assertEquals(30L, pipeline.pendingBytes());
        }

        @Test
        void offerRejectsNull() {
            assertThrows(NullPointerException.class,
                    () -> pipeline.offer(null));
        }

        @Test
        void offerAcceptsEmptyArray() {
            pipeline.offer(new byte[0]);
            assertEquals(1, pipeline.pendingCount());
            assertEquals(0L, pipeline.pendingBytes());
        }
    }

    @Nested
    class FlushByCount {

        @Test
        void shouldFlushWhenBatchSizeReached() {
            for (int i = 0; i < MAX_BATCH_SIZE; i++) {
                pipeline.offer(new byte[]{(byte) i});
            }
            assertTrue(pipeline.shouldFlush(0L));
        }

        @Test
        void shouldNotFlushBelowBatchSize() {
            for (int i = 0; i < MAX_BATCH_SIZE - 1; i++) {
                pipeline.offer(new byte[]{(byte) i});
            }
            assertFalse(pipeline.shouldFlush(0L));
        }

        @Test
        void flushReturnsBatchAndResets() {
            for (int i = 0; i < MAX_BATCH_SIZE; i++) {
                pipeline.offer(new byte[]{(byte) i});
            }

            List<byte[]> batch = pipeline.flush();
            assertEquals(MAX_BATCH_SIZE, batch.size());
            assertArrayEquals(new byte[]{0}, batch.get(0));
            assertArrayEquals(new byte[]{3}, batch.get(3));

            assertEquals(0, pipeline.pendingCount());
            assertEquals(0L, pipeline.pendingBytes());
            assertFalse(pipeline.shouldFlush(Long.MAX_VALUE));
        }
    }

    @Nested
    class FlushByBytes {

        @Test
        void shouldFlushWhenByteLimitReached() {
            pipeline.offer(new byte[MAX_BATCH_BYTES]);
            assertTrue(pipeline.shouldFlush(0L));
        }

        @Test
        void shouldFlushWhenByteLimitExceeded() {
            pipeline.offer(new byte[100]);
            pipeline.offer(new byte[200]); // Total: 300 > 256
            assertTrue(pipeline.shouldFlush(0L));
        }

        @Test
        void shouldNotFlushBelowByteLimit() {
            pipeline.offer(new byte[MAX_BATCH_BYTES - 1]);
            assertFalse(pipeline.shouldFlush(0L));
        }
    }

    @Nested
    class FlushByTime {

        @Test
        void shouldFlushWhenTimeExceeded() {
            pipeline.offer(new byte[]{1});
            // The batching timer starts on this first shouldFlush check, not on offer().
            assertFalse(pipeline.shouldFlush(0L));
            assertTrue(pipeline.shouldFlush(MAX_BATCH_DELAY_NANOS));
        }

        @Test
        void shouldNotFlushBeforeTimeElapsed() {
            pipeline.offer(new byte[]{1});
            assertFalse(pipeline.shouldFlush(0L));
            assertFalse(pipeline.shouldFlush(MAX_BATCH_DELAY_NANOS - 1));
        }

        @Test
        void timerResetsAfterFlush() {
            pipeline.offer(new byte[]{1});
            pipeline.shouldFlush(0L); // starts the timer (return value unused here)

            pipeline.flush();

            pipeline.offer(new byte[]{2});
            assertFalse(pipeline.shouldFlush(MAX_BATCH_DELAY_NANOS - 1));
            pipeline.shouldFlush(1_000_000L); // starts the new batch's timer
            assertTrue(pipeline.shouldFlush(1_000_000L + MAX_BATCH_DELAY_NANOS));
        }
    }

    @Nested
    class Reset {

        @Test
        void resetClearsAllState() {
            pipeline.offer(new byte[]{1, 2, 3});
            pipeline.offer(new byte[]{4, 5});
            pipeline.shouldFlush(0L); // starts the timer (return value unused here)

            pipeline.reset();

            assertEquals(0, pipeline.pendingCount());
            assertEquals(0L, pipeline.pendingBytes());
            assertFalse(pipeline.shouldFlush(MAX_BATCH_DELAY_NANOS * 10));
            assertTrue(pipeline.flush().isEmpty());
        }
    }

    @Nested
    class FlushUnmodifiable {

        @Test
        void flushReturnsUnmodifiableList() {
            pipeline.offer(new byte[]{1});
            List<byte[]> batch = pipeline.flush();
            assertThrows(UnsupportedOperationException.class,
                    () -> batch.add(new byte[]{99}));
        }
    }

    @Nested
    class MultipleBatches {

        @Test
        void consecutiveFlushesProduceIndependentBatches() {
            pipeline.offer(new byte[]{1});
            pipeline.offer(new byte[]{2});
            List<byte[]> batch1 = pipeline.flush();
            assertEquals(2, batch1.size());

            pipeline.offer(new byte[]{3});
            List<byte[]> batch2 = pipeline.flush();
            assertEquals(1, batch2.size());
            assertArrayEquals(new byte[]{3}, batch2.get(0));

            assertEquals(2, batch1.size());
        }
    }
}

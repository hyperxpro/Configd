package io.configd.edge;

import io.configd.common.Clock;
import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.EdgeSnapshotCodec;
import io.configd.observability.MetricsRegistry;
import io.configd.store.ConfigSnapshot;
import io.configd.store.HamtMap;
import io.configd.store.VersionedValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side snapshot-chunk accumulation bounds in {@link EdgeClientCore}. The
 * {@code SnapshotBegin.chunkCount}/{@code totalBytes} fields are attacker-controlled (a
 * malicious or compromised distribution server, or plaintext), so {@code onSnapshotChunk}
 * must not trust them: a flood of chunks could otherwise stream until the edge heap approaches
 * the codec's ~2 GiB reassemble ceiling and OOMs. Accumulation is bounded by the BEGIN-declared
 * {@code chunkCount}/{@code totalBytes} (cross-field) AND the hard
 * {@link EdgeClientCore#MAX_SNAPSHOT_TOTAL_BYTES} / {@link EdgeClientCore#MAX_SNAPSHOT_CHUNKS}
 * ceilings, rejecting a flood through the same protocol-error path as a chunk-outside-transfer
 * ({@link IllegalStateException} -> poison/reconnect). A valid multi-chunk snapshot still
 * reassembles identically.
 */
class EdgeSnapshotAccumulationBoundsTest {

    private static final class TestClock implements Clock {
        long timeMs = 1_000_000L;
        @Override public long currentTimeMillis() { return timeMs; }
        @Override public long nanoTime() { return timeMs * 1_000_000L; }
    }

    private TestClock clock;
    private EdgeClientCore core;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        MetricsRegistry metrics = new MetricsRegistry();
        core = new EdgeClientCore(clock, null,
                metrics.counter(StalenessTracker.IMPLAUSIBLE_METRIC),
                StrongReadKeyClass.DEFAULT, EdgeClientCore.FrameSink.NONE,
                EdgeClientCore.DEFAULT_HEARTBEAT_MS, EdgeClientCore.DEFAULT_SILENCE_FACTOR);
    }

    private static EdgeFrame.SnapshotChunk chunk(int index, int len) {
        return new EdgeFrame.SnapshotChunk(index, new byte[len]);
    }

    @Test
    void chunkBeyondDeclaredCountIsRejectedBeforeAccumulation() {
        // BEGIN declares 2 chunks / 6 bytes; two 3-byte chunks are fine, the THIRD is the
        // (chunkCount+1)-th and is rejected as a protocol error, counted, not accumulated.
        core.onFrame(new EdgeFrame.SnapshotBegin(5L, 2, 6L));
        core.onFrame(chunk(0, 3));
        core.onFrame(chunk(1, 3));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.onFrame(chunk(2, 3)));
        assertTrue(e.getMessage().contains("chunkCount"), e.getMessage());
        assertEquals(1, core.snapshotChunksRejected());
    }

    @Test
    void accumulatedBytesBeyondDeclaredTotalIsRejected() {
        // BEGIN declares 10 chunks but only 4 total bytes; a 5-byte chunk overshoots totalBytes
        // and is rejected before the list grows unbounded (cross-field cap).
        core.onFrame(new EdgeFrame.SnapshotBegin(7L, 10, 4L));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.onFrame(chunk(0, 5)));
        assertTrue(e.getMessage().contains("totalBytes"), e.getMessage());
        assertEquals(1, core.snapshotChunksRejected());
    }

    @Test
    void beginDeclaringAboveTheHardChunkCeilingIsRejectedUpFront() {
        // The declared chunkCount is itself attacker-supplied; a BEGIN over the hard ceiling is
        // rejected before a single chunk is taken.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.onFrame(new EdgeFrame.SnapshotBegin(
                        9L, EdgeClientCore.MAX_SNAPSHOT_CHUNKS + 1, 100L)));
        assertTrue(e.getMessage().contains("MAX_SNAPSHOT_CHUNKS"), e.getMessage());
        assertEquals(1, core.snapshotChunksRejected());
    }

    @Test
    void beginDeclaringAboveTheHardByteCeilingIsRejectedUpFront() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.onFrame(new EdgeFrame.SnapshotBegin(
                        9L, 1, EdgeClientCore.MAX_SNAPSHOT_TOTAL_BYTES + 1)));
        assertTrue(e.getMessage().contains("MAX_SNAPSHOT_TOTAL_BYTES"), e.getMessage());
        assertEquals(1, core.snapshotChunksRejected());
    }

    @Test
    void validMultiChunkSnapshotStillReassembles() {
        // A real multi-chunk snapshot (chunked small so it spans several frames) must apply
        // unchanged: the caps admit exactly the declared count/bytes.
        ConfigSnapshot snap = snapshot(3, "a", "1", "b", "2", "c", "3");
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 8);
        assertTrue(chunks.size() > 1, "expected a multi-chunk transfer");

        core.onFrame(new EdgeFrame.SnapshotBegin(3L, chunks.size(), body.length));
        for (EdgeFrame.SnapshotChunk c : chunks) {
            core.onFrame(c);
        }
        core.onFrame(new EdgeFrame.SnapshotEnd(3L));

        assertEquals(3L, core.cursor());
        assertEquals(1, core.snapshotsApplied());
        assertEquals(0, core.snapshotChunksRejected());
        assertTrue(core.get("c").found());
    }

    private static ConfigSnapshot snapshot(long version, String... kv) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (int i = 0; i < kv.length; i += 2) {
            data = data.put(kv[i],
                    new VersionedValue(kv[i + 1].getBytes(StandardCharsets.UTF_8), version, version));
        }
        return new ConfigSnapshot(data, version, version);
    }
}

package io.configd.distribution.wire;

import io.configd.common.Clock;
import io.configd.store.CommandCodec;
import io.configd.store.ConfigSnapshot;
import io.configd.store.ConfigStateMachine;
import io.configd.store.HamtMap;
import io.configd.store.VersionedConfigStore;
import io.configd.store.VersionedValue;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.constraints.IntRange;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link EdgeSnapshotCodec} reuses the same snapshot ENTRY layout as the Raft state
 * machine, and that the chunk/reassemble path is lossless. The edge body is trailer-less by
 * design (the edge decodes it via {@link EdgeSnapshotCodec#deserialize}); the frozen
 * state-machine snapshot additionally requires the canonical magic-TLV trailer, so the tests
 * that round-trip through {@code ConfigStateMachine.restoreSnapshot} append that trailer to
 * exercise the shared entry layout via the state machine.
 */
class EdgeSnapshotCodecTest {

    private static final Clock CLOCK = new Clock() {
        @Override public long currentTimeMillis() { return 1L; }
        @Override public long nanoTime() { return 1_000_000L; }
    };

    @Test
    void serializedSnapshotIsAcceptedByConfigStateMachineRestore() {
        ConfigSnapshot snap = snapshot(7L, Map.of(
                "svc/a", new byte[]{1, 2, 3},
                "db/b", "value-b".getBytes(StandardCharsets.UTF_8)));

        byte[] body = EdgeSnapshotCodec.serialize(snap);

        VersionedConfigStore store = new VersionedConfigStore(CLOCK);
        ConfigStateMachine sm = new ConfigStateMachine(store, CLOCK);
        sm.restoreSnapshot(withStateMachineTrailer(body));

        assertEquals(7L, store.currentVersion(), "restored version must equal the snapshot seq");
        assertArrayEquals(new byte[]{1, 2, 3}, store.snapshot().get("svc/a"));
        assertArrayEquals("value-b".getBytes(StandardCharsets.UTF_8), store.snapshot().get("db/b"));
    }

    @Test
    void serializeDeserializeRoundTripsStoreContent() {
        ConfigSnapshot snap = snapshot(42L, Map.of(
                "k1", new byte[]{9},
                "k2", new byte[]{8, 7, 6},
                "k3", new byte[0]));
        ConfigSnapshot back = EdgeSnapshotCodec.deserialize(EdgeSnapshotCodec.serialize(snap));
        assertEquals(snap.version(), back.version());
        assertEquals(sorted(snap), sorted(back));
    }

    @Test
    void chunkAndReassembleIsLossless() {
        byte[] body = new byte[5000];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 31 + 7);
        }
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, 1024);
        assertEquals(5, chunks.size(), "5000 bytes / 1024 = 5 chunks");
        assertEquals(0, chunks.get(0).index());
        assertEquals(4, chunks.get(4).index());
        assertArrayEquals(body, EdgeSnapshotCodec.reassemble(chunks));
    }

    @Test
    void oversizeEntryFieldIsRejected() {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        byte[] huge = new byte[EdgeSnapshotCodec.MAX_ENTRY_FIELD_BYTES + 1];
        data = data.put("big", new VersionedValue(huge, 1L, 0L));
        ConfigSnapshot snap = new ConfigSnapshot(data, 1L, 0L);
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.serialize(snap));
    }

    @Test
    void reassembleRejectsOutOfOrderChunks() {
        List<EdgeFrame.SnapshotChunk> bad = List.of(
                new EdgeFrame.SnapshotChunk(0, new byte[]{1}),
                new EdgeFrame.SnapshotChunk(2, new byte[]{2}));
        assertThrows(IllegalArgumentException.class, () -> EdgeSnapshotCodec.reassemble(bad));
    }

    @Property(tries = 100)
    void anySnapshotRoundTripsThroughChunkedRestore(
            @ForAll("snapshots") ConfigSnapshot snap,
            @ForAll @IntRange(min = 1, max = 4096) int chunkBytes) {
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        List<EdgeFrame.SnapshotChunk> chunks = EdgeSnapshotCodec.chunk(body, chunkBytes);
        byte[] reassembled = EdgeSnapshotCodec.reassemble(chunks);
        assertArrayEquals(body, reassembled);

        VersionedConfigStore store = new VersionedConfigStore(CLOCK);
        ConfigStateMachine sm = new ConfigStateMachine(store, CLOCK);
        sm.restoreSnapshot(withStateMachineTrailer(reassembled));
        assertEquals(snap.version(), store.currentVersion());
        assertEquals(sorted(snap), sorted(store.snapshot()));
    }

    @Test
    void edgeSnapshotBodyIsCarrierVersioned() {
        ConfigSnapshot snap = snapshot(123L, Map.of("k", new byte[]{1}));
        byte[] body = EdgeSnapshotCodec.serialize(snap);
        long leadU64 = java.nio.ByteBuffer.wrap(body).getLong();
        assertEquals(123L, leadU64,
                "the lead u64 is the DATA sequence (snapshot.version()), not a format version");
        assertEquals(123L, EdgeSnapshotCodec.deserialize(body).version(),
                "the trailer-less body decodes via the edge consumer, preserving the data sequence");
    }

    private static final int SNAPSHOT_TRAILER_MAGIC = 0xC0FD7A11;

    /**
     * Appends the canonical magic-TLV trailer the frozen state-machine snapshot format
     * requires (magic, then length 8, then signingEpoch 0). The EdgeSnapshotCodec body is
     * trailer-less by design, since the edge decodes it via
     * {@link EdgeSnapshotCodec#deserialize}; the restore path of the state machine demands
     * the TLV trailer, so we add it to drive the shared entry layout through the state
     * machine.
     */
    private static byte[] withStateMachineTrailer(byte[] body) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(body.length + 4 + 4 + 8);
        b.put(body);
        b.putInt(SNAPSHOT_TRAILER_MAGIC);
        b.putInt(8);
        b.putLong(0L);
        return b.array();
    }

    private static ConfigSnapshot snapshot(long version, Map<String, byte[]> entries) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (var e : entries.entrySet()) {
            data = data.put(e.getKey(), new VersionedValue(e.getValue(), version, 0L));
        }
        return new ConfigSnapshot(data, version, 0L);
    }

    private static Map<String, String> sorted(ConfigSnapshot snap) {
        Map<String, String> out = new TreeMap<>();
        snap.data().forEach((k, vv) ->
                out.put(k, java.util.HexFormat.of().formatHex(vv.valueUnsafe())));
        return out;
    }

    @Provide
    Arbitrary<ConfigSnapshot> snapshots() {
        Arbitrary<String> keys = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<byte[]> vals = Arbitraries.bytes().array(byte[].class).ofMaxSize(64);
        Arbitrary<Long> version = Arbitraries.longs().between(0, 1_000_000);
        return Arbitraries.maps(keys, vals).ofMaxSize(12)
                .flatMap(map -> version.map(v -> {
                    HamtMap<String, VersionedValue> data = HamtMap.empty();
                    for (var e : map.entrySet()) {
                        data = data.put(e.getKey(), new VersionedValue(e.getValue(), v, 0L));
                    }
                    return new ConfigSnapshot(data, v, 0L);
                }));
    }

    /** Keeps CommandCodec import warm - the codec reuse story is the point of this module. */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP = CommandCodec.class;
}

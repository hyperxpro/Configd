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
 * Proves {@link EdgeSnapshotCodec} reuses the same snapshot byte format as the Raft
 * state machine: the bytes a {@link ConfigSnapshot} serializes to are accepted by
 * {@code ConfigStateMachine.restoreSnapshot}, and the chunk/reassemble path is lossless.
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

        // The state-machine consumer must accept our body as a (trailer-less / legacy) snapshot.
        VersionedConfigStore store = new VersionedConfigStore(CLOCK);
        ConfigStateMachine sm = new ConfigStateMachine(store, CLOCK);
        sm.restoreSnapshot(body);

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
        sm.restoreSnapshot(reassembled);
        assertEquals(snap.version(), store.currentVersion());
        assertEquals(sorted(snap), sorted(store.snapshot()));
    }

    // ---- helpers ------------------------------------------------------------

    private static ConfigSnapshot snapshot(long version, Map<String, byte[]> entries) {
        HamtMap<String, VersionedValue> data = HamtMap.empty();
        for (var e : entries.entrySet()) {
            data = data.put(e.getKey(), new VersionedValue(e.getValue(), version, 0L));
        }
        return new ConfigSnapshot(data, version, 0L);
    }

    /** Key-sorted (key -> value-bytes-as-hex) view for byte-equality comparison. */
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

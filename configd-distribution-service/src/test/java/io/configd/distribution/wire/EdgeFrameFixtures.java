package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical, deterministic frame instances for the edge-codec golden fixture
 * (CT-41). One frame of EVERY {@link FrameType}, every {@link ErrorCode}, the
 * empty-NOTIFY edge case, and a {@link EdgeFrame.SnapshotChunk} at exactly 1 MiB.
 *
 * <p>The inputs here are fixed constants so the encoded bytes are stable: this is the
 * single source of truth both {@code EdgeFrameCodecGoldenFixtureTest} (which asserts
 * byte-equality against the pinned hex) and the rebaseline path use. Changing any value
 * here changes the golden bytes and so MUST bump {@link EdgeFrameCodec#EDGE_WIRE_VERSION}
 * and re-pin {@link EdgeFrameGoldenBytes}.
 */
final class EdgeFrameFixtures {

    private EdgeFrameFixtures() {
    }

    /** Fixed signature bytes (16) used in the signed-NOTIFY fixture. */
    private static final byte[] SIG = bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
            0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xF0, 0x0F);

    /** Fixed 8-byte nonce used in the F-0052 signed-NOTIFY fixture. */
    private static final byte[] NONCE = bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08);

    /**
     * Builds the canonical fixture set, keyed by a stable {@code name.bin} string. Order
     * is insertion order (a {@link LinkedHashMap}) so the generator prints deterministically.
     */
    static Map<String, EdgeFrame> build() {
        Map<String, EdgeFrame> m = new LinkedHashMap<>();

        m.put("subscribe_full_store.bin",
                new EdgeFrame.Subscribe(true, List.of(), 0L, -1L, "edge-A"));
        m.put("subscribe_prefixes.bin",
                new EdgeFrame.Subscribe(false, List.of("svc/", "db/"), 4096L, 5000L, "edge-B"));

        m.put("subscribe_ok_tail.bin",
                new EdgeFrame.SubscribeOk(12345L, EdgeFrame.Mode.TAIL));
        m.put("subscribe_ok_snapshot_first.bin",
                new EdgeFrame.SubscribeOk(67890L, EdgeFrame.Mode.SNAPSHOT_FIRST));

        m.put("notify_single_unsigned.bin",
                new EdgeFrame.Notify(List.of(notif(100L, 1_700_000_000_000L,
                        new ConfigDelta(99L, 100L,
                                List.of(new ConfigMutation.Put("svc/cfg", bytes(0xDE, 0xAD, 0xBE, 0xEF))))))));
        m.put("notify_batch_signed.bin",
                new EdgeFrame.Notify(List.of(
                        notif(200L, 1_700_000_000_100L, new ConfigDelta(199L, 200L,
                                List.of(new ConfigMutation.Put("a/k1", bytes(0x01))), SIG, 7L, NONCE)),
                        notif(201L, 1_700_000_000_200L, new ConfigDelta(200L, 201L,
                                List.of(new ConfigMutation.Delete("a/k2")), SIG, 8L, NONCE)))));
        m.put("notify_empty.bin", new EdgeFrame.Notify(List.of()));

        m.put("snapshot_begin.bin", new EdgeFrame.SnapshotBegin(5000L, 3, 2_500_000L));
        m.put("snapshot_chunk_small.bin",
                new EdgeFrame.SnapshotChunk(0, bytes(0x10, 0x20, 0x30, 0x40)));
        // SNAPSHOT_CHUNK at exactly 1 MiB (the cap). Deterministic fill so the bytes are
        // stable; the golden test pins only the CRC + header (the body is regenerated).
        m.put("snapshot_chunk_1mib.bin",
                new EdgeFrame.SnapshotChunk(7, oneMiBFill()));
        m.put("snapshot_end.bin", new EdgeFrame.SnapshotEnd(5000L));

        m.put("cursor_ack.bin", new EdgeFrame.CursorAck(4242L));
        m.put("heartbeat.bin", new EdgeFrame.Heartbeat(9000L, 1_700_000_000_500L));

        // One ERROR_CLOSE per taxonomy code.
        for (ErrorCode ec : ErrorCode.values()) {
            m.put("error_" + ec.name().toLowerCase() + ".bin",
                    new EdgeFrame.ErrorClose(ec, ec.name()));
        }
        return m;
    }

    /** A deterministic 1 MiB byte fill for the at-cap snapshot-chunk fixture. */
    static byte[] oneMiBFill() {
        byte[] b = new byte[EdgeFrameCodec.MAX_SNAPSHOT_CHUNK_BYTES];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i & 0xFF);
        }
        return b;
    }

    private static CommitNotification notif(long seq, long ts, ConfigDelta d) {
        return new CommitNotification(seq, ts, d);
    }

    private static byte[] bytes(int... xs) {
        byte[] b = new byte[xs.length];
        for (int i = 0; i < xs.length; i++) {
            b[i] = (byte) xs[i];
        }
        return b;
    }

    /** Names whose full byte string is too large to inline as a hex constant (1 MiB chunk). */
    static List<String> oversizeFixtureNames() {
        List<String> out = new ArrayList<>();
        out.add("snapshot_chunk_1mib.bin");
        return out;
    }
}

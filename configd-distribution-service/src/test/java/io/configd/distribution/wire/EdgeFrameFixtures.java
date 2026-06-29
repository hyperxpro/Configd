package io.configd.distribution.wire;

import io.configd.distribution.CommitNotification;
import io.configd.store.ConfigDelta;
import io.configd.store.ConfigMutation;

import java.nio.charset.StandardCharsets;
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

        // One ERROR_CLOSE per built taxonomy code. NOT_AUTHORIZED is a 0x02-era addition
        // (W7-5a) and is covered as a 0x02 fixture in buildV2(), so the v1 golden image stays
        // entirely frozen (no new v1 entry).
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec == ErrorCode.NOT_AUTHORIZED) {
                continue;
            }
            m.put("error_" + ec.name().toLowerCase() + ".bin",
                    new EdgeFrame.ErrorClose(ec, ec.name()));
        }
        return m;
    }

    /**
     * Canonical, deterministic 0x02 ({@link EdgeFrameCodec#EDGE_WIRE_VERSION_V2}) fixtures:
     * one frame of every RFC §2 watch type ({@code WATCH_*}), plus a {@code NOTIFY} reused at
     * 0x02 (proving the design-A "only the version byte differs" property vs its 0x01 fixture)
     * and an {@code ERROR_CLOSE} carrying the new {@link ErrorCode#NOT_AUTHORIZED} code. The
     * cursor vector is pinned in three forms: single-component (N=1, {@code watch_create}),
     * empty/from-now ({@code watch_create_fromnow}), and multi-component ({@code watch_progress}).
     * The {@code watch_event} fixture pins the signed-i32 {@code val_len} sentinel: a non-empty
     * PUT (val_len &gt; 0), an empty PUT (val_len == 0), and a DELETE (val_len == -1).
     */
    static Map<String, EdgeFrame> buildV2() {
        Map<String, EdgeFrame> m = new LinkedHashMap<>();

        m.put("watch_create.bin", new EdgeFrame.WatchCreate(
                7L, 2, EdgeFrame.WATCH_TARGET_KEY, "svc/cfg".getBytes(StandardCharsets.UTF_8),
                WatchCursor.of(0, 42L),
                EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY | EdgeFrame.WATCH_FLAG_WITH_INITIAL_SNAPSHOT));
        m.put("watch_create_fromnow.bin", new EdgeFrame.WatchCreate(
                8L, 0, EdgeFrame.WATCH_TARGET_PREFIX, "svc/".getBytes(StandardCharsets.UTF_8),
                WatchCursor.fromNow(), 0));
        // FULL target: empty path (path-empty-iff-FULL shape), from-now cursor, full_chain_verify.
        m.put("watch_create_full.bin", new EdgeFrame.WatchCreate(
                9L, 2, EdgeFrame.WATCH_TARGET_FULL, new byte[0],
                WatchCursor.fromNow(), EdgeFrame.WATCH_FLAG_FULL_CHAIN_VERIFY));

        m.put("watch_cancel.bin", new EdgeFrame.WatchCancel(7L));

        m.put("watch_created.bin", new EdgeFrame.WatchCreated(7L, List.of(
                new EdgeFrame.ShardMode(0, 100L, EdgeFrame.Mode.TAIL),
                new EdgeFrame.ShardMode(1, 200L, EdgeFrame.Mode.SNAPSHOT_FIRST))));

        m.put("watch_event.bin", new EdgeFrame.WatchEvent(7L, 0, 101L, 1_700_000_000_000L, List.of(
                EdgeFrame.WatchChange.put("a/k1", bytes(0xDE, 0xAD)),
                EdgeFrame.WatchChange.put("a/empty", bytes()),
                EdgeFrame.WatchChange.delete("a/k2"))));
        // A larger commit batch exercising the i32 val_len sentinel mix in a golden:
        // non-empty PUTs (val_len 3/2/1), an empty PUT (val_len 0), and a DELETE (val_len -1).
        m.put("watch_event_batch.bin", new EdgeFrame.WatchEvent(7L, 0, 250L, 1_700_000_001_234L, List.of(
                EdgeFrame.WatchChange.put("svc/a", bytes(0x01, 0x02, 0x03)),
                EdgeFrame.WatchChange.put("svc/b", bytes(0xCA, 0xFE)),
                EdgeFrame.WatchChange.put("svc/empty", bytes()),
                EdgeFrame.WatchChange.delete("svc/gone"),
                EdgeFrame.WatchChange.put("svc/c", bytes(0xFF)))));

        m.put("watch_progress.bin", new EdgeFrame.WatchProgress(7L,
                new WatchCursor(List.of(
                        new WatchCursor.Component(0, 101L),
                        new WatchCursor.Component(1, 205L))),
                1_700_000_000_500L));

        m.put("watch_canceled_gap.bin", new EdgeFrame.WatchCanceled(
                7L, ErrorCode.GAP_UNRECOVERABLE, WatchCursor.of(0, 50L), "gap"));
        m.put("watch_canceled_not_authorized.bin", new EdgeFrame.WatchCanceled(
                11L, ErrorCode.NOT_AUTHORIZED, null, ""));

        m.put("watch_snapshot_begin.bin", new EdgeFrame.WatchSnapshotBegin(7L, 1, 200L, 2, 4096L));
        m.put("watch_snapshot_chunk.bin",
                new EdgeFrame.WatchSnapshotChunk(7L, 1, 0, bytes(0x10, 0x20, 0x30, 0x40)));
        // At-cap (1 MiB) watch snapshot chunk — too large to inline as hex; pinned by its
        // full-frame CRC32C in EdgeFrameGoldenBytes.goldenCrcV2() (mirrors the v1 at-cap chunk).
        m.put("watch_snapshot_chunk_1mib.bin",
                new EdgeFrame.WatchSnapshotChunk(7L, 1, 3, oneMiBFill()));
        m.put("watch_snapshot_end.bin", new EdgeFrame.WatchSnapshotEnd(7L, 1, 200L));

        // A NOTIFY reused on a 0x02 connection — byte-identical to its v1 fixture
        // (notify_single_unsigned) except the version byte and the CRC over it (W5-2 / W5-11).
        m.put("notify_reused.bin", new EdgeFrame.Notify(List.of(notif(100L, 1_700_000_000_000L,
                new ConfigDelta(99L, 100L,
                        List.of(new ConfigMutation.Put("svc/cfg", bytes(0xDE, 0xAD, 0xBE, 0xEF))))))));

        // ERROR_CLOSE carrying the 0x02-era NOT_AUTHORIZED code (W7-5a).
        m.put("error_not_authorized.bin",
                new EdgeFrame.ErrorClose(ErrorCode.NOT_AUTHORIZED, ErrorCode.NOT_AUTHORIZED.name()));

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

    /** v2 oversize fixtures (the 1 MiB watch snapshot chunk) — CRC-pinned, not inline hex. */
    static List<String> oversizeV2FixtureNames() {
        List<String> out = new ArrayList<>();
        out.add("watch_snapshot_chunk_1mib.bin");
        return out;
    }
}

package io.configd.distribution.wire;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Edge wire-compat golden fixture for the watch frames at
 * {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V2} (0x02). The v2 analogue of
 * {@link EdgeFrameCodecGoldenFixtureTest}: encodes one frame of every {@code WATCH_*} type
 * (plus a reused NOTIFY and the NOT_AUTHORIZED ERROR_CLOSE) at 0x02 and asserts byte-equality
 * against the pinned hex in {@link EdgeFrameGoldenBytes#forVersion(int)} for version 2.
 *
 * <p><b>This does NOT rebaseline the 0x01 fixtures.</b> The built {@code 0x01} golden image is
 * frozen and proven byte-identical by {@link EdgeFrameCodecGoldenFixtureTest}; 0x02 is purely
 * additive. The <b>rebaseline rule</b> for v2 is the same: a drift here is a
 * 0x02 wire-format change - revert, or regenerate via {@code EdgeFrameGoldenBytesGenerator}.
 */
class EdgeFrameCodecV2GoldenFixtureTest {

    private static final byte V2 = EdgeFrameCodec.EDGE_WIRE_VERSION_V2;

    @TestFactory
    List<DynamicTest> everyV2FixtureMatchesGoldenBytes() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.buildV2();
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V2 & 0xFF);
        List<String> oversize = EdgeFrameFixtures.oversizeV2FixtureNames();
        HexFormat hf = HexFormat.of();

        List<DynamicTest> tests = new ArrayList<>(fixtures.size());
        for (Map.Entry<String, EdgeFrame> e : fixtures.entrySet()) {
            String name = e.getKey();
            EdgeFrame frame = e.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                byte[] live = EdgeFrameCodec.encode(frame, V2);
                assertEquals(V2, live[4], "v2 fixture " + name + " must be stamped 0x02");
                if (oversize.contains(name)) {
                    // Byte-pin the 1 MiB at-cap watch chunk via its full-frame CRC32C (too large
                    // to inline as hex), mirroring the v1 at-cap snapshot-chunk fixture.
                    CRC32C crc = new CRC32C();
                    crc.update(live, 0, live.length);
                    assertEquals(EdgeFrameGoldenBytes.goldenCrcV2(), crc.getValue(),
                            "1 MiB watch-snapshot-chunk frame CRC drifted — 0x02 wire change without a"
                                    + " regenerate (see EdgeFrameGoldenBytes rebaseline rule)");
                    assertEquals(frame, EdgeFrameCodec.decode(live),
                            "the at-cap watch chunk must decode back to the same frame");
                    return;
                }
                byte[] expected = golden.get(name);
                if (expected == null) {
                    fail("missing v2 golden entry for: " + name + " (live bytes: " + hf.formatHex(live) + ")");
                }
                assertArrayEquals(expected, live,
                        "edge 0x02 wire drift for " + name + ": expected " + hf.formatHex(expected)
                                + " but got " + hf.formatHex(live) + ". Revert, or regenerate.");
                assertEquals(frame, EdgeFrameCodec.decode(expected),
                        "v2 golden bytes for " + name + " must decode to the canonical frame");
            }));
        }
        return tests;
    }

    @Test
    void everyV2GoldenEntryDecodesCleanly() {
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V2 & 0xFF);
        for (Map.Entry<String, byte[]> e : golden.entrySet()) {
            EdgeFrame f = EdgeFrameCodec.decode(e.getValue());
            assertEquals(e.getValue().length, EdgeFrameCodec.peekLength(e.getValue()),
                    "peekLength mismatch on v2 golden " + e.getKey());
            assertEquals(EdgeFrameCodec.encode(f, V2).length, e.getValue().length,
                    "re-encode length mismatch on v2 golden " + e.getKey());
        }
    }

    @Test
    void v2FixturesCoverEveryWatchFrameType() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.buildV2();
        for (FrameType ft : FrameType.values()) {
            boolean watch = switch (ft) {
                case WATCH_CREATE, WATCH_CANCEL, WATCH_CREATED, WATCH_EVENT, WATCH_PROGRESS,
                     WATCH_CANCELED, WATCH_SNAPSHOT_BEGIN, WATCH_SNAPSHOT_CHUNK, WATCH_SNAPSHOT_END -> true;
                default -> false;
            };
            if (watch && fixtures.values().stream().noneMatch(f -> f.type() == ft)) {
                fail("no v2 fixture covers watch frame type " + ft);
            }
        }
    }

    /**
     * The design-A property: a frame reused on a 0x02 connection is
     * byte-identical to its 0x01 encoding <b>except the version byte (offset 4) and the CRC
     * trailer</b>. Proven against the same NOTIFY payload encoded at both versions.
     */
    @Test
    void reusedNotifyDiffersFromV1OnlyInVersionByteAndCrc() {
        // The exact same Notify instance the v1 golden (notify_single_unsigned) pins.
        EdgeFrame notify = EdgeFrameFixtures.buildV2().get("notify_reused.bin");
        byte[] v1 = EdgeFrameCodec.encode(notify);                            // stamps 0x01
        byte[] v2 = EdgeFrameCodec.encode(notify, V2);                        // stamps 0x02

        assertEquals(v1.length, v2.length, "same payload ⇒ same frame length");
        assertEquals(EdgeFrameCodec.EDGE_WIRE_VERSION, v1[4]);
        assertEquals(V2, v2[4]);

        int crcStart = v1.length - EdgeFrameCodec.TRAILER_SIZE;
        for (int i = 0; i < v1.length; i++) {
            if (i == 4) {
                assertNotEquals(v1[i], v2[i], "version byte (offset 4) must differ");
            } else if (i < crcStart) {
                assertEquals(v1[i], v2[i], "byte " + i + " (length/type/payload) must be identical");
            }
        }
        // The CRC trailer differs because it covers the differing version byte.
        byte[] crc1 = java.util.Arrays.copyOfRange(v1, crcStart, v1.length);
        byte[] crc2 = java.util.Arrays.copyOfRange(v2, crcStart, v2.length);
        assertNotEquals(HexFormat.of().formatHex(crc1), HexFormat.of().formatHex(crc2),
                "CRC trailer must differ (it covers the version byte)");

        // And it is the SAME payload the v1 golden image pins (notify_single_unsigned).
        byte[] v1Golden = EdgeFrameGoldenBytes.forVersion(1).get("notify_single_unsigned.bin");
        assertArrayEquals(v1Golden, v1,
                "the reused NOTIFY's 0x01 encoding must equal the frozen v1 golden image");
    }

    /** A WATCH_* frame cannot be encoded on a 0x01 connection - caller error. */
    @Test
    void watchFrameRefusedUnderV1Encode() {
        EdgeFrame watchCreate = EdgeFrameFixtures.buildV2().get("watch_create.bin");
        assertThrows(IllegalArgumentException.class,
                () -> EdgeFrameCodec.encode(watchCreate),
                "encoding a WATCH_* frame via the legacy 0x01 path must throw");
        assertThrows(IllegalArgumentException.class,
                () -> EdgeFrameCodec.encode(watchCreate, EdgeFrameCodec.EDGE_WIRE_VERSION),
                "encoding a WATCH_* frame explicitly at 0x01 must throw");
    }


    @Test
    void peekVersionReturnsStampedVersionAndBoundsChecks() {
        byte[] v1 = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(1));
        byte[] v2 = EdgeFrameCodec.encode(EdgeFrameFixtures.buildV2().get("watch_cancel.bin"), V2);
        assertEquals(EdgeFrameCodec.EDGE_WIRE_VERSION, EdgeFrameCodec.peekVersion(v1));
        assertEquals(V2, EdgeFrameCodec.peekVersion(v2));
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.peekVersion(new byte[EdgeFrameCodec.HEADER_SIZE - 1]));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }

    @Test
    void decodeWithNegotiatedVersionPinsPerConnection() {
        byte[] v1Frame = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(9));
        byte[] v2Frame = EdgeFrameCodec.encode(EdgeFrameFixtures.buildV2().get("watch_cancel.bin"), V2);

        assertEquals(new EdgeFrame.CursorAck(9),
                EdgeFrameCodec.decode(v1Frame, EdgeFrameCodec.EDGE_WIRE_VERSION));
        assertEquals(new EdgeFrame.WatchCancel(7L), EdgeFrameCodec.decode(v2Frame, V2));

        // A 0x02 frame on a 0x01-negotiated connection fails closed (and the reverse).
        EdgeFrameCodec.CodecException a = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(v2Frame, EdgeFrameCodec.EDGE_WIRE_VERSION));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, a.code());
        EdgeFrameCodec.CodecException b = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(v1Frame, V2));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, b.code());

        // An unsupported negotiated version is a caller/programming error (0x04 is now the valid
        // auth-phase version, so use 0x05 for the genuinely-unsupported case).
        assertThrows(IllegalArgumentException.class,
                () -> EdgeFrameCodec.decode(v1Frame, (byte) 0x05));
    }

    /**
     * A FULL-target WATCH_CREATE carrying a NON-EMPTY path is rejected as FRAME_CORRUPT (the
     * decode-side {@code WatchCreate} path-empty-iff-FULL invariant). The arbitraries cannot
     * construct this combination, so it is exercised by hand-crafting the wire: take the KEY
     * fixture (which has a non-empty path) and flip its target_kind byte to FULL.
     */
    @Test
    void fullTargetWithNonEmptyPathRejected() {
        byte[] wire = EdgeFrameCodec.encode(EdgeFrameFixtures.buildV2().get("watch_create.bin"), V2);
        wire[15] = (byte) EdgeFrame.WATCH_TARGET_FULL; // target_kind byte (offset 15) := FULL(2)
        CRC32C crc = new CRC32C();
        crc.update(wire, 0, wire.length - EdgeFrameCodec.TRAILER_SIZE);
        int v = (int) crc.getValue();
        int off = wire.length - EdgeFrameCodec.TRAILER_SIZE;
        wire[off] = (byte) (v >>> 24);
        wire[off + 1] = (byte) (v >>> 16);
        wire[off + 2] = (byte) (v >>> 8);
        wire[off + 3] = (byte) v;
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(wire));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }
}

package io.configd.distribution.wire;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Edge wire-compat golden fixture for the filtered-fan-out frames at
 * {@link EdgeFrameCodec#EDGE_WIRE_VERSION_V3} (0x03). The v3 analogue of
 * {@link EdgeFrameCodecGoldenFixtureTest}: encodes the {@link EdgeFrame.Subscribe} /
 * {@link EdgeFrame.SubscribeOk} fixtures (which carry the extra {@code acceptsFiltered} /
 * {@code filtered} byte under 0x03) and asserts byte-equality against the pinned hex in
 * {@link EdgeFrameGoldenBytes#forVersion(int)} for version 3.
 *
 * <p><b>This does NOT rebaseline the 0x01/0x02 fixtures.</b> The 0x03 fields are purely additive
 * (ADR-0045); the frozen 0x01/0x02 golden images are proven byte-identical by their own tests.
 */
class EdgeFrameCodecV3GoldenFixtureTest {

    private static final byte V3 = EdgeFrameCodec.EDGE_WIRE_VERSION_V3;

    @TestFactory
    List<DynamicTest> everyV3FixtureMatchesGoldenBytes() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.buildV3();
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V3 & 0xFF);
        HexFormat hf = HexFormat.of();

        List<DynamicTest> tests = new ArrayList<>(fixtures.size());
        for (Map.Entry<String, EdgeFrame> e : fixtures.entrySet()) {
            String name = e.getKey();
            EdgeFrame frame = e.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                byte[] live = EdgeFrameCodec.encode(frame, V3);
                assertEquals(V3, live[4], "v3 fixture " + name + " must be stamped 0x03");
                byte[] expected = golden.get(name);
                if (expected == null) {
                    fail("missing v3 golden entry for: " + name + " (live bytes: " + hf.formatHex(live) + ")");
                }
                assertArrayEquals(expected, live,
                        "edge 0x03 wire drift for " + name + ": expected " + hf.formatHex(expected)
                                + " but got " + hf.formatHex(live) + ". Revert, or regenerate.");
                // Golden bytes decode back to the canonical frame.
                assertEquals(frame, EdgeFrameCodec.decode(expected),
                        "v3 golden bytes for " + name + " must decode to the canonical frame");
            }));
        }
        return tests;
    }

    /** Every v3 golden entry is a structurally valid frame the decoder accepts, and peekLength agrees. */
    @Test
    void everyV3GoldenEntryDecodesCleanly() {
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V3 & 0xFF);
        for (Map.Entry<String, byte[]> e : golden.entrySet()) {
            EdgeFrame f = EdgeFrameCodec.decode(e.getValue());
            assertEquals(e.getValue().length, EdgeFrameCodec.peekLength(e.getValue()),
                    "peekLength mismatch on v3 golden " + e.getKey());
            assertEquals(EdgeFrameCodec.encode(f, V3).length, e.getValue().length,
                    "re-encode length mismatch on v3 golden " + e.getKey());
        }
    }

    /**
     * The 0x03 fields are 0x03-only: encoding the same filtered SUBSCRIBE at 0x01 drops the
     * {@code acceptsFiltered} byte, producing bytes identical to the frozen v1 {@code
     * subscribe_prefixes} golden image (which is the same frame minus the opt-in). This is the
     * design-A "the extra field lives only under the new version" property.
     */
    @Test
    void filteredFieldExistsOnlyUnderV3() {
        EdgeFrame.Subscribe filtered = (EdgeFrame.Subscribe)
                EdgeFrameFixtures.buildV3().get("subscribe_prefixes_filtered.bin");
        // Same wire fields as the v1 subscribe_prefixes fixture, differing only by the 0x03 opt-in.
        byte[] atV1 = EdgeFrameCodec.encode(filtered);        // 0x01: acceptsFiltered byte absent
        byte[] atV3 = EdgeFrameCodec.encode(filtered, V3);    // 0x03: acceptsFiltered byte present
        assertEquals(atV1.length + 1, atV3.length,
                "the 0x03 SUBSCRIBE carries exactly one extra byte (acceptsFiltered)");
        byte[] v1Golden = EdgeFrameGoldenBytes.forVersion(1).get("subscribe_prefixes.bin");
        assertArrayEquals(v1Golden, atV1,
                "a filtered SUBSCRIBE encoded at 0x01 equals the frozen v1 subscribe_prefixes image");
        // And the acceptsFiltered opt-in round-trips under 0x03.
        assertEquals(filtered, EdgeFrameCodec.decode(atV3));
        // While the same bytes decoded at 0x01 yield acceptsFiltered=false (the field is 0x03-only).
        EdgeFrame.Subscribe atV1Decoded = (EdgeFrame.Subscribe) EdgeFrameCodec.decode(atV1);
        org.junit.jupiter.api.Assertions.assertFalse(atV1Decoded.acceptsFiltered());
    }

    /** A 0x03 frame on a 0x01/0x02-negotiated connection fails closed (and each reverse). */
    @Test
    void decodeVersionPinRejectsCrossVersionFrames() {
        byte[] v3Frame = EdgeFrameCodec.encode(
                EdgeFrameFixtures.buildV3().get("subscribe_ok_filtered.bin"), V3);
        byte[] v1Frame = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(9));

        // Matching pin succeeds.
        assertEquals(new EdgeFrame.SubscribeOk(12345L, EdgeFrame.Mode.TAIL, true),
                EdgeFrameCodec.decode(v3Frame, V3));

        // 0x03 frame on a 0x01-pinned connection and vice versa: BAD_WIRE_VERSION.
        EdgeFrameCodec.CodecException a = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(v3Frame, EdgeFrameCodec.EDGE_WIRE_VERSION));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, a.code());
        EdgeFrameCodec.CodecException b = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(v1Frame, V3));
        assertEquals(ErrorCode.BAD_WIRE_VERSION, b.code());
    }

    /** A 0x03 SUBSCRIBE truncated before the acceptsFiltered byte is FRAME_CORRUPT. */
    @Test
    void truncatedV3SubscribeMissingOptInByteRejected() {
        byte[] wire = EdgeFrameCodec.encode(
                EdgeFrameFixtures.buildV3().get("subscribe_full_store.bin"), V3);
        // Drop the last payload byte (acceptsFiltered) plus re-CRC over the shortened frame so
        // the failure is the missing field, not a CRC mismatch.
        byte[] truncated = java.util.Arrays.copyOf(wire, wire.length - 1 - EdgeFrameCodec.TRAILER_SIZE);
        int newLen = truncated.length + EdgeFrameCodec.TRAILER_SIZE;
        byte[] reframed = java.util.Arrays.copyOf(truncated, newLen);
        reframed[0] = (byte) (newLen >>> 24);
        reframed[1] = (byte) (newLen >>> 16);
        reframed[2] = (byte) (newLen >>> 8);
        reframed[3] = (byte) newLen;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(reframed, 0, truncated.length);
        int v = (int) crc.getValue();
        reframed[truncated.length] = (byte) (v >>> 24);
        reframed[truncated.length + 1] = (byte) (v >>> 16);
        reframed[truncated.length + 2] = (byte) (v >>> 8);
        reframed[truncated.length + 3] = (byte) v;
        EdgeFrameCodec.CodecException ex = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(reframed));
        assertEquals(ErrorCode.FRAME_CORRUPT, ex.code());
    }
}

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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Edge wire-compat golden fixture. Encodes one frame of EVERY {@link FrameType} (including
 * each {@link ErrorCode}), the empty-NOTIFY edge case, and a 1 MiB at-cap snapshot chunk,
 * then asserts byte-equality against the pinned hex in {@link EdgeFrameGoldenBytes}. This is
 * the wire-compat guard that keeps the edge protocol's bytes from drifting silently and keeps
 * the edge codec off the Raft fixture gate (a separate codec + version byte).
 *
 * <p><b>Rebaseline rule.</b> A failure here means the encoded bytes changed. Revert, or -
 * for an intentional protocol change - bump {@link EdgeFrameCodec#EDGE_WIRE_VERSION} and
 * regenerate {@link EdgeFrameGoldenBytes} via {@code EdgeFrameGoldenBytesGenerator}. See
 * {@link EdgeFrameGoldenBytes}'s class Javadoc.
 */
class EdgeFrameCodecGoldenFixtureTest {

    @TestFactory
    List<DynamicTest> everyFixtureMatchesGoldenBytes() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.build();
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(EdgeFrameCodec.EDGE_WIRE_VERSION & 0xFF);
        List<String> oversize = EdgeFrameFixtures.oversizeFixtureNames();
        HexFormat hf = HexFormat.of();

        List<DynamicTest> tests = new ArrayList<>(fixtures.size());
        for (Map.Entry<String, EdgeFrame> e : fixtures.entrySet()) {
            String name = e.getKey();
            EdgeFrame frame = e.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                byte[] live = EdgeFrameCodec.encode(frame);
                if (oversize.contains(name)) {
                    // Byte-pin the at-cap chunk via its full-frame CRC32C (1 MiB is too
                    // large to inline as hex).
                    CRC32C crc = new CRC32C();
                    crc.update(live, 0, live.length);
                    assertEquals(EdgeFrameGoldenBytes.goldenCrc(), crc.getValue(),
                            "1 MiB snapshot-chunk frame CRC drifted — wire change without a"
                                    + " EDGE_WIRE_VERSION bump (see EdgeFrameGoldenBytes rebaseline rule)");
                    // And it must decode back to the same frame.
                    assertEquals(frame, EdgeFrameCodec.decode(live));
                    return;
                }
                byte[] expected = golden.get(name);
                if (expected == null) {
                    fail("missing golden entry for: " + name
                            + " — add it to EdgeFrameGoldenBytes.v1() (live bytes: "
                            + hf.formatHex(live) + ")");
                }
                assertArrayEquals(expected, live,
                        "edge wire drift for " + name + ": expected " + hf.formatHex(expected)
                                + " but got " + hf.formatHex(live)
                                + ". Revert, or bump EDGE_WIRE_VERSION and regenerate.");
                // Round-trip: golden bytes decode back to the canonical frame.
                assertEquals(frame, EdgeFrameCodec.decode(expected),
                        "golden bytes for " + name + " must decode to the canonical frame");
            }));
        }
        return tests;
    }

    /** Every golden hex entry is a structurally valid frame the decoder accepts. */
    @Test
    void everyGoldenEntryDecodesCleanly() {
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(EdgeFrameCodec.EDGE_WIRE_VERSION & 0xFF);
        for (Map.Entry<String, byte[]> e : golden.entrySet()) {
            EdgeFrame f = EdgeFrameCodec.decode(e.getValue());
            // peekLength agrees with the actual length on every golden frame.
            assertEquals(e.getValue().length, EdgeFrameCodec.peekLength(e.getValue()),
                    "peekLength mismatch on golden " + e.getKey());
            assertEquals(EdgeFrameCodec.encode(f).length, e.getValue().length,
                    "re-encode length mismatch on golden " + e.getKey());
        }
    }

    /**
     * The fixture set covers every frame type, every error code, the empty-NOTIFY edge
     * case, and the at-cap chunk - a coverage tripwire so a future type/code addition
     * cannot land without a golden entry. Coverage is taken across BOTH the v1 fixtures
     * ({@link EdgeFrameFixtures#build()}) and the v2 fixtures
     * ({@link EdgeFrameFixtures#buildV2()}): the watch frame types and the
     * {@link ErrorCode#NOT_AUTHORIZED} code are 0x02-era additions covered in the v2 set.
     */
    @Test
    void fixtureSetCoversEveryTypeAndErrorCode() {
        List<EdgeFrame> all = new ArrayList<>(EdgeFrameFixtures.build().values());
        all.addAll(EdgeFrameFixtures.buildV2().values());
        all.addAll(EdgeFrameFixtures.buildV4().values());

        // Every FrameType present in some fixture (v1, v2, or v4 auth).
        for (FrameType ft : FrameType.values()) {
            boolean present = all.stream().anyMatch(f -> f.type() == ft);
            if (!present) {
                fail("no golden fixture covers frame type " + ft);
            }
        }
        // Every ErrorCode present (as an ErrorClose or a WatchCanceled - both carry the code).
        for (ErrorCode ec : ErrorCode.values()) {
            boolean present = all.stream().anyMatch(f ->
                    (f instanceof EdgeFrame.ErrorClose close && close.code() == ec)
                            || (f instanceof EdgeFrame.WatchCanceled wc && wc.code() == ec));
            if (!present) {
                fail("no golden fixture covers error code " + ec);
            }
        }
        // Empty-NOTIFY edge case present (v1).
        boolean emptyNotify = EdgeFrameFixtures.build().values().stream().anyMatch(f ->
                f instanceof EdgeFrame.Notify n && n.notifications().isEmpty());
        if (!emptyNotify) {
            fail("no golden fixture covers the empty-NOTIFY edge case");
        }
    }
}

package io.configd.distribution.wire;

import io.configd.common.auth.Credential;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden byte-pin for the 0x04 ({@link EdgeFrameCodec#EDGE_WIRE_VERSION_V4}) auth-phase frames
 * (AUTH / REFRESH_AUTH), plus the type&lt;-&gt;version legality matrix and the redaction discipline. The
 * 0x01/0x02/0x03 golden images stay untouched (this version is purely additive) - that byte-identity is
 * asserted by {@code EdgeFrameCodec{,V2,V3}GoldenFixtureTest}.
 */
class EdgeFrameCodecV4GoldenFixtureTest {

    private static final byte V4 = EdgeFrameCodec.EDGE_WIRE_VERSION_V4;

    @TestFactory
    List<DynamicTest> everyV4FixtureMatchesGoldenBytes() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.buildV4();
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V4 & 0xFF);
        HexFormat hf = HexFormat.of();

        List<DynamicTest> tests = new ArrayList<>(fixtures.size());
        for (Map.Entry<String, EdgeFrame> e : fixtures.entrySet()) {
            String name = e.getKey();
            EdgeFrame frame = e.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                byte[] live = EdgeFrameCodec.encode(frame, V4);
                assertEquals(V4, live[4], "v4 fixture " + name + " must be stamped 0x04");
                byte[] expected = golden.get(name);
                if (expected == null) {
                    fail("missing v4 golden entry for: " + name + " (live: " + hf.formatHex(live) + ")");
                }
                assertArrayEquals(expected, live,
                        "edge 0x04 wire drift for " + name + ": expected " + hf.formatHex(expected)
                                + " but got " + hf.formatHex(live) + ". Revert, or regenerate.");
                // Byte-level round-trip (decode then re-encode == golden). Stronger than object equality,
                // and correct for a BasicCredential whose char[] password uses reference equals as a record.
                assertArrayEquals(expected, EdgeFrameCodec.encode(EdgeFrameCodec.decode(expected), V4),
                        "v4 golden bytes for " + name + " must round-trip through decode+encode unchanged");
            }));
        }
        return tests;
    }

    @Test
    void everyV4GoldenEntryDecodesCleanlyAndPins() {
        Map<String, byte[]> golden = EdgeFrameGoldenBytes.forVersion(V4 & 0xFF);
        for (Map.Entry<String, byte[]> e : golden.entrySet()) {
            byte[] bytes = e.getValue();
            assertEquals(bytes.length, EdgeFrameCodec.peekLength(bytes), "peekLength mismatch on " + e.getKey());
            assertEquals(V4, EdgeFrameCodec.peekVersion(bytes), "v4 golden " + e.getKey() + " must peek 0x04");
            // A 0x04 frame decoded under an EXPLICIT business pin fails closed (the transport never does
            // this - it exempts 0x04 from the pin - but the codec still enforces a supplied pin).
            assertEquals(ErrorCode.BAD_WIRE_VERSION, assertThrows(EdgeFrameCodec.CodecException.class,
                    () -> EdgeFrameCodec.decode(bytes, EdgeFrameCodec.EDGE_WIRE_VERSION_V2)).code());
            // Byte round-trip under the matching pin (avoids char[] record-equals for BasicCredential).
            assertArrayEquals(bytes, EdgeFrameCodec.encode(EdgeFrameCodec.decode(bytes, V4), V4));
        }
    }

    @Test
    void v4FixturesCoverBothAuthTypes() {
        List<EdgeFrame> all = new ArrayList<>(EdgeFrameFixtures.buildV4().values());
        assertEquals(true, all.stream().anyMatch(f -> f.type() == FrameType.AUTH), "AUTH covered");
        assertEquals(true, all.stream().anyMatch(f -> f.type() == FrameType.REFRESH_AUTH), "REFRESH_AUTH covered");
    }


    @Test
    void authFrameCannotBeEncodedUnderABusinessVersion() {
        EdgeFrame auth = new EdgeFrame.Auth(new Credential.BearerToken("t"));
        for (byte v : new byte[]{EdgeFrameCodec.EDGE_WIRE_VERSION, EdgeFrameCodec.EDGE_WIRE_VERSION_V2,
                EdgeFrameCodec.EDGE_WIRE_VERSION_V3}) {
            assertThrows(IllegalArgumentException.class, () -> EdgeFrameCodec.encode(auth, v),
                    "AUTH must not encode under 0x" + Integer.toHexString(v & 0xFF));
        }
    }

    @Test
    void businessFrameCannotBeEncodedUnderV4() {
        assertThrows(IllegalArgumentException.class,
                () -> EdgeFrameCodec.encode(new EdgeFrame.CursorAck(1), V4));
        assertThrows(IllegalArgumentException.class, () -> EdgeFrameCodec.encode(
                EdgeFrameFixtures.buildV2().get("watch_cancel.bin"), V4));
    }

    @Test
    void authTypeStampedUnderABusinessVersionDecodesAsFrameCorrupt() {
        // Re-stamp a genuine AUTH frame to version 0x01 and fix the CRC, so it is a deliberate
        // (CRC-valid) AUTH-under-0x01 frame - which the legality matrix must reject as FRAME_CORRUPT.
        byte[] frame = EdgeFrameCodec.encode(new EdgeFrame.Auth(new Credential.BearerToken("t")), V4);
        frame[4] = EdgeFrameCodec.EDGE_WIRE_VERSION;
        fixCrc(frame);
        assertEquals(ErrorCode.FRAME_CORRUPT, assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(frame)).code());
    }

    @Test
    void businessTypeStampedUnderV4DecodesAsFrameCorrupt() {
        byte[] frame = EdgeFrameCodec.encode(new EdgeFrame.CursorAck(9));
        frame[4] = V4;
        fixCrc(frame);
        assertEquals(ErrorCode.FRAME_CORRUPT, assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(frame)).code());
    }


    @Test
    void aClientCertificateCannotRideAnAuthFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeFrame.Auth(new Credential.ClientCertificate(List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new EdgeFrame.RefreshAuth(new Credential.ClientCertificate(List.of())));
    }

    @Test
    void authFrameToStringRedactsTheCredential() {
        EdgeFrame bearer = new EdgeFrame.Auth(new Credential.BearerToken("super-secret-token"));
        assertFalse(bearer.toString().contains("super-secret-token"), "an AUTH frame must not log the token");
        EdgeFrame basic = new EdgeFrame.Auth(new Credential.BasicCredential("u", "hunter2".toCharArray()));
        assertFalse(basic.toString().contains("hunter2"), "an AUTH frame must not log the password");
    }

    @Test
    void unknownAuthSchemeIsFrameCorrupt() {
        // Re-stamp a bearer AUTH frame's scheme byte (offset 6) to an unknown value; fix the CRC.
        byte[] frame = EdgeFrameCodec.encode(new EdgeFrame.Auth(new Credential.BearerToken("t")), V4);
        frame[6] = (byte) 0x7F; // neither BEARER(1) nor BASIC(2)
        fixCrc(frame);
        assertEquals(ErrorCode.FRAME_CORRUPT, assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(frame)).code());
    }

    /** Recomputes the CRC32C trailer over [0, len-4) after a deliberate header edit. */
    private static void fixCrc(byte[] frame) {
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, frame.length - 4);
        ByteBuffer.wrap(frame, frame.length - 4, 4).putInt((int) crc.getValue());
    }
}

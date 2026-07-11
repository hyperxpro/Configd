package io.configd.distribution.wire;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red-team coverage for the SUBSCRIBE {@code prefixCount} pre-allocation bound. Bounds-checking
 * {@code prefixCount} against {@code remaining} BYTES alone is not enough: a 2 MiB frame could
 * declare {@code prefixCount ~= 2.1M} and drive an {@code new ArrayList<>(2.1M)} (~8-17 MB) before
 * the read loop - a pre-authorization heap amplifier. The codec pre-checks
 * {@code prefixCount * 4 <= remaining} (min prefix = a u32 length of a zero-length string = 4
 * bytes) and caps the element count at {@link EdgeFrameCodec#MAX_PREFIXES}. Both reject BEFORE the
 * allocation, as a {@link ErrorCode#FRAME_CORRUPT} {@link EdgeFrameCodec.CodecException}; a
 * well-formed SUBSCRIBE is unchanged (its byte-identity is separately pinned by the golden
 * fixtures).
 */
class EdgeSubscribeBoundsTest {

    /**
     * Builds a CRC-valid 0x01 SUBSCRIBE frame that DECLARES {@code prefixCount} but carries only
     * {@code fillerBytes} of (zero) payload after it - so the declared count is a lie the decoder
     * must reject before allocating. The CRC is recomputed so the frame passes the codec's
     * CRC-before-payload gate and the {@code prefixCount} check (not the CRC) is what fires.
     */
    private static byte[] hostileSubscribe(int prefixCount, int fillerBytes) {
        int payloadLen = 1 /*fullStore*/ + 4 /*prefixCount*/ + fillerBytes;
        int total = EdgeFrameCodec.HEADER_SIZE + payloadLen + EdgeFrameCodec.TRAILER_SIZE;
        byte[] f = new byte[total];
        ByteBuffer bb = ByteBuffer.wrap(f);
        bb.putInt(total);                                  // length prefix
        bb.put(EdgeFrameCodec.EDGE_WIRE_VERSION);          // 0x01
        bb.put((byte) FrameType.SUBSCRIBE.code());         // type
        bb.put((byte) 0);                                  // fullStore = false
        bb.putInt(prefixCount);                            // the hostile declared count
        // filler stays zero
        CRC32C crc = new CRC32C();
        crc.update(f, 0, total - EdgeFrameCodec.TRAILER_SIZE);
        bb.putInt(total - EdgeFrameCodec.TRAILER_SIZE, (int) crc.getValue());
        return f;
    }

    @Test
    void hugePrefixCountRejectedByTheTightByteBoundBeforeAllocation() {
        // The classic amplifier: a near-2^31 prefixCount in a tiny frame. prefixCount*4 dwarfs
        // remaining, so it is rejected as FRAME_CORRUPT before new ArrayList<>(prefixCount).
        for (int count : new int[]{Integer.MAX_VALUE, 2_000_000, 600_000}) {
            EdgeFrameCodec.CodecException e = assertThrows(EdgeFrameCodec.CodecException.class,
                    () -> EdgeFrameCodec.decode(hostileSubscribe(count, 0)));
            assertEquals(ErrorCode.FRAME_CORRUPT, e.code(), "count=" + count);
            assertTrue(e.getMessage().contains("bad prefix count"), e.getMessage());
        }
    }

    @Test
    void prefixCountAboveMaxPrefixesRejectedEvenWhenBytesWouldAllowIt() {
        // prefixCount = MAX_PREFIXES + 1, with EXACTLY enough trailing bytes that the tight
        // byte-bound (count*4 <= remaining) passes - so the MAX_PREFIXES element cap is the guard
        // that fires. Proves the cap is a real second line, not shadowed by the byte-bound.
        int count = EdgeFrameCodec.MAX_PREFIXES + 1;
        int filler = count * 4; // remaining after prefixCount == count*4, so count*4 > remaining is false
        EdgeFrameCodec.CodecException e = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(hostileSubscribe(count, filler)));
        assertEquals(ErrorCode.FRAME_CORRUPT, e.code());
        assertTrue(e.getMessage().contains("MAX_PREFIXES"), e.getMessage());
    }

    @Test
    void maxPrefixesExactlyIsStillAcceptedByTheCountCap() {
        // The boundary is inclusive: MAX_PREFIXES itself passes the cap (it is a legal, if
        // extreme, count). It then fails on the truncated prefix bytes - still FRAME_CORRUPT, but
        // NOT via the MAX_PREFIXES message, proving the cap rejects only strictly-above.
        int count = EdgeFrameCodec.MAX_PREFIXES;
        int filler = count * 4;
        EdgeFrameCodec.CodecException e = assertThrows(EdgeFrameCodec.CodecException.class,
                () -> EdgeFrameCodec.decode(hostileSubscribe(count, filler)));
        assertEquals(ErrorCode.FRAME_CORRUPT, e.code());
        assertTrue(!e.getMessage().contains("MAX_PREFIXES"),
                "MAX_PREFIXES itself must pass the count cap: " + e.getMessage());
    }

    @Test
    void wellFormedSubscribeStillRoundTrips() {
        // The fix must not perturb a valid SUBSCRIBE: a real one has a handful of prefixes, well
        // under MAX_PREFIXES and inside the tight byte-bound, so it decodes unchanged.
        EdgeFrame.Subscribe original =
                new EdgeFrame.Subscribe(false, List.of("svc/", "db/", "cfg/"), 4096L, 5000L, "edge-A");
        EdgeFrame decoded = EdgeFrameCodec.decode(EdgeFrameCodec.encode(original));
        assertEquals(original, decoded);
    }
}

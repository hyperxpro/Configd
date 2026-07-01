package io.configd.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Wire-format invariants for the 8-byte reserved epoch field in {@link FrameCodec}.
 *
 * <p>Three properties pin the dormant-but-correct reservation:
 * <ol>
 *   <li><b>MBZ on send</b> - the encoder writes 8 zero bytes for the epoch (offset 18..25), for both
 *       the array and the {@link ByteBuffer} overload.</li>
 *   <li><b>Ignored on receive</b> - a frame whose epoch bytes are NON-zero (a hypothetical future
 *       v2.x sender that populates the field) still decodes cleanly to the same logical {@link
 *       FrameCodec.Frame}, so activating epoch later needs no further wire bump (forward-compatible).</li>
 *   <li><b>Byte-identity except the sanctioned diff</b> - a v2 frame, with its version byte reset to
 *       0x01 and the 8 reserved epoch bytes spliced out (length + CRC fixed), is byte-for-byte the
 *       canonical v1 frame. i.e. v2 == v1 + version-bump + reserved-epoch, and <em>nothing else</em>.</li>
 * </ol>
 */
class FrameCodecEpochReservationTest {

    private static final int GROUP_ID = 0x01020304;
    private static final long TERM = 0x0A0B0C0D0E0F1011L;
    private static final byte[] PAYLOAD = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

    // Offsets in the v2 layout: len(4) ver(1) type(1) gid(4) term(8) epoch(8) payload... crc(4).
    private static final int EPOCH_OFFSET = 4 + 1 + 1 + 4 + 8; // = 18
    private static final int EPOCH_SIZE = 8;

    @Test
    void wireVersionIsV2AndHeaderIs26() {
        assertEquals((byte) 0x02, FrameCodec.WIRE_VERSION, "Seam F bumped the wire to v2");
        assertEquals(26, FrameCodec.HEADER_SIZE, "v2 header = 18 + 8 reserved epoch bytes");
    }

    @Test
    void encodeWritesZeroEpoch_arrayOverload() {
        byte[] frame = FrameCodec.encode(MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        for (int i = EPOCH_OFFSET; i < EPOCH_OFFSET + EPOCH_SIZE; i++) {
            assertEquals(0, frame[i], "reserved epoch byte at offset " + i + " must be zero (MBZ)");
        }
        assertEquals((byte) 0x02, frame[4], "version byte must be 0x02");
    }

    @Test
    void encodeWritesZeroEpoch_byteBufferOverload() {
        ByteBuffer buf = ByteBuffer.allocate(FrameCodec.frameSize(PAYLOAD.length));
        FrameCodec.encode(buf, MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        byte[] frame = buf.array();
        for (int i = EPOCH_OFFSET; i < EPOCH_OFFSET + EPOCH_SIZE; i++) {
            assertEquals(0, frame[i], "reserved epoch byte at offset " + i + " must be zero (MBZ)");
        }
    }

    @Test
    void arrayAndByteBufferOverloadsAreByteIdentical() {
        byte[] a = FrameCodec.encode(MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        ByteBuffer buf = ByteBuffer.allocate(FrameCodec.frameSize(PAYLOAD.length));
        FrameCodec.encode(buf, MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        assertArrayEquals(a, buf.array(), "the two encode overloads must agree (epoch included)");
    }

    @Test
    void decodeIgnoresNonZeroEpoch_forwardCompatible() {
        // Forge a frame whose reserved epoch bytes are NON-zero (a future v2.x sender), with a valid
        // CRC, and assert it still decodes to the same logical frame - the decode-but-ignore contract.
        byte[] frame = FrameCodec.encode(MessageType.REQUEST_VOTE, GROUP_ID, TERM, PAYLOAD);
        for (int i = EPOCH_OFFSET; i < EPOCH_OFFSET + EPOCH_SIZE; i++) {
            frame[i] = (byte) 0xFF; // populate the reserved field
        }
        // Recompute the CRC32C trailer over the mutated pre-trailer bytes so framing stays valid.
        int crcOffset = frame.length - FrameCodec.TRAILER_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, crcOffset);
        ByteBuffer.wrap(frame, crcOffset, FrameCodec.TRAILER_SIZE).putInt((int) crc.getValue());

        // Sanity: the epoch really is non-zero now.
        assertNotEquals(0L, ByteBuffer.wrap(frame, EPOCH_OFFSET, EPOCH_SIZE).getLong());

        FrameCodec.Frame decoded = FrameCodec.decode(frame);
        assertEquals(MessageType.REQUEST_VOTE, decoded.messageType());
        assertEquals(GROUP_ID, decoded.groupId());
        assertEquals(TERM, decoded.term());
        assertArrayEquals(PAYLOAD, decoded.payload(),
                "a non-zero reserved epoch must be ignored, not corrupt the payload boundary");
    }

    @Test
    void v2DiffersFromV1OnlyByVersionAndReservedEpoch() {
        // Canonical v1 append_entries fixture (pre-Seam-F golden bytes).
        byte[] canonicalV1 = hex("0000001a0101010203040a0b0c0d0e0f1011deadbeef19b5b90b");
        byte[] v2 = FrameCodec.encode(MessageType.APPEND_ENTRIES, GROUP_ID, TERM, PAYLOAD);
        assertArrayEquals(canonicalV1, downgradeV2ToV1(v2),
                "v2 frame, minus the version bump and the 8 reserved epoch bytes, must be byte-for-byte"
                        + " the v1 frame — proving the only wire change is version + reserved epoch");
    }

    /**
     * Reconstructs what a v2 frame's v1 encoding would have been: splice out the 8 epoch bytes, reset
     * the version byte to 0x01, fix the length prefix, and recompute the CRC32C trailer.
     */
    private static byte[] downgradeV2ToV1(byte[] v2) {
        int v1len = v2.length - EPOCH_SIZE;
        byte[] v1 = new byte[v1len];
        // [0, EPOCH_OFFSET) = length, version, type, groupId, term
        System.arraycopy(v2, 0, v1, 0, EPOCH_OFFSET);
        // skip epoch [EPOCH_OFFSET, EPOCH_OFFSET+8); copy payload + (old) trailer region after it
        int afterEpoch = EPOCH_OFFSET + EPOCH_SIZE;
        System.arraycopy(v2, afterEpoch, v1, EPOCH_OFFSET, v2.length - afterEpoch);
        v1[4] = 0x01; // version 0x02 -> 0x01
        ByteBuffer.wrap(v1, 0, 4).putInt(v1len); // fix length prefix
        CRC32C crc = new CRC32C();
        crc.update(v1, 0, v1len - FrameCodec.TRAILER_SIZE);
        ByteBuffer.wrap(v1, v1len - FrameCodec.TRAILER_SIZE, FrameCodec.TRAILER_SIZE)
                .putInt((int) crc.getValue());
        return v1;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}

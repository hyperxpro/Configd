package io.configd.distribution.wire;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Prints fresh golden hex for every edge fixture (CT-41 rebaseline tool). NOT an
 * assertion test — it is the regeneration path referenced by {@link EdgeFrameGoldenBytes}'s
 * rebaseline rule. Run it (e.g. {@code -Dtest=EdgeFrameGoldenBytesGenerator}) after an
 * intentional, version-bumped wire change and paste the printed hex into
 * {@link EdgeFrameGoldenBytes}. Disabled-by-output: it always passes; it only prints.
 */
class EdgeFrameGoldenBytesGenerator {

    @Test
    void printGoldenHex() {
        Map<String, EdgeFrame> fixtures = EdgeFrameFixtures.build();
        HexFormat hf = HexFormat.of();
        for (Map.Entry<String, EdgeFrame> e : fixtures.entrySet()) {
            String name = e.getKey();
            if (EdgeFrameFixtures.oversizeFixtureNames().contains(name)) {
                byte[] wire = EdgeFrameCodec.encode(e.getValue());
                CRC32C crc = new CRC32C();
                crc.update(wire, 0, wire.length);
                System.out.println("OVERSIZE " + name + " fullFrameCrc=0x"
                        + Long.toHexString(crc.getValue()) + "L len=" + wire.length);
                continue;
            }
            byte[] wire = EdgeFrameCodec.encode(e.getValue());
            System.out.println("HEX " + name + " = " + hf.formatHex(wire));
        }
    }
}

package io.configd.transport.wirecompat;

import io.configd.transport.FrameCodec;
import io.configd.transport.MessageType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic generator for the golden-bytes wire fixtures consumed by
 * {@code WireCompatGoldenBytesTest} and the {@code wire-compat} CI job.
 *
 * <p>§8.10 hard rule: the wire format is part of the public contract.
 * Any change that alters the encoded byte stream MUST be accompanied by
 * either (a) no fixture diff (purely additive at the application layer)
 * or (b) a {@link FrameCodec#WIRE_VERSION} bump and regenerated fixtures
 * under {@code configd-transport/src/test/resources/wire-fixtures/v<N>/}.
 *
 * <p>Run with no arguments to print the current fixture map (useful in
 * tests). Run with {@code --update-fixtures <output-dir>} to (re)write
 * the fixtures to disk; this mode is invoked by maintainers when a
 * legitimate wire bump lands.
 */
public final class WireFixtureGenerator {

    /** Stable, deterministic inputs — do not change without a wire bump. */
    private static final int FIXTURE_GROUP_ID = 0x01020304;
    private static final long FIXTURE_TERM = 0x0A0B0C0D0E0F1011L;
    private static final byte[] FIXTURE_PAYLOAD = bytes(0xDE, 0xAD, 0xBE, 0xEF);
    private static final byte[] EMPTY = new byte[0];

    private WireFixtureGenerator() {}

    /**
     * Builds the canonical fixture map: one entry per {@link MessageType},
     * keyed by the lowercase enum name. Encoding is deterministic — the
     * same map will be produced on every invocation.
     */
    public static Map<String, byte[]> build() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (MessageType type : MessageType.values()) {
            // Heartbeat is conventionally empty; everything else gets the
            // shared 4-byte payload so fixture sizes stay easy to eyeball.
            byte[] payload = (type == MessageType.HEARTBEAT) ? EMPTY : FIXTURE_PAYLOAD;
            byte[] frame = FrameCodec.encode(type, FIXTURE_GROUP_ID, FIXTURE_TERM, payload);
            out.put(type.name().toLowerCase(java.util.Locale.ROOT) + ".bin", frame);
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        boolean update = args.length >= 1 && "--update-fixtures".equals(args[0]);
        Path outDir = Path.of(args.length >= 2
                ? args[1]
                : "configd-transport/src/test/resources/wire-fixtures/v1");

        Map<String, byte[]> fixtures = build();
        if (update) {
            Files.createDirectories(outDir);
            for (Map.Entry<String, byte[]> e : fixtures.entrySet()) {
                Path target = outDir.resolve(e.getKey());
                Files.write(target, e.getValue());
                System.out.println("wrote " + target + " (" + e.getValue().length + " bytes)");
            }
            System.out.println("WIRE_VERSION=0x"
                    + Integer.toHexString(FrameCodec.WIRE_VERSION & 0xFF));
        } else {
            for (Map.Entry<String, byte[]> e : fixtures.entrySet()) {
                System.out.println(e.getKey() + "\t" + toHex(e.getValue()));
            }
        }
    }

    private static byte[] bytes(int... in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) out[i] = (byte) in[i];
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}

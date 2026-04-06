package io.configd.transport.wirecompat;

import io.configd.transport.FrameCodec;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * §8.10 wire-compat guardrail. Encodes one frame of every
 * {@link io.configd.transport.MessageType} with the canonical inputs
 * defined in {@link WireFixtureGenerator} and asserts byte-equality
 * against the golden bytes in {@link GoldenFixtures}.
 *
 * <p>If you legitimately changed the wire format, bump
 * {@link FrameCodec#WIRE_VERSION} and update {@link GoldenFixtures}
 * by running {@code WireFixtureGenerator} (no args) to print new hex strings.
 */
class WireCompatGoldenBytesTest {

    @TestFactory
    List<DynamicTest> everyMessageTypeMatchesGolden() {
        Map<String, byte[]> generated = WireFixtureGenerator.build();
        Map<String, byte[]> golden = GoldenFixtures.forVersion(FrameCodec.WIRE_VERSION & 0xFF);
        List<DynamicTest> tests = new ArrayList<>(generated.size());
        for (Map.Entry<String, byte[]> e : generated.entrySet()) {
            String name = e.getKey();
            byte[] live = e.getValue();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                byte[] expected = golden.get(name);
                if (expected == null) {
                    fail("Missing golden entry for: " + name
                            + " — add it to GoldenFixtures.v" + (FrameCodec.WIRE_VERSION & 0xFF) + "()");
                }
                assertArrayEquals(expected, live,
                        "Wire-format drift detected for " + name
                                + ". Either revert the change or bump FrameCodec.WIRE_VERSION"
                                + " and update GoldenFixtures (see §8.10).");
            }));
        }
        return tests;
    }

    @SuppressWarnings("unused")
    private static final Class<?> KEEP_JUNIT = ExtensionContext.class;
}

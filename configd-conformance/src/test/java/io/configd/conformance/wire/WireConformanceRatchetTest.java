package io.configd.conformance.wire;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runner I's anti-regression RATCHET (the protobuf {@code --failure_list} analog): runs every wire case
 * ({@link WireCases}) and asserts the ACTUAL outcome set EQUALS the checked-in expected-results manifest
 * ({@code /conformance/wire-manifest.txt}). An unexpected FAIL (a codec regression) AND an unexpected PASS
 * (a silently-changed reject / a stale manifest) BOTH fail the build — bidirectional drift detection is what
 * makes the corpus a frozen contract (§00 OV5-5). A deliberate wire change is landed by regenerating +
 * committing the manifest (the manifest is generated to {@code target/} when absent — the golden-file pattern).
 */
// Runner I genuinely exercises these codec clauses: the golden ACCEPT corpus proves the frame layouts, the
// version pin, the vector cursor, the auth frames, and the error taxonomy encode/decode byte-for-byte; the
// poison REJECT corpus proves the CRC / cap / trailing-bytes / unknown-type / type-version / version-pin
// reject paths with their specific ErrorCode.
@Tag("clause:OV5-5")
@Tag("clause:OV7-4_3")
@Tag("clause:A9-1")
@Tag("clause:F2-1")
@Tag("clause:F2-2")
@Tag("clause:F2-3")
@Tag("clause:F3-1")
@Tag("clause:F3-2")
@Tag("clause:F4-3")
@Tag("clause:F6-1")
@Tag("clause:F6-1a")
@Tag("clause:F6-2_F6-2a")
@Tag("clause:F6-3")
@Tag("clause:F6-4..F6-6")
@Tag("clause:F6-7")
@Tag("clause:F6-8_F6-8a")
@Tag("clause:F6A-1..F6A-2")
@Tag("clause:F6A-3")
@Tag("clause:F6A-4")
@Tag("clause:F7-1")
@Tag("clause:F7-2")
@Tag("clause:F8-1_F8-2")
@Tag("clause:F11-1")
@Tag("clause:F11-2")
@Tag("clause:F11-3")
@Tag("clause:E3-1")
class WireConformanceRatchetTest {

    private static final String MANIFEST = "/conformance/wire-manifest.txt";

    @Test
    void everyWireCaseMatchesTheManifest() throws IOException {
        Map<String, String> actual = new LinkedHashMap<>();
        for (WireCases.Case c : WireCases.all()) {
            String prior = actual.put(c.id(), c.outcome());
            if (prior != null) {
                fail("duplicate wire case id: " + c.id());
            }
        }

        Map<String, String> expected = loadManifest();
        if (expected == null) {
            Path generated = writeGenerated(actual);
            fail("no wire manifest on the classpath (" + MANIFEST + "). A generated one was written to "
                    + generated + " — review it and copy to src/test/resources" + MANIFEST + ".");
            return;
        }

        assertEquals(expected.keySet(), actual.keySet(),
                "wire case-id set drift vs the manifest (a case was added or removed — regenerate the manifest "
                        + "deliberately if intended)");
        for (Map.Entry<String, String> e : actual.entrySet()) {
            assertEquals(expected.get(e.getKey()), e.getValue(),
                    "wire outcome drift for '" + e.getKey() + "' — an unexpected pass or fail (regenerate the "
                            + "manifest only for a deliberate wire change)");
        }
    }

    private static Map<String, String> loadManifest() throws IOException {
        try (InputStream in = WireConformanceRatchetTest.class.getResourceAsStream(MANIFEST)) {
            if (in == null) {
                return null;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int bar = s.indexOf('|');
                if (bar < 0) {
                    throw new IllegalStateException("malformed manifest line (want 'id | outcome'): " + s);
                }
                m.put(s.substring(0, bar).strip(), s.substring(bar + 1).strip());
            }
            return m;
        }
    }

    private static Path writeGenerated(Map<String, String> actual) {
        try {
            Path dir = Path.of("target", "conformance");
            Files.createDirectories(dir);
            Path out = dir.resolve("wire-manifest.generated.txt");
            StringBuilder sb = new StringBuilder("# Wire-format conformance expected results (generated). "
                    + "id | ACCEPT | REJECT:<ErrorCode>\n");
            actual.forEach((id, outcome) -> sb.append(id).append(" | ").append(outcome).append('\n'));
            Files.writeString(out, sb.toString());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

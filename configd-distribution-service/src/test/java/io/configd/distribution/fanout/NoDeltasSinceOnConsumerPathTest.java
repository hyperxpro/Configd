package io.configd.distribution.fanout;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CT-22 STATIC GUARD (source scan; mirrors
 * {@code configd-transport/.../NoBlockingConnectOnConsensusPathTest}). ADR-0034's
 * handoff step 3 is a hard rule: <b>never consume {@code FanOutBuffer.deltasSince}</b>
 * (the legacy non-atomic read, RR-066) — only {@code readSince}. The legacy method is
 * kept public solely for the pre-existing fan-out tests; it must never appear on a
 * production drain or sim-driver call site.
 *
 * <h3>Why a static scan, not a runtime assertion</h3>
 * The defect is structural — a specific call shape — so a static scan is deterministic,
 * timing-free, runs in milliseconds, and catches the regression at the exact site
 * regardless of test coverage (the same rationale the RR-002 transport guard records).
 * It pins that as C1/C2 code lands, no production/sim consumer reintroduces the
 * non-atomic read.
 *
 * <p><b>Scope:</b> {@code configd-distribution-service/src/main} (production) and
 * {@code configd-testkit/src/test} (the sim drivers). Exempt: {@code FanOutBuffer.java}
 * itself (it defines and retains the legacy method) and the legacy
 * {@code FanOutBufferTest.java} (the only sanctioned caller).
 */
class NoDeltasSinceOnConsumerPathTest {

    /** Roots that must be free of any {@code deltasSince} consumer. */
    private static final List<Path> SCAN_ROOTS = List.of(
            moduleSrc("configd-distribution-service", "src/main/java"),
            moduleSrc("configd-testkit", "src/test/java"));

    /** Files allowed to mention {@code deltasSince} (the definition + its sanctioned test). */
    private static final List<String> EXEMPT_FILE_NAMES = List.of(
            "FanOutBuffer.java",       // defines + retains the legacy method
            "FanOutBufferTest.java");  // the only sanctioned legacy caller

    /** Any reference to the {@code deltasSince} member (call or method ref). */
    private static final Pattern DELTAS_SINCE =
            Pattern.compile("\\bdeltasSince\\s*\\(|::\\s*deltasSince\\b");

    @Test
    void noProductionOrSimConsumerCallsDeltasSince() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : SCAN_ROOTS) {
            if (!Files.isDirectory(root)) {
                fail("scan root does not exist: " + root.toAbsolutePath()
                        + " — update SCAN_ROOTS for the new layout");
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files
                        .filter(f -> f.toString().endsWith(".java"))::iterator) {
                    scanFile(p, violations);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("CT-22 / RR-066: a consumer of the non-atomic FanOutBuffer.deltasSince "
                    + "appeared on a production/sim path (use readSince — ADR-0034 handoff "
                    + "step 3):\n  " + String.join("\n  ", violations));
        }
    }

    private static void scanFile(Path file, List<String> violations) throws IOException {
        String name = file.getFileName().toString();
        if (EXEMPT_FILE_NAMES.contains(name)) {
            return;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = stripCommentsAndStrings(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            Matcher m = DELTAS_SINCE.matcher(line);
            if (m.find()) {
                violations.add(file + ":" + (i + 1) + "  -> " + lines.get(i).trim());
            }
        }
    }

    /** Drops line/block comments and string/char literals (line-local, best effort). */
    private static String stripCommentsAndStrings(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inStr = false, inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!inStr && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (!inStr && !inChar && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                break;
            }
            if (!inStr && !inChar && c == '*') {
                if (line.stripLeading().startsWith("*")) {
                    break;
                }
            }
            if (!inChar && c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inStr = !inStr;
                continue;
            }
            if (!inStr && c == '\'' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                continue;
            }
            if (!inStr && !inChar) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Path moduleSrc(String module, String relSrc) {
        Path reactorRoot = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(reactorRoot.resolve(module))) {
            Path parent = reactorRoot.getParent();
            if (parent != null && Files.isDirectory(parent.resolve(module))) {
                reactorRoot = parent;
            }
        }
        return reactorRoot.resolve(module).resolve(relSrc);
    }

    @Test
    void scanRootsResolveToRealDirectories() {
        for (Path root : SCAN_ROOTS) {
            assertTrue(Files.isDirectory(root),
                    "scan root must exist (guard would otherwise scan nothing): " + root.toAbsolutePath());
        }
    }
}

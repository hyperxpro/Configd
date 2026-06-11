package io.configd.distribution.fanout;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h3>Scope is DERIVED, not enumerated (C2 contract-qa audit follow-up)</h3>
 * The original hand-enumerated {@code SCAN_ROOTS} went stale twice in one session: the
 * C1 audit found it could not catch a configd-server regression, and the C2 audit found
 * the brand-new {@code configd-edge-node} module silently outside the scan. The roots
 * are therefore now derived from the reactor pom's {@code <modules>} list — every
 * module's {@code src/main/java} — plus {@code configd-testkit/src/test/java} (the sim
 * drivers live in test scope there). A module may only escape via
 * {@link #EXEMPT_MODULES}, each entry carrying its justification, and the
 * {@link #everyReactorModuleIsScannedOrExplicitlyExempted() tripwire} asserts
 * scanned-set == reactor-modules − exemptions, so a future module can never silently
 * escape the guard.
 *
 * <p><b>File-level exemptions:</b> {@code FanOutBuffer.java} itself (it defines and
 * retains the legacy method) and the legacy {@code FanOutBufferTest.java} (the only
 * sanctioned caller).
 */
class NoDeltasSinceOnConsumerPathTest {

    /**
     * Reactor modules exempted from the main-source scan, each with a one-line reason.
     * EMPTY today — and that is the point: every module's {@code src/main/java} is
     * scanned, including ones with no conceivable consumer (the scan is milliseconds and
     * a vacuous scan is cheaper than a stale enumeration). Add an entry ONLY with a
     * reason a reviewer can audit; the tripwire fails on stale entries.
     */
    private static final Map<String, String> EXEMPT_MODULES = Map.of(
            // (no exemptions — see javadoc; keep the reason format "module → why")
    );

    /**
     * Extra scan roots beyond the derived {@code <module>/src/main/java} set:
     * configd-testkit's TEST tree carries the sim drivers (EdgeActor, EdgeFanOutSim,
     * C1StreamDriver) that are production-shaped consumers of the boundary.
     */
    private static final List<String> EXTRA_SCAN_ROOTS =
            List.of("configd-testkit/src/test/java");

    /** Files allowed to mention {@code deltasSince} (the definition + its sanctioned test). */
    private static final List<String> EXEMPT_FILE_NAMES = List.of(
            "FanOutBuffer.java",       // defines + retains the legacy method
            "FanOutBufferTest.java");  // the only sanctioned legacy caller

    /** Any reference to the {@code deltasSince} member (call or method ref). */
    private static final Pattern DELTAS_SINCE =
            Pattern.compile("\\bdeltasSince\\s*\\(|::\\s*deltasSince\\b");

    /** Matches one {@code <module>...</module>} entry in the reactor pom. */
    private static final Pattern POM_MODULE =
            Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    @Test
    void noProductionOrSimConsumerCallsDeltasSince() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : scanRoots()) {
            if (!Files.isDirectory(root)) {
                fail("scan root does not exist: " + root.toAbsolutePath()
                        + " — a module without src/main/java must be added to "
                        + "EXEMPT_MODULES with a reason");
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

    /**
     * THE TRIPWIRE: the set of scanned modules must equal the reactor's
     * {@code <modules>} list minus {@link #EXEMPT_MODULES} — a new module is therefore
     * either scanned or loudly accounted for, never silently invisible (the gap class
     * the C1 AND C2 contract-qa audits each caught one instance of).
     */
    @Test
    void everyReactorModuleIsScannedOrExplicitlyExempted() throws IOException {
        Set<String> modules = reactorModules();
        assertTrue(modules.contains("configd-distribution-service"),
                "reactor pom parse sanity: expected the owning module in " + modules);

        // No stale exemptions: every exempt entry must still be a reactor module.
        for (String exempt : EXEMPT_MODULES.keySet()) {
            assertTrue(modules.contains(exempt),
                    "stale EXEMPT_MODULES entry (not a reactor module anymore): " + exempt
                            + " — " + EXEMPT_MODULES.get(exempt));
        }

        // Scanned == modules − exemptions, and every scanned root exists on disk.
        Set<String> expectedScanned = new LinkedHashSet<>(modules);
        expectedScanned.removeAll(EXEMPT_MODULES.keySet());
        Set<String> actuallyScanned = new LinkedHashSet<>();
        Path root = reactorRoot();
        for (String module : expectedScanned) {
            Path src = root.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(src),
                    "module '" + module + "' has no src/main/java — it cannot be scanned; "
                            + "either create the source root or add it to EXEMPT_MODULES "
                            + "with a reason");
            actuallyScanned.add(module);
        }
        assertTrue(actuallyScanned.equals(expectedScanned),
                "scanned-module set must equal reactor modules minus exemptions; scanned="
                        + actuallyScanned + " expected=" + expectedScanned);

        // The extra (non-derived) roots must exist too, or the sim drivers go unguarded.
        for (String extra : EXTRA_SCAN_ROOTS) {
            assertTrue(Files.isDirectory(root.resolve(extra)),
                    "extra scan root must exist: " + extra);
        }
    }

    // -----------------------------------------------------------------------
    // Scan-root derivation (reactor pom <modules> − EXEMPT_MODULES + extras)
    // -----------------------------------------------------------------------

    /** Every non-exempt {@code <module>/src/main/java} plus the extra roots. */
    private static List<Path> scanRoots() throws IOException {
        Path root = reactorRoot();
        List<Path> roots = new ArrayList<>();
        for (String module : reactorModules()) {
            if (EXEMPT_MODULES.containsKey(module)) {
                continue;
            }
            roots.add(root.resolve(module).resolve("src/main/java"));
        }
        for (String extra : EXTRA_SCAN_ROOTS) {
            roots.add(root.resolve(extra));
        }
        return roots;
    }

    /** Parses the reactor pom's {@code <modules>} list (XML comments stripped first). */
    private static Set<String> reactorModules() throws IOException {
        Path pom = reactorRoot().resolve("pom.xml");
        String xml = Files.readString(pom, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        int begin = xml.indexOf("<modules>");
        int end = xml.indexOf("</modules>");
        if (begin < 0 || end < begin) {
            fail("reactor pom has no <modules> block: " + pom.toAbsolutePath());
        }
        Set<String> modules = new LinkedHashSet<>();
        Matcher m = POM_MODULE.matcher(xml.substring(begin, end));
        while (m.find()) {
            modules.add(m.group(1));
        }
        assertTrue(!modules.isEmpty(), "no <module> entries parsed from " + pom);
        return modules;
    }

    /**
     * Resolves the reactor root: walks up from the working directory (surefire runs in
     * the module dir) to the first pom.xml containing a {@code <modules>} block.
     */
    private static Path reactorRoot() throws IOException {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)
                    && Files.readString(pom, StandardCharsets.UTF_8).contains("<modules>")) {
                return dir;
            }
        }
        fail("could not locate the reactor root (no pom.xml with <modules> above "
                + Path.of("").toAbsolutePath() + ")");
        throw new AssertionError("unreachable");
    }

    // -----------------------------------------------------------------------
    // Matching (UNCHANGED — the pattern-aware logic the C1 hardening reviewed)
    // -----------------------------------------------------------------------

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
}

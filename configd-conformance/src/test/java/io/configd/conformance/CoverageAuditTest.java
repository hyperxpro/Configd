package io.configd.conformance;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The STRICT auditable-coverage gate and the frozen-contract coverage RECORD. It resolves EVERY one of the 244
 * catalog clauses ({@code catalog-clauses.txt}, transcribed from the requirements catalog -- the single source)
 * to either a covered conformance case (a test {@code @Tag("clause:<id>")}, discovered via the JUnit Platform
 * launcher -- so "covered" means a real test asserts it, not an aspirational claim) OR an explicit
 * {@code SKIP:<reason>} ({@code coverage-skips.txt}). An UNMAPPED clause fails the build -- no clause silently
 * drops. It then emits the per-clause breakdown to {@code conformance-coverage.md} and asserts it matches the
 * checked-in golden (the {@code EdgeFrameGoldenBytes} regenerate-and-commit pattern), so the true tally is
 * durably reviewable and diffable in the repo.
 */
class CoverageAuditTest {

    private static final String CATALOG = "/conformance/catalog-clauses.txt";
    private static final String SKIPS = "/conformance/coverage-skips.txt";
    private static final Path GOLDEN_REPORT = Path.of("conformance-coverage.md");

    private record Clause(String id, String plane, String holder, String kind) {
    }

    @Test
    void everyCatalogClauseIsCoveredOrExplicitlySkipped() throws IOException {
        List<Clause> catalog = loadCatalog();
        Map<String, String> skips = loadSkips();               // clause-id -> reason
        Map<String, Set<String>> covered = discoverCoveredClauses(); // clause-id -> covering test ids

        Set<String> catalogIds = new TreeSet<>();
        for (Clause c : catalog) {
            catalogIds.add(c.id());
        }

        List<String> staleSkips = skips.keySet().stream().filter(id -> !catalogIds.contains(id)).sorted().toList();
        List<String> staleTags = covered.keySet().stream().filter(id -> !catalogIds.contains(id)).sorted().toList();
        List<String> unmapped = catalogIds.stream()
                .filter(id -> !covered.containsKey(id) && !skips.containsKey(id)).sorted().toList();
        // 3) A clause must not be BOTH covered and skipped (ambiguous accounting).
        List<String> both = catalogIds.stream()
                .filter(id -> covered.containsKey(id) && skips.containsKey(id)).sorted().toList();

        if (!staleSkips.isEmpty() || !staleTags.isEmpty() || !unmapped.isEmpty() || !both.isEmpty()) {
            fail("coverage audit failed (STRICT):"
                    + section("UNMAPPED (no covering case and no SKIP — cover it or add an honest SKIP)", unmapped)
                    + section("COVERED AND SKIPPED (remove one)", both)
                    + section("stale SKIP (clause not in the catalog)", staleSkips)
                    + section("stale clause: tag (clause not in the catalog)", staleTags));
        }

        String report = buildReport(catalog, covered, skips);
        String existing = Files.exists(GOLDEN_REPORT) ? Files.readString(GOLDEN_REPORT) : null;
        if (existing == null || !existing.equals(report)) {
            Path generated = Path.of("target", "conformance", "conformance-coverage.generated.md");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, report);
            fail("conformance-coverage.md is " + (existing == null ? "missing" : "stale") + ". A fresh one was "
                    + "written to " + generated.toAbsolutePath() + " — review the tally and copy it to "
                    + GOLDEN_REPORT.toAbsolutePath() + " (the coverage change must be deliberate + committed).");
        }
    }

    /**
     * Discovers every conformance test's {@code clause:<id>} tags via the launcher (discovery only, no run).
     * Scopes to THIS module's own compiled test classes ({@code target/test-classes}), NOT the reused test-jar
     * dependencies (edge/http/wire) on the classpath -- so coverage is attributed only to conformance cases,
     * and a client-conforms case may live in a plane package (e.g. {@code io.configd.client.http}) to reach a
     * package-private mock, yet still be discovered.
     */
    private static Map<String, Set<String>> discoverCoveredClauses() {
        Path testClasses = Path.of("target", "test-classes");
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(testClasses)))
                .build();
        Launcher launcher = LauncherFactory.create();
        TestPlan plan = launcher.discover(request);
        Map<String, Set<String>> covered = new TreeMap<>();
        collect(plan, plan.getRoots(), covered);
        return covered;
    }

    private static void collect(TestPlan plan, Set<TestIdentifier> ids, Map<String, Set<String>> covered) {
        for (TestIdentifier id : ids) {
            for (org.junit.platform.engine.TestTag tag : id.getTags()) {
                String v = tag.getName();
                if (v.startsWith("clause:")) {
                    covered.computeIfAbsent(v.substring("clause:".length()).strip(), k -> new TreeSet<>())
                            .add(id.getDisplayName());
                }
            }
            collect(plan, plan.getChildren(id), covered);
        }
    }

    private static List<Clause> loadCatalog() throws IOException {
        List<Clause> out = new ArrayList<>();
        for (String line : readResource(CATALOG)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] parts = s.split("\\|");
            if (parts.length < 4) {
                throw new IllegalStateException("malformed catalog line: " + s);
            }
            out.add(new Clause(parts[0].strip(), parts[1].strip(), parts[2].strip(), parts[3].strip()));
        }
        return out;
    }

    private static Map<String, String> loadSkips() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : readResource(SKIPS)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int bar = s.indexOf('|');
            if (bar < 0) {
                throw new IllegalStateException("malformed skip line (want 'clause | SKIP:<reason>'): " + s);
            }
            out.put(s.substring(0, bar).strip(), s.substring(bar + 1).strip());
        }
        return out;
    }

    private static List<String> readResource(String path) throws IOException {
        try (InputStream in = CoverageAuditTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + path);
            }
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    private static String buildReport(List<Clause> catalog, Map<String, Set<String>> covered,
                                      Map<String, String> skips) {
        int coveredCount = 0;
        int skipCount = 0;
        Map<String, Integer> skipByReason = new TreeMap<>();
        StringBuilder rows = new StringBuilder();
        for (Clause c : catalog) {
            String status;
            if (covered.containsKey(c.id())) {
                status = "COVERED — " + String.join(", ", covered.get(c.id()));
                coveredCount++;
            } else {
                String reason = skips.get(c.id());
                status = reason;
                skipCount++;
                String key = reason.startsWith("SKIP:") ? reason.substring(0, Math.min(reason.length(),
                        reason.indexOf(' ') < 0 ? reason.length() : reason.indexOf(' '))) : reason;
                skipByReason.merge(key, 1, Integer::sum);
            }
            rows.append("| ").append(c.id()).append(" | ").append(c.plane()).append(" | ")
                    .append(c.holder()).append(" | ").append(c.kind()).append(" | ").append(status).append(" |\n");
        }

        StringBuilder md = new StringBuilder();
        md.append("# Configd Driver-Protocol Conformance Coverage\n\n");
        md.append("**Generated + asserted by `CoverageAuditTest`** against `catalog-clauses.txt` (the 244 ")
                .append("normative clauses transcribed from the requirements catalog). Do not hand-edit — a ")
                .append("change here must come from adding a covering case or an honest SKIP, then regenerating.\n\n");
        md.append("## Tally\n\n");
        md.append("- Total clauses: ").append(catalog.size()).append('\n');
        md.append("- **Covered** (a `@Tag(\"clause:…\")` conformance case asserts it): ").append(coveredCount)
                .append('\n');
        md.append("- **Skipped** (explicit, reasoned): ").append(skipCount).append('\n');
        skipByReason.forEach((reason, n) -> md.append("  - ").append(reason).append(": ").append(n).append('\n'));
        md.append("\n## Per-clause breakdown\n\n");
        md.append("| Clause | Plane | Holder | Kind | Status |\n|---|---|---|---|---|\n");
        md.append(rows);
        return md.toString();
    }

    private static String section(String title, List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        return "\n  " + title + " (" + ids.size() + "): " + String.join(", ", ids);
    }
}

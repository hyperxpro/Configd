# Release Engineering Production Readiness Review -- Configd

**Date:** 2026-04-11
**Reviewer:** release-engineer / observability-auditor
**Verdict:** NOT READY -- SNAPSHOT version, no CI/CD, no artifact signing, no reproducible builds

---

## 1. Build System

### 1.1 Build Tool

- **Build tool:** Apache Maven (reactor build)
- **Root POM:** `pom.xml` -- Maven reactor with 10 modules
- **Java version:** 25 (with `--enable-preview`)
- **Compiler plugin:** `maven-compiler-plugin` 3.13.0
- **Test runner:** `maven-surefire-plugin` 3.5.2

### 1.2 Module Structure

| Module | Artifact ID | Internal Dependencies |
|---|---|---|
| configd-common | configd-common | (none) |
| configd-transport | configd-transport | configd-common |
| configd-consensus-core | configd-consensus-core | configd-common, configd-transport |
| configd-config-store | configd-config-store | configd-common, configd-consensus-core |
| configd-edge-cache | configd-edge-cache | configd-common, configd-config-store |
| configd-observability | configd-observability | configd-common |
| configd-replication-engine | configd-replication-engine | configd-common, configd-consensus-core, configd-transport |
| configd-distribution-service | configd-distribution-service | configd-common, configd-config-store, configd-transport |
| configd-control-plane-api | configd-control-plane-api | configd-common, configd-consensus-core, configd-replication-engine, configd-config-store, configd-observability |
| configd-testkit | configd-testkit | configd-common, configd-consensus-core, configd-config-store, configd-edge-cache, configd-distribution-service, configd-transport |

### 1.3 Reproducible Builds

| Capability | Status | Notes |
|---|---|---|
| Deterministic compilation (same source -> same bytecode) | NOT CONFIGURED | No `maven-reproducible-build` plugin or `project.build.outputTimestamp` property |
| Reproducible JAR packaging | NOT CONFIGURED | No `maven-jar-plugin` configuration for reproducible output (timestamps, ordering) |
| Build environment pinned | PARTIAL | Dockerfile uses `eclipse-temurin:25-jdk-noble` (specific JDK), but Maven version floats (installed via `apt-get install -y maven`) |
| Dependency checksums verified | NOT CONFIGURED | No `maven-enforcer-plugin` with dependency checksum verification |

**Finding: MAJOR.** Builds are not reproducible. The same source will produce different JARs across builds due to: (1) embedded timestamps in JAR manifests, (2) non-deterministic Maven version from apt, (3) no `project.build.outputTimestamp`.

### 1.4 Artifact Signing

| Capability | Status |
|---|---|
| GPG signing of JARs | NOT PRESENT |
| `maven-gpg-plugin` configured | NOT PRESENT |
| Signature verification in consumers | NOT PRESENT |

**Finding: MAJOR.** No artifact signing. Consumers cannot verify artifact integrity.

### 1.5 Version Stamping

| Capability | Status | Notes |
|---|---|---|
| Version in POM | PRESENT | `0.1.0-SNAPSHOT` |
| Version in JAR manifest (`Implementation-Version`) | NOT CONFIGURED | No `maven-jar-plugin` manifest configuration |
| Git commit hash in binary | NOT CONFIGURED | No `git-commit-id-plugin` or equivalent |
| Build timestamp in binary | NOT CONFIGURED | |

**Finding: MAJOR.** Deployed binaries cannot be traced back to a specific source commit. When debugging production issues, operators cannot determine which exact build is running.

### 1.6 Deterministic Dependency Resolution

| Capability | Status | Notes |
|---|---|---|
| All versions pinned (no ranges) | YES | All dependency versions are exact (no `[1.0,2.0)` ranges) |
| `dependencyManagement` used consistently | YES | Parent POM manages all versions; child POMs inherit |
| No floating `LATEST`/`RELEASE` versions | YES | |
| `maven-enforcer-plugin` for convergence | NOT CONFIGURED | No enforcer plugin to detect version conflicts |
| Lock file / BOM verification | NOT CONFIGURED | No `maven-dependency-plugin:resolve` output committed |

**Assessment:** Dependency versions are correctly pinned in `dependencyManagement`. No version ranges are used. This is good practice. However, transitive dependency convergence is not enforced.

---

## 2. Docker

### 2.1 Build Image (`docker/Dockerfile.build`)

- **Base:** `eclipse-temurin:25-jdk-noble`
- **Maven:** Installed via `apt-get` (version floats with Ubuntu package repository)
- **Layer caching:** POM files copied first for dependency resolution layer, source copied second
- **Default command:** `mvn clean verify -B`
- **Tests:** Run inside the container (hermetic)

**Issues:**
1. Maven version is not pinned -- different builds may use different Maven versions
2. `mvn dependency:resolve -B -q || true` swallows dependency resolution failures silently
3. No multi-stage build -- the build image is also the test image (which is fine for CI, but the image is large)

### 2.2 Runtime Image (`docker/Dockerfile.runtime`)

- **Build stage:** `eclipse-temurin:25-jdk-noble`
- **Runtime stage:** `eclipse-temurin:25-jre-noble`
- **Multi-stage:** Yes -- compiles in JDK image, copies JARs to JRE image
- **No ENTRYPOINT:** Correct -- this is a library base image, not a runnable service
- **JAR collection:** `find . -path '*/target/*.jar'` copies all module JARs to `/app/libs`
- **Tests skipped in runtime build:** `mvn clean package -DskipTests -B`

**Issues:**
1. Same Maven version floating issue as build image
2. No health check defined (would matter if this becomes a service image)
3. No non-root user created -- runs as root by default
4. No resource limits or JVM flags in the image

---

## 3. CI/CD

### 3.1 CI Configuration

| CI System | Config File Exists? |
|---|---|
| GitHub Actions | NO (no `.github/workflows/` directory) |
| Jenkins | NO (no `Jenkinsfile`) |
| GitLab CI | NO (no `.gitlab-ci.yml`) |
| CircleCI | NO (no `.circleci/config.yml`) |
| Any CI YAML/config | NO |

**Finding: CRITICAL.** No CI/CD pipeline exists. There is no automated gate between code changes and deployment.

### 3.2 Required CI Gates

| Gate | Status | Notes |
|---|---|---|
| Unit tests | NOT GATED | Tests exist but no CI runs them |
| Property tests (jqwik) | NOT GATED | Property tests exist but no CI runs them |
| Deterministic simulation | NOT GATED | `RaftSimulation` exists in testkit but no CI runs it |
| JMH smoke test | NOT GATED | `RaftCommitBenchmark` exists but no CI runs it |
| Chaos/fault injection smoke | NOT GATED | Network fault injection exists in `SimulatedNetwork` but no CI runs it |
| TLA+ model checking | NOT GATED | `spec/ConsensusSpec.tla` + `spec/ConsensusSpec.cfg` exist, TLC output exists (`spec/tlc-output.txt`), but no CI runs model checking |
| Static analysis (SpotBugs, ErrorProne) | NOT CONFIGURED | No static analysis plugins |
| Dependency vulnerability scan | NOT CONFIGURED | No `maven-dependency-check-plugin` or Snyk/Dependabot |
| Container image scan | NOT CONFIGURED | |

**Finding: CRITICAL.** All quality gates that exist (tests, benchmarks, simulation, TLA+ model checking) are manual-only. There is no enforcement that they pass before merge.

---

## 4. Dependencies

### 4.1 External Dependencies (from root `dependencyManagement`)

| Group ID | Artifact ID | Version | Pinned? | SNAPSHOT? | Notes |
|---|---|---|---|---|---|
| org.agrona | agrona | 1.23.1 | YES | NO | Off-heap buffers, ring buffers |
| org.jctools | jctools-core | 4.0.5 | YES | NO | Lock-free concurrent queues |
| org.junit.jupiter | junit-jupiter | 5.11.4 | YES | NO | Test framework |
| org.openjdk.jmh | jmh-core | 1.37 | YES | NO | Benchmark framework |
| org.openjdk.jmh | jmh-generator-annprocess | 1.37 | YES | NO | JMH annotation processor |
| org.hdrhistogram | HdrHistogram | 2.2.2 | YES | NO | High-dynamic-range histogram |
| io.micrometer | micrometer-core | 1.14.4 | YES | NO | Metrics facade (declared but unused) |
| net.jqwik | jqwik | 1.9.2 | YES | NO | Property-based testing |

### 4.2 Internal Dependencies (inter-module)

All internal dependencies use `${project.version}` which resolves to `0.1.0-SNAPSHOT`.

### 4.3 SNAPSHOT Dependencies

| Artifact | Version | Issue |
|---|---|---|
| io.configd:configd (root) | `0.1.0-SNAPSHOT` | Project version is SNAPSHOT |
| All internal module references | `${project.version}` = `0.1.0-SNAPSHOT` | |

**Finding: MAJOR.** The entire project is at a SNAPSHOT version. For release, this must be changed to a release version (e.g., `0.1.0`). SNAPSHOT versions are non-deterministic -- Maven may resolve them to different builds depending on repository state and update policy.

### 4.4 Dependency Assessment

| Concern | Status |
|---|---|
| All external versions pinned | YES |
| No version ranges | YES |
| No external SNAPSHOT dependencies | YES (all external deps are release versions) |
| No floating versions (LATEST/RELEASE) | YES |
| Transitive dependency convergence enforced | NO (no enforcer plugin) |
| Vulnerability scanning | NOT CONFIGURED |
| License compliance | NOT CHECKED |

**Assessment:** External dependency management is sound. All versions are pinned to exact release versions. The only SNAPSHOT is the project's own version, which is expected for development but must be addressed for release.

### 4.5 Missing Dependencies (from PROMPT.md tech constraints)

The following dependencies are specified in PROMPT.md but are absent from the build:

| Required Dependency | Present? | Notes |
|---|---|---|
| Netty (data plane transport) | NO | Not in any pom.xml |
| Spring Boot (control plane API) | NO | Not in any pom.xml |
| OpenTelemetry (tracing) | NO | Not in any pom.xml |
| SLF4J / Logback (logging) | NO | Not in any pom.xml |
| Lombok (boilerplate) | NO | Not in any pom.xml (may be intentional) |

**Finding: MAJOR.** Several dependencies that the architecture document (PROMPT.md) mandates are missing entirely. Netty and Spring Boot are critical for the transport and API layers.

---

## 5. Build Plugin Analysis

### 5.1 Configured Plugins

| Plugin | Version | Scope |
|---|---|---|
| maven-compiler-plugin | 3.13.0 | Compilation with Java 25 + preview features |
| maven-surefire-plugin | 3.5.2 | Test execution with `--enable-preview` |

### 5.2 Missing Plugins (recommended for production)

| Plugin | Purpose | Priority |
|---|---|---|
| maven-enforcer-plugin | Dependency convergence, JDK version enforcement | HIGH |
| maven-jar-plugin (manifest config) | Version stamping, Main-Class | HIGH |
| maven-source-plugin | Source JAR publication | MEDIUM |
| maven-javadoc-plugin | Javadoc JAR publication | MEDIUM |
| maven-gpg-plugin | Artifact signing | HIGH |
| maven-release-plugin | Release version management | HIGH |
| maven-dependency-check-plugin | CVE vulnerability scanning | HIGH |
| spotbugs-maven-plugin | Static analysis | MEDIUM |
| jacoco-maven-plugin | Code coverage reporting | LOW |
| versions-maven-plugin | Dependency update tracking | LOW |
| maven-shade-plugin or maven-assembly-plugin | Fat JAR for deployment | MEDIUM |

---

## 6. TLA+ / Formal Verification

### 6.1 Spec Status

| Artifact | Present? | Notes |
|---|---|---|
| `spec/ConsensusSpec.tla` | YES | Formal specification |
| `spec/ConsensusSpec.cfg` | YES | TLC configuration |
| `spec/tla2tools.jar` | YES | TLC model checker bundled |
| `spec/tlc-output.txt` | YES | Previous TLC run output |
| `spec/tlc-results.md` | YES | Results documentation |
| TTrace files (6 files) | YES | Trace exploration artifacts |

### 6.2 Verified Invariants

From `spec/ConsensusSpec.cfg`:

| Invariant | Checked? |
|---|---|
| TypeOK | YES |
| ElectionSafety | YES |
| StateMachineSafety | YES |
| NoStaleOverwrite | YES |
| LogMatching | YES |
| ReconfigSafety | YES |
| SingleServerInvariant | YES |
| NoOpBeforeReconfig | YES |
| EdgePropagationLiveness | COMMENTED OUT (requires fairness, substantially slower) |

**Model parameters:** 3 nodes, MaxTerm=3, MaxLogLen=3, 2 values. State space: ~13.8M states, ~3.3M distinct.

**Finding: GOOD.** Formal verification is present and covers the core safety properties. However, liveness (EdgePropagationLiveness) is not checked in the default configuration.

---

## 7. Summary of Findings

### Critical (must fix before release)

1. **No CI/CD pipeline.** Zero automated quality gates. All testing is manual.
2. **SNAPSHOT version.** Cannot produce a release build. Non-deterministic version resolution.

### Major (must fix before GA)

3. **Builds not reproducible.** No `project.build.outputTimestamp`, no pinned Maven version, no deterministic JAR packaging.
4. **No artifact signing.** Consumers cannot verify artifact provenance.
5. **No version stamping in binaries.** Cannot trace deployed artifacts to source commits.
6. **No dependency vulnerability scanning.** No CVE detection.
7. **Missing mandated dependencies.** Netty, Spring Boot, OpenTelemetry, SLF4J are required by architecture but absent.
8. **No dependency convergence enforcement.** Transitive conflicts may exist undetected.
9. **Docker images run as root.** Security concern for runtime containers.

### Minor

10. Maven version floats in Docker images.
11. `mvn dependency:resolve` failure swallowed in Dockerfile.
12. No static analysis plugins.
13. EdgePropagationLiveness not checked in default TLA+ configuration.

---

## 8. Recommendations

### Immediate (before next sprint)

1. **Create GitHub Actions CI workflow** with gates for: compile, unit test, property test, simulation test, TLA+ model check (using bundled `tla2tools.jar`).
2. **Pin Maven version** in Dockerfiles (e.g., download specific version rather than `apt-get`).
3. **Add `project.build.outputTimestamp`** to root POM for reproducible builds.

### Before Release

4. **Remove SNAPSHOT** from version. Establish release process with `maven-release-plugin` or equivalent.
5. **Add `maven-gpg-plugin`** for artifact signing.
6. **Add `maven-jar-plugin` manifest configuration** with `Implementation-Version`, `Implementation-Revision` (git hash), `Build-Timestamp`.
7. **Add `maven-enforcer-plugin`** with `dependencyConvergence` and `requireJavaVersion` rules.
8. **Add `maven-dependency-check-plugin`** for OWASP CVE scanning.
9. **Create non-root user** in `Dockerfile.runtime`.

### Before GA

10. **Integrate remaining mandated dependencies** (Netty, Spring Boot, OpenTelemetry, SLF4J).
11. **Add JMH smoke gate to CI** (regression detection on commit benchmarks).
12. **Enable EdgePropagationLiveness** checking in TLA+ CI (even if slow, run nightly).
13. **Add container image scanning** (Trivy, Grype, or equivalent).

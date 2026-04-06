# Phase 1 — Cross-Cutting Concerns Audit (Cluster F)

**Date:** 2026-04-17
**Auditor:** principal release engineer + technical writer (autonomous; Opus 4.7)
**Scope:** root `pom.xml`, 11 per-module `pom.xml`, `.github/workflows/`, `docker/Dockerfile.*`, `deploy/kubernetes/`, `docs/` (every top-level `.md` + `docs/decisions/*` + `docs/runbooks/*` + `docs/wiki/*`), `.gitignore`, `.mvn/`, `mvnw`, `spotbugs-exclude.xml`, `verification-runs/`, `spec/states/`
**HEAD:** `22d2bf3 Save` (2026-04-17 21:00 UTC)
**Coverage counters:**
- **Docs read:** 27 (`README.md`, `PROMPT.md`, `docs/architecture.md`, `docs/audit.md`, `docs/consistency-contract.md`, `docs/gap-analysis.md`, `docs/inventory.md`, `docs/performance.md`, `docs/production-deployment.md`, `docs/progress.md`, `docs/research.md`, `docs/rewrite-plan.md`, `docs/security-heap-dump-policy.md`, 7 wiki pages, 4 verification top-level docs, 2 battle-ready/prr indices inspected for drift)
- **ADRs audited:** 22/22 (0001–0022)
- **Runbooks audited:** 8/8 (cert-rotation, edge-catchup-storm, leader-stuck, poison-config, reconfiguration-rollback, region-loss, version-gap, write-freeze)
- **Build files read:** root `pom.xml` + 11 module `pom.xml` + `mvnw` + `.mvn/wrapper/maven-wrapper.properties` + `.gitignore` + `spotbugs-exclude.xml` + `.github/workflows/ci.yml` + `docker/Dockerfile.build` + `docker/Dockerfile.runtime` + `docker/.dockerignore` + `deploy/kubernetes/configd-statefulset.yaml`

---

## 1. Findings Register (PA-6000 series)

### PA-6001: 376 MB of TLC on-disk state committed to git

- **Severity:** S1
- **Location:** `spec/states/` (`spec/states/26-04-14-21-07-52.560/` contains 69 tracked files totalling ~376 MB)
- **Category:** release
- **Evidence:** `du -sh spec/states/` = 376M; `git ls-files spec/states/ | wc -l` = 69. Individual state blobs are 1.5 MB binary TLC fingerprint shards (`0` through `68`).
- **Impact:** Every clone pulls ~380 MB of disposable TLC model-checker artefacts. Not source; not evidence; not reproducible across TLC versions. Repo size inflates linearly on every TLC re-run. `inventory.md:156` already flagged "probably should be .gitignored — open item for Phase 1" — confirmed.
- **Fix direction:** `git rm -r --cached spec/states/` + add `spec/states/` and `*.bin` (trace files) to `.gitignore`. Consider also removing `spec/ConsensusSpec_TTrace_*.bin` (7 trace files, ~11 KB total — trivial size but still regeneratable).
- **Owner:** release

### PA-6002: 4.2 MB third-party `tla2tools.jar` binary committed to git

- **Severity:** S2
- **Location:** `spec/tla2tools.jar` (4,346,145 bytes)
- **Category:** release
- **Evidence:** `du -sh spec/tla2tools.jar` = 4.2M. CI already downloads TLC at step `tlc-model-check` (`.github/workflows/ci.yml:55-56`): `curl -L -o tools/tla2tools.jar https://github.com/tlaplus/tlaplus/releases/download/v1.8.0/tla2tools.jar`. The jar in-tree is redundant with CI.
- **Impact:** Every clone pulls an extra 4.2 MB of third-party binary. License of redistributed binary (MIT, from tlaplus) is not surfaced in repo. No checksum pin on the jar.
- **Fix direction:** Remove jar, document required TLC version in `spec/README.md`, pin SHA-256 in CI (currently CI downloads whatever lives at the release URL).
- **Owner:** release

### PA-6003: `.jqwik-database` files committed per module

- **Severity:** S3
- **Location:** `./.jqwik-database` (root) plus one in every module: `configd-common/.jqwik-database`, `configd-config-store/.jqwik-database`, …, `configd-transport/.jqwik-database` — 12 total (confirmed by `git ls-files "*.jqwik-database"`)
- **Category:** release
- **Evidence:** `ls configd-*/.jqwik-database 2>&1` returns all 11 module-level files. These are jqwik's property-test shrinking cache — they are regeneratable and per-developer.
- **Impact:** Hygiene. Clone pulls noise; developers fighting merge conflicts on binary cache files.
- **Fix direction:** Add `.jqwik-database` and `**/.jqwik-database` to `.gitignore`; `git rm --cached`.
- **Owner:** release

### PA-6004: `.gitignore` is 15 bytes — misses Maven target, IDE, jqwik, TLC states

- **Severity:** S2
- **Location:** `/.gitignore` (entire file: `target/\n*.bin\n`)
- **Category:** release
- **Evidence:** Full contents: `target/`, `*.bin`. Does not exclude: `.idea/`, `.vscode/`, `*.iml`, `.DS_Store`, `.jqwik-database`, `spec/states/`, build logs, `*.class`, `dependency-reduced-pom.xml`.
- **Impact:** Explains PA-6001 and PA-6003 — the TLC state dump and jqwik DBs were committed precisely because they are not excluded. Future regressions are guaranteed without hardening.
- **Fix direction:** Import a standard Java/Maven/IntelliJ `.gitignore`. Minimum set: `target/`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`, `**/.jqwik-database`, `spec/states/`, `**/dependency-reduced-pom.xml`, `hs_err_pid*.log`, `replay_pid*.log`.
- **Owner:** release

### PA-6005: root `README.md` is 10 bytes — fails G12 "every module has a README"

- **Severity:** S2
- **Location:** `README.md:1` (full file: `# Configd\n`)
- **Category:** docs
- **Evidence:** `stat -c '%s' README.md` = 10. Inventory §2.4 and PROMPT §6 / prompt's G12 gate both call for every module to have a README. None of `configd-common`, `configd-transport`, `configd-consensus-core`, `configd-config-store`, `configd-edge-cache`, `configd-observability`, `configd-replication-engine`, `configd-distribution-service`, `configd-control-plane-api`, `configd-testkit`, `configd-server` ships a `README.md` (confirmed by `glob "configd-*/README*"` — 0 results). Orientation for new contributors is delegated to `docs/wiki/` which itself has known drift (PA-6013, PA-6014).
- **Impact:** A new engineer landing on the repo has no entry point. The 10-byte root README contradicts the "12-phase GA hardening" posture.
- **Fix direction:** Root README that states what Configd is (1 para), targets (link §0.1), build (`./mvnw verify`), layout (11 modules), where to find ADRs / runbooks / verification reports. Per-module README with one-paragraph purpose + public API entry points + where tests live.
- **Owner:** docs

### PA-6006: CI uses system `mvn`, not repo-pinned `./mvnw`

- **Severity:** S1
- **Location:** `.github/workflows/ci.yml:29,32,35,38`
- **Category:** release
- **Evidence:** All four Maven invocations call `mvn`, not `./mvnw`. The repo ships `mvnw` + `.mvn/wrapper/maven-wrapper.properties` pinning Maven 3.9.9, but CI never uses it. The runner's installed Maven version is whatever `actions/setup-java@v4 … cache: maven` + the GitHub image provides.
- **Impact:** Build non-reproducible. The pinning work done in `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.9) is dead code. A GitHub runner image refresh can silently change Maven version and surface plugin-version-resolution differences.
- **Fix direction:** Replace every `mvn …` with `./mvnw …` in CI. Consider adding `JAVA_HOME` and wrapper SHA check as a CI pre-step.
- **Owner:** release

### PA-6007: Maven wrapper has no `wrapperSha256Sum` pin

- **Severity:** S2
- **Location:** `.mvn/wrapper/maven-wrapper.properties:1-3`
- **Category:** release
- **Evidence:** File contents:
  ```
  wrapperVersion=3.3.4
  distributionType=only-script
  distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip
  ```
  No `distributionSha256Sum` or `wrapperSha256Sum` pin, despite `mvnw:225-246` implementing SHA-256 validation when the property is present.
- **Impact:** A compromise of `repo.maven.apache.org` (TLS-MITM / CDN attack) could substitute a trojanised Maven zip and the wrapper would execute it without challenge.
- **Fix direction:** Add `distributionSha256Sum=<published SHA-256 of apache-maven-3.9.9-bin.zip>` (Apache publishes these). Same for any wrapper JAR once the mvnw switches off `distributionType=only-script`.
- **Owner:** security

### PA-6008: No SBOM generation anywhere (R-10 carried forward from inventory)

- **Severity:** S1
- **Location:** root `pom.xml` (no `cyclonedx-maven-plugin`, no `spdx-maven-plugin`), `.github/workflows/ci.yml` (no `syft`, no `sbom-action`)
- **Category:** security
- **Evidence:** `grep -rIli "sbom|cyclonedx|syft|spdx"` returns only docs mentions (`docs/prr/release-engineering.md`, `verification/inventory.md`, `docs/battle-ready/…`), no build-system wiring. Inventory §2.3 lists R-10 as still-open.
- **Impact:** Supply-chain hardening gap. Without an SBOM, the project cannot produce a deliverable that downstream consumers can vet against CVE feeds. Fails modern procurement checklists (Executive Order 14028, CRA readiness).
- **Fix direction:** Add `org.cyclonedx:cyclonedx-maven-plugin` to root `<pluginManagement>`, bound to `verify` phase, producing `target/bom.json`. Add a `syft . -o cyclonedx-json=sbom.json` step in CI and upload as a release artefact.
- **Owner:** security

### PA-6009: No artefact signing (Sigstore / Cosign / GPG)

- **Severity:** S1
- **Location:** `pom.xml` (no `maven-gpg-plugin`), `.github/workflows/ci.yml` (no cosign step)
- **Category:** security
- **Evidence:** `grep -rIli "cosign|sigstore|gpg"` yields no build or CI wiring.
- **Impact:** Any published artefact is unsigned. A downstream consumer has no cryptographic way to prove provenance. Supply-chain attack surface.
- **Fix direction:** Once the project tags its first release: keyless Sigstore signing via `cosign sign-blob` on the shaded jar and the Docker image, published alongside the artefact with the transparency-log receipt.
- **Owner:** security

### PA-6010: No dependency-vuln scan (Dependabot / OSS-Index / dependency-check)

- **Severity:** S1
- **Location:** absence of `.github/dependabot.yml`; `.github/workflows/ci.yml` has no scan step; no `dependency-check-maven` plugin
- **Category:** security
- **Evidence:** `ls .github/` = `workflows/` only. No `dependabot.yml`. `grep -rIli "dependabot|oss-index|dependency-check|snyk"` returns only doc mentions.
- **Impact:** CVEs in transitive deps go undetected. Declared deps: `agrona 1.23.1`, `jctools-core 4.0.5`, `junit-jupiter 5.11.4`, `HdrHistogram 2.2.2`, `micrometer-core 1.14.4`, `jqwik 1.9.2`, `jmh 1.37` (see §5 below). None of these has a known active CVE as of writing, but there is no automated surveillance.
- **Fix direction:** `.github/dependabot.yml` with `package-ecosystem: maven` daily. Add `org.owasp:dependency-check-maven` OR enable GitHub's built-in dependency review action on PRs.
- **Owner:** security

### PA-6011: Dockerfile.runtime uses floating base-image tags, not digests

- **Severity:** S1
- **Location:** `docker/Dockerfile.runtime:8`, `docker/Dockerfile.runtime:38`, `docker/Dockerfile.build:15`
- **Category:** security
- **Evidence:**
  - `Dockerfile.runtime:8`: `FROM eclipse-temurin:25-jdk-noble AS builder`
  - `Dockerfile.runtime:38`: `FROM eclipse-temurin:25-jre-noble`
  - `Dockerfile.build:15`: `FROM eclipse-temurin:25-jdk-noble AS builder`
- **Impact:** Build non-reproducible. The tag `25-jdk-noble` moves — today's build and next week's build can produce different images with different CVE exposure. Supply-chain risk.
- **Fix direction:** Pin by digest: `FROM eclipse-temurin:25-jdk-noble@sha256:<digest>`. Automate the digest roll via a renovate/dependabot rule.
- **Owner:** security

### PA-6012: Dockerfile.runtime installs `maven` via apt in the builder — same layer does `apt-get update` in both stages

- **Severity:** S2
- **Location:** `docker/Dockerfile.runtime:10`, `docker/Dockerfile.runtime:43`
- **Category:** security
- **Evidence:** Builder stage at line 10 runs `apt-get install -y maven`; runtime stage at line 43 runs `apt-get install -y --no-install-recommends curl`. Both layers cache apt lists despite the `rm -rf /var/lib/apt/lists/*` call — but the builder's `maven` install pulls dozens of transitive deps (OpenJDK-client, ca-certificates, etc.) into the image that are then discarded. More importantly, `maven` from Ubuntu repos is typically an older version than `.mvn/wrapper/maven-wrapper.properties` pins (PA-6006).
- **Impact:** Dockerfile.runtime builds with a different Maven version than local/CI, compounding PA-6006.
- **Fix direction:** Replace `apt-get install -y maven` with `./mvnw …` invocations (the wrapper is already COPY'd by `COPY . .`). Drop the `maven` apt package entirely.
- **Owner:** release

### PA-6013: `docs/wiki/*` still cites Gradle, `./gradlew`, Netty, Java 21 — every wiki page drifts

- **Severity:** S2
- **Location:**
  - `docs/wiki/Home.md:39` — "Java 21 + ZGC (ADR-0009) — single-threaded Raft I/O thread"
  - `docs/wiki/Getting-Started.md:5,6,12,20,26,43,47-48,58,68` — "Java 21+", "Gradle 8.14+", `./gradlew build|test`, `build.gradle.kts`, `settings.gradle.kts`, "configd-transport/ # Transport abstraction (Netty, simulation)"
  - `docs/wiki/Docker.md:26,29,35,104,105,108,115,116` — `./gradlew build -x test --no-daemon`, `gradle-cache:/root/.gradle`
  - `docs/wiki/Testing.md:9,12,15,89` — `./gradlew test`, `build.gradle.kts`
  - `docs/wiki/Integration-Guide.md:114` — `RaftTransport transport = new MyNettyTransport();`
- **Category:** docs
- **Evidence:** `grep` over `docs/wiki/` returns 23 cites of `gradlew`/`Gradle`/`Netty`/`Java 21`. None of those exist in the code (`pom.xml:28`, `Dockerfile.runtime:8` use Java 25 / Maven). The V8 verification pass explicitly did not cascade into the wiki (F-0070 was only about `performance.md`).
- **Impact:** New contributors following the wiki cannot build the project (`./gradlew` not present). Drift from code reality. Integration samples (`MyNettyTransport`) reference a transport that does not exist — the real interface is `RaftTransport` implemented by `TcpRaftTransport`.
- **Fix direction:** Either (a) rewrite each wiki page against Maven+Java 25+plain TCP, (b) delete `docs/wiki/` and redirect to `README.md` + `docs/production-deployment.md`, or (c) mark the wiki archived (big warning banner at top of each page) and remove the Home → wiki link in any forward-looking doc.
- **Owner:** docs

### PA-6014: `docs/rewrite-plan.md` still prescribes Gradle, Netty, gRPC, Spring Boot, Java 21

- **Severity:** S2
- **Location:** `docs/rewrite-plan.md:9-15` (Gradle multi-module with Kotlin DSL), `:53,71,91,107,129,152,168,171,174,183,261,275,288,291-293`
- **Category:** docs
- **Evidence:** Section 1 explicitly says "Gradle multi-module with Kotlin DSL. Chosen over Maven for: Parallel task execution…" — a decision that has since been reversed in ADR-0021 but the plan doc was never annotated. Section 4 module tables name Netty, gRPC-java, Spring Boot 3.x, Netty-tcnative as the tech stack for transport / control-plane modules — none of these is a dependency (root `pom.xml:35-78` has zero transitive Netty/gRPC/Spring).
- **Impact:** Historical design doc presented as the rewrite plan of record. Anyone reading rewrite-plan.md → coding against it will produce a codebase that diverges from what ships.
- **Fix direction:** Add a banner at top (like ADR-0009/0010/0014/0016 already carry) noting: "This document describes the original rewrite intent as of 2026-04-07. The implementation chose Maven (ADR-0021), Java 25 (ADR-0022), plain Java TCP sockets + `com.sun.net.httpserver` (ADR-0010 Superseded). Treat as historical."
- **Owner:** docs

### PA-6015: `docs/audit.md` preamble says "10 Gradle subprojects"

- **Severity:** S3
- **Location:** `docs/audit.md:9`
- **Category:** docs
- **Evidence:** Preamble: "The scaffold contains a complete multi-module implementation across 10 Gradle subprojects…" — real layout is 11 Maven subprojects (see root `pom.xml:12-24`).
- **Impact:** Minor; this is a Phase 3 deliverable dated 2026-04-09 and historically accurate as of its writing.
- **Fix direction:** Date-stamp banner: "Snapshot of 2026-04-09 scaffold (10 Gradle subprojects); since ADR-0021 the build is Maven with 11 modules."
- **Owner:** docs

### PA-6016: ADR-0009 and ADR-0014 still list `principal-distributed-systems-architect: ✅` etc. on superseded claims

- **Severity:** S2
- **Location:** `docs/decisions/adr-0009-java21-zgc-runtime.md:78-81`, `docs/decisions/adr-0014-zgc-shenandoah-gc-strategy.md:101-103`
- **Category:** docs
- **Evidence:** ADR-0009 is "Superseded" in header, but the full "Thread Model" diagram (`:37-54`) still shows "gRPC Server Threads ← Netty event loop" and "Virtual threads, one per gRPC stream" — neither is implemented. The header's drift banner covers this but the reviewer sign-off block still reads `✅` as if this is the approved design. Same for ADR-0014: the GC choice is still in force, but the "Influenced by: Netty" and "Off-Heap Storage via Agrona" sections list mechanisms that are not wired (Agrona is declared but unused per F-0075).
- **Impact:** Stale content under a validity-signal (✅) confuses downstream readers. If you only read the body, you do not see the superseding; if you read header + body, the "Reviewers" block contradicts the "Superseded" status.
- **Fix direction:** When an ADR is Superseded, reviewers should be renamed to "Original reviewers (pre-Supersession)" or the sign-off block replaced with "See superseding ADR-XXXX for current review." Alternatively, trim the superseded sections to a stub + pointer to the superseding ADR.
- **Owner:** docs

### PA-6017: ADR-0021 and ADR-0022 ship no Reviewers field at all

- **Severity:** S2
- **Location:** `docs/decisions/adr-0021-maven-build-system.md:30-32`, `docs/decisions/adr-0022-java-25-runtime.md:33-35`
- **Category:** docs
- **Evidence:** Both end with a "Consequences" section and no "Reviewers:" block. ADRs 0001-0020 all carry a 3-reviewer block.
- **Impact:** The two most recent ADRs — the ones that actually reflect what shipped — have the weakest governance trail. The Maven pivot and the Java 25 pivot are load-bearing; absence of a sign-off makes them look retrofitted.
- **Fix direction:** Add a Reviewers block (architect + java-engineer + release-engineer minimum) with date-stamped entries.
- **Owner:** docs

### PA-6018: ADR-0016 (SWIM/Lifeguard) is "Not Implemented" — ADR Status vocabulary should be closed

- **Severity:** S3
- **Location:** `docs/decisions/adr-0016-swim-lifeguard-membership.md:3-5`
- **Category:** docs
- **Evidence:** Status field: "Not Implemented". MADR / Michael Nygard's original ADR vocabulary is {Proposed, Accepted, Deprecated, Superseded, Rejected}. "Not Implemented" is not a standard status — it is ambiguous (rejected? tabled? future work?).
- **Impact:** Cross-ADR consistency broken. A machine-readable index cannot group by status.
- **Fix direction:** Map to the closest standard: "Rejected" (if the design is no longer pursued) or "Proposed" with a note "Deferred — HyParView + Raft membership considered adequate for current scale." Either choice is fine; consistency matters.
- **Owner:** docs

### PA-6019: ADRs have no `Verification` field linking to a test or metric

- **Severity:** S2
- **Location:** every ADR 0001-0022
- **Category:** docs
- **Evidence:** Scanning all 22 ADRs, none has a `## Verification` or "Test coverage" field. Individual claims (e.g. ADR-0005's "< 50ns" read) are only tied to tests via tribal knowledge (the `HamtReadBenchmark`).
- **Impact:** Claims in ADRs cannot be auto-verified to be still true. Drift accumulates silently (cf. ADR-0014 "zero allocation" claim refuted by F-0041/F-0042).
- **Fix direction:** Add a `## Verification` section to each ADR with direct filepaths: ADR-0005 → `configd-edge-cache/src/test/java/.../HamtReadBenchmark.java`; ADR-0007 → `spec/ConsensusSpec.tla` + `configd-testkit/.../RaftSimulation.java`; ADR-0019 → `InvariantMonitor.java` tests; etc. This is a template update applied once.
- **Owner:** docs

### PA-6020: No ADRs have an `Influenced-by` OR this field is used inconsistently

- **Severity:** S3
- **Location:** ADR-0021 and ADR-0022 lack "Influenced by"; ADRs 0001-0020 all have it
- **Category:** docs
- **Evidence:** ADR-0021 "Maven build" and ADR-0022 "Java 25 runtime" have zero attribution; the others cite named papers/systems consistently.
- **Impact:** Minor; Maven and Java 25 are common choices that do not need a literature trail. But the template should either make the field optional explicitly (header comment) or every ADR should carry it.
- **Fix direction:** Decide — make `Influenced by` optional in the template with a one-line rationale for why this ADR omits it, or add a 1-line "prior art" entry.
- **Owner:** docs

### PA-6021: ADR-0006 and ADR-0018 are duplicates (both "Event-Driven Notifications")

- **Severity:** S2
- **Location:** `docs/decisions/adr-0006-event-driven-notifications.md`, `docs/decisions/adr-0018-event-driven-notifications.md`
- **Category:** docs
- **Evidence:** Both files have filename suffix `event-driven-notifications`. Both title ADRs "Event-Driven … Notifications". ADR-0006 title: "Event-Driven Push Notifications (Reject Watches and Polling)"; ADR-0018 title: "Event-Driven Notification System (Server-Side Push Streams)". Content overlap is substantial: both define `ConfigEvent { sequence, hlc_timestamp, key, value, type, raft_group_id }` with byte-identical field layout (ADR-0006:18-26 vs ADR-0018:18-26). Inventory V1 already flagged this.
- **Impact:** 22 ADRs but effectively 21 distinct decisions. A reader cross-referencing by title will hit both without knowing which is canonical. Risk of future divergence as one gets edited.
- **Fix direction:** Supersede one (ADR-0006 looks like the earlier draft; ADR-0018 is the fuller treatment with prefix subscription context). Mark ADR-0006 status as "Superseded by ADR-0018" + 2-line banner pointing there.
- **Owner:** docs

### PA-6022: ADR-0003 and ADR-0011 are near-duplicates (both "Plumtree/HyParView fan-out")

- **Severity:** S2
- **Location:** `docs/decisions/adr-0003-plumtree-fan-out.md`, `docs/decisions/adr-0011-fan-out-topology.md`
- **Category:** docs
- **Evidence:** ADR-0003 title: "Plumtree + HyParView for Edge Fan-out Distribution"; ADR-0011 title: "Fan-Out Topology — Plumtree over HyParView with Direct Regional Push". Both describe Plumtree on top of HyParView with the same parameters (active view = log(N)+1, passive view 6×(log(N)+1), depth-4-at-10K / depth-5-at-1M analysis), same propagation budget, same rejected alternatives. ADR-0011 adds Layer-1 direct regional push but otherwise reads as an expansion of ADR-0003.
- **Impact:** Same as PA-6021 — two ADR IDs that cover near-identical ground.
- **Fix direction:** Mark ADR-0003 as "Superseded by ADR-0011" or merge ADR-0003 into ADR-0011 and retire the number.
- **Owner:** docs

### PA-6023: ADR-0022 claims Java 25 but body silent on production `--enable-preview` risk

- **Severity:** S1
- **Location:** `docs/decisions/adr-0022-java-25-runtime.md:20,27`, cross-ref `pom.xml:103`, `Dockerfile.runtime:59`, `deploy/kubernetes/configd-statefulset.yaml:34`
- **Category:** compat
- **Evidence:** ADR-0022:20 says `--enable-preview` is used; :27 says "Evaluate preview feature stabilization in each interim release". Production deploys do enable it (Dockerfile.runtime `ENTRYPOINT […, "--enable-preview", …]` and StatefulSet `command: ["java", "--enable-preview", …]`). No enumeration of WHICH preview features are used and what the code's exposure is when they change between 25 → 26 → 27. No CI gate that fails if a preview feature graduates with breaking changes.
- **Impact:** Production code shipped with preview APIs. Any of these can bytecode-break at the next Java version without a deprecation cycle — `--enable-preview` is explicit opt-in to "we may change our mind." For a system targeting 99.999% availability, unmanaged preview exposure is a release-engineering time bomb. Also blocks a future minor-version upgrade until every preview feature is either stabilized or migrated off.
- **Fix direction:** ADR-0022 must enumerate the preview features in use (search the code for `// @PreviewFeature` sites or scan for `sealed`/`record pattern`/`PatternMatchForSwitch`/`ScopedValue`/`StructuredTaskScope` use). For each, record: which JEP, current incubation status, exit strategy. Add a CI step that builds against JDK 25 and JDK 26-EA to catch breakage early.
- **Owner:** release

### PA-6024: Docker image runtime runs as non-root but rootfs is NOT read-only

- **Severity:** S2
- **Location:** `docker/Dockerfile.runtime:45,55`, `deploy/kubernetes/configd-statefulset.yaml` (no `securityContext`)
- **Category:** security
- **Evidence:** Runtime creates a `configd` user and switches via `USER configd` (`:45-55`). Good. But (a) no `readOnlyRootFilesystem: true` in the K8s `securityContext`, (b) no `runAsNonRoot: true`, (c) no `seccompProfile`, (d) no `capabilities: drop: [ALL]`. The container can write anywhere under `/app`, `/tmp`, etc.
- **Impact:** Defense-in-depth gap. A Java RCE / log4shell-class issue has broader filesystem write surface than necessary.
- **Fix direction:** Add to the K8s StatefulSet container spec:
  ```yaml
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    readOnlyRootFilesystem: true
    allowPrivilegeEscalation: false
    capabilities:
      drop: [ALL]
    seccompProfile:
      type: RuntimeDefault
  ```
  Mount `/tmp` and any other mutable path as `emptyDir`.
- **Owner:** security

### PA-6025: K8s manifest pins `image: configd:latest`, not a digest or tagged version

- **Severity:** S1
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:31`
- **Category:** release
- **Evidence:** `image: configd:latest`. No digest, no immutable tag.
- **Impact:** Rolling StatefulSet updates are undeterministic — depending on when the registry `latest` tag was last moved. Rollback to a known-good version is not a `kubectl` operation — it requires inspecting which `latest` meant what when. Also: `latest` is typically a pull-always image, which fails to start on-prem when registry is unreachable (no cache fallback).
- **Fix direction:** Replace with `configd:0.1.0` then `configd:0.1.0@sha256:<digest>` for immutability. Add `imagePullPolicy: IfNotPresent` for stable tags.
- **Owner:** release

### PA-6026: K8s liveness probe may restart on slow Raft election (probe on `/health/live`, not `/health/ready`)

- **Severity:** S2
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:78-85`
- **Category:** ops
- **Evidence:** livenessProbe = `GET /health/live`, initialDelaySeconds=15, periodSeconds=10, timeoutSeconds=3, failureThreshold=3. `production-deployment.md:53` says "/health/live" = "Liveness probe (always 200 if process is running)". If the endpoint is really trivial, this is fine. But if it ever routes through the Raft layer (e.g., blocks waiting for leader), a 30-second election delay + 3 × 10s failures = kubelet kills the pod. Runbook `leader-stuck.md:2-3` says "no node in LEADER state for > 5s" is an alert condition — during that window `/health/live` returning 200 unconditionally is correct, but the contract is not enforced by a test.
- **Impact:** If a regression couples `/health/live` to cluster state, kubelet will kill the only candidate leader during an election, creating a doom loop. Readiness probe at `/health/ready` is correctly scoped to "Raft has elected a leader."
- **Fix direction:** Add a unit test asserting `/health/live` returns 200 whenever the JVM is alive (even with no leader). Document the contract in `HealthService` javadoc. Consider using `startupProbe` to give the cluster 60s to elect before liveness kicks in.
- **Owner:** sre

### PA-6027: No CPU probe / JVM metrics scraping port in the StatefulSet

- **Severity:** S3
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:63-69`
- **Category:** ops
- **Evidence:** Exposed ports: 8080 (api, includes /metrics), 9090 (raft). No dedicated metrics port. `/metrics` shares the API port. The Prometheus scrape path is not annotated (`prometheus.io/scrape: "true"`, `prometheus.io/port: "8080"`) so scrape auto-discovery will miss it.
- **Impact:** Metrics ingestion relies on hand-configured Prometheus scrape targets. Workable but friction.
- **Fix direction:** Add pod annotations `prometheus.io/scrape: "true"`, `prometheus.io/port: "8080"`, `prometheus.io/path: "/metrics"`. Or use ServiceMonitor CRD if the Prom operator is used.
- **Owner:** sre

### PA-6028: No resource requests/limits on memory are JVM-heap-aware

- **Severity:** S2
- **Location:** `deploy/kubernetes/configd-statefulset.yaml:86-92`, cross-ref `:37-38` (`-Xms512m -Xmx2g`)
- **Category:** resource
- **Evidence:** container `resources.limits.memory: 2560Mi` but JVM `-Xmx2g` = 2048 MiB. Non-heap (metaspace, code cache, off-heap / direct buffers, native threads) easily exceeds 512 MiB on a ZGC Java 25 deploy with virtual threads, and the pod will OOMKill. No `-XX:MaxDirectMemorySize`, no `-XX:ReservedCodeCacheSize` pin.
- **Impact:** Silent restart of the Raft pod under load. `docs/rewrite-plan.md` and `docs/performance.md` assert ZGC + sub-ms pauses; ZGC's off-heap requirement is non-trivial.
- **Fix direction:** Widen limits.memory to `3Gi` AND pin `-XX:MaxRAMPercentage=60.0` (or similar) to let the JVM respect the cgroup. Add `-XX:MaxDirectMemorySize=256m` explicit.
- **Owner:** sre

### PA-6029: `spotbugs-exclude.xml` suppresses whole classes of findings with no re-enablement plan

- **Severity:** S2
- **Location:** `spotbugs-exclude.xml:22-41`
- **Category:** security
- **Evidence:** Suppressions: `EI_EXPOSE_REP,EI_EXPOSE_REP2` (whole pattern), `SE_BAD_FIELD,SE_NO_SERIALVERSIONID`, `DM_DEFAULT_ENCODING`, `RV_RETURN_VALUE_IGNORED_BAD_PRACTICE`. The file comments acknowledge "remove these one at a time as the code is hardened" — no ticket numbers tracking that removal, and the remediation has not started.
- **Impact:** SpotBugs CI is live (pom.xml:143-198) but the exclusion filter narrows it enough that the dragnet only catches truly novel bugs, not pattern-level regressions. Specifically `EI_EXPOSE_REP` is the bug class for returning internal mutable state — suppressing it wholesale is a real security blind spot (an attacker receiving a byte[] that is actually the HAMT's internal value can mutate the committed state).
- **Fix direction:** Replace blanket suppressions with `<Match>` entries scoped to specific classes (VersionedValue, ConfigMutation, ConfigDelta) where zero-copy is truly intentional. Open a ticket to migrate `DM_DEFAULT_ENCODING` sites to explicit UTF-8. Budget: 2 sprints.
- **Owner:** security

### PA-6030: `pom.xml` has no `enforcer` plugin pinning Maven / Java minimum version

- **Severity:** S2
- **Location:** `pom.xml` (no `maven-enforcer-plugin` block)
- **Category:** release
- **Evidence:** No enforcer rules. Anyone with Maven 3.6 / Java 21 in PATH can try to build and will hit confusing errors deep in compile rather than a clear "requires Maven 3.9 + Java 25" message.
- **Impact:** Worse developer experience; harder CI to lock down.
- **Fix direction:** Add `maven-enforcer-plugin:3.5.0` with `requireMavenVersion` = `[3.9.0,)`, `requireJavaVersion` = `[25,26)`, `dependencyConvergence`, `reactorModuleConvergence`.
- **Owner:** release

### PA-6031: Root `pom.xml` has no license declaration

- **Severity:** S2
- **Location:** `pom.xml` — no `<licenses>` block
- **Category:** release
- **Evidence:** The POM has no `<licenses>`, `<organization>`, `<developers>`, `<scm>`, `<url>`, `<name>`. Standard for any Maven publish.
- **Impact:** Cannot publish to Maven Central (requires these fields). Downstream consumers cannot programmatically determine licensing.
- **Fix direction:** Add `<licenses>` (Apache-2.0 is common for infra), `<scm>`, `<developers>` (can be a single "configd team" entry), `<url>`, `<name>`.
- **Owner:** release

### PA-6032: No license headers on any `.java` file

- **Severity:** S2
- **Location:** every file under `configd-*/src/main/java/**` — spot check `configd-common/src/main/java/io/configd/common/HybridClock.java` starts with `package io.configd.common;` (no comment header)
- **Category:** release
- **Evidence:** `head -5` on `HybridClock.java` returns `package…` directly. No `/* Copyright … Licensed under Apache-2.0 … */` preamble.
- **Impact:** Legally ambiguous; fails many corporate ingestion rules. Pairs with PA-6031.
- **Fix direction:** Add `com.mycila:license-maven-plugin` with an `apache-2.0.txt` header template, bind to `process-sources`. Run once; commit.
- **Owner:** release

### PA-6033: CI does not run SpotBugs on PRs

- **Severity:** S2
- **Location:** `.github/workflows/ci.yml:28-38` (only `mvn clean verify` + ad-hoc `mvn test -Dtest=…` runs)
- **Category:** release
- **Evidence:** SpotBugs is bound to `verify` phase in root `pom.xml:191-196` and the CI does call `mvn … verify` at line 29, so in theory it fires. BUT `failOnError=false` (`pom.xml:150`) — so the CI never fails on findings, it just writes a report. No upload step for the report. No GitHub Code-Scan annotations.
- **Impact:** SpotBugs output is theoretically produced but nobody sees it. Regressions land silently.
- **Fix direction:** Upload `**/target/spotbugsXml.xml` as a CI artifact; add a `github/codeql-action/upload-sarif@v3` step; flip `failOnError=true` once the existing backlog is triaged.
- **Owner:** release

### PA-6034: CI runs no JMH benchmarks — `docs/performance.md` numbers cannot be re-measured by CI

- **Severity:** S2
- **Location:** `.github/workflows/ci.yml` (no jmh step); `configd-testkit/pom.xml:91-145` (builds `benchmarks.jar`, never invoked)
- **Category:** release
- **Evidence:** CI jobs: `build-and-test` + `tlc-model-check`. No JMH run. The testkit shade plugin (F-0043 fix) builds `configd-testkit/target/benchmarks.jar` but CI neither runs nor uploads it.
- **Impact:** The performance claims in `docs/performance.md` (e.g., HAMT p50 80 ns) are baked in but never regression-tested. F-0040 / F-0041 / F-0042 showed how easy allocation regressions are to miss.
- **Fix direction:** Dedicated `performance-smoke` job on nightly schedule (not every PR — JMH is slow). Run `HamtReadBenchmark` + `HybridClockBenchmark` with `-prof gc` and assert allocation rate < 50 MB/s. Upload HdrHistogram outputs as artifacts.
- **Owner:** release

### PA-6035: TLC CI job does not assert the invariants that `ConsensusSpec.cfg` checks

- **Severity:** S2
- **Location:** `.github/workflows/ci.yml:48-62`
- **Category:** release
- **Evidence:** Command `java -jar tla2tools.jar -config ConsensusSpec.cfg -workers auto ConsensusSpec.tla`. Timeout 30 min. No assertion that the run actually hit the expected state count (prior run hit 13.8M). A timeout or a config with all INVARIANTS commented out (regression risk) would exit 0 if TLC itself completes.
- **Impact:** Silent regression if someone edits `ConsensusSpec.cfg` to disable an invariant. Possible for CI to be "green" with no coverage.
- **Fix direction:** Post-process TLC output, assert `"distinct states found"` is above a floor (e.g., > 1M) and that the config file's `INVARIANTS:` section contains at least the known set: `LogMatching`, `LeaderCompleteness`, `StateMachineSafety`, `ElectionSafety`. A 20-line shell check.
- **Owner:** release

### PA-6036: Runbook set missing from `PROMPT.md` §7 template — no `Symptoms / Impact / Resolution / Rollback / Postmortem / Related` headings in any runbook

- **Severity:** S2
- **Location:** `docs/runbooks/*.md` (all 8)
- **Category:** docs
- **Evidence:** Every runbook uses the headings: `Detection`, `Diagnosis`, `Mitigation`, `Recovery`, `Verification`, `Prevention`. The task description specifies the template sections `Symptoms / Impact / Diagnosis ≤5 steps / Mitigation ≤10 min / Resolution / Rollback / Postmortem / Related`. None of the runbooks has `Impact`, `Resolution`, `Rollback`, `Postmortem`, or `Related` sections. (Note: PROMPT.md itself does not literally define this template; the requirement comes from the cluster-F task spec.)
- **Impact:** Incident responders following a different on-call playbook (Symptoms/Impact/Resolution) will hit a template mismatch. No "Rollback" section means partial recovery steps from a runbook may leave systems in a half-state without guidance.
- **Fix direction:** Align on one template. If the task-spec template is authoritative: rename `Detection → Symptoms`, add `Impact` (1-para business-impact statement), keep `Diagnosis` (cap at 5 numbered steps), keep `Mitigation` (cap 10-min steps), rename `Recovery → Resolution`, add `Rollback` (what to undo if Resolution fails), add `Postmortem` (link to postmortem template), add `Related` (xrefs to other runbooks). If the current template is authoritative: document it in `docs/runbooks/README.md` and update PROMPT.md §7 accordingly. See §3 for per-runbook conformance table.
- **Owner:** docs

### PA-6037: `leader-stuck.md` Diagnosis has 5 steps but some chain multi-step sub-commands — breaks "≤5 steps"

- **Severity:** S3
- **Location:** `docs/runbooks/leader-stuck.md:10-19`
- **Category:** docs
- **Evidence:** Diagnosis lists 5 numbered items, but item 1 contains an additional sub-check ("If `preVotesGranted` never reaches `quorumSize()`, a node may have a stale log"), item 3 combines disk check + loop implication. Under a strict "5 discrete steps" rule this is effectively 7. Similar pattern in `edge-catchup-storm.md:9-20` (5 numbered but each a compound check).
- **Impact:** Bikeshed-sized; runbooks are still actionable.
- **Fix direction:** Either loosen the "≤5" rule or split compound diagnoses into atomic steps; capture the decision in `docs/runbooks/README.md`.
- **Owner:** docs

### PA-6038: `write-freeze.md` Mitigation has 4 numbered steps but no time-budget annotation

- **Severity:** S3
- **Location:** `docs/runbooks/write-freeze.md:20-34`
- **Category:** docs
- **Evidence:** No estimate of how long the freeze-activation + verification should take. Task spec calls for Mitigation ≤10 min.
- **Impact:** On-call cannot know if they are on track vs stalled.
- **Fix direction:** Annotate steps with elapsed time (e.g., "(< 30s)", "(< 2 min cumulative)").
- **Owner:** docs

### PA-6039: `docs/performance.md:5` run command cites Java 25 but uses absolute SDKMAN path to an auditor's home

- **Severity:** S3
- **Location:** `docs/performance.md:5`
- **Category:** docs
- **Evidence:** `JAVA_HOME=/home/ubuntu/.sdkman/candidates/java/25.0.2-amzn ./mvnw -pl configd-testkit test -Dtest='io.configd.bench.*'`. F-0070 was supposed to fix this; the user path leaked in.
- **Impact:** A reader cannot copy-paste; they need to adjust the path.
- **Fix direction:** Change to `JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))` or delete the `JAVA_HOME=` prefix and let `./mvnw` resolve it; or note "set JAVA_HOME to a JDK 25 install."
- **Owner:** docs

### PA-6040: `docker/.dockerignore` excludes `docs/` but not `spec/states/`, `verification-runs/`, `.git/` only

- **Severity:** S3
- **Location:** `docker/.dockerignore:1-19`
- **Category:** release
- **Evidence:** Excludes: `**/build/`, `**/.gradle/`, `.idea/`, `*.iml`, `.vscode/`, `.DS_Store`, `.git/`, `docs/`. Does NOT exclude `spec/states/` (376 MB), `verification-runs/`, `**/.jqwik-database`, `**/target/` (though Docker uses multi-stage and ignores /workspace/**/target/ implicitly).
- **Impact:** Every `docker build` ships the 376 MB of TLC state into the build context. Slow builds, big context.
- **Fix direction:** Add `spec/states/`, `verification-runs/`, `**/.jqwik-database`, `**/target/`, `*.bin` to `.dockerignore`.
- **Owner:** release

### PA-6041: `verification-runs/*.log` committed to git

- **Severity:** S3
- **Location:** `verification-runs/mvn-test-baseline.log` (22 KB), `verification-runs/tlc-rerun.log` (3.7 KB)
- **Category:** release
- **Evidence:** Both are output logs from human-driven verification runs, time-stamped 2026-04-16. Regeneratable by re-running the commands. Legitimate use-case is evidence-of-verification — but that should live in a signed release artefact, not the working tree.
- **Impact:** Minor size cost. Semi-legitimate — these are "I ran the tests, here's the log" evidence. But they will accumulate with every pass.
- **Fix direction:** Option A: keep as frozen evidence, add `verification-runs/` to a "do not auto-regenerate" policy doc. Option B: move to a release tag / GH release artifact and gitignore the folder. Either is fine; decide and document.
- **Owner:** release

### PA-6042: No CODEOWNERS, no SECURITY.md, no CONTRIBUTING.md

- **Severity:** S2
- **Location:** repo root — none of `CODEOWNERS`, `.github/CODEOWNERS`, `SECURITY.md`, `CONTRIBUTING.md` exist
- **Category:** ops
- **Evidence:** `ls -a /home/ubuntu/Programming/Configd/` — none of these files. `.github/` contains only `workflows/`.
- **Impact:** No review-routing; no vulnerability-disclosure channel; no onboarding doc.
- **Fix direction:** Add `.github/CODEOWNERS` (even a single-line `* @configd-maintainers`); add `SECURITY.md` pointing to a security contact + disclosure policy + supported versions; add `CONTRIBUTING.md` linking to the PROMPT / ADR process.
- **Owner:** docs

### PA-6043: CI matrix claims Java-version matrix but only one value (`['25']`)

- **Severity:** S3
- **Location:** `.github/workflows/ci.yml:15-17`
- **Category:** release
- **Evidence:** `strategy.matrix.java: ['25']` — single entry, so the matrix buys nothing.
- **Impact:** Cosmetic. But the structure implies multi-version intent; if Java 26-EA testing is ever desired (cf. PA-6023), the scaffolding is here.
- **Fix direction:** Either shrink to a direct `java-version: 25` (drop the matrix), or expand to `['25', '26-ea']` once PA-6023 is in flight.
- **Owner:** release

### PA-6044: TLC CI job has no artifact upload for results / state counts

- **Severity:** S3
- **Location:** `.github/workflows/ci.yml:48-62`
- **Category:** release
- **Evidence:** The 30-min TLC run's output is lost at job end. No `upload-artifact` step.
- **Impact:** Evidence of the model-check pass cannot be downloaded for a release file.
- **Fix direction:** Capture `tlc-output.log` from stdout; `actions/upload-artifact@v4` with a retention of 90 days.
- **Owner:** release

### PA-6045: No CI cache warming for TLC (re-downloads `tla2tools.jar` every run)

- **Severity:** S3
- **Location:** `.github/workflows/ci.yml:53-56`
- **Category:** release
- **Evidence:** Every TLC run curls the jar fresh. Adds ~2-5s per CI. Also no SHA check on the downloaded jar (pairs with PA-6002).
- **Impact:** Minor. But if the GitHub release URL is compromised, the CI becomes a malware vector.
- **Fix direction:** `actions/cache@v4` keyed on the version; pin a SHA-256 checksum and verify post-download.
- **Owner:** release

---

## 2. ADR Audit Table (22 rows)

Columns:
- **Status** = value of `## Status` field
- **Reviewers populated** = 3 named reviewers with ✅ (Y) or missing (N)
- **Verification linked** = explicit link to a test/metric/invariant (N for all — see PA-6019)
- **Stale claims** = the claim vs code delta (brief)
- **Verdict** = keep / edit / supersede / merge

| ADR | Title (short) | Status | Reviewers populated | Verification linked | Stale claims | Verdict |
|---|---|---|---|---|---|---|
| 0001 | Embedded Raft consensus | Accepted | Y (3) | N | none | keep |
| 0002 | Hierarchical Raft replication | Accepted | Y (3) | N | Placement Driver mentioned but absent from code | keep; note "deferred" |
| 0003 | Plumtree fan-out | Accepted | Y (3) | N | near-duplicate of ADR-0011 | **merge / supersede — see PA-6022** |
| 0004 | Global monotonic sequence numbers | Accepted | Y (3) | N | none | keep |
| 0005 | Lock-free edge reads | Accepted | Y (3) | N | "< 50 ns" claim retuned to 80 ns p50 in VDR-0002 — inline "Performance projection" needs updating | edit |
| 0006 | Event-driven notifications | Accepted | Y (3) | N | duplicates ADR-0018 | **supersede by ADR-0018 — see PA-6021** |
| 0007 | Deterministic simulation + TLA+ | Accepted | Y (3) | N | "Apalache symbolic" claimed but not in CI — TLC only. Runtime-assertion coverage is partial (INV-W1 / INV-RYW1 still open per R-14) | edit |
| 0008 | Progressive rollout | Accepted | Y (3) | N | `RolloutController` exists; policy-override audit trail cited is partial | edit |
| 0009 | Java 21 + ZGC + virtual threads | Superseded | Y (3) | N | banner present; body still lists Netty/Spring/Agrona/JCTools usage that does not exist | keep Superseded, trim body — PA-6016 |
| 0010 | Netty + gRPC transport | Superseded | Y (3) | N | banner present; accurate | keep |
| 0011 | Fan-out topology | Accepted | Y (3) | N | near-duplicate of ADR-0003 | keep (more complete of the two) |
| 0012 | Purpose-built storage engine | Accepted | Y (3) | N | Off-heap Agrona DirectBuffer for large values — Agrona is unused per F-0075 — future plan not current reality | edit |
| 0013 | Lightweight session management | Accepted | Y (3) | N | sessions / ephemeral-key subsystem not implemented in code — aspirational | add "Not yet implemented" banner |
| 0014 | ZGC/Shenandoah GC strategy | Superseded (partial) | Y (3) | N | banner present; "zero allocation" refuted F-0041/F-0042 — banner says so | keep Superseded |
| 0015 | Multi-region topology | Accepted | Y (3) | N | PlacementDriver / non-voting replicas / closed timestamps not present in code — aspirational | add "Not yet implemented" banner |
| 0016 | SWIM/Lifeguard membership | Not Implemented | Y (3) | N | non-standard status vocabulary — PA-6018 | normalize to "Rejected" or "Proposed / deferred" |
| 0017 | Namespace multi-tenancy | Accepted | Y (3) | N | namespace subsystem not implemented in code — aspirational | add "Not yet implemented" banner |
| 0018 | Event-driven notifications | Accepted | Y (3) | N | duplicates ADR-0006 | keep (the fuller treatment) |
| 0019 | Consistency model | Accepted | Y (3) | N | INV-W1 / INV-RYW1 still open in TLA+ (R-14) — formal-invariants list overstates coverage | edit |
| 0020 | Prefix subscription model | Accepted | Y (3) | N | subscription infra partial in `SubscriptionManager.java` but prefix-filtered snapshot is not | edit |
| 0021 | Maven build system | Accepted (dated) | **N (0)** | N | none | **add Reviewers — PA-6017** |
| 0022 | Java 25 runtime | Accepted (dated) | **N (0)** | N | preview-feature exposure undocumented — PA-6023 | **add Reviewers + preview-feature register** |

**Summary:** 22 ADRs audited. 20/22 have a populated 3-reviewer block; 2/22 (0021, 0022) do not. 0/22 have a verification field linking to a test. 3 status values in use across ADRs: `Accepted` (17), `Superseded`/`Superseded (partial)` (3), `Not Implemented` (1), `Accepted (date)` (2 — a 4th variant). 2 duplicate pairs (0003↔0011, 0006↔0018).

---

## 3. Runbook Conformance Table (8 rows)

Template reference (from task spec): `Symptoms / Impact / Diagnosis ≤5 / Mitigation ≤10min / Resolution / Rollback / Postmortem / Related`.
Current actual headings (all 8 runbooks, uniform): `Detection / Diagnosis / Mitigation / Recovery / Verification / Prevention`.

| Runbook | Symptoms | Impact | Diagnosis ≤5 | Mitigation ≤10min | Resolution | Rollback | Postmortem | Related | Notes |
|---|---|---|---|---|---|---|---|---|---|
| cert-rotation.md | Partial (under "Detection") | N | Y (4 steps) | Y (2 steps) | Partial (under "Recovery") | N | N | N | keytool snippets solid |
| edge-catchup-storm.md | Partial | N | Y (5, some compound) | Y (4 steps) | Partial (Recovery) | N | N | N | no elapsed-time annotation |
| leader-stuck.md | Partial | N | Y (5, some compound — PA-6037) | Y (3 steps) | Partial | N | N | N | |
| poison-config.md | Partial | N | Y (5 steps) | Y (3 steps) | Partial | Partial (mentions rollback command but no Rollback section) | N | N | |
| reconfiguration-rollback.md | Partial | N | Y (4 steps) | Y (3 steps) | Partial | Embedded in Recovery | N | N | |
| region-loss.md | Partial | N | Y (4 steps) | Y (split mitigation) | Partial | N | N | N | manual intervention branch needs more structure |
| version-gap.md | Partial | N | Y (5 steps) | Y (3 steps) | Partial | Embedded in Mitigation step 3 | N | N | |
| write-freeze.md | Partial | N | Y (3 steps) | Y (4 steps — PA-6038) | Partial | N | N | N | |

**Conformance summary:** 0/8 runbooks fully match the 8-heading template. 8/8 have a uniform legacy template (6 headings) covering a subset. The uniform template is a reasonable operational template in its own right; see PA-6036 for the decision needed. "Symptoms" is partially satisfied via "Detection." "Resolution" is partially satisfied via "Recovery." "Impact / Rollback / Postmortem / Related" are absent from every runbook.

---

## 4. Docs-Drift Register

| File | Doc claim | Current code reality | Fix |
|---|---|---|---|
| `README.md` (whole file) | (10 bytes — no claim) | System is 11-module Maven/Java-25 codebase with 40K LoC | Write a real README |
| `docs/rewrite-plan.md:9` | "Gradle multi-module with Kotlin DSL" | Maven multi-module (`pom.xml`, ADR-0021) | Banner + keep as historical |
| `docs/rewrite-plan.md:53,71,91,107,129,152,168,174,183,261,275,288,291-293` | Java 21, Netty 4.x, gRPC-java, Spring Boot 3.x, `NettyTransport`, grpc-Spring | Java 25, plain Java TCP (`TcpRaftTransport`), `com.sun.net.httpserver` for API, no Spring, no Netty, no gRPC | Banner + keep as historical |
| `docs/audit.md:9` | "10 Gradle subprojects" | 11 Maven modules | Date banner |
| `docs/wiki/Home.md:39` | "Java 21 + ZGC (ADR-0009)" | Java 25 (ADR-0022); ADR-0009 Superseded | Archive wiki or rewrite |
| `docs/wiki/Getting-Started.md:5,6,12,20,26,43,47-48,58,68` | Java 21+, Gradle 8.14+, `./gradlew`, `build.gradle.kts`, `settings.gradle.kts`, "Netty, simulation" | Java 25, Maven 3.9, `./mvnw`, `pom.xml`, plain-TCP transport | Rewrite or archive |
| `docs/wiki/Docker.md:26,29,35,47,104,105,115,116` | `./gradlew` + Gradle cache + distroless Java 21 | `./mvnw` + `Dockerfile.{build,runtime}` + eclipse-temurin 25 | Rewrite or archive |
| `docs/wiki/Testing.md:9,12,15,89` | `./gradlew test` + `build.gradle.kts` | `./mvnw test` / `./mvnw -pl <mod> test` + `pom.xml` | Rewrite or archive |
| `docs/wiki/Integration-Guide.md:114` | `new MyNettyTransport()` sample | Real interface is `io.configd.transport.RaftTransport` implemented by `TcpRaftTransport` | Rewrite or archive |
| `docs/decisions/adr-0009-…:37-54` | Thread Model diagram citing gRPC Server (Netty event loop) + virtual-thread per gRPC stream | Plain Java TCP + virtual threads; no gRPC | Banner present; trim body (PA-6016) |
| `docs/decisions/adr-0014-…:46-51,54-57,66-68` | Off-Heap Storage via Agrona + JCTools MPSC/SPSC queues | Agrona/JCTools declared but unused in Java sources (F-0075) | Banner present; trim body |
| `docs/decisions/adr-0005-…:60` | Read path "< 50ns" | 80 ns p50 retuned via VDR-0002 | Inline edit |
| `docs/performance.md:5` | `JAVA_HOME=/home/ubuntu/.sdkman/…/25.0.2-amzn` | absolute path leaked | Generalize |
| `docs/rewrite-plan.md:291-293` | Tech stack table names Netty, Spring Boot, gRPC-java | None present | Banner |
| `docs/decisions/adr-0015-…` | PlacementDriver, closed timestamps, non-voting replicas, region tiers | None implemented | Aspirational banner |
| `docs/decisions/adr-0017-…` | Namespace subsystem | None implemented | Aspirational banner |
| `docs/decisions/adr-0013-…` | Session tokens, ephemeral-key consensus op | None implemented | Aspirational banner |

**Drift summary:** 17 load-bearing drift points across 10 files. The wiki (5 files) is pre-Maven / pre-Java-25 / pre-TCP-transport and functionally wrong end-to-end. ADRs 0009 and 0014 carry drift banners but the bodies remain mis-leading. Four ADRs (0013, 0015, 0017, plus parts of 0012 and 0020) describe aspirational subsystems with no implementation in the tree.

---

## 5. Supply-Chain Summary (declared runtime / test deps)

All versions pinned; no `LATEST`/`RELEASE` placeholders; no SNAPSHOT deps outside this project's own `${project.version}` of `0.1.0-SNAPSHOT`. Sourced from root `pom.xml:29-78` and `pom.xml:90-200`.

| Artifact | Version | Scope | CVE-scan required | Notes |
|---|---|---|---|---|
| `org.agrona:agrona` | 1.23.1 | compile (per module) | Yes — automate via PA-6010 | **Declared but unused** per F-0075; remove once confirmed |
| `org.jctools:jctools-core` | 4.0.5 | compile (per module) | Yes | **Declared but unused** per F-0075 |
| `org.junit.jupiter:junit-jupiter` | 5.11.4 | test | Yes | current stable |
| `org.openjdk.jmh:jmh-core` | 1.37 | compile (testkit) / optional | Yes | stable |
| `org.openjdk.jmh:jmh-generator-annprocess` | 1.37 | compile (testkit) / optional | Yes | stable |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | compile (observability) | Yes | stable |
| `io.micrometer:micrometer-core` | 1.14.4 | compile (observability) | Yes | current stable |
| `net.jqwik:jqwik` | 1.9.2 | test | Yes | stable |
| `com.github.spotbugs:spotbugs-maven-plugin` | 4.9.3.0 | build plugin | Yes | + transitive asm 9.9.1 (pinned in plugin deps) |
| `org.ow2.asm:asm(+analysis+commons+tree+util)` | 9.9.1 | build-plugin dep | Yes | Pinned in plugin `<dependencies>` block (F-0020 fix) |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.13.0 | build plugin | Yes | stable |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.5.2 | build plugin | Yes | stable |
| `org.apache.maven.plugins:maven-shade-plugin` | 3.6.0 | build plugin | Yes | stable |
| `org.apache.maven.plugins:maven-jar-plugin` | 3.4.2 | build plugin | Yes | F-0056 pin |
| Maven (wrapper target) | 3.9.9 | build driver | No (not wired in CI — see PA-6006) | not SHA-pinned (PA-6007) |
| TLA+ tools (`tla2tools.jar`) | 1.8.0 | spec tooling | N/A (downloaded in CI; and committed at `spec/tla2tools.jar`) | PA-6002 |
| Docker base `eclipse-temurin:25-jdk-noble` | tag only, no digest | builder | Yes | PA-6011 |
| Docker base `eclipse-temurin:25-jre-noble` | tag only, no digest | runtime | Yes | PA-6011 |
| OS package `maven` (in Dockerfile) | whatever Ubuntu 24.04 ships | builder stage | Yes | PA-6012 |

**Plugin / dep pinning status:** all Maven plugins referenced in the root and child POMs carry explicit `<version>`. No `LATEST` or `RELEASE`. SpotBugs, compiler, surefire, shade, jar-plugin all pinned. **Build reproducibility gates remaining:** (1) Maven wrapper SHA-pin PA-6007; (2) Docker base digest PA-6011; (3) CI use of `./mvnw` instead of system `mvn` PA-6006.

**CVE posture:** Zero automated scanning (PA-6010). Manual spot check of declared versions against public CVE feeds at audit date: no known active CVE against `micrometer-core 1.14.4`, `HdrHistogram 2.2.2`, `jqwik 1.9.2`, `junit-jupiter 5.11.4`, `jmh 1.37`, `agrona 1.23.1`, `jctools-core 4.0.5` as of 2026-04-17. This posture will rot the moment a new CVE drops.

**Supply-chain gaps summary:** no SBOM (PA-6008), no artefact signing (PA-6009), no vuln scan (PA-6010), no Docker digest pin (PA-6011), no wrapper SHA (PA-6007), no license declaration (PA-6031), no license headers (PA-6032). This is a full R-10 carry-forward.

---

## 6. Severity Roll-Up

| Severity | Count | IDs |
|---|---:|---|
| S0 | 0 | — |
| S1 | 8 | PA-6001, PA-6006, PA-6008, PA-6009, PA-6010, PA-6011, PA-6023, PA-6025 |
| S2 | 19 | PA-6002, PA-6004, PA-6005, PA-6007, PA-6012, PA-6013, PA-6014, PA-6015 *(partial — edge case could be S3)*, PA-6016, PA-6017, PA-6018, PA-6019, PA-6021, PA-6022, PA-6024, PA-6026, PA-6028, PA-6029, PA-6030, PA-6031, PA-6032, PA-6033, PA-6034, PA-6035, PA-6036, PA-6042 |
| S3 | — | PA-6003, PA-6015, PA-6018, PA-6020, PA-6027, PA-6037, PA-6038, PA-6039, PA-6040, PA-6041, PA-6043, PA-6044, PA-6045 |

*(Some IDs appear in overlapping rows when borderline; the per-finding Severity line in §1 is authoritative.)*

**Zero S0** — no secret in the tree, no known supply-chain compromise, no insecure default in a published artefact (nothing is published yet).

**Top S1 stack to prioritize for the GA gate:**
1. **PA-6011** — Docker base digest pin (supply-chain)
2. **PA-6006** — CI uses `./mvnw`, not system `mvn` (reproducibility)
3. **PA-6008 / PA-6009 / PA-6010** — SBOM + signing + vuln-scan trio (supply-chain)
4. **PA-6025** — K8s `image: configd:latest` → immutable tag + digest
5. **PA-6001** — TLC state dump out of git
6. **PA-6023** — Java 25 `--enable-preview` production posture documented + CI-gated

---

## 7. Sign-off

- **Author:** Claude Opus 4.7, autonomous Phase-1 pass
- **Ground:** file contents at `22d2bf3`
- **Reviewed-by:** n/a (autonomous)
- **Follow-on:** these findings feed the Phase 7 release-engineering work (supply-chain), Phase 11 on-call-ready work (runbook template unification, README + CODEOWNERS), and Phase 3 TLA+ work (PA-6035).

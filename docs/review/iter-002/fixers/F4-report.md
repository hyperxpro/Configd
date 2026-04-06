# F4 — Docs / CI / onboarding fix report (iter-2)

## Tasks completed

| ID | File:line | Edit |
|---|---|---|
| **N-101** | `README.md` (full rewrite) | 30-line README: pre-GA banner, JDK 25 + `--enable-preview`, `./mvnw -T 1C verify` commit-gate, module pointer, `ops/runbooks/` (NOT `docs/runbooks/`), CHANGELOG + handoff + CONTRIBUTING + ga-review links. |
| **N-103** | `docs/wiki/Getting-Started.md`, `docs/wiki/Docker.md`, `docs/wiki/Testing.md` (full rewrites) | Removed every Gradle reference; replaced `Java 21+` with `Java 25 (Corretto)`; replaced `./gradlew build` with `./mvnw -T 1C verify`; updated module tree, dep snippets, Dockerfile base images, layer-cache notes, Compose example. |
| **N-102** | `CONTRIBUTING.md` (new) | Prereqs, commit-gate command, every-commit-passes-verify rule, ADR process (next-NNNN convention since no template ships), runbook conformance link, §0.1 sign-off rule, honesty §8 callouts, PR checklist. |
| **N-104** | `docs/wiki/Home.md:14-29,39` | Status table rewritten to match `docs/verification/inventory.md` (all 11 modules `Implemented`); `Java 21 + ZGC (ADR-0009)` → `Java 25 + ZGC (ADR-0022, supersedes ADR-0009)`. |
| **N-107** | `docs/decisions/adr-0001-embedded-raft-consensus.md:46` | Operator check now uses `/health/ready` plus a `PUT /v1/config/__probe__/leader-check` to read `X-Leader-Hint`; added `> NOTE: ... PA-7001 ...` block. |
| **N-108** | `ops/runbooks/release.md:276` | Replaced `PA-XXXX` with `PA-7002` in the admin-endpoint TODO. F3 had already swapped the parity check to `__release_probe__` + `X-Config-Version`; only the issue-id was open. |
| **DOC-027** | `ops/runbooks/snapshot-install.md:48,61` | Both `SnapshotConformanceTest.java` citations replaced with `configd-consensus-core/.../SnapshotInstallSpecReplayerTest.java`. |
| **DOC-028** | `docs/decisions/adr-0028-snapshot-on-disk-format.md` (new); `ops/runbooks/snapshot-install.md:149`; `ops/runbooks/restore-from-snapshot.md:292` | Authored ADR-0028 documenting body layout + TLV-with-magic trailer (`0xC0FD7A11`), legacy/raw/TLV decode order, F-0013/F-0053 bounds, deferred chunking + CRC. Both runbooks re-pointed at ADR-0028. |
| **DOC-029** | `ops/runbooks/runbook-conformance-template.md:30` | Replaced nonexistent `InvariantMonitor.assertAll()` with `checkAll()` + `violations()` (the methods that actually ship). |
| **DOC-030** | — | Already closed by F3 (`docs/runbooks/README.md` deprecation banner present). No-op for F4. |
| **DOC-033** | `ops/runbooks/raft-saturation.md:35` | Already closed by F3 (`configd_apply_total` → `configd_apply_seconds_count` + TODO PA-XXXX comment). No further F4 edit needed. |
| **DOC-035** | `docs/architecture.md:85,116` | Added F7-style honesty banners: cross-region write commit row footnoted as MODELED (~100 ms per `docs/performance.md` §11); read-path `< 50 ns` annotated `target — not measured this pass; see docs/performance.md §10`. |
| **R-004** | `.github/workflows/release.yml` (new step before `Install cosign`) | Added `Trivy runtime image scan` using `aquasecurity/trivy-action@0.20.0` against `${IMAGE}@${digest}`; mirrors ci.yml's `severity: HIGH,CRITICAL` + `exit-code: '1'`; deliberately omits `ignore-unfixed: true` per spec (SEC-021 scope). |
| **R-005** | `.github/workflows/ci.yml` (wire-compat step) | Replaced default `git diff` with `--diff-filter=ACMRD` so deletions are caught; added explicit `fixtures_deleted` echo and an inline comment block explaining the §8.10 rule. |
| **P-017** | `docs/performance.md:79` | Added MODELED, NOT MEASURED banner above the table covering every concrete ns/op number in §3 (no JMH run committed; cheaper path chosen). |

## Tasks deferred

None. All assigned tasks closed within scope.

## F3 overlap / coordination

- `ops/runbooks/release.md` — F3 had already converted §6 parity check to the `__release_probe__` + `X-Config-Version` pattern; only the `PA-XXXX → PA-7002` swap remained for F4.
- `ops/runbooks/snapshot-install.md` — F3 had updated the threshold + added `PA-XXXX` TODO blocks while F4 was editing; F4's later edit (DOC-028 ADR re-point) was layered cleanly.
- `docs/runbooks/README.md` — F3 already created the deprecation banner (DOC-030 no-op for F4).
- `ops/runbooks/raft-saturation.md` — F3 already swapped `configd_apply_total` → `configd_apply_seconds_count`; DOC-033's cheapest path was already in tree.

No conflicting edits; no rollback of F3 work.

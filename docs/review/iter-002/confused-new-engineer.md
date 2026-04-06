# confused-new-engineer — iter-002

**Reviewer lens:** new engineer joining Monday at 09:00. README -> wiki -> ADRs -> runbooks -> code, in that order.
**Date:** 2026-04-19.
**Severity floor:** S3.

iter-1 confirmed-closed (and not re-flagged here):
- raftctl removed in favour of `curl`/`kubectl` (iter-1 N-001).
- `configd-bootstrap.yaml` exists (iter-1 N-007).
- `restore-snapshot.sh` and `restore-conformance-check.sh` exist (iter-1 N-006/008).
- `disaster-recovery.md` `kubectl delete pvc` is gated on a `read -p` confirmation (iter-1 N-013).
- ADR cross-refs in `control-plane-down.md`/`write-commit-latency.md` now point to ADR-0027 / ADR-0007 correctly (iter-1 N-010/011).
- Every ADR carries a `## Verification` section; every `ops/runbooks/*.md` has the 8-section skeleton + `Operator-Setup`. iter-1 §8.14/§8.15 floors hold.
- `docs/performance.md` cross-region rows now read **MODELED, NOT MEASURED** explicitly. (iter-1 P-001 closed.)

Below are 14 fresh findings I would actually freeze on, in the order I'd hit them.

**Findings:** 14

---

## N-101 — `README.md` is a one-line stub
- **Severity:** S0
- **Location:** `/README.md` (entire file)
- **Category:** missing-onboarding
- **Evidence:** the file is exactly two lines: `# Configd\n` and an empty line. Nothing else.
- **What I'd do Monday at 09:00:** I clone the repo, open README. I see "Configd". I do not know the JDK version, the build tool, the entrypoint, the relationship between the 11 sub-modules, where to find getting-started, or that `./mvnw -T 1C verify` is the commit-gate command. I open the wiki (`docs/wiki/Getting-Started.md`) — see N-103 for what happens next.
- **Fix proposal:** A 30-line README that says, in order: (1) one-paragraph what-it-is, (2) prerequisites = JDK 25 + `--enable-preview`, (3) `./mvnw -T 1C verify` for the commit-gate build, (4) module layout pointer to `docs/wiki/Module-Reference.md`, (5) where the canonical runbooks live (`ops/runbooks/`, not `docs/runbooks/`), (6) link to `CHANGELOG.md` + `docs/handoff.md`. Repository hygiene; this is the first file every new contributor opens.

## N-102 — `CONTRIBUTING.md` does not exist
- **Severity:** S2
- **Location:** repo root — file absent.
- **Category:** missing-onboarding
- **Evidence:** `ls CONTRIBUTING.md` → no such file. `docs/handoff.md:194` notes the cold-cache build takes ~20 min and `git log` shows the project relies on a strict 11-commit grouping; none of that lives anywhere a new contributor can find without reading handoff.md (which is itself addressed to "the human picking this up", not to a continuing contributor).
- **What I'd do Monday at 09:00:** start hacking, push a branch, see CI break on something I could have known if a `CONTRIBUTING.md` enumerated: required pre-commit checks, signing key for commits, JDK version, the `--enable-preview` requirement, `mvnw` not `gradlew`, ADR process for changing system targets (`PROMPT.md §0.1`), runbook-conformance-template usage. Right now this is folklore from `docs/handoff.md`.
- **Fix proposal:** Create `CONTRIBUTING.md` with: prereqs (JDK 25 Corretto, Maven wrapper), build/test commands, the "every commit must pass `./mvnw -T 1C verify`" rule, ADR process pointer, `docs/decisions/adr-NNNN-template.md` link, runbook-conformance link, sign-off rule for changing `PROMPT.md §0.1` targets.

## N-103 — `docs/wiki/Getting-Started.md` is build-system drift: tells me to use Gradle and JDK 21
- **Severity:** S0
- **Location:** `docs/wiki/Getting-Started.md:5,12,20,26,29,38-49,57-63,68,71`
- **Category:** README→code drift
- **Evidence:** "**Java 21+** (Temurin/Corretto/GraalCE recommended)" / "**Gradle 8.14+** (wrapper included — no global install needed)" / "`./gradlew build`" / "`build.gradle.kts`" / "`settings.gradle.kts`". The repo has `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` — no `gradlew`, no `build.gradle.kts`, no `settings.gradle.kts`. `pom.xml:28` pins `<maven.compiler.release>25</maven.compiler.release>`; `docker/Dockerfile.build:18` pins `eclipse-temurin:25-jdk-noble`; ADR-0022 mandates Java 25.
- **What I'd do Monday at 09:00:** install JDK 21 (wasted), install Gradle (wasted), run `./gradlew build` — file not found. Run `gradle build` — fails on missing `build.gradle.kts`. Page my onboarding buddy. Hour gone before I find `mvnw`.
- **Fix proposal:** Rewrite `docs/wiki/Getting-Started.md` to match Maven + JDK 25 reality. Same drift exists in `docs/wiki/Docker.md:7,8,18,26,29,35-43,47,49,57,61,99-109,116` and `docs/wiki/Testing.md:7-20`. The cheap fix: a single `sed -i 's/gradlew/mvnw/; s/gradle/maven/; s/Java 21/Java 25/g'` plus deleting the imaginary file-tree section, with a manual pass for the docker `./gradlew build -x test --no-daemon` lines that have no Maven equivalent.

## N-104 — `docs/wiki/Home.md` claims half the implemented modules are "Planned"
- **Severity:** S2
- **Location:** `docs/wiki/Home.md:18-29,39`
- **Category:** README→code drift
- **Evidence:** Status table marks `configd-replication-engine`, `configd-distribution-service`, `configd-control-plane-api`, and `configd-observability` as **Planned** — `docs/verification/inventory.md:16-26` shows all four implemented (146, 139, 50, 119 tests respectively, all passing per `iter-001/verify.md:32-46`). "Java 21 + ZGC (ADR-0009)" — superseded by ADR-0022 (Java 25); ADR-0009 itself was never marked superseded.
- **What I'd do Monday at 09:00:** assume the data plane and observability modules don't work yet, plan my first ticket against `configd-replication-engine` as "build the planned skeleton", spend a day before noticing the full module exists with passing tests.
- **Fix proposal:** Rewrite the status table to match `docs/verification/inventory.md`. Update the "Java 21 + ZGC" bullet to reference ADR-0022 (Java 25). Either supersede ADR-0009 (set `## Status\nSuperseded by ADR-0022`, mirroring how ADR-0006 was superseded) or rename the bullet to "Java 25 + ZGC (ADR-0022, supersedes ADR-0009)".

## N-105 — Two parallel runbook directories, only one canonical, no breadcrumb
- **Severity:** S2
- **Location:** `docs/runbooks/` (8 files) vs `ops/runbooks/` (11 files) — no `docs/runbooks/README.md`
- **Category:** terminology/structure ambiguity
- **Evidence:** Both directories ship live in the tree. `ops/runbooks/README.md` is the authoritative index; `docs/runbooks/` has zero README and no header in any file saying "deprecated, see ops/runbooks/...". `docs/handoff.md:411-418` flags this as known drift but explicitly defers it. Worse, `docs/runbooks/leader-stuck.md` references metric names (`configd_raft_role`, `configd_raft_election_started_total`, `configd_raft_election_won_total`, `configd_raft_current_term`, `configd_raft_heartbeat_sent_total`, `configd_raft_prevote*`) that are not registered in `ConfigdMetrics.java` — a new on-call who finds the wrong directory will run queries that return zero series.
- **What I'd do Monday at 09:00:** I see "runbooks" in two places. I read `docs/runbooks/leader-stuck.md` first because `docs/` sounds canonical. I copy queries it suggests — Grafana shows nothing. I conclude metrics are broken and either silence the alert or escalate to a phantom problem.
- **Fix proposal:** delete `docs/runbooks/` (consensus reached in `docs/handoff.md:411-418`), or at minimum add `docs/runbooks/README.md` with a single line: `> **DEPRECATED.** See [ops/runbooks/](../../ops/runbooks/) for the canonical, alert-linked runbooks. Files in this directory are pre-GA design notes; do NOT use during incidents.` Same warning header at the top of each file inside `docs/runbooks/`.

## N-106 — `docs/runbooks/leader-stuck.md` references `*Ms` config fields that were renamed to `*Ticks` in iter-1
- **Severity:** S2
- **Location:** `docs/runbooks/leader-stuck.md:45-46`
- **Category:** README→code drift (D-003 follow-on)
- **Evidence:** "Tune `electionTimeoutMinMs` and `electionTimeoutMaxMs` to be well above network RTT (default 150-300ms). Ensure `heartbeatIntervalMs` (default 50ms) is significantly less than `electionTimeoutMinMs`." iter-1 D-003 (verify.md:71) renamed the `RaftConfig` fields to `electionTimeoutMin/MaxTicks` and `heartbeatIntervalTicks`. The "Ms" suffix and the "150-300ms" default are both lies post-rename — these are now tick counts (one tick ≈ 10 ms).
- **What I'd do Monday at 09:00:** Try to find a `*Ms` field in `RaftConfig`, fail, get confused about whether the doc or code is wrong; talk myself into believing the doc. Set the value as if it were ms. The cluster either elects too aggressively or never times out.
- **Fix proposal:** either delete `docs/runbooks/leader-stuck.md` per N-105, or rewrite this section to say `electionTimeoutMin/MaxTicks` (tick = 10 ms, default 15-30 = 150-300 ms wall) and `heartbeatIntervalTicks` (default 5 = 50 ms). Cite the rename in iter-1 verify.md so future readers know the history.

## N-107 — ADR-0001 "Operator check" instructs `curl /raft/status` — endpoint never implemented
- **Severity:** S1
- **Location:** `docs/decisions/adr-0001-embedded-raft-consensus.md` (last line of `## Verification`)
- **Category:** ADR honesty / broken cross-reference
- **Evidence:** "**Operator check:** ... `kubectl exec configd-0 -- curl -sf http://localhost:8080/raft/status` returns a leader from the in-cluster voter set." `HttpApiServer.java:100-110` only registers `/health/live`, `/health/ready`, `/metrics`, `/v1/config/`. iter-1 N-002 flagged the same endpoint in restore runbooks; the runbooks were honest about it (every callsite carries a `<!-- TODO PA-XXXX: admin endpoint missing -->` comment). ADR-0001 is the **only** post-iter-1 file that still cites `/raft/status` as if it works. ADR honesty floor (§8.15) was supposed to keep this clean.
- **What I'd do Monday at 09:00:** read ADR-0001 (the foundational decision) on Monday, type the curl, get HTTP 404, open ten tabs trying to find the right endpoint, conclude the entire architecture is unimplemented before I notice `restore-from-snapshot.md` quietly flagging the same gap. Wasted hour and a credibility hit on the ADR set.
- **Fix proposal:** rewrite the operator check to use the surfaces that DO exist: `kubectl exec configd-0 -- curl -sf http://localhost:8080/health/ready` for the structural assertion, plus a probe `PUT /v1/config/__probe__/leader-check` to read the `X-Leader-Hint` header (the same approach `control-plane-down.md:62-72` uses). Add an `> NOTE: A dedicated /admin/raft/status endpoint is tracked under PA-XXXX; until it ships the X-Leader-Hint header is the operator-visible signal.` Match the runbook honesty pattern.

## N-108 — `release.md` step 6 quietly assumes `/admin/raft/status` exists; no PA-XXXX TODO
- **Severity:** S2
- **Location:** `ops/runbooks/release.md:272-279`
- **Category:** missing-command (release-skeptic adjacent)
- **Evidence:** "Commit-index parity: every pod's `/admin/raft/status` reports the same `commit_index` ... `curl -sf http://localhost:8080/admin/raft/status | jq .commit_index`". Three other runbooks that reference the same endpoint (`control-plane-down.md:58-61,85-87`, `restore-from-snapshot.md:171-175,188-190,206-208`, `snapshot-install.md:68-73,82-83`) each carry the disciplined `<!-- TODO PA-XXXX: admin endpoint missing -->` HTML comment. `release.md` is the one runbook where the gap is silent. A new release engineer will copy-paste the loop and get HTTP 404 on every pod, then either silence the check or block the rollback.
- **What I'd do Monday at 09:00:** during a real rollback, run the parity loop, see four 404s in a row, panic that the cluster is wedged, escalate.
- **Fix proposal:** add the `<!-- TODO PA-XXXX: admin endpoint missing -->` comment block matching the other runbooks, and replace the parity check with what actually works today — a `GET /v1/config/__release_probe__` that returns `X-Config-Version` (`HttpApiServer.java:297`); compare versions across pods. Update the rollback success criteria to allow this approximation until the admin endpoint ships.

## N-109 — `control-plane-down.md` voter-health probe uses `app=configd-server`; StatefulSet labels pods `app=configd`
- **Severity:** S2
- **Location:** `ops/runbooks/control-plane-down.md:73`
- **Category:** misleading-command
- **Evidence:** "Check voter health: `kubectl get pods -l app=configd-server`". Compare against `deploy/kubernetes/configd-statefulset.yaml:30-31,40-41` which labels pods `app: configd`. Every other runbook (`disaster-recovery.md:171`, `release.md:215,257,270,283`, `restore-from-snapshot.md:104,117,204`) uses `-l app=configd`. control-plane-down.md is the outlier — and it is the runbook a paged on-call will hit first.
- **What I'd do Monday at 09:00:** at 03:00 on a `ConfigdControlPlaneAvailability` page, run the suggested command, get "No resources found in default namespace", conclude the voters are gone, declare a disaster (`disaster-recovery.md`), wipe PVCs.
- **Fix proposal:** change `kubectl get pods -l app=configd-server` to `kubectl -n configd get pods -l app=configd` (also missing the `-n configd` namespace). Grep the file for any other `configd-server` selector (none currently).

## N-110 — `snapshot-install.md` Related links cite `adr-0009-snapshot-format.md`; ADR-0009 is the GC ADR
- **Severity:** S3
- **Location:** `ops/runbooks/snapshot-install.md:132-133`
- **Category:** broken-link
- **Evidence:** "`docs/decisions/adr-0009-snapshot-format.md` — snapshot-format decision; `SnapshotConsistency` invariant lives here". Actual file at `docs/decisions/adr-0009-java21-zgc-runtime.md` is the GC strategy. There is no ADR named `snapshot-format`; `ls docs/decisions/ | grep snapshot` is empty. The `SnapshotConsistency` invariant referenced lives in `spec/SnapshotInstallSpec.tla`, which the same file already links one line above.
- **What I'd do Monday at 09:00:** click the link, find an unrelated GC ADR, doubt my own understanding of the runbook, escalate. iter-1 fixed three other broken-ADR cross-refs (N-010/011) — this one slipped.
- **Fix proposal:** delete the bullet (the spec link above already covers it), or write the ADR with a free number (`adr-0028-snapshot-on-disk-format.md`). Sweep `ops/runbooks/` once more for stale ADR numbers.

## N-111 — `ConfigdMetrics` registers `apply.seconds` but runbook step 1 references `configd_apply_total` (not registered)
- **Severity:** S2
- **Location:** `ops/runbooks/raft-saturation.md:35-37` vs `configd-observability/src/main/java/io/configd/observability/ConfigdMetrics.java:42`
- **Category:** undefined-metric (post-F5 residual)
- **Evidence:** Diagnosis step 1 says "Check apply throughput. `rate(configd_apply_total[1m])` — compare to ingress `rate(configd_write_commit_total[1m])`. If apply < ingress, queue grows." `ConfigdMetrics` declares `NAME_APPLY_SECONDS = "configd.apply.seconds"` (a histogram, exported as `configd_apply_seconds_*`) but no `configd.apply.total` counter. The runbook's diagnostic step is unrunnable as written.
- **What I'd do Monday at 09:00:** triage `ConfigdRaftPipelineSaturation`, run `rate(configd_apply_total[1m])` — empty series. Try `apply_seconds_count` (the histogram's count series), which is what was meant. Spend ten minutes on the translation while the apply queue keeps growing.
- **Fix proposal:** either rewrite the runbook step to use `rate(configd_apply_seconds_count[1m])` (which IS exported by F5), or register a `configd.apply` counter alongside `configd.apply.seconds` and increment it from the same call-site. The first option is cheaper; do that and add a one-liner explaining the histogram-count vs counter distinction so future readers don't repeat the confusion.

## N-112 — Dashboard cluster filter relies on `configd_build_info`; metric never registered
- **Severity:** S3
- **Location:** `ops/dashboards/configd-overview.json:11-19,30,58,77,90,102,114` — `configd-observability/src/main/java/io/configd/observability/ConfigdMetrics.java` does not register `configd.build.info`
- **Category:** undefined-metric / partial iter-1 close (extends N-019)
- **Evidence:** Dashboard template variable: `"query": "label_values(configd_build_info, cluster)"`. Every panel filters on `{cluster=\"$cluster\"}`. `Grep configd_build_info` over the source tree returns zero matches. iter-1 N-019 is officially closed (per F5 wire-up) but the wire-up did not include `configd_build_info`. New on-call imports the dashboard and gets an empty `cluster` dropdown — every panel is empty regardless of what the operator does at the Prometheus level.
- **What I'd do Monday at 09:00:** import the dashboard, see no data, assume Prometheus scrape is broken, escalate.
- **Fix proposal:** register a constant gauge `configd.build.info` in `ConfigdMetrics` that emits `1` with labels `{version, git_sha, cluster}` — `cluster` is read from a startup env var (`$CONFIGD_CLUSTER`, default `unknown`). Or document an "Operator setup required: scrape config must apply `cluster=<name>` external label" in `ops/dashboards/README.md` (which itself doesn't exist — create it).

## N-113 — `ChaosScenariosTest` class doc oversells: scenarios test the simulator, not Raft-on-the-simulator
- **Severity:** S2
- **Location:** `configd-testkit/src/test/java/io/configd/testkit/ChaosScenariosTest.java:18-36,80-87,147-152,243-252`
- **Category:** tests-the-mock
- **Evidence:** "exercises {@link SimulatedNetwork}'s chaos primitives and demonstrates the canonical adversarial scenarios called out in `docs/gap-closure.md`: snapshot-drop-Nth-chunk, slow-consumer death spiral, partition-heal cycle, slow-peer freeze (PA-4019 reproducer harness), TLS reload races (PA-4005 reproducer harness)." Every test in the file sends `String`/`Integer` payloads through `SimulatedNetwork` and asserts the simulator delivers/drops correctly. None of them stand a `RaftNode`, `ConfigStateMachine`, or actual `InstallSnapshot` payload on top — so PA-4019 / PA-4005 are not "reproducer harnesses" in the failure-of-the-system-under-test sense; they're fixture tests for the test fixture.
- **What I'd do Monday at 09:00:** read the class doc, believe `ChaosScenariosTest` is the regression guard for slow-consumer death spiral; ship a "fix" to `RaftNode` that doesn't change behaviour because the test never touches `RaftNode`.
- **Fix proposal:** either (a) downscope the doc — say the file tests `SimulatedNetwork`'s primitives that PA-4019 / PA-4005 fixes will use; the regressions themselves live in TODO file `RaftNodeChaosTest.java` — or (b) write the production-on-fixture tests the doc claims exist (a `RaftSimulationChaosTest` that drives `RaftNode` instances over `SimulatedNetwork` with the same chaos primitives, asserting Raft safety properties). The current state is doc-claim-vs-test-shape drift; one or the other has to move.

## N-114 — `SafeLog.SAFE_LOG_VALUE` regex is referenced by `isSafeForLog` but the field has been declared private and looks unused on first reading
- **Severity:** S3
- **Location:** `configd-observability/src/main/java/io/configd/observability/SafeLog.java:38,98`
- **Category:** misleading-naming / least-astonishment
- **Evidence:** Line 38 declares `private static final Pattern SAFE_LOG_VALUE = Pattern.compile("[a-zA-Z0-9._\\-/]{1,128}");`. The pattern is consumed only inside `isSafeForLog(String)` (line 98) — and `isSafeForLog` is itself the third utility on the class, after `redact` and `cardinalityGuard`. The pattern's role isn't documented in the field-level comment; new readers grepping for the field assume it's dead and either delete it or argue against it in PR review.
- **What I'd do Monday at 09:00:** open `SafeLog.java`, see `SAFE_LOG_VALUE` near the top, think "unused private field — IDE didn't warn me?", scroll down 60 lines to find the consumer. Slow. Also: the regex's `{1,128}` length cap is the same constant later checked separately in `rejectsExcessiveLength`, so the regex is the sole authority on length but reads as if `isSafeForLog` and the test enforce length independently — easy to drift them apart later.
- **Fix proposal:** add a one-line comment on the field: `/** Allow-list for {@link #isSafeForLog}: ASCII-printable identifier characters, max 128 chars. */`. Make the 128 a named `private static final int MAX_LOG_VALUE_LENGTH = 128` so the regex and the test reference the same constant.

---

## Honesty notes (iter-1 closures verified, not re-flagged)

- iter-1 N-001 (raftctl): every previous `raftctl` callsite in the runbooks is now `kubectl exec ... curl ...` or carries a `<!-- TODO PA-XXXX: admin endpoint missing -->` honest gap marker. Done.
- iter-1 N-007 (configd-bootstrap.yaml): file exists at `deploy/kubernetes/configd-bootstrap.yaml` per `docs/handoff.md` ledger. Done.
- iter-1 N-006/008 (restore scripts): `ops/scripts/restore-snapshot.sh` and `ops/scripts/restore-conformance-check.sh` exist; the snapshot script defaults to `--dry-run=true`, requires `--i-have-a-backup`. Done.
- iter-1 N-013 (kubectl confirmation guard): `disaster-recovery.md:166-170` now has a `read -r -p "Type the cluster name to confirm destructive PVC delete: "` block. Done.
- iter-1 N-010/011 (ADR cross-refs): control-plane-down.md cites ADR-0027, write-commit-latency.md cites ADR-0007 (deterministic-simulation-testing). Done.
- iter-1 §8.14 (8-section runbook skeleton): all 9 actionable runbooks under `ops/runbooks/` carry Symptoms/Impact/Operator-Setup/Diagnosis/Mitigation/Resolution/Rollback/Postmortem/Related/Do-not. Done.
- iter-1 §8.15 (ADR ## Verification): all 26 ADRs (now 27 with adr-0027-sign-or-fail-close.md) have a Verification section. The N-107 finding above is a content-honesty bug in one section, not a structural §8.15 violation.
- iter-1 P-001 (perf claim disclaimer): `docs/performance.md` cross-region rows now read `**MODELED, NOT MEASURED**` explicitly. Done.

The drift density is sharply down from iter-1 (22 findings → 14), and the new findings are concentrated in two areas: (1) the README → wiki entry path is broken (N-101..N-104, N-106 — onboarding regression), and (2) honest "TODO PA-XXXX admin endpoint missing" markers were applied to 9/10 callsites but not consistently (N-107, N-108). Tighten those two and iter-3 should be quiet at this lens.

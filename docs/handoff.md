# Handoff — Configd v0.1 GA Hardening

**Written:** 2026-04-19 at end of autonomous loop.
**Audience:** the human engineer picking this up to commit, review, and
either sign `docs/ga-approval.md` or push the remaining work into v0.2.

This document assumes the reader has not been in the loop. Everything
you need to act is here. Where this document and `docs/ga-review.md`
conflict, `ga-review.md` wins — it is the authoritative gate
accounting; this document is the execution aid.

---

## 1. Commit plan

The working tree has 20 modified + 50 untracked files. **Nothing is
committed yet.** Group into the 11 commits below, each independently
reviewable. `.claude/scheduled_tasks.lock` is session tooling state —
exclude from every commit. If your workflow prefers fewer commits,
groups (6) and (7), (9) and (10), (8) and (11) can each merge without
loss of clarity.

Run in order; each commit should pass `./mvnw -T 1C verify` (the
ones that only touch docs or YAML still pass because they don't change
compile output).

### Commit 1 — Inventory & audit ground truth (Phases 0–1)

```
git add docs/inventory.md docs/gap-closure.md docs/verification/inventory.md \
        docs/prod-audit.md docs/prod-audit-cluster-A.md \
        docs/prod-audit-cluster-B.md docs/prod-audit-cluster-C.md \
        docs/prod-audit-cluster-D.md docs/prod-audit-cluster-E.md \
        docs/prod-audit-cluster-F.md
```
Message: `docs: inventory + production audit findings (Phases 0-1)`

### Commit 2 — TLA+ spec closures (Phase 3)

```
git add spec/ConsensusSpec.tla spec/tlc-results.md \
        spec/ReadIndexSpec.tla spec/ReadIndexSpec.cfg \
        spec/SnapshotInstallSpec.tla spec/SnapshotInstallSpec.cfg \
        configd-consensus-core/src/test/java/io/configd/raft/ReadIndexLinearizabilityReplayerTest.java \
        configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java
```
Message: `spec: close F1/F2 — ReadIndex and SnapshotInstall specs with replayer tests`

### Commit 3 — Property-based test pyramid (Phase 4)

```
git add configd-config-store/src/test/java/io/configd/store/CommandCodecPropertyTest.java \
        configd-server/src/main/java/io/configd/server/RaftMessageCodec.java \
        configd-server/src/test/java/io/configd/server/RaftMessageCodecPropertyTest.java \
        configd-transport/src/test/java/io/configd/transport/FrameCodecPropertyTest.java
```
Message: `test: property + fuzz coverage for codecs and RaftMessageCodec bounds (S9)`

### Commit 4 — Chaos primitives + scenarios (Phase 5)

```
git add configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java \
        configd-testkit/src/test/java/io/configd/testkit/SimulatedNetworkTest.java \
        configd-testkit/src/test/java/io/configd/testkit/ChaosScenariosTest.java
```
Message: `testkit: chaos hooks (drop-nth, link-slowness, peer-freeze, tls-reload, partition-heal) + ChaosScenariosTest`

### Commit 5 — JMH benchmarks + perf baseline (Phase 6)

```
git add configd-testkit/pom.xml \
        configd-testkit/src/main/java/io/configd/bench/HistogramBenchmark.java \
        configd-testkit/src/main/java/io/configd/bench/SubscriptionMatchBenchmark.java \
        docs/perf-baseline.md
```
Message: `bench: SubscriptionMatch + Histogram JMH harnesses + measured baseline`

### Commit 6 — Supply-chain + sign-or-fail-close (Phase 7 code)

```
git add .github/workflows/ci.yml \
        .gitignore \
        .mvn/wrapper/maven-wrapper.properties \
        spotbugs-exclude.xml \
        configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java \
        configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java
```
Message: `security: SHA-pin toolchain, tighten SpotBugs excludes, fail-close signing (S3/B2-B5/B7/B11)`

### Commit 7 — ADRs 23-25 (Phase 7 decisions)

```
git add docs/decisions/adr-0023-multi-raft-sharding-deferred.md \
        docs/decisions/adr-0024-cross-dc-bridge-deferred.md \
        docs/decisions/adr-0025-on-call-rotation-required.md
```
Message: `docs: ADR 23-25 — multi-Raft, cross-DC, on-call rotation deferrals`

### Commit 8 — Observability code (Phase 8)

```
git add configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java \
        configd-observability/src/main/java/io/configd/observability/PrometheusExporter.java \
        configd-observability/src/main/java/io/configd/observability/SafeLog.java \
        configd-observability/src/test/java/io/configd/observability/PrometheusExporterTest.java \
        configd-observability/src/test/java/io/configd/observability/SafeLogTest.java
```
Message: `observability: VarHandle histogram min/max, Prometheus histogram type, SafeLog PII helper (O5/O6/O7, PA-5016)`

### Commit 9 — Ops surface (Phase 8 ops + ADR-0026)

```
git add ops/alerts/configd-slo-alerts.yaml \
        ops/dashboards/configd-overview.json \
        ops/runbooks/README.md \
        ops/runbooks/write-commit-latency.md \
        ops/runbooks/edge-read-latency.md \
        ops/runbooks/propagation-delay.md \
        ops/runbooks/control-plane-down.md \
        ops/runbooks/raft-saturation.md \
        ops/runbooks/snapshot-install.md \
        docs/decisions/adr-0026-opentelemetry-interop-stub.md
```
Message: `ops: SLO alerts + Grafana dashboard + runbooks + ADR-0026 OTel bridge`

### Commit 10 — Release engineering (Phase 9)

```
git add .github/workflows/release.yml \
        docker/Dockerfile.build \
        docker/Dockerfile.runtime \
        deploy/kubernetes/configd-statefulset.yaml \
        ops/runbooks/release.md \
        RELEASE_NOTES_TEMPLATE.md
```
Message: `release: Cosign + SLSA workflow, digest-pinned base images, PodSecurity-restricted manifest (B6/B8/B9/B10, PA-6012)`

### Commit 11 — DR runbooks + shadow harness + GA review (Phases 10-11)

```
git add perf/soak-72h.sh perf/burn-7d.sh perf/longevity-30d.sh perf/shadow-14d.sh \
        ops/runbooks/disaster-recovery.md \
        ops/runbooks/restore-from-snapshot.md \
        ops/runbooks/runbook-conformance-template.md \
        ops/dr-drills/README.md \
        docs/ga-review.md \
        docs/handoff.md \
        docs/progress.md
```
Message: `dr+ga: shadow/soak/burn/longevity stubs, DR runbooks, GA review (no sign-off)`

### Files intentionally excluded from the commit plan

- `.claude/scheduled_tasks.lock` — transient tooling state.
- Anything under `target/`, `perf/results/`, or `spec/states/` — build
  output, already covered by `.gitignore` (introduced in Commit 6).

---

## 2. Pre-GA checklist — fully specified

Eight steps. Each one converts one or more YELLOW rows in
`docs/ga-review.md` to GREEN. Do not sign `ga-approval.md` until the
full checklist passes *or* a non-passing row is explicitly accepted as
a residual for v0.1.

### Step 1 — Real 72-h soak on production-shaped cluster

- **Owner:** platform SRE (you).
- **Command:**
  ```sh
  ./perf/soak-72h.sh --duration=$((72*3600)) --seed=42 \
    --out=perf/results/soak-prod-$(date -u +%Y%m%dT%H%M%SZ)
  ```
  Prerequisite: a 5-node cluster per `deploy/kubernetes/`. The harness
  today only honours the duration contract; you must wire the write
  loop / read loop / RSS sampler described in the script's header
  comment block before running.
- **Expected duration:** 72 h wall time.
- **Pass/fail criteria:**
  - `result.txt` shows `measured_elapsed_sec >= 259200`.
  - Write commit p99 stays under 150 ms throughout.
  - Edge read p99 stays under 1 ms throughout.
  - Propagation p99 stays under 500 ms throughout.
  - RSS grows by <10 % from t+15 min to t+end.
  - Zero leader churn after warmup window.
- **Evidence lands at:** `perf/results/soak-prod-<UTC>/result.txt`.
- **Flips:** GA row **C1 — 72-h soak** from YELLOW → GREEN.

### Step 2 — Real 14-day shadow-traffic run

- **Owner:** platform SRE + operator (needs prod traffic mirror).
- **Command:** `./perf/shadow-14d.sh --duration=$((14*24*3600))`
  against two real clusters, one running the previous GA build and one
  running v0.1. Production traffic must be mirrored via the operator's
  gateway mirroring proxy to both clusters.
- **Note:** v0.1 is the first GA, so there is no previous build to
  shadow against. Resolve this either by:
  - Running v0.1 against a canary slice of production traffic
    (accepted as a "self-shadow" for first release), **or**
  - Explicitly dropping C4 to a documented residual and deferring the
    real shadow protocol to v0.2's first update. The ga-review table
    makes this deferral accounting possible.
- **Expected duration:** 14 days wall time.
- **Pass/fail criteria:** every read response byte-identical between
  control and candidate; candidate p99 ≤ control p99 × 1.05; zero new
  ERROR log classes in candidate vs. control.
- **Evidence lands at:** `perf/results/shadow-prod-<UTC>/result.txt`.
- **Flips:** GA row **C4 — 14-day shadow** from YELLOW → GREEN (or
  into a documented v0.2 residual).

### Step 3 — Restore-from-snapshot drill

- **Owner:** on-call rotation (per ADR-0025).
- **Procedure:** follow `ops/runbooks/restore-from-snapshot.md`
  step-by-step against a staging cluster seeded with a known data set.
- **Expected duration:** ~30 minutes wall time (operator SLA).
- **Pass/fail criteria:** runbook executable from documented steps
  only, no undocumented commands; recovered cluster passes
  `InvariantMonitor.assertAll()`; every key in the snapshot manifest
  reads back byte-identical on the restored cluster.
- **Evidence lands at:**
  `ops/dr-drills/results/restore-from-snapshot-<UTC>/result.txt` per
  the format in
  `ops/runbooks/runbook-conformance-template.md`.
- **Flips:** GA row **Runbook conformance — restore** from YELLOW →
  GREEN.

### Step 4 — Leader-loss recovery drill

- **Owner:** on-call rotation.
- **Procedure:** force-kill the current Raft leader on a staging
  cluster; follow `ops/runbooks/control-plane-down.md` through to
  write resumption.
- **Expected duration:** <5 minutes wall time.
- **Pass/fail criteria:** new leader elected under 10 s; write commit
  p99 recovers within 30 s of election completion; zero committed
  writes lost per Raft log comparison.
- **Evidence lands at:**
  `ops/dr-drills/results/control-plane-down-<UTC>/result.txt`.
- **Flips:** GA row **Runbook conformance — leader loss** from YELLOW
  → GREEN.

### Step 5 — Operator confirms on-call rotation

- **Owner:** operator lead (organisational, not technical).
- **Procedure:** write an attestation (email, PR comment, or
  dedicated file `ops/on-call-rotation.md`) naming:
  - Paging service wired to `ops/alerts/configd-slo-alerts.yaml`
    annotations.
  - Rotation schedule covering 24×7.
  - Escalation path per severity (page vs. warn).
  - Incident-commander pool for disaster declarations.
- **Expected duration:** one rotation-cycle review window (typically
  2 weeks).
- **Pass/fail criteria:** all four items filled, operator lead's
  signature.
- **Evidence lands at:** `ops/on-call-rotation.md` or equivalent
  operator document.
- **Flips:** GA row **R-12 — on-call rotation** from YELLOW → GREEN.

### Step 6 — Decision on S1/S2/S4/S6/S7/S8 RED items

- **Owner:** engineering lead + security lead.
- **Procedure:** for each RED row in the Red-gate register
  (section 4 below), decide:
  - Implement before GA (pushes GA date out), **or**
  - Accept residual risk for v0.1, documented in a new
    `docs/decisions/adr-0027-v0.1-accepted-residuals.md` explicitly
    enumerating each accepted RED and its compensating control.
- **Expected duration:** half-day meeting + ADR review.
- **Pass/fail criteria:** every RED has an explicit disposition —
  none left in limbo.
- **Evidence lands at:** `docs/decisions/adr-0027-…` (if created) or
  `docs/ga-approval.md` residuals section.
- **Flips:** GA rows **S1, S2, S4, S6, S7, S8** from RED to one of
  {GREEN via implementation, YELLOW via accepted-residual ADR}.

### Step 7 — End-to-end release dry-run

- **Owner:** release engineer.
- **Procedure:**
  1. Cut `v0.1.0-rc.1` tag on a fork (not main) to exercise
     `.github/workflows/release.yml` without affecting production.
  2. Verify the workflow completes: image built, Cosign signature
     present, SLSA attestation present, SBOM attached to the GH
     release.
  3. From an unrelated environment, run the verify commands in
     `ops/runbooks/release.md` §Verification.
  4. Deploy the verified image to a staging cluster; confirm the
     StatefulSet comes up healthy.
- **Expected duration:** 1–2 hours.
- **Pass/fail criteria:** all four sub-steps green; verify commands
  return success; staging cluster passes readiness.
- **Evidence lands at:** release URL on the fork + screenshot or
  terminal log of verify commands in `docs/ga-approval.md`.
- **Flips:** GA row **B6/PA-6012 release pipeline** from "landed, not
  exercised" to "exercised end-to-end."

### Step 8 — Independent review of formal specs vs. implementation

- **Owner:** a Java engineer who did not author the TLA+ specs.
- **Procedure:** read `spec/ConsensusSpec.tla`,
  `spec/ReadIndexSpec.tla`, `spec/SnapshotInstallSpec.tla` end-to-end.
  For each invariant stated in the spec, find the corresponding
  assertion in the Java code and confirm it matches.
- **Expected duration:** 1 day.
- **Pass/fail criteria:** independent reviewer signs off; any
  divergence they find becomes a bug ticket filed before GA sign.
- **Evidence lands at:** review notes in `docs/ga-approval.md`
  "Formal-spec conformance" section.
- **Flips:** gives the approver evidence that F1/F2 GREEN is not
  just "TLC passes" but "TLC passes *and* the Java matches the
  spec."

---

## 3. Yellow-gate closure plan

Every YELLOW row in `docs/ga-review.md`, with the specific action that
flips it. GA-blocking rows are called out explicitly; everything else
can ship YELLOW and close during burn-in.

| Gate | What flips it | Owner | Calendar time | Blocks GA? |
|------|---------------|-------|---------------|------------|
| Phase 7 (overall) | S1/S2/S4-S8 dispositioned (Step 6) | eng + sec lead | 1 day | No — already-landed items are GREEN; residual disposition required |
| Phase 8 (overall) | O5 real fix OR acceptance ADR; O8 accepted per ADR-0026 (already done) | perf eng | 1 sprint | No |
| Phase 10 (overall) | Steps 3 + 4 drills executed | on-call | 2 days real + scheduling | No — runbooks landed |
| Phase 11 (overall) | This doc + approver signs `ga-approval.md` | approver | 1 day | **Yes — by definition** |
| C1 — 72-h soak | Step 1 runs to completion with pass criteria | SRE | 72 h + setup | No — signal strong enough if other gates green |
| C2 — 7-day burn | run `perf/burn-7d.sh` at full duration; pass burn-rate criteria | SRE | 7 days | No |
| C3 — 30-day longevity | run `perf/longevity-30d.sh` at full duration | SRE | 30 days | No — burn-in activity, not pre-GA |
| C4 — 14-day shadow | Step 2 (or accept self-shadow for first GA) | SRE + operator | 14 days | No — ADR-0027 can accept self-shadow |
| R-12 — on-call | Step 5 attestation | operator | 2 weeks review | **Yes** — paging a GA system without a rotation is undefined |
| O5 — histogram cursor | per-thread cursor striping + rebench | perf eng | 3 days | No — perf regression, not correctness |
| O8 — OTel interop | already accepted per ADR-0026 | — | — | No (by-design YELLOW) |
| B12 — `--enable-preview` ADR | draft ADR and commit | eng | 2 hours | No |
| S5 — TLS reload prod path | rebuild SSLServerSocket on reload (PA-4005) | sec eng | 1 week | No — negative path covered in chaos |
| PA-5018 — unit alignment | operator picks ns or seconds for histograms; align `le=` in alerts file | operator | 1 day | No — silent-no-series bug is recoverable, but SHOULD be fixed pre-GA (see known-holes §5) |
| Runbook drift (`docs/runbooks/`) | delete OR demote to v0.2 design notes (see Step 6 of this checklist) | eng | 1 hour | No — confusing but non-breaking |

**GA-blocking yellows:** Phase 11 (approver must sign), R-12 (on-call
rotation must exist). Everything else is YELLOW-shippable.

---

## 4. Red-gate deferral register

Each RED row with the v0.2 target close date, the v0.1 risk, and
today's compensating control.

| ID | Why it is v0.2 | Risk in v0.1 | Compensating control | v0.2 target |
|----|----------------|--------------|----------------------|-------------|
| S1 | DeltaVerifier is a new component that formally decouples delta validity from commit signing; substantial design + review effort | Malicious or buggy delta could pass signing (edge propagates bad HAMT delta) | Commit-level Ed25519 signing (S3) + state-machine fail-close on verify failure means the receiving node can refuse to apply | 2026-Q3 |
| S2 | SubscriptionAuthorizer needs per-prefix ACL model + integration with S7 AclService | Any authenticated client can subscribe to any prefix | Operator-procured gateway-level auth scopes subscriptions to coarse tenants | 2026-Q3 |
| S4 | Requires cert-subject → peer-id binding at handshake time (PA-4007) | TLS-authenticated peer could impersonate another peer if handshake is replayed | mTLS still prevents unknown-party attack; impersonation within authorised fleet is detectable via audit logs when S8 lands | 2026-Q3 |
| S6 | Requires startup-time check (PA-4004) | Operator can inadvertently run without TLS and still accept auth tokens | Runbook + deploy manifest force TLS; no cleartext port exposed in reference K8s manifest | 2026-Q2 |
| S7 | Root-principal override (PA-4011) requires AclService redesign | No emergency-access path if ACL store is itself corrupted | Operator can bypass via kubectl exec + direct state-machine repair, documented in disaster-recovery.md §Reset | 2026-Q3 |
| S8 | AuditLogger (R-13 / PA-4001) blocked on F3 (HLC-aware time source — RED itself) | No signed audit trail; forensics depend on operator log retention | Application logs + Raft log + external log collector; disaster-recovery.md §Data-loss calls out the gap | 2026-Q3 (co-lands with F3) |
| F3 | HLC-aware time source requires cross-module refactor of timekeeping | Logical-time-based invariants use wall-clock with skew tolerance; subtle ordering bugs possible under clock skew | Spec-level invariants proven under bounded skew; operator NTP discipline | 2026-Q3 |
| Process-death / disk-full / fsync-stall chaos | Jepsen-style data-path harness is its own project-scale effort | Unknown behaviour under these faults in a real cluster | TLA+ model proves safety under these faults; unit tests cover impl; operator drills (Step 3 + 4) exercise recovery | 2026-Q4 |

---

## 5. Known holes a principal SRE would find in 30 minutes

These are not on the YELLOW or RED lists because they were not
surfaced by the audit-driven gate accounting. They are real. Fix or
acknowledge each.

1. **Release workflow never exercised end-to-end.**
   `.github/workflows/release.yml` parses as YAML and the steps are
   syntactically right, but no real tag push has ever validated OIDC
   token shape, Cosign identity match, SLSA attestation subject
   format, or GHCR write permissions. Pre-GA checklist Step 7 covers
   this; do not sign GA without running it.

2. **Cosign identity regex over-matches.** In `release.yml`, the
   verify example uses
   `--certificate-identity-regexp 'https://github.com/<owner>/<repo>/.github/workflows/release.yml@.*'`.
   The `@.*` accepts any ref — including a malicious tag crafted on
   a fork that a compromised contributor pushes. Narrow to
   `@refs/tags/v[0-9]+\.[0-9]+\.[0-9]+$` before GA.

3. **Bucket-ladder unit mismatch is silent.**
   `PrometheusExporter.DEFAULT_BUCKET_BOUNDS` is integer-valued
   (1 → 10⁹), but `ops/alerts/configd-slo-alerts.yaml` queries
   `le="0.150"` (seconds). If the application records nanoseconds —
   as the naming convention suggests — the alert returns no series,
   and Prometheus treats "no series" as the rule condition not
   holding. **The alert will never fire.** This is flagged as
   PA-5018 YELLOW but is one integer-literal mismatch away from
   making every SLO alert in this ship dead. Fix before real
   traffic.

4. **`image: configd:GIT_SHA` literal substitution in K8s manifest is
   fragile.** `ops/runbooks/release.md` tells operators to `sed -i`
   the placeholder at deploy time. Any operator applying the
   manifest without running the sed ends up in `ErrImagePull`.
   Preferred: use Kustomize image transformer or Helm values. Out of
   scope for this session but worth raising before GA.

5. **O5's "bottleneck moved to cursor" claim rests on one re-bench.**
   The progress log reports 52 → 49 ops/μs and concludes the
   `AtomicLong.getAndIncrement` is now the hot spot. I ran one
   benchmark, not a JFR profile or cache-miss sampler. The claim
   could be wrong — the ceiling could be GC, biased locking, false
   sharing on the buffer array, etc. If v0.2 is going to stripe the
   cursor, start with `async-profiler` not the current hypothesis.

6. **`docs/runbooks/` still ships in the tree.** The GA review flags
   this as drift but leaves it. A reviewer looking at the repo for
   the first time will find two runbook directories with no
   top-level pointer to which is authoritative. Add a one-line
   `docs/runbooks/README.md` pointing to `ops/runbooks/` or delete
   `docs/runbooks/` — either before committing is ideal. Deferred
   because it requires a judgement call (are those files worth
   salvaging as v0.2 design notes?).

7. **No `CHANGELOG.md`** despite `RELEASE_NOTES_TEMPLATE.md`
   expecting "what changed since vX.Y.(Z-1)". The template is
   unusable for v0.1 because there is no previous version; add a
   "Initial GA — see `docs/progress.md` for scope" note to the
   first release and create a `CHANGELOG.md` structure for v0.2.

8. **`SafeLog.cardinalityGuard` uses `String.hashCode()`.** Java's
   `String.hashCode` is algorithmically stable within a JVM
   version and is the same on HotSpot and Corretto today, but is
   not guaranteed across JVM changes. If a future JVM upgrade
   subtly changes the hash distribution, every `bucket-NN` label
   silently rebuckets and your historical metrics lose continuity.
   For a stable bucketing function, use a keyed non-crypto hash
   (e.g. xxHash) with a fixed seed. Deferred but worth tracking.

9. **`/metrics` endpoint has no documented protection.** The K8s
   NetworkPolicy allows API traffic from any pod in the cluster.
   Scraping the Prometheus endpoint typically wants Prometheus-only
   auth or a separate cluster-local port. Today, any pod can scrape.
   Likely fine for most operators; loud for the paranoid ones.

10. **Nothing validates that progress.md, ga-review.md, and handoff.md
    agree.** Three documents, hand-written, claim the same truth.
    The next time a gate moves, the drift starts. A short CI check
    parsing the ga-review table and comparing to `progress.md` phase
    summaries would catch this early. v0.2 housekeeping.

---

## 6. Approver's minimum-evidence set

Before signing `docs/ga-approval.md`, the approver MUST have seen at
minimum:

1. **A green run of the full test suite on the commit being approved.**
   `./mvnw -T 1C test` → `BUILD SUCCESS`. Capture the line count
   (expected ≥ 20,149) and paste it into the approval doc.
2. **A successful end-to-end release dry-run on a fork** (Step 7 of
   the pre-GA checklist). Link the GHCR image digest, the `cosign
   verify` success, and the `gh attestation verify` success.
3. **An operator attestation that on-call rotation exists** (Step 5).
   A real operator's signature, not a stub.
4. **A disposition for every RED row** (Step 6). Either an
   implementation commit or `adr-0027-v0.1-accepted-residuals.md`.
5. **At least one real drill executed** (Step 3 *or* Step 4). One is
   enough; both is preferred; zero is not.

Everything else in the pre-GA checklist is *preferred but not
required*. The approval doc should explicitly state which preferred
items were skipped and why.

The bar is **"can this run in production without surprise to the
operator"** — not "everything is perfect." The YELLOW and RED lists
document what is not perfect; the approver's job is to confirm
nothing dangerous is hiding outside those lists and that the
yellow/red items are acceptable.

---

## 7. Related

- `docs/ga-review.md` — authoritative gate accounting
- `docs/progress.md` — phase-by-phase narrative
- `docs/gap-closure.md` — original closure plan (Phase 2)
- `docs/decisions/` — ADRs, including the four new ones (23-26)
  landed in this pass
- `ops/runbooks/runbook-conformance-template.md` — drill result
  format
- `.github/workflows/release.yml` — the pipeline Step 7 exercises

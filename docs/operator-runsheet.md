# Operator Runsheet — Configd v0.1 GA

**Audience:** the platform SRE / release engineer / on-call lead who
owns the seven calendar-bounded harnesses that flip YELLOW gates to
GREEN in `docs/ga-review.md`.

**Promise of this file:** you can execute the whole GA hardening cycle
top-to-bottom from this document alone. Every command, duration,
hardware shape, pass criterion, fail criterion, evidence path, and
GA-row mapping is right here. You should not need to open
`docs/handoff.md`, `docs/ga-review.md`, or any runbook to *plan* —
only to execute the runbook step text once you reach steps 1 and 2.

**Authored:** 2026-04-22 by the autonomous self-healing loop
(Opus 4.7), as Type C **artifact preparation** following iter-2
termination per §5. Type C closure (signing `docs/ga-approval.md`)
remains a human gate per the §4.7 honesty invariant.

---

## 0. Read this first

- **Order:** harnesses below are ordered **shortest-first**. Run them
  in order unless the dependency graph in §9 says otherwise.
- **Two execution tracks:** drills (steps 1–2) execute on the
  **staging** cluster; the four perf harnesses (steps 3–6) execute on
  a separate **production-shaped 5-node** cluster. The on-call
  bootstrap (step 7) is organisational and runs in parallel to
  everything else.
- **Honesty invariant:** record measured elapsed seconds. Do not round
  up. Do not mark a row GREEN until the result file exists at the
  named path AND its numbers meet the pass criterion below.
- **One harness per cluster at a time.** The perf harnesses share
  hardware and contend for the same 5-node footprint; serialize them.
- **Each harness produces exactly one `result.txt`** at the path named
  in its "Evidence lands at" line. That path is what the approver
  pastes into `docs/ga-approval.md` §6.5 / §4.

---

## 1. Step 1 — Leader-loss recovery drill (≤ 5 min)

- **Purpose:** prove the leader-loss runbook is executable as written
  and the cluster recovers within SLA.
- **Hardware:** staging cluster matching `deploy/kubernetes/`
  (5 nodes minimum, 3 voters; can reuse perf cluster between perf
  runs).
- **Procedure:** force-kill the current Raft leader, then follow
  `ops/runbooks/control-plane-down.md` end-to-end through write
  resumption. Identify the leader via:
  ```sh
  kubectl exec -n configd configd-0 -- curl -s localhost:8080/raft/status | jq .leader
  kubectl delete pod -n configd <leader-pod-name> --grace-period=0 --force
  ```
- **Required duration:** **< 5 minutes wall time** (one drill).
- **Pass — exact numbers:**
  - New leader elected in **< 10 s** from kill.
  - Write-commit p99 returns under 150 ms within **< 30 s** of new
    leader's first heartbeat.
  - **Zero** committed writes lost (Raft log comparison: highest
    committed index on every surviving node ≥ pre-kill highest
    committed index).
- **Fail:** any of: no leader after 10 s, p99 still elevated after
  30 s, any committed-index regression, runbook step that requires an
  undocumented command. Any one of these is a fail; do not partial-
  credit.
- **Evidence lands at:**
  `ops/dr-drills/results/control-plane-down-<UTC>/result.txt` per
  `ops/runbooks/runbook-conformance-template.md`.
- **Flips:** GA row **Runbook conformance — leader loss** YELLOW →
  GREEN.

## 2. Step 2 — Restore-from-snapshot drill (~ 30 min)

- **Purpose:** prove the restore runbook works on a real cluster from
  a real snapshot, not just from in-memory test fixtures.
- **Hardware:** staging cluster matching `deploy/kubernetes/`,
  pre-seeded with a known data set whose key list is stored in the
  snapshot manifest (`snapshot-manifest.json` produced by `configd
  snapshot create`).
- **Procedure:** follow `ops/runbooks/restore-from-snapshot.md`
  step-by-step. Use no commands not in the runbook — that is part of
  the test.
- **Required duration:** **~30 minutes wall time** (operator SLA).
- **Pass — exact numbers:**
  - Runbook executable from documented steps only — operator records
    "0 undocumented commands" on the result file.
  - `InvariantMonitor.assertAll()` returns success (one log line:
    `INVARIANTS OK`) on the restored cluster.
  - **100 %** of keys in the snapshot manifest read back
    byte-identical from the restored cluster (`diff
    expected-keys.txt restored-keys.txt` is empty).
- **Fail:** any undocumented command needed; any invariant assertion
  failure; any key with non-identical bytes; any restore exceeding
  60 minutes.
- **Evidence lands at:**
  `ops/dr-drills/results/restore-from-snapshot-<UTC>/result.txt`.
- **Flips:** GA row **Runbook conformance — restore** YELLOW → GREEN.

## 3. Step 3 — 72-hour soak (259 200 s)

- **Purpose:** baseline steady-state perf and detect short-window
  regressions (memory, p99, leader churn).
- **Command:**
  ```sh
  ./perf/soak-72h.sh --duration=$((72*3600)) --seed=42 \
    --out=perf/results/soak-prod-$(date -u +%Y%m%dT%H%M%SZ)
  ```
  **Prerequisite:** the harness today honours the duration contract
  only; you must wire the write/read/RSS-sampler loops described in
  the script's header before the run.
- **Hardware:** production-shaped 5-node cluster per
  `deploy/kubernetes/`. Dedicated; no co-tenants for the duration.
- **Required duration:** **259 200 s wall time** (3 days).
- **Pass — exact numbers (from `result.txt`):**
  - `measured_elapsed_sec >= 259200`
  - Write-commit p99 **< 150 ms** every sampling window.
  - Edge read p99 **< 1 ms** every sampling window.
  - Propagation p99 **< 500 ms** every sampling window.
  - RSS growth t+15min → t+end **< 10 %**.
  - Leader-churn count after warm-up window: **0**.
- **Fail:** any p99 breach in any window; RSS growth ≥ 10 %; any
  leader change post-warmup; `measured_elapsed_sec < 259200`.
- **Evidence lands at:**
  `perf/results/soak-prod-<UTC>/result.txt`.
- **Flips:** GA row **C1 — 72-h soak** YELLOW → GREEN.

## 4. Step 4 — 7-day burn (604 800 s)

- **Purpose:** surface accumulating drift at sustained 80%-capacity
  with periodic chaos: fd leaks, cache fragmentation, log-segment
  fragmentation, follower metric staleness.
- **Command:**
  ```sh
  ./perf/burn-7d.sh --duration=$((7*24*3600)) --seed=43 \
    --out=perf/results/burn-prod-$(date -u +%Y%m%dT%H%M%SZ)
  ```
  Chaos schedule per script header: 1 leader kill / 12 h, 1
  partition-then-heal / 24 h (≤ 30 s), 1 disk-fsync stall / 48 h
  (≤ 5 s), 1 TLS hot-reload / 36 h.
- **Hardware:** same 5-node prod-shaped cluster. **Must run AFTER
  step 3** completes — they share hardware and step 3 establishes
  the steady-state baseline this run drifts away from.
- **Required duration:** **604 800 s wall time** (7 days).
- **Pass — exact numbers:**
  - `measured_elapsed_sec >= 604800`.
  - Open file descriptors at end ≤ FD count at t+1h × **1.05**.
  - Resident-set size at end ≤ RSS at t+1h × **1.10**.
  - Day-7 write-commit p99 ≤ Day-1 write-commit p99 × **1.10**.
  - All chaos events recover within their per-event SLA stated
    above (each emits a recovery-time line in `result.txt`).
- **Fail:** FD growth > 5 %; RSS growth > 10 %; p99 drift > 10 %;
  any chaos event whose recovery exceeds its SLA.
- **Evidence lands at:**
  `perf/results/burn-prod-<UTC>/result.txt`.
- **Flips:** GA row **C2 — 7-day burn** YELLOW → GREEN.

## 5. Step 5 — 14-day shadow traffic (1 209 600 s)

- **Purpose:** verify v0.1 produces byte-identical reads under real
  production traffic against a control build. v0.1 is the first GA,
  so see "Resolution" below for the self-shadow protocol.
- **Command:**
  ```sh
  ./perf/shadow-14d.sh --duration=$((14*24*3600)) \
    --out=perf/results/shadow-prod-$(date -u +%Y%m%dT%H%M%SZ)
  ```
  Production traffic mirrored via the operator's gateway mirroring
  proxy to **two** clusters: the previous-GA control and the
  candidate v0.1.
- **Hardware:** **two** 5-node prod-shaped clusters running
  concurrently. **Independent of step 3 / 4 hardware** — this can
  run in parallel to the perf track if a second cluster pair is
  available.
- **Required duration:** **1 209 600 s wall time** (14 days).
- **Resolution for v0.1 first-release case:** there is no previous
  build to shadow against. Resolve **before** starting the run, by:
  - **Option A** — run v0.1 against a canary slice of production
    traffic; explicitly accept "self-shadow" in
    `docs/decisions/adr-0027-v0.1-accepted-residuals.md`. Pass
    criterion below applies to the canary slice.
  - **Option B** — drop C4 to a YELLOW residual in ADR-0027 and
    defer real shadow to v0.2's first update. Skip this step.
  Pick one before starting; both are valid GA dispositions.
- **Pass — exact numbers:**
  - Every read response **byte-identical** between control and
    candidate (diff count = **0**).
  - Candidate p99 ≤ control p99 × **1.05**.
  - **Zero** new ERROR log classes in candidate vs. control
    (compared by error-class fingerprint, not by count).
- **Fail:** any byte-divergence; p99 ratio > 1.05; any new error
  class.
- **Evidence lands at:**
  `perf/results/shadow-prod-<UTC>/result.txt`.
- **Flips:** GA row **C4 — 14-day shadow** YELLOW → GREEN
  (Option A) OR YELLOW-via-accepted-residual (Option B documented
  in ADR-0027).

## 6. Step 6 — 30-day longevity (2 592 000 s)

- **Purpose:** prove the snapshot subsystem is healthy across many
  install/truncate cycles; detect WAL segment growth, snapshot
  install-latency drift, disk high-watermark.
- **Command:**
  ```sh
  ./perf/longevity-30d.sh --duration=$((30*24*3600)) --seed=44 \
    --out=perf/results/longevity-prod-$(date -u +%Y%m%dT%H%M%SZ)
  ```
- **Hardware:** same 5-node prod-shaped cluster as steps 3 / 4.
  **Must run AFTER step 4** — same hardware contention; longevity
  is the longest single run, so do not start it until burn has
  passed.
- **Required duration:** **2 592 000 s wall time** (30 days).
- **Pass — exact numbers:**
  - `measured_elapsed_sec >= 2592000`.
  - Daily snapshot install latency p99 stays within **±10 %** of
    Day-1 baseline.
  - WAL segment count at end ≤ Day-1 segment count × **1.20**
    (segments grow but are truncated under cap).
  - Disk-space high-watermark stays under **70 %** of available
    capacity throughout.
  - Day-30 read p99 within **±10 %** of Day-1 read p99.
- **Fail:** snapshot p99 drift > 10 %; WAL growth > 20 %; disk
  watermark ≥ 70 %; read p99 drift > 10 %.
- **Evidence lands at:**
  `perf/results/longevity-prod-<UTC>/result.txt`.
- **Flips:** GA row **C3 — 30-day longevity** YELLOW → GREEN.

## 7. Step 7 — On-call rotation bootstrap (one rotation cycle)

- **Purpose:** establish that a real human can be paged for a real
  incident within an SLA, with a defined escalation path.
- **Owner:** operator lead — organisational, not technical.
- **Procedure:** write an attestation (PR commit at
  `ops/on-call-rotation.md` is the canonical form) naming:
  1. Paging service wired to
     `ops/alerts/configd-slo-alerts.yaml` annotations.
  2. Rotation schedule covering 24×7.
  3. Escalation path per severity (page vs. warn).
  4. Incident-commander pool for disaster declarations.
- **Required duration:** **one rotation cycle** — typically 2 weeks
  to validate that hand-offs work and at least one synthetic page
  has been acknowledged by each rostered human.
- **Pass — exact numbers:**
  - All four items above filled in `ops/on-call-rotation.md`.
  - Operator-lead's signature line filled.
  - At least **1 synthetic page acknowledged per rostered human**
    within the rotation cycle (logged in the same file).
- **Fail:** any of the four items missing; any rostered human who
  did not acknowledge a synthetic page; signature line blank.
- **Evidence lands at:** `ops/on-call-rotation.md` (or equivalent
  named operator document, committed to the repo).
- **Flips:** GA row **R-12 — on-call rotation** YELLOW → GREEN.
- **Runs in parallel to all other steps.** Start it on day 0 of GA
  hardening, not day 30.

---

## 8. Other GA gates not in this runsheet

These exist in `docs/handoff.md` §2 but are not calendar-bounded
harnesses; they are listed here so you do not forget them when
collecting evidence for `docs/ga-approval.md`:

| Step | What | Owner | Where it lives |
|------|------|-------|----------------|
| §2 Step 6 | Disposition for every RED row | eng + sec lead | `docs/decisions/adr-0027-v0.1-accepted-residuals.md` |
| §2 Step 7 | End-to-end release dry-run on a fork | release engineer | release URL on fork + `cosign verify` / `gh attestation verify` output pasted into `docs/ga-approval.md` §6.2 |
| §2 Step 8 | Independent formal-spec ↔ implementation review | Java engineer who did NOT author the TLA+ specs | review notes pasted into `docs/ga-approval.md` |

---

## 9. Dependency graph

```
                          day 0
                            │
            ┌───────────────┼─────────────────────┐
            │               │                     │
            ▼               ▼                     ▼
   step 7 on-call      step 1 leader-loss   step 5 shadow
   bootstrap           drill (≤ 5 min)      (independent
   (runs in            ─┐                    cluster pair,
   parallel for         │                    14 days; can
   ~2 weeks)            ▼                    start any time)
                   step 2 restore drill
                   (~ 30 min)
                        │
                        ▼
                   step 3 soak-72h
                   (3 days, prod cluster)
                        │
                        ▼
                   step 4 burn-7d
                   (7 days, same cluster)
                        │
                        ▼
                   step 6 longevity-30d
                   (30 days, same cluster)
```

**Read this as:**

- **Serial chain** (steps 1 → 2 → 3 → 4 → 6): drills then perf
  harnesses, all sharing the prod-shaped 5-node cluster. Total
  serialised wall-clock minimum: ~40 days + chaos margin.
- **Parallel track A** (step 5 — shadow): needs its **own** cluster
  pair plus a traffic mirror. Independent of the serial chain;
  start whenever the second cluster pair and mirror are available.
  14 days wall.
- **Parallel track B** (step 7 — on-call): organisational, no
  hardware dependency. **Start on day 0** of GA hardening so it
  can finish concurrently with the serial chain.
- **Drills (steps 1–2)** technically only need the staging cluster
  and could run before the perf hardware is provisioned; sequencing
  them at the head of the chain just makes it tidy.
- **Critical path to GA-able evidence set:** `max(serial_chain,
  shadow_track, on_call_track)` ≈ **40 days** if everything passes
  first time. Budget **45–60 days** for re-runs after a fail.

---

## 10. After all seven harnesses pass

1. Confirm every result file from §1–§7 exists at the named path
   AND meets its pass criterion. If any is missing or fails, the
   corresponding GA row stays YELLOW; do not proceed.
2. Open `docs/ga-approval.md` (the unsigned template authored
   alongside this runsheet on 2026-04-22).
3. Fill §3 with the commit SHA you intend to promote.
4. Fill §6.1–§6.5 minimum-evidence checkboxes, each with the
   matching artifact path / signature.
5. Walk the §5 signatures through architect / SRE / security /
   performance / release engineer. **Each signature is a personal
   attestation, not a delegation to the loop.**
6. The first signature's date starts the 90-day expiry clock per
   §6.

The loop will not do steps 1–6. The loop ended on 2026-04-19 per
`docs/loop-state.json` `termination_mode = "stable_two_consecutive"`.
Type C closure is yours.

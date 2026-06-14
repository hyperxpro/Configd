# Integrity Checkpoint 4.5 — Independent Audit of Sessions 1–4

**Scope.** Read-only audit between Session 4 (chaos/durability) and Session 5 (performance). Verifies
that the recorded history of Sessions 1–4 is REAL: every claimed commit exists and contains its
claimed change, every gate runs and passes honestly (not no-op green), every RESOLVED finding has the
evidence its row claims, and the documentation chain has not drifted from the code.

**Method.** The `integrity-lead` (this report) reconciled register counts and ownership directly, and
spawned four fresh `opus` verification sub-agents — `commit-historian`, `gate-verifier`,
`evidence-auditor`, `drift-auditor` — each re-running (not re-reading) the claims in its lane. Maven
work was serialized on a shared `flock` mutex (2-vCPU box); the parent-commit reproduction ran in an
isolated git worktree so it could not disturb the shared HEAD.

**Audited tip.** branch `session-4-chaos`, HEAD `cdf3dbf`, working tree clean.

---

## Verdict table (per check)

| Check | Verdict | One-line basis |
|---|---|---|
| §3.1 Commit-chain integrity | **PASS** | Clean linear s0→s1→s2→s3→s4 chain; all named commits real, non-doc, claim-consistent; "18 commits" correct |
| §3.2 Gate reality | **PASS-with-exceptions** | Gate-4 is non-hollow (independent live run green, all `-Dtest` real); but it has **never run in real CI** (branch unpushed) and stale comments claim C/D/E aren't wired |
| §3.3 Resolved-finding evidence | **PASS** | All 4 P0s real; RR-001 closure is a LIVE Docker E2E (not sim-only); RR-005/103/008/095 RED reproduced; 1 capture-fidelity P3 |
| §3.4 Documentation drift | **PASS-with-1-P3** | ~18 VERIFIED rows still true at tip (2 core S4 rows re-run live, exact numbers); ADRs all exist; ENV-BLOCKED honest. One stale line anchor (P3) |
| §3.5 Register hygiene | **PASS-with-exceptions** | Counts reconcile; 4 P0 all RESOLVED; 0 stuck IN-PROGRESS; 1 justified ACCEPTED-RISK. Owner-session numbering is off-by-one / overloaded (hygiene) |

---

## §3.1 — Commit-chain integrity — **PASS** (verifier: `commit-historian`)

Topology is a **single clean linear chain**, not independent branches off main:

```
s0(ca5c3a2) → s1(2a08b6f) → s2(de89a78) → s3(14a0f87) → [s4: 892eb95 … cdf3dbf]  ← HEAD
```

- Each session tip is the exact `merge-base` of the next (clean fast-forward descent, no merge
  commits). `session-0-topology-adr` is the chain root; `session-a1/a2/a3` are early ancestors at/before
  the s1 tip (parallel agent work that landed into early history; none divergent/unmerged); `main` is
  an ancestor (chain is ahead of main).
- **S2 tip = `de89a78`; S3 tip = `14a0f87`** (matches closeout).
- All named commits reachable from `session-4-chaos`: `2a08b6f` `869dbdb` `a4535b5` `47b6022`
  `f6931e5` `e75cec9` `dec3910` `892eb95` `14a0f87` `cdf3dbf`.
- **"18 commits (`892eb95..cdf3dbf`)" is accurate.** `892eb95`'s parent IS the s3 tip `14a0f87`, so
  `892eb95` is the first S4 commit; inclusive count = 18 (the `..` exclusive form gives 17). No
  off-by-one in the closeout.
- **`47b6022` (RR-005 fix) is a real production change, not doc-only:** touches 8 java files incl. the
  full compaction-trigger path `RaftNode.maybeCompact` → `MultiRaftDriver.maybeCompact` →
  `ConfigdServer` tick-loop `driver.maybeCompact(THRESHOLD)`, plus the `FileStorage.checkedLogReadSize`
  recovery guard. Matches the closeout exactly.
- `f6931e5` adds `CrashStorage.lieOnSyncForKey` + `gapDetectionFiresWhenSnapshotFsyncLied`; `e75cec9`
  / `dec3910` add+extend `StorageEnospcConsensusReactionTest`; `869dbdb` adds `ReconfigurationTest`
  (+179); `a4535b5` adds the 3 A3 edge-chaos test files (+311). All non-empty, no doc-only masquerades,
  no empty commits in the S4 range.
- Working tree clean (`git status --porcelain` empty); nothing claimed-committed lives only in a stash
  (`git stash list` empty).

**Noted (not a defect):** `session-4-chaos` is **local-only / unpushed** — `origin` has only
`main` + `session-1/2/3`. Relevant to §3.2's CI-reality check (S4's `gate-4` job has had no opportunity
to execute in real GitHub CI). DISCREPANCIES: none.

---

## §3.2 — Gate reality — **PASS-with-exceptions** (verifier: `gate-verifier`)

**The gate is real and passes honestly — it is NOT green-but-hollow.** Independent live run
`flock /tmp/configd-mvn.lock bash -c 'GATE4_SKIP_GATE3=1 GATE4_SKIP_NIGHTLY=1 bash gates/gate-4.sh'`
→ exit 0, reached `=== GATE-4: ALL STEPS GREEN ===` with real `Tests run: N` (N>0, 0 fail/err) per step:
liveness 3 classes, reconfig 14, durability {Snapshot 7, Compaction 1, FileStorage 18, MultiRaft 23,
ENOSPC 2, ConfigdServer 3}, edgechaos {FanOut 18, Governor 2, EdgeMtls 1}, **partition(C) 6,
overload(D) 2**.

- **Static hollow-step audit (gate-1..4 + e2e/smoke) — PASS.** No `set +e`; the five `|| true` are all
  benign (they feed console filters / capture-into-var that is then emptiness-checked, with the real
  assertion being the subsequent `grep -q "BUILD SUCCESS"` on the tee'd log). gate-4 uses
  `MVN="$ROOT/mvnw -B"` (no `-q`), so `run_tests` greps a non-quiet log under `set -euo pipefail` — an
  mvn failure both fails the pipe and removes the BUILD SUCCESS line. **The Session-2 `-q`+grep bug is
  NOT reintroduced** (the one `-q`, in `step_install`, correctly checks the exit code instead of grepping
  for BUILD SUCCESS). All 18 `-Dtest=` classes + the 4 named `#method`s exist as real files.
- **Cumulative chaining — PASS.** Static chain real (gate-4→gate-3→gate-2→gate-1); ci.yml enforces it
  via `needs:` (gate-3 `needs: gate-2`, gate-4 `needs: gate-3`). The `GATE*_SKIP_GATE*=1` skips on the
  CI-subset path are **loud** (echo "… NOT verified") and the dropped coverage is supplied by the
  separate CI jobs — no silent skip.
- **Gate-4 "C/D/E NOT YET IN GATE-4" self-contradiction — DISCREPANCY (doc drift only).** `main()`
  calls `step_partition` (C) and `step_overload` (D) **unconditionally — no SKIP guard** → both run in
  the CI subset; E (`MiniJepsenSweepTest`) is inside `step_nightly` → nightly-only. The gate-4.sh header
  (≈L43-46) and ci.yml (≈L229-231) still say C/D/E are "NOT YET IN GATE-4" — **the code is ahead of the
  comments.** No real coverage gap; the closeout's "C+D+E landed & gate-wired" claim is backed by the
  code. → new finding **RR-106**.
- **CI execution reality — DISCREPANCY / CANNOT-VERIFY (CI): gate-4 has NEVER run in real GitHub CI.**
  The cited "green at pickup" run `27412723506` is on branch `session-3-data-plane` @ `14a0f87` (the S4
  *baseline*, 18 commits behind cdf3dbf), `workflow_dispatch`, success — its jobs are gate-1/2/3,
  wire-compat, tlc, build-and-test, **no gate-4 job.** `origin` has only `main` + `session-1/2/3`;
  `session-4-chaos` is **unpushed**, and no CI run anywhere has ever contained a gate-4 job. The
  "green at pickup" claim is accurate *for the S3 baseline* but says nothing about gate-4. → new finding
  **RR-107**.
- **Nightly chaos job — PASS (config) / DISCREPANCY (provenance).** ci.yml has the `schedule: cron
  '17 3 * * *'` + a `Run gate-4 FULL (nightly)` step gated `if: github.event_name == 'schedule'`. The 3
  capture files show real BUILD SUCCESS / "ALL STEPS GREEN" / `safetyViolations=0`, internally
  consistent (per-class counts match the `-Dtest=` classes; nightly-edge 10000 seeds; mini-Jepsen 16
  seeds / 13920 faults / 0 safety; the `cpStalls`/`deliveryViolations` are the RR-095 ACCEPTED-RISK
  liveness artifacts, explicitly not safety violations). **But their headers cite S4 commits + the
  literal local command line → these are LOCAL runs, not GitHub-scheduled CI runs.** The nightly
  *steps* were exercised locally; the nightly *CI job* never fired (same root cause as RR-107).
- **Full gate-1..4-from-clean** — CANNOT-VERIFY-HERE (time, 2-vCPU box); the static chain is real and CI
  `needs:` enforces it.

**Bottom line for §3.2:** no gate step is hollow; gate-4 genuinely passes locally and C/D execute in the
CI subset exactly as claimed. The exceptions are (a) stale comments and (b) the gate-4 job, while
correctly *wired*, has never actually *executed* on CI because the branch was never pushed — so every
"gate-4 green / nightly chaos green" claim rests on local captures, not CI provenance.

---

## §3.3 — Resolved-finding evidence — **PASS** (verifier: `evidence-auditor`, isolated worktree)

**No claimed-RESOLVED P0 is hollow; the headline RR-001 closure is real and live.** Method note: the S4
discriminating tests were committed in the *same* commit as their fix, so the fix's parent has no test;
pre-fix RED was proven two ways — (a) overlaying the committed test on parent production code →
**compile-fails** (the fixed API didn't exist), and (b) the documented mutation-revert on the fixed code →
RED **byte-identical** to the captured RED.

| Finding | Test(s) exist | Current status | Pre-fix RED | Verdict |
|---|---|---|---|---|
| **RR-001** (P0, headline) | `gates/e2e-compose-scenario.sh` + `deploy/compose/` | E2E capture **19/19 PASS, SCENARIO_EXIT=0** | n/a (closure) | **PASS** |
| **RR-002** (P0, S2) | `TcpRaftTransportBlackholeTest`, `NoBlockingConnectOnConsensusPathTest` | exist (not run — transport, cross-session) | CANNOT-VERIFY (cost) | **PASS** (existence) |
| **RR-003** (P0, S2) | `SnapshotCrashRecoveryTest` (incl. `durable_prefix_no_gap`, non-vacuous) | **7/0 GREEN** (re-run at tip) | CANNOT-VERIFY (S2 cost) | **PASS** |
| **RR-004** (P0, S2) | `AckEqualsCommitTest` (`violations==0` + non-vacuity `ackedAndSurvived>0`) | exist (not run — 200-seed×3 cost) | CANNOT-VERIFY (cost) | **PASS** (existence) |
| **RR-005** (P1 headline, S4) | `RaftLogCompactionTriggerTest`, `FileStorageTest`, `ConfigdServerTest#rr005_…`, `MultiRaftDriverTest` | **all GREEN** (Compaction 1/0, FileStorage 18/0, ConfigdServer 2/0, Snapshot 7/0) | **REPRODUCED ×2** | **PASS** |
| **RR-008** (P1, S4) | `InboundRoutingThrowableHandlerTest` | **2/0 GREEN** | **REPRODUCED** | **PASS** |
| **RR-103** (P1, S4) | `Rr103InflightWindowRecoveryTest`, `LivenessBoundedProgressSweepTest` | **1/0 GREEN** | **REPRODUCED** | **PASS** |
| **RR-095** (P3, ACCEPTED-RISK) | `Rr095StallSeedDiagnosisTest`, `LivenessBoundedProgressSweepTest` | wired into `gate-4.sh:101` | live-net **REPRODUCED** | **PASS** |

- **RR-001 — the pipeline's headline claim is substantiated as LIVE, not simulator-only.** Closure rests on
  `gates/e2e-compose-scenario.sh` over a real containerized topology (`compose.yaml` + `Dockerfile.server`/
  `Dockerfile.edge`; **3 control-plane containers + 4 edge containers**, real mTLS), capture
  `docs/session-3/captures/e2e-compose-scenario-run.txt` shows a genuine Docker run (`configd-e2e-cp1/2/3`,
  `edge1..4`), real SIGKILL leader-kill, real network partition, **19/19 PASS, SCENARIO_EXIT=0**. Production
  networking modules (`FanOutServer`, `EdgeClientCore`, `EdgeNodeMain`) exist; the 10k sweep is *supporting*,
  not sole, evidence. Closure commits `31a4225`/`1c39615`.
- **RR-005 (hardest) — RED reproduced two ways.** Fix = `47b6022` (matches charter). (a) Parent `47b6022~1`:
  `maybeCompact(long)` does not exist → overlaid test **compile-fails** "cannot find symbol method
  maybeCompact" (compaction was provably unreachable). (b) M-compact mutation (`maybeCompact`→`return
  false`) → `RaftLogCompactionTriggerTest:74` "must compact above the threshold … expected:<true> but
  was:<false>" — byte-identical to `exp-006-rr005-compaction-unreachable-RED.txt`.
- **RR-095 — the liveness net is a LIVE net, not a rubber stamp.** (i) The register row names the precise
  conditions — *"sustained drop rate 0.384–0.498 and/or active partitions … a NEVER-HEALED-schedule
  artifact"*, 7 seeds enumerated (`rr095-perseed-diagnosis.txt` confirms each `leaderElected=false`,
  endDropRate 0.384–0.498). (ii) Reverting the RR-103 fix makes `LivenessBoundedProgressSweepTest:248` fire
  at seed 0 ("LIVENESS VIOLATION — after heal the cluster did not return to full service within 800 ticks …
  the RR-103 failure shape") byte-identical to `liveness-sweep-rr103-livenet-proof.txt` — a real
  bounded-progress assertion (commit on all 5 nodes within 800 ticks), not `assertTrue(true)`.
- **RR-103 / RR-008** — RED reproduced (RR-103 decay revert → `recoveryTicks=-1`; RR-008 bare-swallow revert →
  the wiring leg fails "must be surfaced as a counter (RR-008) … expected: not <null>", byte-identical to
  `rr008-prefix-failure.txt`).
- **RR-002 / RR-004** — tests exist with non-vacuous assertions; not independently re-run (transport / 200-seed
  cost, cross-session) → pre-fix reproduction CANNOT-VERIFY-HERE (cost), as the charter permits. **RR-003**
  independently re-run **GREEN (7/0)** at the tip (it is heavily reused by S4).

**One DISCREPANCY (P3, evidence fidelity):** the RR-103 pre-fix capture `rr103-prefix-failure.txt` reflects a
**pre-commit draft** of the test (fails at line 167, `assertEquals expected:<19> but:<5>`), whereas the
committed test (added at `892eb95`, 104 lines) fails at line 94 in an `assertTrue` form (`recoveryTicks=-1`).
`git log --follow` shows the test was committed exactly once. The bug signature is faithful and the
independent live reproduction confirms the committed test discriminates correctly — but the captured RED does
not correspond line-for-line to the committed test. → new finding **RR-109** (re-capture for fidelity).

**Worktree hygiene:** the evidence-auditor finished detached at `cdf3dbf`, clean, no commits, mutation-reverts
undone, `.m2` reinstalled clean. The main worktree is unchanged (HEAD `cdf3dbf`, only the untracked
`docs/checkpoint-4.5/` deliverable present).

---

## §3.4 — Documentation drift — **PASS (1 P3 exception)** (verifier: `drift-auditor`)

**No silent invalidation of any CORE guarantee.** ~18 VERIFIED rows re-verified at `cdf3dbf` across all
four sessions' conversions + the S1 master matrix — all still true. Highlights:

- **Two S4 core rows re-run LIVE** (under the Maven mutex) and reproduced the **exact documented
  numbers**: D-1 write-flood `queuePlateau=1024` (= `maxPendingProposals`, accepted=1022/shed=478), D-2
  reconnect-storm `recoveryTicks=258` (5 edges, all recovered, none terminal) — matching handoff §4 and
  the D-1/D-2 register rows.
- S2 ack=commit (`HttpApiServer.java:389-390` `200 "Committed: seq="`, no `Accepted:` path), S2 determinism
  RNG, S2 assertion twins (all 7 present in `AssertionTwinFiringTest`), S3 fan-out evict-before-overwrite
  (`FanOutBuffer.java:43-54,115`, RR-096/ADR-0036 ordering intact), S3 edge wire handshake
  (`EdgeFrame.java:50-81` `resumeCursor`+`failoverResumeCursor`), S4 §6/§12 partition cells (each
  `PartitionMatrixTest` method present at the cited symbol) — all PASS.
- S1-matrix rows (CM-034/043/045/004/021) verified by **symbol**, with line numbers drifted (e.g. ReadIndex
  `:392`→`:534`) — **expected and not a discrepancy**: the S1 matrix is immutable and pins lines at the S1
  commit; later refactors moving lines are out of its scope.
- **Consistency-contract ADR cross-check — PASS.** Every renegotiating ADR EXISTS and is Accepted:
  ADR-0031 (write-avail), 0033 (commit-confirmed), 0034 (commit-notification boundary), 0035 (HLC descope —
  matched: `LogEntry` has no ts field), 0036 (fanout-evict), 0038 (signed-chain), 0039 (frontier),
  0040 (poison-pill — `PoisonPillRebootstrapTest` present). Contract §8 assertion rows map to real checks
  (`apply_owner_thread` `ConfigStateMachine.java:280` exact; `durable_prefix_no_gap` `RaftNode.java:257`
  exact). No clause claims a guarantee the code/test does not back.
- **ENVIRONMENT-BLOCKED list — HONEST and COMPLETE.** Real `go` is **absent** (`command -v go` → NO-GO,
  matching "Go is absent") and `sudo -n iptables` **works** (chains listed, exit 0, matching "single-host
  iptables works"). Every blocked item names exact infra (device/FS/mount-flags/host-count/build cmd) and
  a named always-on in-sim/in-CI substitute — none is a vague "needs hardware" or a disguised skip. The B3
  fsync>1s step-down is correctly carried as a *residual* (not env-blocked).

**One DISCREPANCY (P3, doc drift):** `consistency-contract.md:236` (a live/maintained doc, not the immutable
S1 matrix) cites the RR-003 `applyCommitted` check at `RaftNode.java:1655`; the method is actually at
`:1841` (check at `:1858`) — ~186 lines stale; the sibling `ctor :257` citation is still exact, so the row
is half-drifted. Symbol + behavior intact. → new finding **RR-108** (P3, refresh the line anchor).

---

## §3.5 — Register hygiene & count reconciliation — **PASS-with-exceptions** (verifier: `integrity-lead`)

Parsed `docs/readiness-register.md` directly (104 `| RR-… |` rows; status read from the table's status
column, not raw token grep).

### Row-status counts (reconciled)

| | Count |
|---|---|
| Total finding rows | **104** |
| RESOLVED | **37** |
| OPEN | **66** |
| ACCEPTED-RISK | **1** (RR-095 only) |
| IN-PROGRESS | **0** |

By severity: **P0 = 4 (all RESOLVED)** · P1-class = 25 (24 `P1` + 1 `P1 (GA BLOCKER)` = RR-021): 17
RESOLVED / 8 OPEN · P2 = 47 (11 R / 36 O) · P3 = 28 (5 R / 22 O / 1 ACCEPTED-RISK). Sums: 37 R / 66 O /
1 AR = 104. ✓

### §3.5(a) No finding stuck IN-PROGRESS with no owner — **PASS**
Zero rows in IN-PROGRESS status. (The "1 IN-PROGRESS" a raw token scan reports is the **legend line**
defining the status vocabulary, not a row.)

### §3.5(c) ACCEPTED-RISK justification & no silent downgrade — **PASS**
Exactly one ACCEPTED-RISK row, **RR-095** (P3, liveness — expected never-healed artifact). Its row
carries a written justification naming the **precise** conditions the charter requires: *"a sustained
~44% drop window and/or partitions never healed before end-of-run"*, 7/10,000 seeds **enumerated**
(452, 869, 4740, 5100, 5159, 5500, 8319), deterministic replay, root-caused to `DROP_WINDOW_BEGIN
param=0.861` with no paired END + 3 partitions, second-agent characterization cited
(`docs/session-2/reviews/sim-work-review.md §6`). No ACCEPTED-RISK row was downgraded without
justification. (The *live-net-not-rubber-stamp* property is being verified independently by
`evidence-auditor`.)

### §3.5 count reconciliation vs the closeouts — **PASS**
S1 baseline (per pipeline state) was **94 findings / 4 P0**. The register now holds **104 findings / 4
P0 (all RESOLVED)** — i.e. +10 findings discovered across S2–S4 with the P0 set stable and fully
resolved. The S4 handoff's claimed register deltas all land correctly: **RR-103 → RESOLVED · RR-095 →
ACCEPTED-RISK · RR-008 → RESOLVED · RR-005 → RESOLVED · RR-018 → RESOLVED** (D§2 annotation). The lone
ACCEPTED-RISK total (1 = RR-095) matches the handoff's single S4 accepted-risk.

### §3.5(b) "Every OPEN finding has an owning session ≥ 5" — **DISCREPANCY (hygiene, non-blocking)**
This cannot be verified mechanically because the register's **owner-session integers are off-by-one
from the live pipeline and the "3" is overloaded**:

- The register legend numbers owners by **role**: `2 Correctness · 3 Durability,Recovery&Chaos · 4
  Performance · 5 Security · 6 Operability · 7 Adversarial`.
- The **actual** pipeline inserted an **edge-data-plane session as S3**, so durability/chaos ran as
  actual **S4** and performance is the next session — the charter (and the handoff title
  `handoff-to-session-5`) call performance **"Session 5."** Confirmed by topic: owner-4 OPEN findings
  are performance items (RR-007 per-entry WAL fsync cost; RR-009 32 B/op zero-alloc-read contradiction)
  → owner-4 = the *future* Performance session. Owner-3 OPEN findings are durability items (RR-019
  InstallSnapshot 4 MiB cliff; RR-064 byte-format unguarded; RR-086 crash-durability asserted by no
  test) → owner-3 = the *just-completed* durability session.
- The "3" is also **used inconsistently**: RR-001 (an *edge* P0 closed in the edge session) is owned
  "3", while RR-019/064/086 (durability) are *also* owned "3". One integer, two sessions.

Consequence: applying "owner ≥ 5" to the raw integers would wrongly flag the legitimate future-Performance
work (owner-4) and wrongly pass nothing useful. Read **by role**, ~17 OPEN findings sit under
**already-executed** sessions — 1 under Correctness (RR-029) and ~16 under Durability/Chaos (incl. P1s
**RR-019** and **RR-086**, the P2 block RR-032–041/043, RR-022, RR-065, and RR-088 noted "7 orphan
sweep"). Of these, only **RR-019 / RR-064 / RR-086** are explicitly carried forward in the S4 handoff
§2; the remainder are deferred-by-omission with a stale owner integer rather than re-pointed to an
active session. This is a register-coherence defect, **not** a correctness fiction — see new finding
**RR-105** below. It does not block Session 5.

---

## New register findings filed by this checkpoint

All discrepancies are filed as register rows **RR-105–RR-109** (`docs/readiness-register.md`). **None is
P0/P1; none is a hollow-green gate, an unresolved claimed-resolved P0, or a commit missing its claimed
fix.**

| ID | Sev | Owner | Lane | Summary |
|---|---|---|---|---|
| **RR-105** | P2 | S5 | §3.5 | Register owner-session numbering is off-by-one from the executed pipeline and "3" is overloaded (edge vs durability); ~17 OPEN findings under already-executed roles not re-pointed forward; RR-027 uses a non-canonical `RESOLVED-with-note` token. Reconcile numbering + re-triage ownership. |
| **RR-106** | P3 | S5 | §3.2 | `gate-4.sh` header (L43-46) + `ci.yml` (L229-231) stale comments claim C/D/E "NOT YET IN GATE-4"; code already runs C+D in the CI subset and E nightly. Doc-only. |
| **RR-107** | P2 | S5 | §3.2 | gate-4 has never executed in real CI (branch `session-4-chaos` unpushed; no CI run has a gate-4 job). All gate-4/nightly evidence is local captures. Push/merge so the job actually runs before relying on CI-green. |
| **RR-108** | P3 | S6 | §3.4 | `consistency-contract.md` §8 RR-003 row cites `applyCommitted :1655`; method is at `:1841` (check `:1858`). Symbol/behavior intact; refresh the line anchor. |
| **RR-109** | P3 | S5 | §3.3 | RR-103 pre-fix capture reflects a pre-commit draft of the test (line 167/`assertEquals`); committed test fails at line 94/`assertTrue`. Re-capture for evidence fidelity; substance is sound. |

Severity rationale: the two **P2**s are forward-looking process/hygiene items (CI provenance; ownership
re-triage) that Session 5 should clear but that do not impugn any correctness claim; the three **P3**s are
documentation/evidence freshness. No finding changes the truth of any RESOLVED safety claim.

---

## Reconciliation: claimed vs actual

| Dimension | Closeout claimed | Audit found | Match |
|---|---|---|---|
| S4 commit range | 18 commits `892eb95..cdf3dbf`; named commits real | 18 (inclusive); `892eb95` parent = s3 tip; all named commits real, non-doc | ✓ |
| Branch chain | per-session chain | clean linear s0→s1→s2→s3→s4, no merges | ✓ |
| gate-4 | CI-wired, cumulative, green + captured | wired ✓, cumulative ✓, **non-hollow green ✓ (live-verified)**; never *executed* in CI (RR-107) | ✓ w/ exception |
| Register status totals | RR-103/008/005/018 RESOLVED · RR-095 ACCEPTED-RISK | exactly those; 37 R / 66 O / 1 AR / 0 IN-PROGRESS / 104 rows | ✓ |
| P0s | all resolved | 4 P0, all RESOLVED, evidence verified (RR-001 = live Docker E2E) | ✓ |
| ACCEPTED-RISK | 1 (RR-095), justified, live net | 1, precise conditions named, live-net reproduced | ✓ |
| ENV-BLOCKED list | exact infra per item | honest + specific; `go` absent & `iptables` present both confirmed | ✓ |

---

## Bottom line

**The recorded history of Sessions 1–4 is faithful-with-5-exceptions** (RR-105–RR-109 — two P2
process/hygiene, three P3 documentation/evidence-freshness; **zero P0/P1, zero hollow gates, zero
missing-fix commits**; the headline RR-001 closure independently confirmed as a real live end-to-end
Docker run, the RR-005 fix reproduced RED two ways, and every reconciled count matches), **and Session 5
may proceed** — after noting that gate-4's protection is real and green *locally* but has never executed
in GitHub CI (**RR-107**: push/merge `session-4-chaos` so the gate-4 job and its nightly chaos schedule
actually run). No P0-class discrepancy was found, so the checkpoint imposes no stop condition on Session 5.

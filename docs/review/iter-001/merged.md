# iter-001 — Merged & Triaged Findings

**Date:** 2026-04-19
**Severity floor:** S3 (per §4.7 — iter 1-3 floor is S3)
**Reviewers:** 9 (8 lenses + honesty-auditor)
**Honesty-auditor verdict:** 0 honesty failures, loop proceeds; 6 GREEN→YELLOW demotions ordered.

## Severity rollup (raw, before dedup)

| Reviewer | S0 | S1 | S2 | S3 | Total |
|---|---|---|---|---|---|
| hostile-principal-sre (H-) | 1 | 4 | 10 | 3 | 18 |
| distributed-systems-adversary (D-) | 3 | 2 | 5 | 2 | 12 |
| security-red-team (SEC-) | 0 | 5 | 9 | 2 | 16 |
| performance-skeptic (P-) | 0 | 0 | 12 | 4 | 16 |
| chaos-skeptic (C-) | 2 | 3 | 8 | 3 | 16 |
| confused-new-engineer (N-) | 1 | 8 | 8 | 5 | 22 |
| release-skeptic (R-) | 1 | 6 | 5 | 2 | 14 |
| docs-linter (DOC-) | 0 | 11 | 11 | 4 | 26 |
| **Total raw** | **8** | **39** | **68** | **25** | **140** |

## §8 hard-rule violations confirmed

| Rule | Finding(s) | Status |
|---|---|---|
| §8.1 — no perf claim without HdrHistogram artifact | P-001 | OPEN |
| §8.10 — deprecation ≥ 2 releases (wire-compat) | R-005, R-007, R-008 | OPEN |
| §8.14 — runbook 8-section skeleton | DOC-002 | OPEN |
| §8.15 — every ADR has `## Verification` | DOC-001 | OPEN |

## 6 Honesty-auditor demotions to apply to `docs/ga-review.md`

| Row | From | To | Reason |
|---|---|---|---|
| Phase 4 — Test pyramid | GREEN | YELLOW | No on-disk test-count artifact pinned to SHA (DOC-019) |
| Phase 6 — Performance | GREEN | YELLOW | No JMH JSON/HdrHistogram artifacts under `perf/results/` |
| Phase 9 — Release engineering | GREEN | YELLOW | Pipeline never exercised end-to-end |
| B6 — Cosign image signing | GREEN | YELLOW | Same root cause as Phase 9 |
| PA-6012 — SLSA provenance | GREEN | YELLOW | Same root cause as Phase 9 |
| O7 — SafeLog PII helper | GREEN | YELLOW | Test-count miscount: claims 17, actual 14 (DOC-003) |

## Deduplication (cross-reviewer overlaps)

The 140 raw findings collapse to **~95 unique items** after dedup. Notable clusters:

| Cluster | Findings | Root cause |
|---|---|---|
| **Metric-drift** (S0/S1) | H-001, DOC-014, N-003, N-005, N-016 | Runbooks/alerts/dashboards reference metrics never registered in `MetricsRegistry`; SLO pipeline decorative |
| **Missing operator scripts** | H-002, N-006, N-008, DOC-004, DOC-005 | `ops/scripts/restore-snapshot.sh`, `restore-conformance-check.sh` referenced 4×, do not exist |
| **Missing K8s manifest** | N-007, DOC-006 | `deploy/kubernetes/configd-bootstrap.yaml` referenced, does not exist |
| **`raftctl` missing** | N-001, N-015, DOC-012 | CLI referenced 8× across runbooks; no source/binary |
| **HTTP body unbounded** | H-008, SEC-002 | `HttpApiServer.java:269` `readAllBytes()` — false-GREEN vs PA-4002 |
| **InstallSnapshot matchIndex overshoot** | D-002, D-011 | `RaftNode.java:1494-1500` uses leader's snapIndex, not follower's installed index |
| **Signing-state drift** | D-004, D-010 | `signingEpoch` not snapshotted; `SecureRandom` nonce in `apply()` non-deterministic |
| **CatchUpService.trimHistory O(N²)** | H-013, P-010 | `CatchUpService.java:159-170` linear scan per `recordDelta` |
| **SubscriptionManager O(P)** | H-014, P-005 | Linear prefix scan; D13 not landed |
| **MetricsRegistry torn-read / lossy `_sum`** | H-016, P-011 (read), H-017, P-004 (sum) | `bucketCount()` non-wrap-aware; `_sum = mean × count` |
| **Silent-failure stderr-only catches** | H-003, H-004, H-006, H-007, SEC-007, SEC-015 | Tick loop, TLS reload, RaftTransportAdapter.decode, TcpRaftTransport accept loop |
| **Wrong ADR cross-references** | N-010, N-011, DOC-009, DOC-010, DOC-011 | control-plane-down→adr-0014 (GC), write-commit→adr-0007 (sim test), release.md→adr-0026 (OTel) |
| **Peer-id ↔ TLS-cert binding** | SEC-003, C-015 | S4 already RED in ga-review; restated as broader (covers response messages too) |

## Severity tally after dedup (loose estimate)

S0: 8 | S1: ~30 | S2: ~45 | S3: ~12 — **~95 unique items at S3 floor**.

---

## All S0 items (must close to advance loop)

| ID | Lens | Location | Type | Action |
|---|---|---|---|---|
| **H-001** | SRE | `ops/alerts/configd-slo-alerts.yaml` ↔ `MetricsRegistry` | A | Wire metrics OR delete decorative alerts; large fix → `Tier-1-FIX-METRICS` cluster |
| **D-001** | dist-sys | `RaftNode.java:762-777`, `ReadIndexState.java:137-140` | A | Per-read `acked` set + future-round confirmation |
| **D-002** | dist-sys | `RaftNode.java:1494-1500`, `InstallSnapshotResponse.java` | A | Add `lastIncludedIndex` field; leader uses follower-echoed value |
| **D-003** | dist-sys | `ConfigdServer.java:68`, `RaftNode.java:763-764` | A | Either rename `*Ms` to `*Ticks` or convert ms→ticks in RaftNode ctor; assert at startup |
| **C-001** | chaos | `FrameCodec.java:64-79,113-136` | A | Add CRC32C trailer; chaos primitive `corruptEveryNthByte` |
| **C-002** | chaos | `DurableRaftState.java:128-147`, `FileStorage.java:46-86` | A | Wrap `FileStorage.put` payload with `[len:4][crc32:4][payload]` |
| **N-013** | newbie | `ops/runbooks/disaster-recovery.md:88-92` | A | Add interactive confirmation guard before `kubectl delete pvc` |
| **R-002** | release | `ops/runbooks/release.md:86-92` | A | Add `## Rollback` section with concrete commands |

## Iteration 1 fix plan

This iteration fixes **all 8 S0 items + all 4 §8 hard-rule violations + the 6 demotions + the highest-leverage S1 cluster (operator-script & manifest gaps + wrong ADR refs + missing CHANGELOG)**. Remaining S1/S2/S3 items at the current floor are documented as in-progress in `docs/loop-state.json` and carry to iter-2.

**Why not all 95 at once:** the loop runs at S3 floor for iter 1-3 per §4.7, so subsequent iterations will continue closing items. Per §5, the loop terminates when stability signal = 0 for two consecutive iterations at the current floor.

**Fix dispatch (parallel where possible):**

- **F1 — Apply 6 demotions** to `docs/ga-review.md` (in-process).
- **F2 — Tier-1-SAFETY** (in-process, sequential): C-001 FrameCodec CRC, C-002 FileStorage CRC, D-002/D-011 InstallSnapshot echo, D-001 ReadIndex per-read acked set, D-003 tick/ms unit fix, D-004 signingEpoch in snapshot.
- **F3 — Tier-1-DOCS-FIXES (subagent A)**: §8.15 add `## Verification` to all 26 ADRs; §8.14 reformat 8 runbooks to 8 mandated sections + Operator-Setup section per ADR-0025; fix wrong ADR cross-refs; mark ADR-0006 superseded by ADR-0018; CHANGELOG.md stub; ADR-0027-sign-or-fail-close authored.
- **F4 — Tier-1-OPS-GLUE (subagent B)**: ship `ops/scripts/restore-snapshot.sh` + `restore-conformance-check.sh` (operator-glue contracts), `deploy/kubernetes/configd-bootstrap.yaml`, kubectl delete confirmation guard wired into disaster-recovery.md, raftctl HTTP equivalents documented (drop CLI references in favor of curl `/admin/*`).
- **F5 — Tier-1-METRIC-DRIFT (subagent C)**: register the metrics referenced by alerts/runbooks in `MetricsRegistry` and wire them at the appropriate emission points (`ConfigStateMachine.signCommand` for `configd_write_commit_*`, `HttpApiServer.handleGet` for `configd_edge_read_*`, etc.); call `SloTracker.recordSuccess/Failure` from those paths; close H-001 + DOC-014 + N-003.
- **F6 — Tier-1-RELEASE (subagent D)**: rollback section in release.md, RELEASE_NOTES_TEMPLATE updates, wire-version byte in FrameCodec, golden-bytes wire-compat CI job, snapshot/WAL backwards-compat test stubs, release.yml `verify-published` job for R-001.
- **F7 — Tier-1-PERF-DOC-HONESTY (subagent E)**: P-001 — strike "HdrHistogram" claim from `docs/performance.md` (or replace with measured truth: 4096-slot ring buffer); add measured/modeled disclaimer to `docs/performance.md` cross-region predictions; commit JMH log artifacts under `perf/results/jmh-<UTC>/`.

**Deferred to iter-2 (NOT in iter-1 scope):**

- All S1/S2/S3 items not in F1–F7 above (~70 items).
- These are tracked open in `docs/loop-state.json`. Per §4.7 floor stays at S3 through iter-3, so they will be addressed before floor rises.

**Backlog (no fix this loop, document for v0.2):**

- None this iteration. The loop addresses S0–S3 inside the floor; v0.2 items are only entered after the persistence signal trips per §4.6.

---

## Item-by-item disposition

### S0 (8) — all in iter-1 fix scope

H-001 → F5 · D-001 → F2 · D-002 → F2 · D-003 → F2 · C-001 → F2 · C-002 → F2 · N-013 → F4 · R-002 → F6

### S1 in iter-1 scope (~25)

H-002, H-003, H-004, H-005 → F4 · SEC-001 → F6 (token-file flag) · SEC-002 → F4 (HttpApiServer body cap) · SEC-003 → defer (S4 already RED) · SEC-004 → F2 (snapshot entry cap fix) · SEC-005 → defer (S8 already RED) · D-004 → F2 · D-005 → F2 · C-006 → defer (chaos coverage; iter-2) · C-009 → defer · C-010 → defer · N-001/DOC-012 → F4 · N-002 → F4 · N-003/DOC-014 → F5 · N-005 → F5 · N-006/DOC-004 → F4 · N-007/DOC-006 → F4 · N-008/DOC-005 → F4 · N-014 → F4 · DOC-003 → F1 · DOC-007 → F3 · DOC-010 → F3 · DOC-011 → F3 · DOC-016 → F7 · DOC-025 → F3 · R-001 → F6 · R-003 → F6 · R-004 → defer (canary needs real env) · R-005 → F6 · R-007 → F6 · R-008 → F6

### S1 deferred to iter-2 (~5)

SEC-003, SEC-005, R-004, plus chaos-coverage S1s (C-006/C-009/C-010) requiring substantial new harness work.

### S2/S3 (~80) — all carry to iter-2 with current floor S3 maintained per §4.7

Tracked in `docs/loop-state.json` for iter-2 dispatch.

---

This merged.md is the input to §4.4 (Fix). Subagents F3–F7 receive scoped prompts derived from this file.

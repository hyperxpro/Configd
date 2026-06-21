# Phase 0 — Workstream B — Stage 2 — Handoff

> **State as of `e2ac468` on branch `phase0-B-rethreading` (pushed).** `main` stays pinned at the
> verified baseline `cedc706` (D-010); B merges to `main` only at the §6 gate (M4). This handoff lets
> Stage 2 resume cleanly: **M1 done, M2 designed + M2a (mechanism) done; M2b, M3, M4 remain.**

---

## 1. The verified N>1 threading model (what sharding/Phase 1 will build on)

The owner-executor pool from Stage 1 (`ownerExecutor(gid) = pool[floorMod(gid, N)]`) is now:
- **M1 — per-owner ticking at N>1.** `ConfigdServer` schedules `tickOwner(i)`/`maybeCompactOwner(i)` on
  EVERY owner thread; each owner drives only its own groups. Owner-isolation is proven (a cross-group
  access on a *real foreign owner* trips the **per-node** net). Singleton housekeeping (H-3 scrape +
  co-tenant riders) rides owner[0]. Behaviourally EXACT to Stage-1B at N=1.
- **M2a — group rehoming MECHANISM (dormant in production).** Ownership is now DYNAMIC: a `groupOwner`
  map + `migrating` set on `MultiRaftDriver` (default = static `floorMod` via `currentOwnerIndex`);
  `RaftNode.ownerThread` is RE-BINDABLE (`beginHandoff()`→HANDOFF sentinel, `adoptOwnerThread()`);
  `rehomeGroup(g, target)` performs quiesce→publish→adopt with executor `.get()` barriers; marshalled
  work uses check-and-bounce. Production stays single-group and NEVER calls `rehomeGroup` (the mechanism
  is dormant until a Phase-1 placement policy wires it). INERT at N=1.

Authoritative docs: `docs/phase0/threading-contract.md` (as-built §4.2, hazards §6), the M2 design
`docs/phase0-B-stage2/m2-rehoming-handoff-design.md`, decisions D-010..D-017.

## 2. What is BUILT and VERIFIED (Stage 2)

| Piece | Commit | Verified |
|---|---|---|
| **M1** — per-owner tick at N>1 + owner-isolation | `a3c5b7f`+`2666f01`+`c1f3188` | `OwnerIsolationMultiOwnerTest` (N=3): clean run zero-fire + commitIndex GROWTH on every owner; cross-group access trips the PER-NODE net + control; neuter→RED→green (`captures/m1-owner-isolation-net-catch.md`). Four-way: diff-review SOUND, re-run CONFIRMED-GREEN, red-team (found+fixed a setup-satisfiable liveness vacuity → assert commitIndex growth; second-agent replay). S2–S4 sim subset 174/0 + server 165/0. |
| **M2 design** | `832a8a5` | Contract-first handoff protocol + D-016 scope (mechanism in Stage 2, policy = Phase 1). |
| **M2a** — rehoming mechanism (additive, dormant) | `627232b`+`f0876b3`+`e2ac468` | `RehomingHandoffTest` (7): clean rehome preserves state + keeps committing on the new owner (non-vacuous) + stale-routing bounce; net catches the rehoming-race (losing-owner-after-handoff / gaining-owner-before-adopt), neuter→RED on exactly the 3 race tests→green (`captures/m2-rehoming-net-catch.md`); no-double-ownership (unit) via old-owner-locked-out; adopt-guard. Four-way: diff-review SOUND, re-run CONFIRMED-GREEN, **red-team BROKE-IT** (Defect 1 net-masking §6.2 + Defect 2 removeGroup leak — both FIXED, red/green, second-agent REPLAY-CONFIRMED). INERT at N=1 (legacy `MultiRaftDriverTest`, macro stress, server 165/0). |

## 3. What REMAINS in Stage 2 (resume here)

### M2b — close H-4 (the proofs the mechanism still needs)
1. **JMM no-double-ownership (jcstress).** `configd-jcstress` `RehomingDoubleOwnershipTest`: two threads
   race `assertOwnerThread`-style reads around `beginHandoff`/`adoptOwnerThread`; FORBIDDEN outcome =
   both observe themselves as owner. Must be unreachable (one volatile field + the barrier ordering).
   Add a forbidden-hitting CONTROL (naive non-volatile/un-ordered re-bind DOES double-own) excluded from
   the gate, like `HarnessSelfTest.KnownRacyCounter`. The red-team confirmed the unit test cannot
   deterministically expose this JMM race (removing the detach barrier was caught only ~2/3 of runs) —
   so the jcstress proof is genuinely required, not redundant.
2. **S2–S4 surface re-run WITH rehoming injected.** A concurrent harness that rehomes groups under
   adversarial schedules WHILE the multi-owner workload runs (the deterministic single-drive-thread sim
   can't model true multi-owner concurrency — this is a real-executor stress test, an extension of
   `OwnerIsolationMultiOwnerTest`/`RehomingHandoffTest`). Assert: invariants hold, zero unintended net
   fires, groups keep committing across rehomes.
3. **Deferred mechanism gaps (design §8; dormant, low-risk but close before the injected sweep):**
   `flushDurable()`/quiesce step on the losing owner in `rehomeGroup` (so B adopts a durable state) +
   **FlushScheduler retarget across a rehome** (a flush closure capturing owner A could fire on A after
   handoff → off-owner net fire on the rehome path); `abortHandoff()`/rollback (a partial handoff
   currently wedges the group on HANDOFF — loud, but unrecoverable).
   Then mark threading-contract **H-4 → CLOSED** with mechanism + evidence.

### M3 — coalesced heartbeats (cost flat in N)
- `HeartbeatCoalescer` **already EXISTS** (`configd-replication-engine`, 163 lines, tested) but is
  **UNWIRED** (only self + its test reference it). Wire it into the heartbeat send path (one heartbeat
  per peer-node per tick regardless of group count — the CockroachDB/TiKV technique). This touches the
  consensus core (RaftNode's heartbeat broadcast) — DANGEROUS, behind the net, four-way.
- Prove: heartbeat traffic flat in group count (property test); no election spuriously triggered by
  coalescing under load. Re-run S2–S4. Checkpoint.

### M4 — gate-B + merge B to main (§6 gate)
- Assemble `gates/gate-B.sh` (CI-wired, cumulative with 1–7 + phase0): net catches cross-group +
  rehoming races under N>1; owner-isolation at N>1; H-4 no-double-ownership; coalesced-heartbeat
  cost-flat-in-N; full S2–S4 surface green multi-group; `cedc706` baseline-green recorded.
- Merge B → main ONLY when gate-B is green in CI and all milestones are four-way verified.

## 4. Deferred beyond Stage 2 (unchanged)

- **Throughput levers** (proposal batching / replication pipelining / per-tick broadcast coalescing
  beyond heartbeats) — each behind the net, S2–S4 re-closed.
- **The single-group throughput MEASUREMENT** (the Phase-0 v1-vs-v2 decision gate) — **Workstream C on
  real hardware** (D-003), not this 2-vCPU box.
- **Sharding logic** (routing / ShardMap / placement policy / when-to-rehome) — **Phase 1**. M2a built
  the rehoming MECHANISM; Phase 1 adds the POLICY that drives it.

## 5. Residual risks / notes

- **Rehoming is DORMANT in production** (single-group, `rehomeGroup` has no production caller). The
  mechanism is proven at the unit/macro level; the JMM + injected-sweep proofs (M2b) gate H-4 → CLOSED.
- **The HANDOFF sentinel** (`RaftNode.HANDOFF`, a static never-started Thread shared across nodes) is
  safe — `assertOwnerThread` compares the per-node `ownerThread` for inequality with real threads; the
  sentinel never equals any running thread (red-team confirmed; all 13 guarded entry points fire during
  the window).
- **Check-and-bounce is gated on rehome-affected groups** — a NEVER-rehomed group still fires the net on
  a missed marshalling hop (§6.2 preserved). Do NOT remove this gate (D-017 / red-team Defect 1).
- **`removeGroup` clears `groupOwner`/`migrating`** (D-017 / red-team Defect 2) — keep it.
- **2-vCPU box:** targeted `-Dtest` runs, `-Dsurefire.failIfNoSpecifiedTests=false`, never the full
  reactor; worktree-isolated agents start at `cedc706` so pass the exact target SHA. The full gate chain
  is multi-hour — defer to CI. See `[[configd-environment]]`.

## 6. How to resume

Resume from `e2ac468`. Prime directive holds: **four-way each dangerous step** (implementer + diff-review
+ independent re-run + adversarial red-team), the net re-proven NON-VACUOUS for each new violation class
(neuter→RED→revert→green, captured), S2–S4 re-closed after each milestone, and **STOP CLEAN at the last
verified seam** rather than leave a half-built change across a boundary. Start with M2b (close H-4), then
M3, then M4. The M1 + M2a seams are COMPLETE fallbacks.

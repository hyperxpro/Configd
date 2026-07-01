# Production-Readiness Register — Configd v1

> **Purpose.** The single subsystem-organized go/no-go register for Configd v1. Every item carries an
> **audited** status backed by a **named artifact** (class:line / test / CI gate / Accepted ADR /
> measured number) or a **named gap**. This register drives the v1 readiness review; it is meant to
> reflect reality, not optimism.
>
> **Provenance of this audit.** The statuses below were first **pre-filled from memory/docs (guesses)**,
> then every item was **audited against the live repository at HEAD** (`74ab070`, branch
> `phase1-ec2-prep-handoff`) by seven parallel adversarial passes plus operator spot-verification.
> Where the pre-fill was wrong, the row carries a **Δ pre-fill** note — those deltas are the signal.
>
> **Status legend:** ✅ done + evidenced · 🟡 partial / untested / weak-evidence · ❌ not started ·
> ⛔ deliberate recorded non-goal (cites the decision) · 🔬 built but performance-**unmeasured**
> (needs measurement, usually EC2).
>
> **Evidence standard (hard rule).** *Nothing is ✅ without a named artifact.* A claim with no
> test / gate / measurement is 🟡 at best. ✅ items that the audit could not confirm *pass* (only that
> the artifact exists and is CI-wired) are marked with a † and explained.

---

## §0. Audit summary

### Counts (132 items)

> **Note:** these counts are the **snapshot as of the original audit (2026-06-26)**. They have **not**
> been re-tallied for subsequent row updates — in particular the watch rows (§4.8/6.6, §11.8) were
> upgraded 🟡/??? → ✅ (N=1, server-side) on 2026-06-29 (watch arc, PRs #28/#29/#30), so the live ✅/🟡
> split is ~3 items better than shown. See the individual rows for current status.
>
> **⇒ Current reconciled status (authoritative): [`v1-go-no-go-2026-07-01.md`](v1-go-no-go-2026-07-01.md).**
> The 2026-07-01 go/no-go review re-walked this register against `ce7d719` + both EC2 runs. The current
> tally is **✅98 / 🟡25 / ❌2 / ⛔6 / 🔬1** (honest band **✅95–98**; three of the new ✅s — 9.7 soak is
> 6 h≠24 h, 11.12 empirical is not exhaustive, 8.7 ACL is off-by-default — are judgment calls, go/no-go §2.1;
> delta explained there). Several rows below are now stale
> relative to that reconciliation — notably §2 items **2.6/2.7/2.9** (shown `❌ deferred`, but the
> Multi-Raft Phase-1 server wiring has since **landed on main** and was exercised on metal), the §11.8
> row text (says "→ v2"; authoritative status is ✅ N=1), and the 1.12/10.2 † "ADR-0030/0032 Proposed"
> notes (both are now **Accepted**). **Reconciliation applied 2026-07-01 (this pass):** the individual
> status rows below have been refreshed to that reality with dated per-row reconciliation notes citing the
> go/no-go + the verifying artifact. **The counts table immediately below is left as the frozen 2026-06-26
> audit snapshot** (a dated historical record, not re-tallied in place); the reconciled tally is the one in
> this note. The go/no-go review remains the authoritative source of truth.

| Status | Count | Meaning |
|---|---:|---|
| ✅ done + evidenced | **86** | named artifact found and verified to exist + (mostly) CI-wired |
| 🟡 partial | **29** | exists but incomplete / untested / off-by-default / dormant |
| ❌ not started | **7** | genuinely absent |
| ⛔ deliberate non-goal | **6** | recorded scope decision (single-group, Level 0/1, single-region, etc.) |
| 🔬 unmeasured | **4** | correctness/mechanism built, the *number* is not measured (EC2-gated) |

The high ✅ count reflects a genuinely well-built **correctness core** (consensus, durability, the edge
read/consistency plane, the verification harness). The risk is concentrated in a small number of items:
empirical validation, security posture, the sharding/observability deferrals, and three "class exists
but unwired" traps.

### Pre-fill corrections (the deltas)

**Optimistic → downgraded (the dangerous ones — false ✅ that would have hidden a gap):**

| Item | Pre-fill | Audited | Why |
|---|---|---|---|
| 4.8 / 6.6 Watches | ✅ | **✅ (N=1, server-side)** | The **RFC §2 client-facing watch protocol is now implemented server-side** on the edge endpoint (`--edge-port`): the `0x02` wire (`WATCH_*` frames + per-shard cursor vector), the multiplex/filter veneer, the whole-target authorization gate (reject-not-filter, fail-closed), per-watch target-filtered delivery + catch-up snapshots, and **bounded revocation under live ACL reload** (W7-7) — PRs #28/#29/#30. N=1 only (N>1 multi-shard watch = **v3**, fail-closed). **Caveats:** no shipped client driver yet (the conforming RFC §1+§2+§3 driver is the next arc); the watch ACL is conditional on segregating watch clients from the co-resident unauthenticated-beyond-mTLS legacy SUBSCRIBE path; single-scope keyspace at N=1 (see known-limitations §2). The legacy in-process `WatchService` is unrelated server-internal plumbing. |
| 10.12 Buggify | ✅ | **🟡** | `Buggify`/`BuggifyRuntime` built + self-tested but **zero call sites** anywhere; the deterministic sim injects faults via `SimulatedNetwork`, not Buggify. Named primitive is shelfware. |
| 5.11 BloomFilter | ✅ | **🟡** | Built + property-tested but **zero `src/main` callers**; the `DeltaApplier` half of the item is wired, the bloom half is dormant. |
| 9.7 24h soak | 🔬/🟡 | **🟡** | The soak **launched** (2026-06-14) and ran leak-clean (FD/threads/heap flat) but only **~3.45 h of 24 h** before the Linux OOM-killer killed a node (box capacity RR-112, not a Configd leak). No full soak (24 h or 72 h) has completed. |

**Pessimistic → upgraded (pre-fill too harsh — memory under-credited shipped work):**

| Item | Pre-fill | Audited | Why |
|---|---|---|---|
| 5.2 Staleness measurement | 🟡 | **✅** | Leader commit-timestamp is **load-bearing end-to-end** now (leader stamps → wire `CommitNotification.commitTimestampMillis` → edge ADR-0039 frontier → `StalenessTracker`); the nanoTime idle-proxy was **deleted**. `consistency-contract.md` §2 line 74 + the INV-S2 table row are **stale** — they still describe the proxy. |
| 1.8 Pre-vote | 🟡 | **✅** | Dedicated discriminating test `RaftNodeTest.PreVoteTests.preVotePreventsTermInflationFromPartitionedNode`. (Caveat: pre-vote not in `ConsensusSpec.tla`.) |
| 6.4 Reconnect/failover | 🟡 | **✅** | `EdgeStreamClient` auto-reconnects round-robin carrying the resume cursor; `EdgeFailoverTest` proves it end-to-end (kill-mid-stream, cursor-monotonic, consistent refusal). |
| 8.5 Signing-key co-location | 🟡 | **✅** | Now **fail-closed by default** (`ConfigdServer.enforceSigningKeyNotColocated:1066` throws `SecurityException`; `D1FailClosedTest`; ADR-0043). Caveat below. |
| 8.10 Rate limiting | 🟡 | **✅** | Unconditionally wired (global 10k/s `ConfigdServer.java:646` + per-principal `ConfigWriteService.java:283`) — the only security control that is *not* off-by-default. |
| 9.3 Edge read perf | 🟡 | **✅** | Measured: `getHitWithCursor` p50=50 ns/p99=140 ns (JMH, gate-5 enforced) + 53,616 req/s @64 conn (m6i HTTP). |
| 9.12 Allocation profiling | 🟡 | **✅** | Measured: edge-read 14,999→1,704 B/req (8.80×) on production `NettyEdgeHttpServer`; read-path 0 B/op gated (gate-5). |
| 6.10 SDK docs | ❌ | **🟡** | `docs/wiki/Integration-Guide.md` has real edge-read/cursor/staleness examples — but partial + stale (Gradle/Java-21 vs actual Maven/Java-25). |
| 11.8 Watches scope decision | ??? | **✅ (scoped + built, N=1)** | The **RFC §2 driver-protocol watch section** (`docs/rfc/driver-protocol/02-watches.md`, PR #26) scopes the v1 *client* watch surface, and it is now implemented server-side at N=1 (PRs #28/#29/#30 — wire + veneer + authz + bounded revocation). Supersedes the prior "client-facing watches are v2" deferral **for N=1**; N>1 multi-shard watch remains v3. |

### Documentation-staleness flags (found during audit — docs trailing the code)

- **`docs/known-limitations.md` (2026-04-25) is half-stale.** Its "mutation-testing not measured", "jacoco not
  run", "allocation profiling not run" claims are **obsolete** — mutation is now measured + gated (70/70/65
  floors), allocation profiling is captured (8.80×), edge-read perf is gated. Still **true**: JaCoCo
  line/branch coverage is *not* enforced anywhere, and no full soak has completed.
- **`docs/consistency-contract.md` §2 (line 74) + §7 INV-S2 row are stale** — they describe the deleted
  nanoTime staleness proxy; the code uses leader commit-timestamps (see 5.2).
- *(These are flagged, not fixed, here — this audit is the register only. A follow-up doc pass should
  reconcile them.)*

---

## §0.1 v1-blocker shortlist — **RECOMMENDATION** (the readiness review draws the actual ship line)

These are the auditor's judgments about which 🟡/❌/🔬 items genuinely block an **unqualified "v1
production-ready"** claim, versus those that can ship as **documented v2 limitations**. This is a
recommendation for the go/no-go, **not** a decision.

> **Reconciled 2026-07-01 (go/no-go review, authoritative) — this frozen 2026-06-26 recommendation is
> largely resolved.** **#2** (ADR-0030/0032 *Proposed*) — both **Accepted 2026-06-27**
> (`docs/decisions/adr-0030-quicksilver-shaped-topology.md:5`, `adr-0032-linearizability-harness.md:5`).
> **#4** (DR drills never executed) — **executed on metal** (372 ms failover, 0/1000 loss ×3, RTO 4.2/5.9 s;
> `docs/measurement/ec2-2026-06-30/02-dr-drills.md`). **#1** (empirical validation deferred) —
> **substantially discharged** (6 h soak, DR drills, near-linear N×knee measured); residuals only (no
> 24 h/72 h soak, no literal 10 k/s, no WAN). **#3** (encryption at rest) — **decided accept-as-v2** (D-2).
> **#5** (live-cluster linz not in cloud CI) — carried forward as go/no-go condition **C3**. Net: **no
> absolute, deployment-independent blocker survives → GO-WITH-CONDITIONS.** Everything in §0.1 below (the
> numbered blockers **and** the "documented v2 limitations" list — including its "sharding server wiring +
> N×knee is v2/EC2" bullet, now **superseded**: the wiring landed on main and the N×knee is measured, see
> §2.6–2.11) is the frozen 2026-06-26 auditor's snapshot; the reconciled per-row status is in §1–§11 and the
> go/no-go holds the decision.

> **Erratum 2026-07-01 (Gate 3, post-go/no-go) -- #7's "alert on the drop" mitigation does not exist.** The
> go/no-go itself repeated this phantom, so it is corrected here rather than folded into the reconciliation
> above. The leader-side over-cap `InstallSnapshot` drop (`RaftNode.sendInstallSnapshot`) increments **no
> metric**; the only snapshot alert, `ConfigdSnapshotInstallStalled`, fires on the **receiver-side**
> `configd_snapshot_install_failed_total`, which the over-cap frame never reaches, so it **cannot** cover
> this drop. Detection is **log-watch only** (grep `snapshot too large for v1 wire`). Corrected live in
> `docs/operations/known-limitations.md` ("Snapshot size cap" / "Encoder-drop observability") and
> `docs/operations/deployer-must-know.md` section 4. The frozen #7 row below is left as-authored.

### Recommended **blockers** for an unqualified production claim

1. **Empirical validation is deferred to production observation** (11.12, 9.7, 9.8, 7.5). No completed
   soak (24 h OOM'd at 3.45 h), no executed DR drills, load/chaos deferred. `known-limitations.md`:
   *"code-level production-ready, not empirically production-ready … an empirically-unproven contract."*
   This is a recorded **explicit user choice** — so it blocks an *unqualified* claim but is shippable as
   an explicit **burn-in / early-adopter posture**. **The single biggest ship-risk lever.**
2. **ADR-0030 (single-Raft-group topology) and ADR-0032 (linearizability harness) are *Proposed, not
   Accepted*** (11.1, 10.2). The structural decision the *entire consistency contract* rests on, and the
   ADR behind the linearizability proof, are formally unratified. **Cheap to clear** — ratify or revise
   before v1.
3. **Encryption at rest is absent and is an OPEN gap, not a decided non-goal** (8.2). Config values
   (including `secure/` strong-read keys) are plaintext `byte[]` in the control-plane HAMT/WAL/snapshot
   (`security-report.md` MAJOR-4; RR-098 OPEN). **Blocks if v1 stores secrets / has a compliance bar;**
   otherwise needs an explicit accept-as-v2 decision. (Edge at-rest exposure is bounded — the edge store
   is in-memory today.)
4. **DR drills have never been executed** (7.5). `ops/dr-drills/` holds only a README stating *"no drills
   have been executed."* Recommend running at least **restore-from-snapshot** + **leader-loss** drills
   once on the release commit before any operational-readiness claim.
5. **Live-cluster linearizability is not in cloud CI** (10.2). The trusted `anishathalye/porcupine`
   checker self-test (8/8) and sim-history *are* gated, but the **live multi-process iptables-faulted
   matrix** (the path that originally exposed the R-01 race) is `GATE2_FAULTED`-gated → self-hosted/nightly
   only. Recommend a green self-hosted faulted run captured on the release commit.

### Conditional / posture blockers (cheap to clear; recommend clearing)

6. **Security is wired but off-by-default** (8.3, 8.6, 8.8, 8.9). Auth, TLS/mTLS, audit, replay are all
   OFF unless flagged on (loud warning); ACL provisions a single `root→ALL` grant. Mechanisms are
   genuinely wired into the live path (the historical "interface-only" caveat is **stale**) — so this is a
   **secure-by-default / required-operator-config** hardening + documentation item, not a code blocker.
7. **Snapshot 4 MiB cap** (1.7, 3.10, 11.9). A follower needing a >4 MiB `InstallSnapshot` **wedges** —
   the over-cap frame is silently dropped to stderr (`RaftNode.java:2074-2078`). Ship as a **documented
   limitation** with the operational mitigation (keep snapshot <4 MiB; alert on the drop + matchIndex lag);
   chunked install is v2. Blocks only if expected state >4 MiB.

### Ship as **documented v2 limitations** (not v1 blockers)

- **Sharding / multi-Raft production deployment** (2.6–2.9, 2.11) — v1 ships **single-group by design**
  (ADR-0030); the sharding *logic* is sim-verified, the *server wiring* + N×knee measurement are v2/EC2.
- **Watches: server-side protocol done (N=1), no client driver yet** (4.8, 6.6) — the RFC §2 watch wire
  protocol is implemented server-side (PRs #28/#29/#30); the remaining gap is a conforming client driver
  (next arc) + the deployment security model (segregate watch clients from the legacy SUBSCRIBE path) +
  N>1 (v3). Clients without the driver still pull via HTTP + the edge delta-stream.
- **Client-SDK maturity** (6.6–6.11) — no write-SDK (writes are raw HTTP), Java-only, partial/stale docs.
- **Per-shard observability** (2.8, 7.10) — irrelevant at N=1 (the v1 topology); becomes real only with
  sharding (v2).
- **BATCH API** (11.7), **cross-region/WAN** (4.11, 9.11) — planned/single-region; v2.
- **Dead code** (5.11 BloomFilter, 10.12 Buggify, 4.10 RolloutController) — wire or delete; tech-debt, no
  user impact.

---

## §1. Consensus core (Raft)

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 1.1 Leader election + term safety | ✅ | `RaftNode` becomeFollower-on-higher-term, persisted `currentTerm/votedFor`; `ConsensusSpec` `ElectionSafety` (tlc-model-check job); `VotePersistenceCrashTest`, `RaftNodeTest` | — |
| 1.2 Log replication / AppendEntries | ✅ | `AppendEntriesRequest/Response`; `RaftNodeReplicationUnitTest`, `NextIndexWalkBackTest`; ConsensusSpec `LogMatching` | — |
| 1.3 Commit + single-writer apply (owner-thread tripwire) | ✅ | single `applyCommitted()` loop `RaftNode.java:2186` + `assertOwnerThread()` `:493`; jcstress `RaftOwnerThreadGuardTest`; `apply_owner_thread` twin fires in `AssertionTwinFiringTest` (RR-029/W-1) | — |
| 1.4 Membership change / joint consensus | ✅ | `ClusterConfig.joint()` dual-majority `:117-123`; `ReconfigurationTest`, `ReconfigPathUnitTest`; ConsensusSpec `ReconfigSafety` | Mechanism sim-verified; no wired prod admin reconfig trigger (`proposeConfigChange` has no live caller) |
| 1.5 **ReadIndex linearizable reads** | ✅ | Real protocol: `readIndex():767` records (commitIndex,term), `confirmPendingReads():2622` via heartbeat-quorum, role/term re-check `:798`; INV-RI-2/3/4 twins `assertReadServeInvariants():928`; `ReadIndexSpec.tla` (TLC 12.4M states, tlc job); `ReadIndexStateTest`, `ReadIndexLinearizabilityReplayerTest` | — |
| 1.6 Leader lease / no-stale-serve | ✅ | CheckQuorum step-down `:1495`; read serve re-checks `role==LEADER` `:798` + `no_stale_leader_serve` twin `:940` | **Quorum/heartbeat-based ReadIndex + CheckQuorum + role/term recheck, NOT a timed clock lease** — "lease" is loose terminology |
| 1.7 Snapshot install + 4 MiB cap | 🟡 | `handleInstallSnapshot():552` (SnapshotInstallSpec twins); `InstallSnapshotTest`, `SnapshotCrashRecoveryTest` | **Single-blob, no chunking**; >4 MiB snapshot can't transfer → lagging follower can't bootstrap (v0.2) |
| 1.8 Pre-vote / election stability | ✅ | `handlePreVoteRequest/Response:1701/1758`; `RaftNodeTest.PreVoteTests.preVotePreventsTermInflationFromPartitionedNode:415` | Δ pre-fill 🟡→✅ (discriminating test exists). Not in ConsensusSpec.tla |
| 1.9 Write-ack taxonomy (ADR-0033) | ✅ | `CommitOutcome.Kind{COMMITTED,LOST,INDETERMINATE_LOCALLY}` + `ProposeOutcome{NOT_LEADER,…,OVERLOADED}`; mapping `ConfigWriteService.java:299` + `ConfigdServer.java:1334`; **ADR-0033 Accepted**; `CommitOutcomeSeamTest` | Δ pre-fill named `HttpApiServer` — actual mapping is `ConfigWriteService`+`ConfigdServer` |
| 1.10 Heartbeat coalescing (Phase 0 M3) | ✅ | `HeartbeatCoalescer`/`CoalescingRaftTransport`; `HeartbeatCoalescerTest`, `CoalescedHeartbeatLivenessTest`; gate-phase0 | — |
| 1.11 TLA+ ConsensusSpec model-check | ✅ | `ConsensusSpec.tla` 10 invariants; `tlc-model-check` CI job | — |
| 1.12 Strong-read fail-closed (ADR-0030 INV-1) | ✅ | `StrongReadPolicy.java:11`; `StrongReadPolicyTest`, `EdgeStrongReadFailClosedTest`, `StrongReadKeyClassTest` | † **discharged 2026-07-01:** governing **ADR-0030 is now Accepted** (ratified 2026-06-27, pre-EC2 cleanup; `docs/decisions/adr-0030-quicksilver-shaped-topology.md:5`) — was *Proposed* at audit time. Mechanism + tests are real |

## §10. Correctness / verification

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 10.1 Deterministic simulation (ADR-0007 Accepted) | ✅ | `RaftSimulation` seeded PRNG; `SeedSweepTest` 10k seeds (build-and-test job) | — |
| 10.2 **Linearizability (Porcupine)** | ✅ † | Real `github.com/anishathalye/porcupine v1.2.0` (the etcd checker, NOT hand-rolled); gate-1 step b builds + runs `CheckerSelfTest` **8/8**; gate-2 checks sim-history LINEARIZABLE (`SimHistoryCheck`); ADR-0032 | † the **live multi-process iptables-faulted matrix is `GATE2_FAULTED`-gated → skipped in cloud CI** (self-hosted/nightly only) — carried forward as go/no-go condition **C3**. **ADR-0032 discharged 2026-07-01: now Accepted** (ratified 2026-06-27; `docs/decisions/adr-0032-linearizability-harness.md:5`) — was *Proposed* at audit time |
| 10.3 TLA+ specs (Consensus/ReadIndex/SnapshotInstall) | ✅ | all three `.tla` in `spec/`; `tlc-model-check` job runs all; cross-check replayers | — |
| 10.4 Invariant net / runtime assertions + twins | ✅ | `InvariantMonitor` + `RaftNode.InvariantChecker`; `AssertionTwinFiringTest:55` drives every twin to fire; gate-2 step (g) | — |
| 10.5 jcstress concurrency (gate-2) | ✅ | configd-jcstress 10 tests; gate-2 step (f) uber-jar, no forbidden outcomes | — |
| 10.6 Property-based fuzzing | ✅ | `FrameCodecPropertyTest`+`FrameCodecFuzzTest` (corpus), `RaftMessageCodecPropertyTest`, `EdgeFrameCodecFuzzTest` | — |
| 10.7 Mutation testing (PIT) | ✅ | `pitest-maven` floors 70/70/65; gate-2 step (e) **fails build under floor** on nightly; measured 73.1%/72.8% | Δ known-limitations "not measured" is **STALE** |
| 10.8 mini-Jepsen nemesis | ✅ | `MiniJepsenSweepTest` (5-node mixed faults, safety oracle every tick); gate-4 `nightly-jepsen` `gate-4.sh:165` | nightly-only |
| 10.9 Wire-format golden fixtures | ✅ | `GoldenFixtures` 16 msgs + `WireCompatGoldenBytesTest`; `wire-compat` CI job fails on fixture change w/o `WIRE_VERSION` bump | — |
| 10.10 Assertion twins firing | ✅ | `AssertionTwinFiringTest` (consensus-core + config-store); gate-2 step (g) | — |
| 10.11 Coverage measurement (JaCoCo) | 🟡 | jacoco present **only** to feed PIT (`pom.xml:356`); **no `check` goal / no minimum ratio** | Line/branch coverage **not enforced** (only mutation score is gated) |
| 10.12 Buggify fault injection | 🟡 | `Buggify`/`BuggifyRuntime` + `BuggifyRuntimeTest` self-test exist | **Δ ✅→🟡 — ZERO call sites**; sim uses `SimulatedNetwork`. Dormant shelfware |

## §2. Sharding / multi-Raft

> **Headline:** the sharding *logic* is fully sim-verified and CI-gated; the *production server wiring* is
> **deliberately deferred** (charter hard-rule "stop clean beats finish dirty"). **v1 deploys single-group
> (N=1), byte-identical to today.** N>1 is not deployable; the N×knee aggregate is unmeasured.
>
> **Reconciled 2026-07-01 (go/no-go §2.2, authoritative) — this headline is now STALE.** The server wiring
> has since **landed on main and was exercised on metal**: `ConfigdServer` wires `StaticShardMap` + an
> `addGroup` loop + `shardFor` routing (2.6/2.7/2.9 ✅ below), and the sharded aggregate N×knee is
> **measured near-linear 2.45×/3 machines** (2.11/9.2 ✅). **v1 still defaults to N=1** (byte-identical);
> **N>1 + the edge endpoint fail-closes** unless explicitly opted in (`ConfigdServer.java:247-259`), and
> sustained N>1 additionally needs the leadership-balancing follow-up (go/no-go §3.2).

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 2.1 ShardMap + StaticShardMap (hash-within-scope) | ✅ | `StaticShardMap.java` FNV-1a+SplitMix64+`floorMod`, no `groupId==0` special-case; `StaticShardMapTest` 12/0 | — |
| 2.2 N=1 default byte-identical | ✅ | `MultiShardSimTest.nEqualsOne_byteIdenticalToSingleGroup:226` vs single-group control + vacuity guard | **Note corrected 2026-07-01:** the server **does** now wire `StaticShardMap` (2.6); prod N=1 identity holds because `new StaticShardMap(1)` maps every key to **group 0** (a degenerate one-shard map), **not** because ShardMap is absent |
| 2.3 Multi-shard sim + 6 invariants (10k seeds) | ✅ (sim) | `MultiShardSimTest` 73/0; 10,033/0 at ≥10k seeds; 5/6 invariants have injected-RED non-vacuity proofs | 6th (cross-shard isolation) is **structural** (independent harnesses); non-vacuous shared-node proof deferred to EC2-prep |
| 2.4 CrossShardWriteGuard (DISCLAIM) | ✅ | `CrossShardWriteGuard.java`; `CrossShardWriteGuardTest` 7/0; N=1 never rejects | — |
| 2.5 Owner-executor pool / per-owner tick (Phase 0) | ✅ (prod-wired) | `OwnerExecutorPool`/`MultiRaftDriver`; live `ConfigdServer.java:389` + per-owner `tickOwner` `:877` | — |
| 2.6 **Production server N-group wiring** | ✅ (was ❌ deferred; reconciled 2026-07-01) | `ConfigdServer.java:261` `new StaticShardMap(shardCount)`, `:520-546` `addGroup(gid)` loop over `shardMap.shardIds()`, `:795`/`:836` `shardFor(scope,key)` routing (write + read); landed on main + exercised on metal (go/no-go §2.2) | v1 **defaults N=1**; **N>1 + edge fail-closes** (`:247-259`) unless `-Dconfigd.edge.allowPartialShardView=true`. The 2026-06-26 audit's "no ShardMap usage" is superseded |
| 2.7 RaftTransportAdapter groupId routing fix | ✅ (was ❌ deferred; reconciled 2026-07-01) | `RaftTransportAdapter.java:56` stamps the per-frame `groupId` (`RaftMessageCodec.encode(message, groupId)`); `:111` routes inbound by `frame.groupId()` to that group's owner — **no wire-format change** (groupId already in the header). go/no-go §2.2 | groups no longer collapse onto 0; at N=1 every frame is group 0 ⇒ byte-identical |
| 2.8 **Per-shard observability (C4)** | 🟡 (was ❌ deferred; reconciled 2026-07-01) | Seam E per-shard health gauges added — `ConfigdServer.java:610` `registerPerShardMetrics(...)` (per-group leader/term/commit-index/apply-lag + per-node leader count). go/no-go §2.2 | **Partial:** node-level apply-backlog gauge + election counter are **still group-0-only** (`:1063-1079` `if (owner == 0)`). Only material at N>1; ties to 7.10 |
| 2.9 CoalescedHeartbeat wire frame | ✅ (was ❌ deferred; reconciled 2026-07-01) | `RaftMessageCodec.encodeCoalescedHeartbeat:227`; `FrameCodec.WIRE_VERSION=(byte)0x02` (`FrameCodec.java:74`) — the 0x02 wire is live on main. go/no-go §2.2 | **Not exercised at N=1** (a single group sends per-group frames); material only at N>1 |
| 2.10 **Rehoming / dynamic resharding** | ⛔ dormant | `DL-P1-07`; `MultiRaftDriver` rehoming "DORMANT in production"; **D-016 re-verify-on-activation does NOT fire** | activation requires re-verification |
| 2.11 **Sharded aggregate N×knee** | ✅ (was 🔬; reconciled 2026-07-01) | **Measured on metal:** `docs/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md` — 656→1075→1607 committed w/s at N=1/2/3 leader-machines, **2.45×/3 machines**, near-linear (~+475 w/s/machine), churn-bound (CPU ~62 %, NIC <1 %) | the `~800×N×0.75` *model* replaced by a *measured* curve (~535 w/s/group cross-machine). Sustained N>1 needs leadership balancing (go/no-go §3.2) |
| 2.12 gate-phase1 CI-wired | ✅ | `ci.yml:511` `gate-phase1 needs: gate-B`; 200-seed PR / 10k nightly + non-vacuity assert | — |

## §3. Durability & storage

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 3.1 Raft WAL append-before-ack | ✅ | `RaftLog.append→storage.appendToLogNoSync` (`:349`); durable-first ordering proven by `StorageEnospcConsensusReactionTest:76` | — |
| 3.2 **fsync-before-ack / no early-ack** | ✅ | ENFORCED: self counts toward quorum only if `durableIndex>=n` (`RaftNode.java:2115`), which advances only after `syncWal()` fsync; TESTED `GroupCommitDurabilityTest:112`, `RaftProposerCommitConfirmTest:156` | † gate test uses in-memory log (tests the *gate logic*); physical platter-write is covered separately by `force(true)` + ENOSPC ordering — no single end-to-end "byte-on-platter-before-200" test |
| 3.3 DurableRaftState (term/vote) | ✅ | `DurableRaftState.persistValues:163` (put+sync) before in-memory update; `DurableRaftStateTest` | — |
| 3.4 Snapshot on-disk format (ADR-0028 Accepted) | ✅ | `SNAP_MAGIC` integrity envelope `RaftLog.java:681`; `SnapshotState` record | — |
| 3.5 Snapshot+WAL+RaftState integrity HMAC (ADR-0042 Accepted) | ✅ | `IntegrityEnvelope` HMAC-SHA-256 keyed (fail-closed) / CRC32C keyless (`:104-284`); wraps WAL/snapshot/raft-state | **INTEGRITY (tamper-detection), NOT encryption** |
| 3.6 **Crash-injection durability test** | ✅ | `SnapshotCrashRecoveryTest` (3-point crash matrix + 60-seed sweep + fsync-LIE cell + **real-FileStorage torn-tail** `:155`); `StorageEnospcConsensusReactionTest` (disk-full); gate-4 (`ci.yml:230`) | crash is a faithful **MODEL** (`CrashStorage`), not OS kill-9; disk-full IS exercised; **no fsync-stall cell**; real power-cut ENV-BLOCKED |
| 3.7 durable_prefix_no_gap (RR-003) | ✅ | `applyCommitted` refuses to advance past a gap `RaftNode.java:2203`; recovery-side ctor check `:359`; `SnapshotCrashRecoveryTest.gapDetectionFires…` | — |
| 3.8 Config store (VersionedConfigStore, HamtMap) | ✅ | `HamtMapTest`/Property/Collision + `VersionedConfigStoreTest`/Property/Concurrency/Allocation | — |
| 3.9 Compaction | ✅ | Raft-log: `RaftNode.maybeCompact` via `MultiRaftDriver:174` (RR-005), `RaftLogCompactionTriggerTest`; config-snapshot: `Compactor`+`CompactorTest` | — |
| 3.10 Snapshot 4 MiB cap / chunked deferred | 🟡 | `MAX_SNAPSHOT_BLOB_LEN=4*1024*1024` (`RaftMessageCodec.java:74,111`); leader always sends `offset=0,done=true`; **over-cap snapshot DROPPED to stderr** (`RaftNode.java:2074`) | follower-wedge risk (v0.2 chunking) — surface in known-limitations, not just "chunking deferred" |
| 3.11 Storage abstraction + real fsync | ✅ | `FileStorage.force(true)` real (data `:88`, dir-fsync `:96`, log `:136`); `FileStorageTest` | — |
| 3.12 Recovery/restart from WAL+snapshot | ✅ | `RaftLog` ctor replays WAL + recovers snapshot (`:137`); `RaftNode` ctor restores SM before replay; `SnapshotCrashRecoveryTest.recoversCleanlyFromTornFinalWalRecord:155` real FileStorage | — |

## §4. Global replication / fan-out

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 4.1 Plumtree fan-out (ADR-0003) | ✅ | `PlumtreeNode` + `PlumtreeNodeTest`; ticked `ConfigdServer.java:914` | — |
| 4.2 HyParView overlay (ADR-0011) | ✅ | `HyParViewOverlay` + test; wired `:851` | — |
| 4.3 Commit notification source (§4.6) | ✅ | published `ConfigdServer.java:563`; codec round-trip `EdgeFrameCodec:265`; `CommitNotificationSourceTest` | — |
| 4.4 FanOutBuffer evict-before-overwrite (ADR-0036/RR-096) | ✅ | `FanOutBuffer` + `FanOutBufferRaceTest` | — |
| 4.5 Slow-consumer governor / backpressure | ✅ | `fanout/SlowConsumerGovernor` + `SubscriberOverflowDemotionTest`, `QuarantineReBootstrapTest` | — |
| 4.6 Signed-chain streaming (ADR-0038) | ✅ | `FanOutSessionCore`; `FrameBatchingChainIntegrityTest`, `FullChainDeliveryTest` | — |
| 4.7 Edge frame wire format | ✅ | `EdgeFrameCodec` + golden/property/fuzz/boundary tests | — |
| 4.8 **Watches** | ✅ (N=1, server-side) | RFC §2 watch protocol implemented server-side on the edge endpoint: wire `0x02` + `WATCH_*` frames + cursor vector, multiplex/filter veneer, whole-target authz gate, target-filtered delivery + catch-up snapshots, **bounded revocation (W7-7)** (PRs #28/#29/#30); the unrelated legacy in-process `WatchService` stays server-internal | **DECISION UPDATED 2026-06-29: the v1 (N=1) client watch protocol is now built server-side** (supersedes the 2026-06-27 "watches are v2" decision for N=1). Caveats: **no shipped client driver yet** (next arc); watch ACL conditional on SUBSCRIBE-path segregation; single-scope at N=1; **N>1 multi-shard watch = v3** (fail-closed). See known-limitations §2. |
| 4.9 Subscription manager / prefix subs (ADR-0020) | ✅ | `SubscriptionManager` wired `:852`; `SubscriptionManagerTest`; edge `PrefixStorageFilter` | — |
| 4.10 Rollout controller (ADR-0008) | 🟡 | constructed `:526` + accessor `:1565` | **Parked library — zero `rolloutController.` call sites in main**, no rollout endpoint |
| 4.11 Cross-region / WAN replication | ⛔ | single-region per ADR-0030/ADR-0024 (Accepted, defers per-DC Raft to v0.2); WAN leg modeled only in `EdgeStalenessDistributionLoadSimTest` | no cross-region path in code; WAN staleness 🔬 unmeasured |
| 4.12 gate-3 CI-wired | ✅ | `ci.yml:186` runs `gate-3.sh` push/PR + nightly; 4-phase 3CP+3edge `e2e-compose-scenario.sh` runs as a gate-3 step | — |

## §5. Edge replicas / read plane

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 5.1 Lock-free edge reads (ADR-0005) | ✅ | `LocalConfigStore`; gate-3 step (g) jmh-gc-check asserts 0 alloc on read path (CT-34) | — |
| 5.2 **Bounded-staleness measurement** | ✅ | commit-ts load-bearing end-to-end: leader `ConfigdServer.java:562` → wire `EdgeFrameCodec:265` → edge `DeltaApplier:263` → `StalenessTracker.recordUpdate:170`; idle-proxy **deleted** | **Δ 🟡→✅.** Caveat: commit-ts = leader wall-clock at apply (not HLC); skew bounded by `SKEW_ALLOWANCE_MS=50ms` + CT-08 tripwire |
| 5.3 Staleness state machine (thresholds) | ✅ | `StalenessTracker.currentState()` 500/5000/30000 ms; `StalenessUpperBoundTest`, `StalenessTrackerTest` | — |
| 5.4 Monotonic reads (VersionCursor) | ✅ | `ConsistencyPropertyTests$MonotonicReadTest`; `LocalConfigStoreTest$MonotonicReadInvariant` | — |
| 5.5 Read-your-writes (same-region) | ✅ | `ConsistencyPropertyTests$ReadYourWritesTest` | — |
| 5.6 Edge failover (cursor-behind refuse) | ✅ | `EdgeFailoverTest`; `MonotonicReadFailoverTest`; `MonotonicReadAcrossEdgeRestartTest` | — |
| 5.7 Edge bootstrap / cold start | ✅ | `SnapshotReplaySource`; `CatchUpProtocolTest`, `BootstrapSnapshotBackpressureTest`, `SnapshotChunkResumeTest` | — |
| 5.8 Poison pill / negative cache (ADR-0040) | ✅ | `PoisonPillPolicy` wired `EdgeClientCore:316`; `PoisonPillDetectorTest` | — |
| 5.9 Strong-read key class at edge | ✅ | `StrongReadKeyClass` wired in `EdgeClientCore`; server fail-closed `AdminApiHandler:200` | — |
| 5.10 Frontier staleness (ADR-0039) | ✅ | `StalenessTracker.recordFrontier`; `EdgeFrame.Heartbeat(latestSeq, serverNowMillis)`; `EdgeStalenessFrontierSimTest` | — |
| 5.11 Delta applier / bloom filter | 🟡 | `DeltaApplier` wired `EdgeClientCore:331` | **Δ ✅→🟡 — `BloomFilter` DORMANT, no main caller** (only class + tests) |
| 5.12 **p99 staleness distribution (INV-S2)** | 🟡 | `EdgeStalenessDistributionLoadSimTest:69` asserts `p99<500ms`/`p9999<2s` over real frontier; `EdgeStalenessDistributionSimTest` (gate-3, mechanism-only) | the real-bound test is **local-component-only, sim-level, NOT gate/CI-pinned**; global/WAN p99 ENV-BLOCKED |

## §6. Client SDK

> **Architectural framing (the key finding):** there is **no app-embeddable client SDK jar**. The
> application-facing surface is **HTTP pull** (`GET /v1/config/{key}` + `X-Configd-Cursor`) served by a
> local **edge-node sidecar**. What looks like the "client" (`EdgeConfigClient`/`EdgeClientCore`/
> `EdgeStreamClient`) is the **edge node's own internal session engine** — well-built and heavily tested,
> but not an application API. Read path is production-grade; the developer-product layer is thin.

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 6.1 Edge config client | ✅ | `EdgeConfigClient` read `:98/:113` + subscribe `:253`; `EdgeConfigClientTest` (20) | exposes **read + subscribe only**, no client write |
| 6.2 Read API with cursor | ✅ | `EdgeConfigClient.get(key,cursor)`; HTTP `EdgeReadHandler:151` (`X-Configd-Cursor`→404 `cursor-behind`); `getWithCursorAheadOfStoreReturnsNotFound` | — |
| 6.3 Subscription / streaming client | ✅ | `EdgeStreamClient` (mTLS/subscribe/reader+writer/reconnect); `EdgeClientCoreTest` (~30), `EdgeNodeIntegrationTest` | delivers into the edge **local store**, not per-key app callbacks |
| 6.4 Reconnect / failover | ✅ | `EdgeStreamClient.sessionLoop:259` round-robin + `failoverResumeCursor`; **`EdgeFailoverTest`** | **Δ 🟡→✅** |
| 6.5 Cursor management | ✅ | `VersionCursor` record + `INITIAL`/`isNewerThan`; `VersionCursorTest` (15) | — |
| 6.6 Watch API client-side | ✅ (protocol, N=1) | the RFC §2 watch wire protocol (`WATCH_CREATE`/`CANCEL`/`EVENT`/…, `0x02`) is served over the mTLS edge endpoint; a remote client CAN now create/cancel/multiplex watches and receive filtered events + bounded revocation | **the server protocol is live (N=1)**; the remaining piece is a **conforming client driver** (RFC §1+§2+§3) — the next arc. No HTTP/SSE route (the binary edge wire is the surface, by design — W2-7). |
| 6.7 Write API in SDK | 🟡 | writes are HTTP-only; only Java HTTP-write client is the `configd-linz` test harness `ConfigClient.java:70` | **no SDK write client** — apps hand-roll HTTP PUT/DELETE |
| 6.8 Language support | 🟡 | Java only across all `src/main` | no polyglot bindings (v1 Java-only) |
| 6.9 Client backpressure / flow control | 🟡 | bounded queues `EdgeStreamClient:101` (TCP backpressure) + demotion→snapshot `EdgeClientCore:589` | no client-side credit/flow-control class (relies on TCP + server governor) |
| 6.10 SDK docs / examples | 🟡 | `docs/wiki/Integration-Guide.md`, `Getting-Started.md` (real `LocalConfigStore`/cursor/staleness examples) | **Δ ❌→🟡** — covers only the low-level embeddable store; no HTTP/streaming/write client; **build steps stale** (Gradle/Java-21) |
| 6.11 Error handling / retry semantics | 🟡 | ADR-0033 outcomes emitted server-side; client retry/redirect (`X-Leader-Hint`, 504/429) implemented + tested **only** in harness `ConfigClient.java:98` | no shipped SDK surfaces the outcomes — apps implement; reference is in the linz test harness |
| 6.12 Poison-pill handling client-side | ✅ | `PoisonPillPolicy:132` (ADR-0040 ladder) wired `EdgeClientCore.applyNotification:437`; `PoisonPillRebootstrapTest` | — |

## §7. Operations / Day-2

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 7.1 Metrics (exporter wired) | ✅ | `PrometheusExporter`→`GET /metrics` (bearer-gated) `HttpApiServer.java:38`; gate-6 `EdgeMetricsContractTest` | — |
| 7.2 SLO definitions + tracking | ✅ | `ProductionSloDefinitions`, `SloTracker`; gate-6 `MetricsWiringContractTest` | — |
| 7.3 Burn-rate alerting (gate-6) | ✅ | `BurnRateAlertEvaluator`; `ops/alerts/configd-slo-alerts.yaml`+`.test.yaml` promtool fires/quiet in gate-6 | — |
| 7.4 Runbooks | ✅ | **24 runbooks** (`ops/runbooks/` 15 + `docs/runbooks/` 9: disaster-recovery, control-plane-down, raft-saturation, restore-from-snapshot, leader-stuck…); gate-6 `GameDayDrillTest` maps alert→runbook→recovery | — |
| 7.5 **DR drills / game-day** | ✅ (was 🟡; reconciled 2026-07-01) | **Executed on metal:** `docs/measurement/ec2-2026-06-30/02-dr-drills.md` — 3 fault modes: leader-kill under load (**372 ms** failover, 1 bounded election, **0/1000** write loss), WAL-replay restart (RTO **4.2 s**, 0 loss), wipe+InstallSnapshot (RTO **5.9 s**, 0 loss). Scripts + gate-6 `GameDayDrillTest` unchanged | Caveat: single-box 3-co-located topology (cross-machine adds RTT; correctness — no loss, bounded election — is topology-independent) |
| 7.6 Health / readiness | ✅ | `HealthService.readiness()`; `AdminApiHandler:125/128` `GET /health/live` + `/ready`; raft-leader check `:618` | impl is `AdminApiHandler`/`HealthService`, not a `ReadinessHandler` (naming only) |
| 7.7 Deployment | 🟡 | `docker/`, `deploy/compose/` (compose+secrets), `deploy/kubernetes/` (statefulset+bootstrap); gate-3 Compose E2E | `smoke-multinode.sh` is control-plane-only; **no real multi-host/production deploy** evidenced (single-runner Compose only) |
| 7.8 On-call rotation (ADR-0025) | 🟡 | `adr-0025`; runsheet step 7 | operator-procured pre-req; no evidence rotation is active (not code) |
| 7.9 Operator runsheet | ✅ | `docs/operator-runsheet.md` (341 lines; rollback triggers, on-call) | — |
| 7.10 **Per-shard observability** | 🟡 (was ❌; reconciled 2026-07-01) | Seam E per-shard health gauges added (`ConfigdServer.java:610` `registerPerShardMetrics`); mirrors 2.8. go/no-go §2.2 | **Partial:** node-level apply-backlog gauge + election counter still group-0-only (`:1063-1079` `if (owner == 0)`). Only material at N>1; ties to 2.8 |
| 7.11 gate-6 operability CI-wired | ✅ | `ci.yml` `gate-6 needs: gate-5` + promtool; capture `session-6/captures/gate-6-local-green.txt` | checked-in capture labeled "local-green" (CI job is wired) |
| 7.12 Alert thresholds (PROPOSED vs enforced) | 🟡 | `configd-slo-alerts.yaml` (concrete expr/for/severity) promtool fires/quiet-tested in gate-6 | rule mechanism CI-enforced; **threshold VALUES are design-set, not calibrated** against measured production SLO |

## §8. Security

> **Two headline findings:** (1) **Config data is NOT encrypted at rest** (integrity ≠ confidentiality) —
> a registered OPEN gap. (2) **Security mechanisms ARE wired into the live path** (the historical
> "interface-only" caveat is stale) **but are off-by-default** — only rate-limiting is unconditionally on.

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 8.1 At-rest **integrity** HMAC (ADR-0042 Accepted) | ✅ | `IntegrityEnvelope.java:104` keyed HMAC-SHA256 + CRC32C fail-closed; gate-7 `SnapshotIntegrityTest`/`WalRecordIntegrityTest`/`DurableRaftStateIntegrityTest` | **integrity / tamper-detection, NOT encryption** |
| 8.2 **Encryption at rest** | ❌ → v2 | **no `javax.crypto.Cipher`/AES anywhere in `src/main`**; `security-report.md` MAJOR-4 "config values plaintext, no encryption at rest"; **RR-098 OPEN → v2** | config (incl. `secure/` keys) plaintext in control-plane HAMT/WAL/snapshot. **DECIDED 2026-06-27: accept-as-v2** (operator). `secure/` is a read-*freshness* class, **NOT** confidentiality; "do not store secrets" documented (known-limitations / README / Integration-Guide / consistency-contract). (edge store is in-memory → bounded exposure) |
| 8.3 TLS / mTLS in transit | ✅ | `NettyRaftTransport:301` + `NettyFanOutServer:215` `setNeedClientAuth(true)`; fail-closed `ConfigdServer:345`; gate-7 `RaftTransportMtlsAttackTest`, `EdgeTransportSanMismatchTest` | mTLS required **when TLS configured**, but **TLS is off-by-default** (plaintext single-node); admin HTTP is server-TLS+Bearer, not mTLS |
| 8.4 Config signing (sign-or-fail-close, ADR-0027 Accepted) | ✅ | `ConfigStateMachine.signCommand` re-throws on failure (`:620`); Ed25519 `ConfigSigner:51`; `ConfigStateMachineTest.signFailureFailsClose` | — |
| 8.5 Signing-key co-location (D-1) | ✅ | `enforceSigningKeyNotColocated:1066` default `SecurityException`; `D1FailClosedTest`; ADR-0043 | **Δ 🟡→✅.** Caveat: ADR-0043 claims default-relocation but code still defaults to `dataDir` → **server refuses to boot out-of-box** (secure, not as ADR describes); no gate-7.5; HKDF crypto-review pending |
| 8.6 Authentication | 🟡 | wired `AdminApiHandler:159/393`; `AuthInterceptor` constant-time compare | **OFF by default** (only with `--auth-token`; loud warning when off) |
| 8.7 Authorization / ACL | 🟡 | `AclService:27` longest-prefix default-deny; wired `checkAuth:388` | server provisions only a single `root→ALL` grant; no multi-principal ACL config surface |
| 8.8 Replay protection | 🟡 | `ReplayGuard` (timestamp window + LRU nonce); wired `:458`; `ReplayGuardTest` (gate-7) | **OPT-IN, default OFF**; **passive-only** (token-holder can mint fresh requests) |
| 8.9 Audit log | 🟡 | `AuditLog` HMAC-chain persisted via durable `Storage.appendToLog`; wired `:765`; `AuditLogTest` (gate-7) | only active when auth enabled (off by default); tamper-**evident** not tamper-proof |
| 8.10 Rate limiting | ✅ | unconditional global 10k/s `ConfigdServer:646` + per-principal `ConfigWriteService:283`; gates before Raft propose | **Δ 🟡→✅** (only security control not off-by-default) |
| 8.11 Supply chain | 🟡 | gate-7 OWASP dependency-check (`failBuildOnCVSS=7`) + gitleaks; **nightly-only** (`ci.yml:420`, loud-skip on push/PR); `.gitleaks.toml` clean | CVE + gitleaks **not gated on every merge** |
| 8.12 gate-7 CI-wired + residuals | ✅ / 🟡 | gate-7 real CI job (`ci.yml:371` `needs: gate-6`), all steps negative tests | residuals OPEN: **F-S7-TLS-1 leaf-anchor cert-expiry**, **active-replay** (passive-only), **WAL tail-truncation**; slowloris/per-principal-RL/metrics-auth **CLOSED in S7.5** |

## §9. Performance

> **MEASURED:** single-group knee ~800/s, admission 460→848/s, io_uring vs Epoll fan-out, edge-read
> latency + alloc, m6i HTTP throughput. **MODELED only:** sharded N×knee aggregate. **UNMEASURED:**
> cross-region/WAN, dedicated-host knee, true sustained 10k/s & 100k burst, full soak.
>
> **Reconciled 2026-07-01:** the two EC2 runs moved several of these — the sharded N×knee is now
> **MEASURED** (2.45×/3 machines; 2.11/9.2), and the soak is **MEASURED to 6 h** (9.7). Still **UNMEASURED:**
> cross-region/WAN (9.11), dedicated-host knee, literal sustained 10 k/s / 100 k burst (9.8), full 24 h/72 h
> soak. go/no-go §3.

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 9.1 Single-group write knee ~800/s | ✅ | `multiraft/captures/wsC-ladder/primary/ladder.tsv`: 800→**799 achieved, 0 elections, stable**; 1000→637/15-elec collapse. m6id.4xlarge, c58ac1f | single-box 3-co-located; dedicated-host knee deferred (honest) |
| 9.2 **Sharded aggregate N×knee** | ✅ (was 🔬; reconciled 2026-07-01) | **Measured:** `docs/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md` — 656→1075→1607 w/s (N=1/2/3), **2.45×/3 machines**, near-linear, churn-bound. Mirrors 2.11 | model replaced by measured curve; sustained N>1 needs leadership balancing (go/no-go §3.2) |
| 9.3 Edge read throughput/latency | ✅ | `getHitWithCursor` p50=50ns/p99=140ns (JMH, gate-5); m6i HTTP **53,616 req/s @64 conn** | **Δ 🟡→✅.** Unloaded JMH + benchmark-box HTTP (not production concurrent load) |
| 9.4 Fan-out amplification (io_uring) | ✅ | `phase-v-io-uring.md §4`: io_uring 2.04M vs **epoll 4.02M notif/s @1024 subs** (~2× slower, ~8× worse tail); Epoll auto-default | — |
| 9.5 GC ZGC (ADR-0041 Accepted) + JMH gc-check | ✅ | `jmh-gc-check.sh` <1 B/op on getMiss+getIntoHit (gate-5 step b); ZGC bake-off captures | — |
| 9.6 Perf regression gate (gate-5) | ✅ | `gate-5.sh` (read p99<20µs, thrpt floor 50k, alloc<1B/op); `gate-5-real-ci-green.txt` (run 27489285072, 2026-06-14) | — |
| 9.7 **24h soak** | ✅ (was 🟡; reconciled 2026-07-01) — **6 h, NOT literal 24 h** | **6 h clean on metal:** `docs/measurement/ec2-2026-06-30/04-soak.md` — 21,601 s / 691 samples, past the prior 3.45 h OOM; **FD flat 350→350**, RSS 2.6 % spread, heap floor stable, GC 0.92 %, **0 rejected** of 9,000 at 300 w/s; "GATE PASSED" | **Honest caveat: 6 h ≠ literal 24 h/72 h** — the leak/OOM *risk* is closed on clean code (`ce7d719`); a full 24 h/72 h soak has still not completed |
| 9.8 **Sustained 10k/s + 100k burst** | 🟡 (was 🔬; reconciled 2026-07-01) | Characterized as a **horizontal-aggregate target** — the write path scales near-linearly in leader-machines (`docs/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`), so 10 k/s is reachable as a sharded aggregate (~17–19 leader-machines at ~535 w/s each) | **Neither literal number ever run:** single-cluster max measured = **1607 w/s** (3 machines); the 2-vCPU 10k/100k captures were saturation/shed, not achieved throughput. go/no-go §3.1 |
| 9.9 Admission control 460→848/s | ✅ | `wsC-ladder/admission/`: control 2000→460/27-elec collapse; maxInflight16 2000→**848/1-elec stable** (429 shed). `FlowController` maxInflightProposals=16 | — |
| 9.10 Transport Epoll vs io_uring | ✅ | `phase-v-io-uring.md` (m6i, all 4 surfaces); io_uring no throughput win + regresses at fan-out; Epoll auto-default | — |
| 9.11 **Cross-region / WAN latency** | 🔬 | `workstream-c-throughput.md §6` explicitly "not a cross-region absolute"; no WAN capture exists | UNMEASURED |
| 9.12 Allocation profiling hot paths | ✅ | `m1-edge-read-gc-proof.md`: edge-read **14,999→1,704 B/req (8.80×)** on production `NettyEdgeHttpServer`; read-path 0 B/op gated | **Δ 🟡→✅;** known-limitations "not run" stale. Benchmark harness, not production traffic |

## §11. Cross-cutting v1-scope decisions

| Item | Status | Evidence | Gap / note |
|---|---|---|---|
| 11.1 **Single Raft group topology** | ⛔ | `adr-0030` governs `consistency-contract.md` §5; code-verified single-group | **ADR-0030 ratified → Accepted (2026-06-27, pre-EC2 cleanup)** with a reality-update note recording the post-sharding deltas. The structural decision the contract rests on is now formally ratified |
| 11.2 Java 25 + preview (non-LTS) | 🟡 | **ADR-0022 Accepted**; records non-LTS + `--enable-preview` + "no long-term vendor support" | carried **accepted risk** (preview features can break on JDK upgrade) |
| 11.3 Maven build | ✅ | **ADR-0021 Accepted (2026-04-11)** | — |
| 11.4 Netty transport (Epoll auto-default / io_uring opt-in) | ✅ | **ADR-0043 Accepted**; records 2026-06-26 Epoll-default flip | — |
| 11.5 Durability Level 0/1, no early-ack | ⛔ | `DL-P1-08` + `handoff.md:123`; grep finds **no early-ack path** in consensus/replication/server | — |
| 11.6 Multi-region write topology deferred | ⛔ | **ADR-0024 Accepted**: "v0.1 supports exactly one DC per cluster" | — |
| 11.7 BATCH API not wired (CM-033) | ❌ | `HttpApiServer` exposes only `GET\|PUT\|DELETE /v1/config/{key}` — no BATCH route; contract §1 "PLANNED, not yet wired" | absent; documented; guard + single-shard atomic-BATCH semantics designed |
| 11.8 Watches scope decision | ✅ (N=1; was "🟡 → v2"; reconciled 2026-07-01) | **Superseded 2026-06-29 (watch arc, PRs #28/#29/#30):** the RFC §2 client watch protocol is implemented server-side at N=1 — wire `0x02` + `WATCH_*` frames + per-shard cursor vector, whole-target authz gate, bounded revocation (see §4.8/6.6). `docs/rfc/driver-protocol/02-watches.md`. Matches the §0 watch note + go/no-go §2.2 | **N>1 multi-shard watch = v3** (fail-closed); no shipped client driver yet. The prior "watches are v2" call held only for the pre-watch-arc window |
| 11.9 Snapshot 4 MiB cap / chunked deferred | 🟡 | `known-limitations.md:76`; `RaftMessageCodec.java:74` enforced | followers can't bootstrap from >4 MiB snapshot in v1 |
| 11.10 Wire epoch field deferred (DL-P1-04) | 🟡 | in-memory `ShardMap.epoch()` returns 0; wire field deferred | **OPEN operator decision** (reserve-now vs v2 wire break) |
| 11.11 Write-availability target renegotiated | ⛔ | **ADR-0031 Accepted, option (a)** (ratified by owner): keep 99.999% flat target; **sub-second auto region-failover = a GA BLOCKER** | declares an open GA blocker |
| 11.12 Empirical-validation deferred to prod observation | ✅ (was 🟡; reconciled 2026-07-01) — **with residuals** | **Substantially discharged on metal** (two EC2 runs): DR drills (7.5), 6 h soak (9.7), near-linear N×knee (2.11/9.2). `docs/measurement/ec2-2026-06-30/05-go-no-go-summary.md` + `docs/measurement/ec2-horizontal-2026-07-01/04-verdict.md` | **Residuals (burn-in, precisely bounded):** no 24 h/72 h soak, no literal 10 k/s sustained, no WAN; DR on single-box topology. go/no-go §5.1 / condition C4 |

### §11-B. Open decision backlog (refreshed) — calls the readiness review must make

| # | Open call | Evidence today | Decision needed |
|---|---|---|---|
| D-1 | **RESOLVED 2026-06-27 — ADR-0030 + ADR-0032 ratified (Accepted)** | both were *Proposed*; now **Accepted** with reality-update notes (pre-EC2 cleanup) | ✅ done — consistency contract + linz proof now rest on Accepted ADRs |
| D-2 | **DECIDED 2026-06-27 — encryption at rest = accept-as-v2** (operator) | 8.2 ❌; RR-098 OPEN → v2 | ✅ decided — v1 ships without at-rest encryption; `secure/` = freshness-not-confidentiality + "do not store secrets" documented (known-limitations / README / Integration-Guide / contract); RR-098 tracked to v2 |
| D-3 | **Security on-by-default vs operator-config** | auth/TLS/audit/replay off-by-default (8.3/8.6/8.8/8.9); signing-key already refuses-to-boot | choose secure-by-default (or refuse-to-boot-insecure) + document required operator config |
| D-4 | **Empirical-validation / burn-in posture** | 9.7 soak incomplete; 7.5 drills never run; 11.12 | accept the "first-30-days = burn-in" contract, or gate v1 on a completed soak + DR drills |
| D-5 | **Wire epoch reservation (DL-P1-04)** | in-memory epoch present; wire field deferred | reserve now (one v1 `WIRE_VERSION` bump) vs accept a v2 wire break |
| D-6 | **Sub-second region failover (ADR-0031 GA blocker)** | Accepted ADR declares it a GA blocker; not built | confirm v1 ships without it (single-DC) and it's a tracked GA gate |
| D-7 | **Snapshot 4 MiB cap** | 1.7/3.10 enforced; over-cap dropped to stderr | accept as documented v1 limitation + add the drop metric/alert, or pull chunked install into v1 |
| D-8 | **Dead code: BloomFilter / Buggify / RolloutController** | 5.11/10.12/4.10 dormant, zero callers | wire or delete before v1 (avoid shipping shelfware that reads as "done") |

---

## §12. Method & honest scope of this audit

- **What was done:** every item located in the live repo; status assigned only on a named artifact;
  CI-wiring verified against `.github/workflows/ci.yml`; ADR statuses read from the ADR files; the
  highest-stakes claims (ADR-0030/0032 status, encryption-at-rest absence, the `owner==0` gate, watches'
  missing client surface) operator-spot-verified by direct grep.
- **What was NOT done:** the full 21k-test reactor was **not executed** in this audit. ✅ items rest on
  *artifact-exists + CI-wired* (the gate chain has prior green-CI evidence); the `†` markers call out ✅
  items whose *pass* could not be independently confirmed here (notably the cloud-CI-excluded live
  linearizability matrix). No production code was modified — this is a docs-only audit.
- **Adversarial bias:** when evidence was weaker than ✅ requires, the item was downgraded. Three false ✅s
  (watches, Buggify, BloomFilter) were caught this way; they are the "class exists ⇒ assumed done" trap.

*Audited at HEAD `74ab070` (branch `phase1-ec2-prep-handoff`), 2026-06-27.*

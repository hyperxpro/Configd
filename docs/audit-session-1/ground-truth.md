# Ground Truth — Production-Readiness Pipeline, Session 1

> Phase A checkpoint of the Session-1 Ground Truth Audit. Everything below was executed or
> inspected **in this session**; no prior document (including `docs/STATE-OF-REALITY.md`,
> `docs/READINESS-LEDGER.md`, `docs/loop-state.json`, `verification/*`) was accepted as evidence.
> Detailed per-workstream reports with full commands and output live alongside this file:
> `build-report.md`, `harness-runs.md`, `smoke-test.md`, `ops-reality.md`, `code-findings.md`,
> `claim-evidence-matrix.md`.

## 1. Audit target

| | |
|---|---|
| Repository | `/home/ubuntu/Code/Configd` |
| Branch / commit | `session-1-ground-truth` cut from `session-a3-linearizability` @ `423a654` (most complete state: includes unmerged A3-B linearizability harness) |
| Date | 2026-06-10 |
| JDK | OpenJDK 25 (Corretto 25.0.0.36.2) |
| Build tool | Maven 3.9.9 via `./mvnw` wrapper |
| Host | Linux (AWS), 2 vCPU, 7.7 GiB RAM, Docker 29.5.0 available |

**Provenance context.** The repo was produced by an LLM agent run against `PROMPT.md`, then
processed by two prior self-assessment efforts: an automated review loop (iter-1/iter-2,
`docs/loop-state.json`) and a forensic remediation pipeline (`STATE-OF-REALITY.md` +
`READINESS-LEDGER.md`, Sessions 0/A1/A2/A3-B). Both are treated as **claims** in this audit; the
claim–evidence matrix rows their assertions explicitly (§9–§10 of the matrix).

## 2. Inventory

- **12 Maven reactor modules:** common, transport, consensus-core, config-store, edge-cache,
  observability, replication-engine, distribution-service, control-plane-api, testkit, server,
  linz. Single entry point: `io.configd.server.ConfigdServer` (no CLI binary exists despite
  runbook references to one).
- **Specs:** `spec/` — ConsensusSpec, ReadIndexSpec, SnapshotInstallSpec (TLA+, TLC via committed
  `tla2tools.jar`). No Apalache anywhere. 15 committed `*_TTrace_*` counterexample artifacts
  (Apr 10–17) — TLC writes these only on FAILED runs; only ConsensusSpec failures are narrated in
  `spec/tlc-results.md` (evidence-hygiene finding).
- **Docs:** mission (`PROMPT.md`), ~40 files under `docs/` incl. architecture, consistency
  contract, 32 ADRs, perf, two prior audit pipelines' artifacts.
- **CI:** `.github/workflows/ci.yml` (build + full suite + all 3 TLC specs; bare unpinned `mvn`,
  not `./mvnw`), `release.yml` (tag-time image build + Trivy image scan). No multi-node test, no
  linz invocation, and **no supply-chain scan job** despite `loop-state.json:37-39` recording one
  as wired (finding BI-1).
- **Ops:** `ops/` alerts, dashboards, runbooks, dr-drills; `deploy/kubernetes`; `perf/` scripts +
  placeholder results dirs.

## 3. Clean-checkout build — PASS

Fresh clone of `session-1-ground-truth` → `./mvnw -B -fae clean verify`:

- **BUILD SUCCESS, exit 0, 5m25s, all 12 modules + parent.**
- **Tests: 21,408 / 0 failures / 0 errors / 8 skipped.** Skips: 6 = linz self-tests gated on
  `PORCUPINE_BIN` (un-skip and pass once the checker is built — see §5), 2 = honest `@Disabled`
  R-005 wire-compat stubs (the only `@Disabled` in the tree).
- `--enable-preview` is **vestigial**: 0/797 class files are preview-marked; the reactor compiles
  with the flag stripped (proven on a throwaway clone).
- SpotBugs runs at `verify` but is **gate-free** (report-only, `failOnError=false`) and currently
  flags **18 concurrency warnings on `io.configd.raft.RaftNode`** that nobody is required to look at.
- Suite-size reconciliation: docs historically quote **eight** different totals (20,132 / 20,149 /
  21,222 / 21,246 / 21,285 / 21,394 / 21,402 / 21,408); only 21,408 matches the live run.

**Test-count reality:** 20,000 of 21,408 (93.4%) are a single parameterized seed sweep
(`SeedSweepTest`, 2 methods × 10,000 seeds, configd-testkit). **Real test count: 1,408**
(1,276 across the 11 non-testkit modules + 132 non-sweep testkit). All quality statements about
"the suite" must use 1,408, not 21,408.

## 4. Harness execution matrix

| Harness | Exists | Executes | Session-1 result | Full-run cost / owner |
|---|---|---|---|---|
| JUnit reactor | yes | yes | GREEN (see §3) | ~6 min · every session (gate-1) |
| JMH (9 classes, shaded `benchmarks.jar`) | yes | yes — all 9 exit 0 | Smoke only; timing numbers not valid on this box | full perf runs → Session 4 |
| JMH `-prof gc` read path | yes | yes | **Zero-alloc hit path CONTRADICTED: `VersionedStoreReadBenchmark.getHit` = 32.001 B/op** (miss ≈ 0 B/op confirmed; raw HAMT ≈ 0 confirmed) | Session 4 |
| TLC ConsensusSpec | yes | yes (14m00s, 2 workers) | "No error"; 13,775,323 states / 3,299,086 distinct / depth 25 — matches prior claim digit-for-digit | per-change smoke → gate; bounds review → Session 2 |
| TLC ReadIndexSpec | yes | yes (7m45s) | "No error"; 12,403,444 / 2,276,125 / d38 — matches | Session 2 |
| TLC SnapshotInstallSpec | yes | yes (2m37s) | "No error"; 5,995,717 / 847,124 / d14 — matches | Session 2 |
| TLC liveness | n/a | **never checked** — temporal property commented out in all cfgs | — | Session 2 |
| Apalache | **no** | — | claimed in mission prompt; absent | Session 2 decides |
| Deterministic simulation (testkit) | yes | yes | Bounded 500-seed sweep ×2 → identical results. **But determinism is partial: per-node election RNG is entropy-seeded** (`ConsistencyPropertyTests.java:77` → `RaftNode.java:1650`) — same seed ≠ same execution. 3 silent-return vacuous paths confirmed in `commitSurvivesLeaderFailure` (:65-68, :72-75, :85-88). Injects drop/partition/reorder only — **no duplication, clock skew, disk faults, crash/restart**. `@Buggify`: 0 production call sites vs ADR-0007's "~1000" | Session 2 |
| Linearizability harness (configd-linz) | yes | yes | Porcupine checker built in 2.9s (Go); 6/6 gated self-tests pass; live 3-node run (seed 4242, 15s, 2× kill-9 + 2× iptables REJECT, 801 ops) → **LINEARIZABLE**, exit 0. Never runs in CI. Discrimination gates not re-verified this session | Session 2 (multi-seed + discrimination re-run) |
| jcstress | **no** (0 hits) | — | Lock-free `VersionedConfigStore`/`HamtMap` have **no race harness** | Session 2 |
| Jepsen / chaos suite | **no** | — | Word appears only in .md files; zero chaos code | Session 3 |

## 5. Multi-node smoke test — control plane PASS / **edge FAIL (P0)**

3-node localhost cluster from the built server jar (procedure productized as
`gates/smoke-multinode.sh`, ~10s, exits non-zero on failure):

1. Launch 3 nodes — PASS (all `/health/ready` = 200).
2. Leader elected — PASS (leader accepts PUT; followers 503 + `X-Leader-Hint`).
3. Write config — PASS, **but** `200 "Accepted: proposalId=N"` is returned on **leader-local log
   append, pre-quorum-commit** (`RaftNode.java:285-289` → `HttpApiServer.java:277`): ack ≠ commit,
   confirmed live (ledger R-14, matrix CM-009/CM-046, finding CF-44).
4. Read back on all 3 nodes — PASS (default GET eventually-consistent on all nodes; linearizable
   GET leader-only).
5. **Observe at an edge — FAIL. Not demonstrable by any mechanism.** Root causes (code-cited,
   independently established by three agents): deltas dead-end at `fanOutBuffer.append(delta)`
   (`ConfigdServer.java:360`) with no drain caller in src/main; the server exposes only 4 HTTP
   contexts and **no fan-out/subscribe/watch listener of any kind**; `PlumtreeNode.broadcast()` is
   invoked only by a benchmark; `EdgeConfigClient` has zero networking (in-process objects only);
   no edge main() exists. All edge/propagation metrics read 0 on the live cluster.
6. Kill leader (`kill -9`) — PASS: re-election **~2.3s** (consistent with finding CF-50: tick
   misconfiguration makes real election timeout 1.5–3s, 10× documented), write to new leader,
   pre-failover write survived.

**Consequence:** the system's headline purpose — propagate a committed config to edges — cannot be
demonstrated end-to-end. This is the audit's **P0-1**: every staleness / read-your-writes /
monotonic-read guarantee in `consistency-contract.md` is unfalsifiable because the pipeline it
describes does not exist at runtime.

## 6. Observability / ops reality (summary; detail in `ops-reality.md`)

- 3 phantom metrics referenced by alerts/dashboards with no emitter; 3 histogram `_bucket`
  families never emitted at runtime → **6/9 alert rules + 3 p99 dashboard panels can never fire**.
- `configd_write_commit_total` / `_failed_total` NOOP-wired → 0 forever; the availability SLO has
  a permanently-zero denominator.
- `PropagationLivenessMonitor` structurally incapable of firing (feed methods never called).
- Runbooks/runsheet reference a nonexistent `configd` CLI, `configd snapshot create`, and
  `/raft/*` endpoints.

## 7. Claim–evidence snapshot (static pass; run-results being folded in)

188 claims extracted. Static statusing: **78 VERIFIED · 61 CONTRADICTED · 19 FICTION ·
16 EXISTS-UNVERIFIED · 13 PENDING-RUN (now resolvable) · 1 ENVIRONMENT-BLOCKED.** Standouts:
per-entry HLC timestamps are fiction (`LogEntry.java:13` has no timestamp field, yet the contract
defines staleness in terms of it); an Accepted ADR cites a field (`shardId`) and metric
(`configd_raft_groups_total`) that exist nowhere; the contract §7 describes a Wing&Gong
linearizability checker that is actually a scripted single-threaded test; ADR-0030 — the topology
decision everything defers to — is still marked "Proposed".

## 8. What is genuinely real (verified this session — credit where due)

Single-node/control-plane core: full Raft algorithm with PreVote, durable term/vote
(fsync-before-ack **verified correct**, CF-41), joint-consensus code paths, ReadIndex guards;
lock-free MVCC store (no locks/CAS on reads — corroborated); CRC32C+TLS transport with
`setNeedClientAuth(true)`; A1 event-loop serialization and A2 invariant net present in code; the
A3-B linz harness is real (separate JVMs, real OS faults, real Porcupine). TLC reproduces all
previously-claimed state counts exactly.

## 9. Changes made by this audit (full log: `changes.md`)

Zero modifications to tracked source/docs in the audited repo. New artifacts only:
`docs/audit-session-1/*` (reports), `gates/smoke-multinode.sh` (new file). Harness enablement
outside the repo: Porcupine checker binary built under `/home/ubuntu/audit-artifacts/`; scratch
clones `/home/ubuntu/ws-clean`, `/home/ubuntu/ws-smoke`; throwaway preview-flag experiment clone
`/tmp/pv-strip`. Raw run logs: `/home/ubuntu/audit-artifacts/{jmh,tlc,linz-run}/`.

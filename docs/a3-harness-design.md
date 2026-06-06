# A3 — Linearizability + Fault-Injection Harness Design

> **Session A3-D design output (DESIGN ONLY — STOP for human review before A3-B implementation).**
> Produced by an Opus design team (`distributed-systems-lens`, `consistency-lens`, `chaos-lens`)
> investigating independently then cross-examining; lead assembled this from their content. Tooling
> decision: `docs/decisions/adr-0032-linearizability-harness.md`. Per-lens evidence:
> `verification-runs/session-a3/findings-*.md`. Closes the design half of **R-04**.
>
> Evidence rubric on every claim: `[VERIFIED-PASS]` / `[VERIFIED-FAIL]` / `[EXISTS-UNTESTED]` /
> `[DOC-ONLY]` / `[ABSENT]`. All file:line re-confirmed this session (A1/A2 shifted them).

## 1. Scope & fence (hard)

**In scope:** linearizability of **root control-plane Raft-group writes and ReadIndex reads** against
**real, separate-JVM multi-process nodes** under a continuous, seeded, replayable adversarial fault
schedule. **Out of scope (do not design):** edge reads, bounded-staleness, monotonic-read-on-failover
(that is B3; the fan-out pipeline is not wired — testing it now tests vapor) and reconfiguration faults
(see §13). If the harness starts checking edge/staleness, it has drifted — pull back.

## 2. System-under-test facts (re-confirmed; the harness is built around these)

| # | Fact | Class | Evidence (file:line) |
|---|---|---|---|
| 1 | **`ack ≠ commit`** — `200 Accepted` is returned on local append, before commit; `proposalId` is a local counter, not a Raft index | `[VERIFIED-PASS]` | `ConfigWriteService.java:150-154,84/101`; `RaftNode.java:283-289` |
| 2 | **Default GET is a stale local read**; linearizable read requires `?consistency=linearizable` | `[VERIFIED-PASS]` | `HttpApiServer.java:233,236-237,244`; `X-Consistency` header `:254` |
| 3 | **Linearizable GET is flaky** — 150 ms ReadIndex confirm timeout returns 503 even on a healthy leader (~4/5 in prototype) | `[VERIFIED-FAIL]` | `ConfigdServer.java:512` (`resultFuture.get(150, …)`), `:89` |
| 4 | **CheckQuorum wired** — a partitioned leader steps down (~500 ms); **ReadIndex lease is quorum-based, not time-based** (a partitioned leader cannot serve a stale lin-read) | `[VERIFIED-PASS]` | `RaftNode.java:776-785`, `:1616-1627`, `:417-424` |
| 5 | **No determinism seam** — election RNG is `nanoTime`-seeded; no `--seed`; timeouts are tick-count on a monotonic scheduler | `[VERIFIED-FAIL]` on internal repro | `ConfigdServer.java:214-215,169`; `RaftNode.java:59` |
| 6 | **Launchable as separate processes** — shaded jar, real TCP transport starts only with `--peer-addresses` | `[VERIFIED-PASS]` | `configd-server/pom.xml:46/62`; `ConfigdServer.java:246-247,318`; `ServerConfig.java:66` |
| 7 | **Durable** — WAL + `storage.sync()`; survives `kill -9` + restart from same `--data-dir` | `[VERIFIED-PASS]` | `RaftLog.java:367,436`; `FileStorage.java:97-110` |
| 8 | **Live model is a per-key register** — only single-key PUT/DELETE wired; BATCH named but not wired | `[VERIFIED-PASS]` / `[ABSENT]` (BATCH) | `HttpApiServer.java:216-218`; `ConfigWriteService.java:121,164` |

## 3. The model — per-key linearizable register

Each key is an independent linearizable register; the recorded history is **partitioned by key** and each
sub-history checked independently. This is the **correct semantic target** (INV-W1
`consistency-contract.md:152-153`, §5:139-140 "Per-Key Total Order REQUIRED"; INV-L1 `:29-32` is its
real-time instance) **and** keeps checking tractable (the standard sound `independent` reduction). It is
sound here because the contract disclaims cross-*group* ordering (`§5:145-148`) and A3 is fenced to the
single root group — per-key checks cannot false-RED on a legitimate cross-key interleaving. Every PUT value
carries a **globally unique token** (`clientId:opSeq:nonce`) so a read pins exactly which write it observed.

## 4. History recording fidelity

Single driver JVM (one monotonic clock). Per op record: `client_id`, `op_type` (PUT / DELETE-as-write-of-⊥
/ READ), `key`, `arg` (unique token), `ret` (observed token or ⊥), `invoke_ts` (`System.nanoTime()`
immediately before send), `response_ts` (after the full response), `status`, `consistency`
(`linearizable` required on reads), and an **auxiliary-only** `X-Config-Version` (the server's own claim;
never load-bearing for the verdict). Emitted as a checker-neutral op-history (invoke/ok/fail/info).

## 5. Injection mechanism — OS-level, driven from Java

**OS-level partition/latency** via `iptables` DROP / `tc netem` on the real JVM processes, plus process
`kill -9`/restart — shelled out from the Java orchestrator. This is the only mechanism that exercises the
real blocking-SSLSocket / virtual-thread-per-connection wire path (`TcpRaftTransport.java:100,121,206-207`)
that R-01 proved the sim hides; it is real Jepsen practice; it was **demonstrated on this box**
(`findings-chaos-lens.md §2`, `[VERIFIED-PASS]`). The in-process `SimulatedNetwork` is **disqualified**
(`SimulatedNetwork.java:15,33-34` — single-threaded, in-process, the R-01 blind spot). A transport-level
shim is a labelled fallback only (changes the binary; misses socket behavior).

**Topology:** 3- and 5-node; per node a Raft port (9101+), an API port (8101+), its own `--data-dir`, and
the full `--peer-addresses` map; quorum `size/2+1` (`RaftConfig.java:78`). **Leader discovery:** follow the
`X-leader-hint` header on a PUT/DELETE 503 (`HttpApiServer.java:279-285,305-311`); a linearizable-read 503
has **no** hint → treat indeterminate + backoff. **TLS off** for the matrix (plaintext and SSLSocket share
the same blocking-socket/threading path `TcpRaftTransport.java:316-345`; iptables is L4 TLS-agnostic; TLS
only worsens the 150 ms read-timeout flakiness) **plus one TLS-on smoke run** to keep mTLS honest.

## 6. The ack-semantics decision (gates the discrimination plan)

Because `ack ≠ commit` (§2.1), a write's "completion" cannot be taken as a commit. **Decision: (A) now;
(B) recommended as an A3-B follow-on.**

- **(A) — the gate floor (no SUT change).** Record every `200 Accepted` write as **`:info`/indeterminate**.
  Reads pin reality — an observed value promotes its info-write to "happened"; `:ok` reads carry the
  real-time backbone — so the checker retains full discriminating power. **Consequence:** the lost-write
  discrimination (§11) must be **write → linearizable read-back confirms T_new → crash → value gone**; the
  RED is sourced from the post-crash read contradicting the confirmed read, so the ack's indeterminacy is
  irrelevant.
- **(B) — recommended follow-on (a real product gap, not a test hack).** Add a commit-confirmed synchronous
  write path (block until applied, return the commit seq — buildable on `whenReadReady`/`lastApplied`,
  `RaftNode.java:424,453-460`). It makes the contract's *actual* ack model (`§6:163` "acknowledgment with
  commit sequence S") directly testable and makes a vanished `:ok` write a RED by itself. **Not a gate
  prerequisite.**

## 7. Indeterminate-op handling (harness-correctness — the subtle bug class)

A write that times out **may have committed** → it is **`:info`/unknown, NEVER `:fail`**. Treating a
timed-out write as failed produces **both false-greens and false-reds** — the classic homegrown-checker bug.

| Client observation | Recorded status | Why |
|---|---|---|
| 200 Accepted (PUT/DELETE) | `:info` (write) | ack ≠ commit (§2.1) |
| timeout / conn-reset / 5xx-other / killed mid-flight | `:info` | may have committed |
| **linearizable-read 503** (flaky, §2.3) | `:info` (read) | indeterminate; never a read of a definite value |
| 200 OK (linearizable read) | `:ok` (read of observed token) | the real-time backbone |
| 503 Not Leader / 429 / 400 / 403 | `:fail` (definite non-occurrence) | rejected at/before propose (`ConfigWriteService.java:138-152`) |

Porcupine ingests `:info` as a `call` with no matching `return` (placeable anywhere ≥ call, or omitted);
Elle/Knossos as `:type :info`.

## 8. Fault matrix

Faults run **continuously** during the workload on a seeded schedule (§9). Each is injected against the
**real binary**; on a correct system the per-key history stays linearizable.

| Fault | Raft mechanism targeted | Invariant at risk | Injection (real binary) | Correct result |
|---|---|---|---|---|
| **F-E asymmetric partition isolating the leader** (gray failure) | CheckQuorum / ReadIndex lease | stale read / two leaders | iptables DROP leader→majority (one direction) | leader steps down `≤~500ms` (`RaftNode.java:784-786`); no stale lin-read |
| **F-F bridge partition** (C sees A,B; A,B can't see each other) | PreVote + CheckQuorum + one-vote/term | split-brain / divergent logs | iptables matrix between node pairs | no two leaders same term (`handleRequestVote:907-942`, `ClusterConfig.isQuorum:117-123`) |
| **F-A symmetric majority/minority partition + HEALING** | quorum-commit | lost commit / divergence | iptables split into {maj}/{min}, then heal | minority can't commit; on heal both converge; **heal is a first-class scheduled event** |
| **F-G crash (`kill -9`) + restart after ack** | durable WAL / fsync | lost acked write | kill leader after a confirmed read-back, restart same `--data-dir` | confirmed value survives (`RaftLog.java:367,436`) |
| **F-B/F-D leader-crash chain / heavy-loss re-election under churn** | election / Leader Completeness | committed entry lost across terms | repeated kill+restart of the current leader | every new leader's log ⊇ all committed entries |
| **stale-read negative control** (seeded bug, §11.2) | ReadIndex leadership re-check | INV-L1 | delete `RaftNode.java:421` recheck + isolate old leader | (on correct build) stays linearizable; (on mutated build) RED |

**Recorded-and-dropped (analyzed, NOT silently omitted):**
- **Clock-jump / drift** — DROPPED as a safety fault: Raft timers are tick-count on a monotonic
  `scheduleAtFixedRate` (`RaftNode.java:59`; `tickElection:760`/`tickHeartbeat:776`); wall clock feeds only
  HLC entry stamps, never election/heartbeat/lease. Keep as a one-shot regression assertion ("clock chaos
  does not perturb root-group linearizability"), not a recurring schedule fault.
- **Crash-during-InstallSnapshot** — DROPPED as a safety fault: install is single-shot/atomic-apply
  (`sendInstallSnapshot:1283-1292` always `offset=0,done=true`; `handleInstallSnapshot:1501` one
  `restoreSnapshot`) — no partial-install state to corrupt. Record instead the **liveness cliff**: a
  snapshot > 16 MiB is silently dropped (`FrameCodec.java:86`; IAE path `RaftNode.java:1300`) → follower
  permanently stuck. A finding owed to a later session, not an A3 linearizability fault.

## 9. Seeding & reproducibility

One seed reproduces one full fault+workload schedule. The orchestrator builds `SplittableRandom(seed)` and
`.split()`s it into independent fault-stream and workload-stream substreams; every fault and op carries a
seed-derived **logical offset** (ms from t0). The schedule is written to `schedule-<seed>.json` (run from
seed, or replay the file). **Gate-(iii) proof = two runs of the same seed produce byte-identical
`schedule-<seed>.json` (`diff`).** Because the binary has **no determinism seam** (§2.5), we pin the
**inputs** (which faults/ops at which logical offsets), *not* which node wins; the recorded *history* differs
run-to-run by design. The checker is leader-agnostic and the client follows `X-leader-hint` across
re-elections, so cluster nondeterminism does not break the verdict.

## 10. Checker & runtime bounding

**Primary, gate-required: Porcupine** (per-key register fit; native indeterminate support; etcd-trusted).
The op-history is **checker-neutral**, so **Elle** is a drop-in optional cross-check (long-soak) and the
future primary when BATCH is wired (ADR-0032). Bound Porcupine's superlinear worst case by: **(a) per-key
partitioning** (each register sub-history is short — the dominant win, and why the workload uses a small
keyspace); **(b) op-count / wall-time caps** per run (checker process timeout ⇒ that run is **indeterminate**,
not a pass — logged, never silently treated as green); **(c) windowing** for very long soaks.

## 11. DISCRIMINATION PLAN (load-bearing — the harness is worthless until proven to catch a violation)

A green run is meaningful **only after** each seeded bug turns the checker RED on a scratch build, and the
**unmutated control is GREEN**. This is the "who verifies the verifier" answer.

### 11.1 Seeded bug — LOST ACKED WRITE
- **Mutation:** make durability a no-op — `FileStorage.java:110` `channel.force(true)` → no-op (and the
  rewrite path `:62`). (Alt: skip `storage.appendToLog`, `RaftLog.java:282-283`.)
- **Schedule:** PUT k=T_new → ack → **linearizable read-back confirms T_new** → `kill -9` leader → restart
  from same `--data-dir` → later linearizable GET k.
- **Expected RED:** GET returns T_old/404 after T_new was observed — a committed/observed value disappeared.
  Porcupine: no valid linearization for k. Elle: ww/rw cycle on k.

### 11.2 Seeded bug — STALE READ
- **Mutation:** delete the ReadIndex leadership re-check — `RaftNode.java:421`
  `if (role != RaftRole.LEADER) return false;` in `isReadReady` (the FIND-0002 guard). (Alt: confirm
  leadership unconditionally at `:399-402`, bypassing the `confirmPendingReads` quorum `:1616`.)
- **Schedule:** PUT k=T1 → L_old; partition L_old from the majority; PUT k=T2 → L_new (completes before the
  read begins); linearizable GET k against the deposed L_old.
- **Expected RED:** GET returns stale T1 — a read returned a value older than a write that completed before it
  began (direct INV-L1 violation, `consistency-contract.md:29-32`).

### 11.3 Checker self-test suite (the glue must itself be tested — synthetic histories, pinned verdicts)
Fed through the **real** recorder serializer + **real** checker:
1. Sequential sane (PUT v1, GET v1, PUT v2, GET v2) → **GREEN**.
2. Stale-read anomaly (GET v1 after PUT v2 ok, no overlap) → **RED** (guards false-green).
3. **Timed-out write → `info` then read-T → GREEN; the SAME op flipped to `:fail` → RED.** *(The single most
   important self-test — proves the timeout→info-never-fail mapping is wired correctly.)*
4. Lin-read-503 → `info` → GREEN; flipped to fabricated `:ok` value → RED.
5. Default-GET-stale modeled as a window read → GREEN; same bytes on a linearizable read lagging a committed
   write → RED.
6. Unique-token precondition: reusing a value token fails a recorder assert (never confuse two writes of the
   same bytes).

The glue is **not trusted** until 1–6 pass with every flip — the same pass-with-bug / fail-without discipline
A1 and A2 used.

## 12. A3-B exit-gate mapping

The build session must show, with pasted output, in this order:
- **(i) Discrimination first:** §11.1 and §11.2 each produce a **non-linearizable** verdict on the scratch
  build (paste the RED for each); §11.3 self-tests pass with every flip. *If any seeded bug does not go RED,
  the harness is blind to that class — STOP and fix the harness before (ii).*
- **(ii)** On the unmodified binary: **linearizable across N seeded schedules on 3- AND 5-node clusters**,
  faults active throughout (paste the green verdict + seeds).
- **(iii)** Reproducibility: same seed → identical `schedule-<seed>.json` (run twice, `diff`).
- **(iv)** `./mvnw -fae test` → BUILD SUCCESS (harness integrated, suite intact).
- **(v)** Reviewer subagent confirms: (a) real separate-JVM processes over the real transport — NOT the
  in-process sim; (b) indeterminate ops modeled `:info`, not `:fail`; (c) the discrimination tests genuinely
  turn the checker red — with cited call paths/output.

## 13. Residual gaps / forward pointers

- **(B) commit-confirmed synchronous write path** — recommended A3-B follow-on (§6); a real product gap.
- **Reconfiguration-under-fault** — `[EXISTS-UNTESTED]`, **no live trigger** (`proposeConfigChange`
  `RaftNode.java:514` has zero non-test callers; `AdminService` never wired); the only existing test
  `configChangePreservedAcrossElections:257-270` is vacuous (§5.5d). **Out of A3 scope.** Owed to a dedicated
  session that (a) wires a reconfig seam **with a tripwire** (the A1 new-seam prior), then (b) fault-tests
  reconfig-under-partition/election. Do **not** add the seam inside A3-B.
- **>16 MiB snapshot liveness cliff** (§8) — a liveness finding owed to a later session.
- **Long-soak histories** — switch the primary checker to Elle (better long-history scaling), via the neutral
  history.

## 14. Sign-off

All three signed after independently re-verifying (file:line) the parts they own. Full sign-off reasons in
`docs/decisions/adr-0032-linearizability-harness.md` (Reviewers).

- **distributed-systems-lens: SIGN-OFF** — fault matrix §8 + dropped/deferred §8/§13 match my rulings, reasons intact.
- **consistency-lens: SIGN-OFF** — discrimination plan §11 mutation sites + expected REDs re-verified; §6/§7 ack-fork + indeterminate mapping faithful.
- **chaos-lens: SIGN-OFF (Elle-primary dissent recorded)** — §5 injection / §9 reproducibility / §10–§11.3 self-test faithful; accepts the 2-1 Porcupine-primary resolution (per-key partitioning bounds runtime; neutral history keeps Elle a drop-in).

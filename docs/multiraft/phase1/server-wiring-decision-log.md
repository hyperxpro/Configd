# Multi-Raft Phase 1 — Production-Wiring Session, Decision Log

> Autonomous build session: WIRE the sim-verified-but-dormant sharding logic (merged `db854d7`, PR #7)
> into the LIVE `ConfigdServer`. Charter: dependency-ordered seams, four-way rigor on consensus-adjacent
> steps, **N=1 byte-identity preserved after every seam**, STOP at the merge gate and the EC2 (money)
> gate. Operator decisions D1/D2/N are SETTLED (charter §2) — built to, not re-opened.
>
> **Branch:** `multiraft-phase1-server-wiring` (off `origin/main` = `3aa8c82`, which already contains
> Phase 1 + the build plan + the readiness register). One PR at the end; STOPPED at the merge gate.
> Distinct from the Phase-1 `decision-log.md` (the sim-foundation session).

## Seam ledger (each: committed + N=1-verified + green before the next)

| Seam | Charter step | Status | N=1 identity | Evidence |
|---|---|---|---|---|
| **A — C4a config N-selection** | Step 1 | ✅ DONE | preserved | `ShardCountConfigTest` 8/0; `ConfigdServerTest` 22/0 (boot+restart) |
| **B — adapter inbound groupId demux** | Step 3 (inbound) | ✅ DONE, four-way | preserved | `RaftInboundDemuxTest` 2/0; `NettyConsensusLivenessTest` 3/0; loopback+marshalling+owner-net+throwable all green |
| **C — N-group consensus loop** | Step 2 | ✅ DONE, four-way | preserved | `MultiGroupBringupTest` 5/0; full `configd-server` 303/0 (incl. `NettyConsensusLivenessTest` real-wire N=1 + `ConfigdServerTest` boot/restart); `RaftInboundDemuxTest` 3/0; §3 mTLS negatives 5/0 on `NettyRaftTransport` |
| **D — write/read routing + guard** | Step 4 | ✅ DONE, four-way | preserved | `ShardedRoutingTest` 6/0; full `configd-server` 309/0; `configd-control-plane-api` 15/0; `ConfigdServerTest` 22/0 (N=1 boot/restart) |
| **E — per-shard observability** | Step 5 | ✅ DONE | additive | `PerShardMetricsTest` 3/0; full `configd-server` (N=1 metric-regression: existing series unchanged) |
| **F — wire-format D1+D2** | Step 6 | ⏳ DEFERRED | — | handoff §2 |
| **G — isolation sim + fan-out N>1** | Steps 7,8 | ⏳ DEFERRED | — | handoff §2 |

### Session 1 outcome — CLEAN STOP after Seam B (DL-W-07)
Seams **A + B** complete, four-way-verified, N=1-byte-identical; `gate-phase1` GREEN; full
`configd-server` 297/0. C–G deferred to a focused session (the heavy coupled surgery), mapped in
`server-wiring-handoff.md` §2.

### Session 2 outcome — CLEAN STOP after Seam E (C/D/E done; F/G remain)
Seams **C, D, E** are complete and checkpointed (commits up to `ad7153f`). **C + D four-way-verified**
(diff-review + red-team + independent re-run + second-agent replay; the D read-path BLOCKER/SHOULD-FIX
found by the four-way were FIXED in-seam); **E** done with rigor-proportional review (additive
observability). **N=1 byte-identical re-proven after every seam** (full `configd-server` 312/0 incl.
real-wire `NettyConsensusLivenessTest` + `ConfigdServerTest` boot/restart; `MetricsWiringContractTest`
4/0); **`gate-phase1` GREEN** for A/B/C/D/E (wiring-c + wiring-d + wiring-e blocks added). A final
fresh-context Verifier runs before the PR.

The two remaining seams — **F** (the protocol-critical `WIRE_VERSION` bump: epoch reservation +
CoalescedHeartbeat frame + golden-fixture regen — a HARD cutover) and **G** (remove the N>1 boot guard +
N-way fan-out + the C3a coupling-leak isolation sim + EC2-prep) — are each large, self-contained, and
risky to start with limited remaining budget (a half-bumped wire format or half-removed boot guard is
the "finish dirty" the charter calls the worst outcome). Per the prime directive they are deferred CLEAN
to a focused next session, mapped at `file:line` in `server-wiring-handoff-2.md` §2. **The N>1 boot guard
(DL-W-05) stays — every shipped state is safe (N>1 cannot boot until Seam G removes it).** PR opened;
STOPPED at the merge gate. EC2 NOT provisioned (handoff-2 §3: needs C+D+G; G remains).

#### Final fresh-context Verifier (charter §4.1) — APPROVE, 0 MUST-FIX
A fresh-context Verifier (java-distinguished-engineer) independently verified the whole A–E increment
(`origin/main...HEAD`) against all six bars, checking the code rather than trusting this log:
**(a) no N=1 divergence** (traced `buildRaftGroup(0,1,…)` object-by-object; confirmed the per-group RNG
is consumed only for election-timer jitter — never persisted/wire — so the instance split is
format-neutral); **(b) N>1 correctly boot-refused** (guard before marker; {2,4,16}); **(c) no must-fix**.
Also confirmed: writer/reader resolve the same shard for every live (GLOBAL) key; the cross-shard guard
rejects pre-Raft before any admission permit; §3 mTLS+client-auth enforced + negatively tested on the
production `NettyRaftTransport`; R-01′ holds (off-owner reads only the documented-safe
`leaderId()`/`monitorView()`); the SPI blast radius is contained to server/control-plane-api/testkit
(it compiled `configd-edge-node` offline to confirm). Full-reactor `install -DskipTests` GREEN (all 15
modules). Non-blocking observations folded into the F/G handoff: per-inbound-frame handler allocation
(micro-opt), `CrossShardRejected` un-metered (Seam G per-shard write metrics), the GLOBAL-only
read/write scope coupling (guard when a multi-scope seam lands), mTLS operator-optional (Seam-G EC2
obligation).

## Decisions (for retroactive veto)

### DL-W-01 — Branch off `3aa8c82` (latest main), separate PR from the docs-only #9
`origin/main` had advanced to `3aa8c82` (PR #9, the readiness register, MERGED) since the last memory
snapshot. Cut `multiraft-phase1-server-wiring` off `3aa8c82` (contains Phase 1 `db854d7`). The session's
wiring is its own PR, not stacked on the docs work. Baseline build green (exit 0) before any edit.
*Reversible: branch.*

### DL-W-02 — `configd.raft.shardCount` is a system property, default 1 (not a `ServerConfig`/CLI field)
Consistent with every other `configd.raft.*` tunable (`ownerPoolSize`, `electionTimeoutMinMs`, …) read
inline in `start()` via `Integer.getInteger`. The handoff §4.A.1 specifies exactly this. Keeps the
`ServerConfig` record + CLI surface untouched (smaller blast radius). *Reversible: yes.*

### DL-W-03 — Range `[1, MAX_SHARD_COUNT=16]`, validated at boot with a clear error
Operator decision N (charter §2): default 1, ceiling 16 (M1's static ceiling — ~10–11 leaders saturate
a 16-vCPU node, Workstream C). An out-of-range N is a fail-fast `IllegalArgumentException` naming the
bounds. *Reversible: yes (constant).*

### DL-W-04 — Fixed-at-deploy enforced by a persisted marker (`raft-shard-count.meta`)
`enforceFixedShardCount(N, dataDir)` records N under the data dir on first boot and REJECTS a later boot
whose configured N differs — a reshard attempt becomes a LOUD fail-closed startup error, not silent
mis-routing of already-committed keys (operator decision N: "reject an attempt to change N … with a
clear error, not silent corruption"). Atomic write (temp-then-rename) so a crash mid-write cannot
poison the next boot. **Marker safety:** `FileStorage` resolves fixed key-based paths
(`<key>.dat`/`<logName>.wal`) and never enumerates the data dir, so the marker is inert additive
metadata — the Raft WAL/snapshot bytes are unchanged (N=1 byte-identity preserved). Changing N is the v2
dynamic-resharding gap — DOCUMENTED here and in the error message. *Reversible: delete the marker.*

### DL-W-04b — Marker is crash-DURABLE (fsync), after review
Both four-way reviewers flagged that the original atomic temp-then-rename prevented *poisoning* (a torn
marker) but not *loss* (a crash in the OS writeback window could erase the rename, silently resetting the
guard). `enforceFixedShardCount` now writes temp + `force(true)`, atomic-renames, then fsyncs the data
dir — the exact `FileStorage.put` discipline used for Raft persistent state. The guard is now as durable
as the data it protects. *Reversible: yes.*

### DL-W-06 — Seam B = inbound demux only; outbound per-group adapters deferred to Seam C
The DL-P1-06 adapter groupId fix has two halves. The INBOUND demux (route on `frame.groupId()`) is
independent, N=1-byte-identical, and unit-testable now, so it lands in Seam B. The OUTBOUND half ("one
adapter per group, each stamped with its gid") is intrinsic to building N transports in the C3 loop and
is a no-op at N=1 (the single adapter stamps 0), so it is built in Seam C. Both reviewers confirmed the
split is safe: the inbound/outbound inconsistency can only manifest at N>1, which is refused at boot
until Seam C. *Reversible: yes.*

### Four-way review of Seam A+B (DL-P1 charter §3)
- **Diff-review (java-distinguished-engineer): APPROVE-WITH-NITS.** No blockers; N=1 byte-identity
  confirmed for both commits; one SHOULD-FIX (marker durability — DONE, DL-W-04b); nits accepted.
- **Red-team (redteam-auditor): SHIP-WITH-FIXES.** No CRITICAL/HIGH; could not break misrouting,
  hostile-groupId safety, RR-008 throwable surfacing, N=1 identity, or guard-before-write ordering.
  - [MEDIUM] test-vacuity (N=2 boundary untested) → FIXED (`nGreaterThanOneIsRefusedWhileWiringDormant`
    now sweeps {2, 4, 16}).
  - [LOW] marker fsync → FIXED (DL-W-04b).
  - [LOW] fixed `.tmp` name multi-writer race → ACCEPTED/DEFERRED: only matters for concurrent boots
    with *different* N, which is an N>1 (Seam C) concern and subsumed by the pre-existing "no boot lock"
    gap (FileStorage takes no lock either). Revisit with the boot-lock work.
  - [LOW] `Integer.getInteger` swallows malformed config → ACCEPTED: matches the established
    `configd.raft.*` idiom (every tunable) and fails to the safe byte-identical default N=1; diverging
    only for shardCount would be inconsistent.
  - [INFO] CRC32C is a checksum not a MAC, so the demux branches on attacker-influenceable `groupId` →
    the demux handles every hostile gid safely (drop); **carry to EC2 go/no-go: run the Raft transport
    with mTLS + client auth** (already supported, `TcpRaftTransport setNeedClientAuth(true)`).

### DL-W-05 — Temporary `N>1` startup guard until the C3 N-group loop lands
While the server still registers a single group (pre-Seam-C), `resolveShardCount` refuses `N>1` LOUDLY
(rather than routing writes to unregistered groups — silent corruption). The guard is ORDERED BEFORE the
marker write, so a refused `N>1` boot never persists an `N>1` marker that would poison a later `N=1`
boot (regression-tested: `nGreaterThanOneIsRefusedWhileWiringDormant`). This guard is REMOVED in Seam C
when N groups are actually registered. *Reversible: yes — it is scaffolding.*

## Seam C — N-group consensus bring-up (DL-W-C-01..04 + four-way)

The single-group bring-up in `ConfigdServer.start()` is generalized to N groups via the extracted
`buildRaftGroup(gid, …)` → `RaftGroupRuntime`, looped over `shardMap.shardIds()` on the Phase-0
owner-executor pool. Design: `docs/multiraft/phase1/seam-c-multigroup-bringup.md`.

### DL-W-C-01 — One `buildRaftGroup` path for every group (handoff §2.C recommended structure)
The intricate per-shard wiring (storage → log → store → state-machine → node → per-group outbound
adapter → group-commit) is written ONCE and used for all groups, then `driver.addGroup` + owner-bind +
coalescer-bind in the loop. At N=1 the loop runs exactly once (group 0). *Reversible: it is a refactor;
revert the commit.*

### DL-W-C-02 — N=1 reuses the node-level `Storage` instance (byte-identity); N>1 isolates per shard
`buildRaftGroup` uses the node-level `storage` instance when `shardCount == 1` (so the group-0 RaftLog
and the node-level AuditLog share it, exactly as today — same WAL/snapshot bytes + paths) and
`Storage.file(dataDir/shard-<gid>)` when `shardCount > 1`. AuditLog + signing key stay node-level.
Both four-way reviewers confirmed N=1 byte-identity claim-by-claim. *Reversible: yes.*

### DL-W-C-03 — Per-group RNG; HyParView RNG split off
Each group's RaftNode gets its own `RandomGenerator` seeded `nodeId*31 + gid*GID_RNG_STRIDE + nanoTime`
(at gid 0 the stride term is 0 ⇒ the seed FORMULA equals today's). This (a) avoids a cross-owner-thread
RNG data race at N>1 and (b) staggers election timeouts per shard (ADR D-B). The node-level `random`
(formerly shared with the group-0 RaftNode) now feeds HyParView only. RNG affects election-timing jitter
only (already `nanoTime`-non-deterministic) — NOT the WAL/wire/snapshot FORMAT (the byte-identity bar).
Both reviewers ruled this format-neutral and a latent-race improvement. *Reversible: yes.*

### DL-W-C-04 — Inbound demux registered ONCE; hostile-gid dropped on the inbound thread
The inbound demux is registered exactly once (per-group adapters are outbound-only; `registerHandler`
replaces on the shared transport). Red-team hardening: `raftDemuxInboundHandler` now drops a frame for an
UNREGISTERED group on the inbound (Netty) thread BEFORE marshalling onto an owner — closing an
authenticated-but-hostile-peer no-op-task amplification on the owner thread (the `groupId` is an
attacker-influenceable, non-MAC'd field). `driver.routeMessage` re-checks as the backstop. Byte-identical
for legit frames (gid 0 at N=1). Codified by `RaftInboundDemuxTest#hostileGroupIdsAreDroppedSafely`
(MIN_VALUE/MAX_VALUE/negative/large gids → no throw, no send, legit path survives). *Reversible: yes.*

### Four-way review of Seam C (charter §4.1)
- **Diff-review (java-distinguished-engineer): APPROVE-WITH-NITS.** "No way the N=1 production path
  diverges" — verified claim-by-claim (storage instance, seed formula, group-commit, single
  registration, primary selection). All happens-before edges re-confirmed after the reordering; no
  shared-mutable-state leak at N>1; enhanced-for capture correct. NITs FIXED: defensive primary
  selection (by groupId identity, not list position), `GID_RNG_STRIDE` constant, `IntegrityEnvelope`/
  `ArrayList` imports.
- **Red-team (redteam-auditor): SHIP.** Could not break the groupId trust boundary (mTLS+client-auth
  enforced + negatively tested on the production `NettyRaftTransport`; hostile gids dropped safely —
  PoC with MIN_VALUE/MAX_VALUE/−1/unregistered → no throw, 0 sends), the N=1 path (byte-identical,
  validated end-to-end), ordering/isolation, or the N>1 boot guard (still refuses N∈{2,4,16}, no marker
  poison). Actionable LOW FIXED: drop unknown gids before marshalling (DL-W-C-04) + codified PoC test.
- **Independent re-run:** full `configd-server` 303/0 (incl. real-wire `NettyConsensusLivenessTest` +
  `ConfigdServerTest` boot/restart); `MultiGroupBringupTest` 5/0; §3 mTLS negatives 5/0 on
  `NettyRaftTransport`.

### Seam-G-gated obligations carried forward (red-team NOTES — re-verify before lifting the N>1 guard)
1. **Thread-safety of shared node-level deps at N>1** — `configSigner`, the two `InvariantChecker`s, and
   `configdMetrics` are passed to every group and would be touched by multiple owner threads once N>1 is
   live. Inert at N=1 (one owner). Audit `ConfigSigner` (any single `java.security.Signature`?) and the
   checkers before Seam G removes the boot guard.
2. **Partial-bring-up cleanup at N>1** — if `buildRaftGroup` throws for `gid=k>0`, groups `0..k-1` are
   already registered + owner-bound + have opened `shard-<gid>` storage; the loop leaks them on a failed
   boot. Unreachable at N=1; add close-on-failure when Seam G enables N>1.
3. **`configdMetrics` is per-shard-blind** — counters conflate shards (the documented Seam E deferral).
4. **mTLS is operator-optional** (pre-existing, whole-consensus exposure if TLS off) — the EC2 N×knee
   run MUST set TLS on; consider making mTLS mandatory for the Raft transport.

## Seam D — live write/read routing + cross-shard guard (DL-W-D-01..04 + four-way)

The sim-verified ShardMap routing is wired into the LIVE write + read paths; the cross-shard DISCLAIM
guard is active on the live path; redirects are shard-aware. Design:
`docs/multiraft/phase1/seam-d-live-routing.md`. N>1 routing is exercised by tests (the N>1 boot guard
still holds until Seam G); N=1 is byte-identical (every resolution lands on group 0).

### DL-W-D-01 — `requireSingleShard` is the unified live router + guard
`RaftProposer.propose` is widened to `(scope, List<String> keys, command)`. The production proposer
(`raftProposer(driver, shardMap, …)`) resolves the owning shard via
`CrossShardWriteGuard.requireSingleShard(shardMap, scope, keys)` — ONE call that is both the router
(single-key ⇒ `shardFor`) and the DISCLAIM guard (multi-key spanning shards ⇒ `CrossShardRejected`,
caught synchronously before any Raft work / admission permit). The owner executor is re-resolved per
write (`driver.ownerExecutor(gid)`), dropping the captured group-0 executor. A fixed-group proposer
overload is retained for the marshalling/commit regression tests. *Reversible: revert the commit.*

### DL-W-D-02 — shard-aware leader redirect + linearizable confirm
`LeaderHintSupplier.currentLeader()` → `currentLeader(scope, key)` and
`LeadershipConfirmer.confirmLeadership()` → `confirmLeadership(key)`: a `NotLeader`/`Lost` redirect now
points at the leader of the shard that OWNS the key, and a linearizable read runs the ReadIndex protocol
on the OWNING shard's node (a keyless hint/confirm would loop forever / verify the wrong shard at N>1).
At N=1 both resolve to group 0. *Reversible: yes.*

### DL-W-D-03 — sharded reader; `getPrefix` scatter-gather; `currentVersion` aggregate
`ConfigdServer.shardedConfigReader` (package-private, testable): a point read resolves
`shardFor(READ_SCOPE, key)`'s store; `getPrefix` scatter-gathers across all shards and merges (a
prefix's keys may hash to different shards); `currentVersion` is the max across shards (per-key version
still from `ReadResult.version()`). `READ_SCOPE = GLOBAL`, matching every HTTP write
(`AdminApiHandler` is GLOBAL-only) — single-key linearizability preserved. At N=1 it is the one store.
*Reversible: yes.*

### DL-W-D-04 — `ProposeCommitResult.CrossShardRejected` → `WriteResult.ValidationFailed`
A new terminal outcome for the DISCLAIM rejection, mapped to a permanent `ValidationFailed` (HTTP 400) —
retrying the same spanning write cannot succeed. The proposer's pre-Raft guard catch also covers
`IllegalArgumentException` (empty key list) → `CrossShardRejected`, so a malformed multi-key write is a
clean 400, never a 500 (red-team LOW; defensive for a future BATCH path). *Reversible: yes.*

### DL-W-D-05 — the LIVE read path is FULLY sharded (both reviewers' HIGH/BLOCKER, FIXED)
The first four-way pass found the read path only HALF-sharded: the linearizable/strong-read path went
through the sharded `ConfigReadService`, but the **default stale `GET`** read the captured group-0
`configStore` directly (`AdminApiHandler`) and the **read 503 `X-Leader-Hint` was keyless** (group 0) —
both wrong at N>1 (read-your-writes break / wrong-shard redirect loop), unreachable at N=1. FIXED in this
seam (the charter §5.D requires the live READ path sharded, so not deferred):
- stale `GET` now routes through `readService.staleRead` → the sharded reader (`AdminApiHandler.java`);
- the HTTP leader hint is now `Function<String,NodeId>` — keyed, resolving the owning shard's leader
  (mirrors the write redirect); plumbed through `NettyHttpApiServer`/`HttpApiServer`.
Proven by `ShardedRoutingTest#staleGetIsShardedThroughTheHttpHandler` (a shard-k≠0 key GET returns 200
via the sharded reader, not 404 from the group-0 store — red/green for the BLOCKER) +
`#leaderHintResolvesTheOwningShardLeader`. **Still group-0 (Seam G / not-yet-sharded singletons, by
design):** the fan-out/watch listeners + compactor (Seam G N-way merge) and the health-readiness leader
check. *Reversible: yes.*

### Four-way review of Seam D (charter §4.1)
- **Diff-review (java-distinguished-engineer): REQUEST-CHANGES → re-reviewed APPROVE-WITH-NITS (no
  must-fix).** N=1 byte-identity confirmed claim-by-claim ("no way the N=1 path diverges"); write path +
  cross-shard guard + threading sound. Findings FIXED + re-verified on the actual code (second-agent
  replay): [BLOCKER] stale `GET` group-0 read (DL-W-D-05); [SHOULD-FIX] keyless read hint (DL-W-D-05);
  [SHOULD-FIX] HTTP-read test-gap (`staleGetIsShardedThroughTheHttpHandler` — genuine red/green); [NIT]
  `runtimesByGid`/`runtimes` → immutable copies. Residual NITs (non-blocking): empty-keys →
  `CrossShardRejected` is a mild misnomer (accurate reason text; 400 either way); the stale fallback is
  group-0 only in a degenerate stale-only-no-read-service config (N=1-correct, documented).
- **Red-team (redteam-auditor): SHIP-WITH-FIXES.** Could NOT break the redirect race (StaticShardMap
  epoch=0/deploy-fixed ⇒ no stale-map window; hint races only leadership, self-correcting; exactly-once
  = last-writer-wins idempotent, unchanged), the cross-shard guard (pre-Raft, no permit consumed), or
  the N=1 path. Findings FIXED: [HIGH×2] stale `GET` + keyless read hint (DL-W-D-05); [LOW] guard catch
  broadened (DL-W-D-04). [MEDIUM] writer-scope/reader-GLOBAL divergence — DOCUMENTED constraint
  (`AdminApiHandler` is GLOBAL-only; the read scope matches; `ConfigWriteService.put(…,scope)` with a
  non-GLOBAL scope is the v2 multi-scope seam). [INFO] `currentVersion` aggregate / fan-out / health
  remain Seam-G territory.
- **Independent re-run:** `ShardedRoutingTest` 6/0; full `configd-server` 309/0 (incl. real-wire
  `NettyConsensusLivenessTest` + admin API contracts on both transports + `ConfigdServerTest`
  boot/restart); `configd-control-plane-api` 15/0.

## Seam E — per-shard observability (DL-W-E-01..02)

The group-0-only metrics scrape is extended to PER-SHARD health. Design:
`docs/multiraft/phase1/seam-e-per-shard-metrics.md`.

### DL-W-E-01 — per-shard health via name-encoded pull gauges from `monitorView()`
`ConfigdServer.registerPerShardMetrics` (package-private, testable) registers, for every shard,
`raft.shard.{commit_index,last_applied,apply_lag,current_term,leader}.<gid>` + the node-level
`raft.node.leader_count`. `MetricsRegistry` is name-only (not tag-capable), so the shard id is encoded in
the metric NAME (`base.<gid>` — handoff §2.E; bounded ≤16×5 series). Each gauge is a null-safe pull that
reads the group's `RaftNode.monitorView()` — the H-3 safe, never-torn, ≤one-tick-stale snapshot the
Prometheus scrape thread already reads off-owner. The existing GLOBAL group-0 scrape
(`raft_pending_apply_entries` + `raft_elections`) is UNCHANGED (back-compat). Additive at N=1 (registers
exactly the group-0 series; the existing series + all consensus/wire/WAL behaviour untouched).
*Reversible: yes.*

### DL-W-E-02 — per-shard write-throughput / 429 DEFERRED (documented, dormant-until-N>1)
Per-shard write-rate / admission-429 are recorded at the proposer site into a single shared
`ConfigdMetrics`; making them per-shard means threading a per-gid handle through the just-four-way-reviewed
write hot path, and they are DORMANT-until-N>1 (N>1 is boot-refused until Seam G) while the AGGREGATE
series already exist. So Seam E delivers the per-shard HEALTH view (the operator's "see each shard's
health"); the per-shard write-rate breakdown is wired alongside N>1 enablement in Seam G. *Reversible: yes.*

### Review of Seam E (rigor proportional to risk)
E is ADDITIVE observability — pull gauges off the hot path, reading the established-safe `monitorView()`
snapshot; no consensus / wire / WAL / correctness surface. Implementer + independent re-run
(`PerShardMetricsTest` 3/0 — per-shard health present + leader=1 + leader_count=N; N=1 registers only the
group-0 series) + the full `configd-server` suite (N=1 metric-regression: `MetricsWiringContractTest` +
the scrape contract unchanged). The full adversarial/diff four-way is folded into the final fresh-context
Verifier (which reviews every seam) — there is no attack surface in read-only additive gauges to red-team
in isolation.

## Seam F — the `WIRE_VERSION 0x01→0x02` bump (DL-F-01..04)

ONE atomic, protocol-critical wire bump covering BOTH operator-settled wire decisions (charter §2),
both DORMANT at N=1. Design + layout: `docs/multiraft/phase1/seam-f-wire-bump.md`.

### DL-F-01 — D1: reserve an 8-byte epoch field, NOT surfaced on the `Frame` record
`FrameCodec` `HEADER_SIZE 18→26`: an 8-byte epoch field is reserved after the term (offset 18). The
encoder writes zero (MBZ); the decoder reads-and-ignores it (forward-compatible — a future v2.x sender
that populates epoch is still decodable, so *activating* epoch needs NO further wire bump). It is
deliberately NOT added to the `FrameCodec.Frame` record: that record has 60 construction sites (mostly
tests), and a reserved field no caller consumes is premature plumbing + a 60-site blast radius for zero
behavioural gain. When epoch activates (DL-P1-04), that session adds the `Frame.epoch()` accessor + an
`encode` overload — purely additive, no wire bump. The hand-rolled zero-alloc encoders that bypass
`FrameCodec.encode` (the production `NettyConsensusFrameEncoder` + 3 testkit H2H encoders) each gained
the matching `writeLong(0L)` — pinned byte-identical by `NettyConsensusFrameEncoderByteIdentityTest`.
*Reversible: yes (revert the seam; no v1 deployment exists, so it is a clean cutover either way).*

### DL-F-02 — D2: the CoalescedHeartbeat frame + count-bounded payload codec
`MessageType.RAFT_COALESCED_HEARTBEAT(0x11)` (`BY_CODE` 0x11→0x12). Codec home: `RaftMessageCodec`
(`encodeCoalescedHeartbeat`/`decodeCoalescedHeartbeat` — siblings of the RaftMessage codec; a
`CoalescedHeartbeat` is deliberately not a `RaftMessage`, so the sealed `decode` switch gains an
explicit `RAFT_COALESCED_HEARTBEAT` case that throws a *directional* error). Payload = a count-bounded
fixed-size-record format: `[count][n × {groupId, term, leaderId, prevLogIndex, prevLogTerm,
leaderCommit}]` (40 B/record). `from` is NOT in the payload (the transport carries the sender-id prefix);
frame-header groupId/term are sentinels (0). Adversary bounds: `MAX_COALESCED_GROUPS=1024` cap, the
`n×record > remaining` tiny-frame/big-alloc pre-check, reject duplicate group ids, reject trailing
bytes, reject a non-empty AppendEntries on encode (only heartbeats coalesce). *Reversible: yes.*

### DL-F-03 — inbound demux is per-group on the adapter, NOT `routeCoalescedHeartbeat` on the inbound thread
The handoff sketched "inbound → `driver.routeCoalescedHeartbeat`". That method calls `routeMessage`
inline, which runs `node.handleMessage` ON THE CALLING THREAD for a non-rehomed group (every production
group), and `handleMessage` asserts the owner thread. A coalesced frame can bundle groups with DIFFERENT
owners at N>1, so routing them all on the single Netty inbound thread would run `handleMessage` off-owner
for all but one group — firing `assertOwnerThread()` / racing the non-synchronized `RaftNode` (ADR-0009).
So `RaftTransportAdapter.registerInboundHandler` instead DEMUXes the coalesced frame and dispatches each
group via the existing per-group `InboundHandler.accept(from, gid, ae)` path — each marshalled onto ITS
owner executor, through the Seam-C unregistered-group drop + the RR-008 throwable guard.
`routeCoalescedHeartbeat` remains the sim/single-owner-test helper. *Reversible: yes.*

### DL-F-04 — send drain framing extracted + dormant-at-N=1
The `enableHeartbeatCoalescing` drain swapped "frame each group individually" for
`ConfigdServer.frameHeartbeatDrain` (package-private, testable): exactly one group for the peer (ALWAYS
the case at N=1) → a normal `APPEND_ENTRIES` frame (wire byte-for-byte unchanged); more than one (only at
N>1) → ONE coalesced frame. The coalesced branch is unreachable at N=1, so the wire is identical to
today. *Reversible: yes.*

### Four-way + gate (Seam F)
Implementer → diff-review → independent clean re-run → adversarial red-team → fresh-context Verifier
(APPROVE-0-must-fix) before the PR. Evidence: `configd-transport` 143/0 (incl. `FrameCodecEpochReservationTest`
6/0 + `WireCompatGoldenBytesTest` 17/0 incl. the v2 regen), `configd-netty` 71/0 (incl. the byte-identity
+ all real-transport round-trips), `configd-server` 331/0 (incl. `CoalescedHeartbeatCodecTest` 13/0,
`RaftTransportAdapterCoalescedInboundTest` 3/0, `HeartbeatDrainFramingTest` 3/0, and the N=1
boot/restart + metrics regressions). `gate-phase1` block (h)/wiring-f added (CI-wired, cumulative).

## Invariants held (re-checked each seam)
- **N=1 byte-identical** to today (consensus behaviour, Raft WAL/snapshot format; the wire is identical
  EXCEPT the sanctioned version byte `01→02` + the 8 reserved epoch zero-bytes — charter §2 D1). The
  single most important bar.
- No early-ack; durability Level 0/1 unchanged.
- Dynamic resharding NOT built; rehoming DORMANT (D-016 re-verify not triggered).
- ONE `WIRE_VERSION` bump for D1+D2 (Seam F), both dormant; no second wire break.
- EC2 NOT provisioned (money gate) — the N×knee aggregate measurement is the next session.

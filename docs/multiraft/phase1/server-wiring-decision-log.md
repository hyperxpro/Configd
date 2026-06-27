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
| **C — N-group consensus loop** | Step 2 | ⏳ | — | — |
| **D — write/read routing + guard** | Step 4 | ⏳ | — | — |
| **E — per-shard observability** | Step 5 | ⏳ | — | — |
| **F — wire-format D1+D2** | Step 6 | ⏳ | — | — |
| **G — isolation sim + fan-out N>1** | Steps 7,8 | ⏳ | — | — |

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

## Invariants held (re-checked each seam)
- **N=1 byte-identical** to today (consensus behaviour, wire bytes, Raft WAL/snapshot format). The
  single most important bar.
- No early-ack; durability Level 0/1 unchanged.
- Dynamic resharding NOT built; rehoming DORMANT (D-016 re-verify not triggered).
- ONE `WIRE_VERSION` bump for D1+D2 (Seam F), both dormant; no second wire break.
- EC2 NOT provisioned (money gate) — the N×knee aggregate measurement is the next session.

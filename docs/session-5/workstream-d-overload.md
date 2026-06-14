# Session 5 — Workstream D: Backpressure & Overload (empirical thresholds vs §11)

> **Owner:** `backpressure-engineer`. **Method:** as-built threshold reconciliation (code inspection of
> the §11 mechanisms) + the empirical overload behavior measured in Workstream B (100k/s burst on the
> live cluster) + the S4 chaos overload/reconnect evidence (EXP-010). Per charter §8: **any mismatch
> between the documented and the as-built/measured threshold is a finding.** Collector: ZGC (ADR-0041).

## D.1 — §11 documented policy vs as-built code (the reconciliation table)

`architecture.md §11` (and `performance.md §4`) document a multi-path backpressure ladder. The as-built
code implements a **subset**: one real write-backpressure bound (uncommitted-proposals → 429) plus the
fan-out slow-consumer path. The other documented paths are **not implemented as distinct thresholds**.

| §11 documented | As-built (code) | Verdict |
|---|---|---|
| **Write:** Raft queue **> 1000** → reject, **HTTP 429 + `Retry-After: 1`**, recover **queue < 500 (hysteresis)** | `RaftNode.java:372` `uncommitted >= config.maxPendingProposals()` (**default 1024**, `RaftConfig.java:30`) → `WriteResult.Overloaded` → `HttpApiServer.java:411` `sendResponse(429,"Overloaded")` | **MISMATCH ×3** — threshold is **1024 not 1000** (F-D1); **no `Retry-After` header** emitted (F-D2); **no distinct 500 low-water hysteresis** — it is a single hard bound, recovery is simply uncommitted dropping back under 1024 (F-D3) |
| **Write:** apply lag **> 5000** → reject + alert, **HTTP 503**, recover lag < 1000 | no distinct apply-lag threshold exists; the only write-reject is the uncommitted-proposals bound above | **NOT IMPLEMENTED** (F-D4) — the documented second write-backpressure path collapses into the single uncommitted bound |
| **Read (edge):** never shed (lock-free) | edge read path is lock-free / 0-alloc (Workstream A); never sheds; sets `X-Configd-Stale: true` when STALE (`EdgeHttpServer.java:46,76 HDR_STALE`) | **MATCH** |
| **Read (control plane):** ReadIndex queue **> 100** → reject linearizable, **429**, suggest stale | `ReadIndexState` tracks pending reads but has **no queue-depth rejection**; an unconfirmed linearizable read fail-closes 503 (not-leader) or waits, it is not shed on queue depth | **NOT IMPLEMENTED** (F-D5) — no ReadIndex queue-depth shed |
| **Fan-out:** buffer **> 80%** → slow-consumer warning, `X-Configd-Stale` | `FanOutConfig.java:86 queueWarnPct=80`; `FanOutSessionCore.maybeWarnSlowConsumer` (`:505`) fires the warning + metric at 80% | **MATCH** |
| **Fan-out:** buffer **100%** → disconnect slow consumer, re-bootstrap | the WOULD-BLOCK / close machinery (RR-102/104) closes a session whose bounded outbound queue is full → edge re-bootstraps via catch-up | **MATCH** |
| **Load-shedding order** (stale distant reads → low-pri writes → linearizable reads → normal writes → never edge reads) | partially realized as *behavior* (edge reads never shed ✓; writes 429 at the bound ✓; non-leader linearizable reads fail-closed 503 ✓) but there is **no explicit priority scheduler** enforcing this order | **PARTIAL** — the ordering is emergent, not an implemented priority ladder |

**Net:** the system has **one** working write-backpressure mechanism — a bounded proposal queue
(`uncommitted >= 1024`) that sheds with HTTP **429** — plus the fan-out slow-consumer path (80% warn /
100% disconnect, both matching §11). The richer §11 ladder (Retry-After, queue<500 hysteresis,
apply-lag-503, ReadIndex-queue-429) is **documented but not built**. This is a documentation-vs-code
honesty gap, not a safety gap: the bounded queue + 429 is real, working, and bounds memory. Filed as
**RR-110** (recommend: either relabel §11 to the as-built single-bound reality, or implement the missing
paths in S6 operability).

## D.2 — Empirical overload behavior (Workstream B, live cluster, 100k/s burst)

The §11 *thresholds* could not be exercised end-to-end on the 2-vCPU box, because the box's
**leadership collapses before the architected bound is reached**:

- At ~100k/s offered, **99.6% shed at generator backpressure**, **0 × HTTP 429**. The write path never
  reaches the `uncommitted >= 1024` bound: long before the queue fills, the write threads starve the
  Raft heartbeat on 2 vCPUs and the leader steps down (**CheckQuorum → HTTP 503**). The dominant shed
  under overload on this box is **leadership step-down (503), not the architected 429**.
- This is a **host-capacity artifact** (confirmed in Workstream B; the sustainable end-to-end commit
  rate is ~125–172/s — `infrastructure-manifest.md` M-9), **not** a design defect: the bounded-queue
  429 path is correct in code; the box simply dies of CPU starvation first.
- **Verifying the architected 429/503 ladder at its thresholds is therefore ENV-BLOCKED** — it needs a
  cluster with dedicated cores per node so the queue can actually fill before leadership starves
  (`infrastructure-manifest.md` **M-10**; the waiting harness is B's `OpenLoopWriteDriver`).

## D.3 — Post-partition reconnect-storm recovery (S4 EXP-010 + fleet-size distribution)

S4's worst-case overload scenario — the post-partition reconnect storm — was measured in S4 (EXP-010):
**258 ticks (5 edges)** to whole-fleet CURRENT (deterministic in-sim, 1 tick ≈ 1 ms modeled;
`recovery-bounds.md`). The S5 ask is a recovery-time **distribution at realistic fleet sizes**. Because
this is the deterministic in-sim `OverloadChaosTest` (box-cheap, leadership-stable — no live-cluster CPU
starvation), it is re-run at rising edge counts to produce the distribution:

**Method (S5).** A `@ParameterizedTest @ValueSource(ints={5,10,25,50})`
(`OverloadChaosTest#postPartitionReconnectStorm_fleetSizeDistribution`) was added next to the S4 D-2
cell. It shares the exact storm logic (`reconnectStormRecoveryTicks(edges)`): warm the fleet to
CURRENT, partition the WHOLE fleet, commit 5 writes none can see, walk all edges to DISCONNECTED, then
**heal the entire fleet at the same logical instant** and count ticks from heal → whole-fleet-CURRENT.
Each cell re-asserts the S4 invariants (every edge ends CURRENT and caught up to the authoritative
version; **no edge pushed TERMINAL**). Deterministic in-sim (`EdgeFanOutSim`, seed=91, CP=3,
WARMUP=1500); **1 tick ≈ 1 ms modeled** (`recovery-bounds.md`).

| Fleet size (edges) | Recovery ticks (≈ ms) | vs. 5-edge anchor | All CURRENT / none TERMINAL |
|---|---|---|---|
| **5** (S4 EXP-010 anchor) | **258** | — (reproduced exactly) | ✓ / ✓ |
| 10 | 256 | −2 (−0.8%) | ✓ / ✓ |
| 25 | 258 | 0 | ✓ / ✓ |
| 50 | 261 | +3 (+1.2%) | ✓ / ✓ |

**Finding (no defect).** Whole-fleet recovery time is **essentially flat — invariant in fleet size**
across a 10× span (5 → 50 edges), 256–261 ticks, a ±3-tick spread (±1.2%). The catch-up "thundering
herd" does **not** amplify with fleet size in this model: every edge re-bootstraps from its CP node's
snapshot/replay source in parallel and the recovery latency is dominated by the per-edge
reconnect→snapshot→apply→ack chain (the fixed ~258-tick path), not by contention among reconnecting
edges. The small +3-tick drift at 50 edges is the only fleet-size signal, well within tick granularity.
The S4 single point (258/5) is **reproduced exactly**, confirming the harness and the anchor.

**Scope honesty.** This is the *in-sim* fan-out/recovery model (deterministic discrete-event, no
wall-clock schedule → CO structurally absent, methodology §3b note in D.5). It is **leadership-stable**
(the CP cluster is settled before the storm; no CheckQuorum step-down), so it is **NOT** subject to the
live 2-vCPU ceiling that bounds D.2 / M-9 — which is exactly why this distribution IS locally
measurable while the live 429/503 threshold ladder (D.2) is ENV-BLOCKED (M-10). The model does **not**
include the real per-edge WAN reconnect RTT or snapshot-transfer bandwidth at scale; those would add a
modeled term per the methodology RTT matrix (§2) for a production fleet and are not claimed here.

Raw: `docs/session-5/captures/wsD-reconnect-fleet.txt`. Re-run:
`flock /tmp/configd-mvn.lock ./mvnw -q -pl configd-testkit test -Dtest='OverloadChaosTest#postPartitionReconnectStorm_fleetSizeDistribution+postPartitionReconnectStorm_allEdgesRecoverToCurrent'`

## D.4 — Findings & disposition

- **F-D1..F-D5 → filed as RR-110 (P2, doc-vs-code, owner S6 operability):** §11 documents a backpressure
  ladder that is only partially built. The as-built reality is a single bounded-proposal-queue 429
  (threshold 1024, no Retry-After, no 500 hysteresis) + the fan-out 80/100 path; apply-lag-503 and
  ReadIndex-queue-429 are not implemented. (F-D1's 1024-vs-1000 also corroborates S4 EXP-010.)
- **M-10 (ENV-BLOCKED):** the live 429/503 threshold ladder can't be exercised on the box (leadership
  starves first); needs dedicated-core hosts.
- **Matches (no finding):** fan-out 80% warning, 100% disconnect, `X-Configd-Stale`, edge-reads-never-shed.

## D.5 — Coordinated omission / honesty

The empirical numbers (D.2) come from Workstream B's open-loop CO-corrected driver (methodology §3b).
The reconnect-storm (D.3) is deterministic in-sim (no CO — discrete-event, no wall-clock schedule). The
§11 reconciliation (D.1) is code inspection (no measurement). Every live-threshold claim that the box
cannot reach is **ENV-BLOCKED (M-10)**, never reported as a verified threshold.

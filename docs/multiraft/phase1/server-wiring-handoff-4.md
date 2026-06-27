# Multi-Raft Phase 1 — Production-Wiring Session 4, Handoff (Seam G DONE — the switch-flip is LIVE)

> This session completed **Seam G** — the switch-flip. **N>1 now boots in production.** The temporary
> boot guard is removed, gated on the integrated N>1 sweep being green (charter §3.4). Branch
> `multiraft-phase1-seam-g` (off `origin/main` = `777bac5`, which has Seam F merged); commits `2fb3e81`(G1)
> · `8ffd701`(G2) · `f2c7d98`(G3+audit) · G4 (this commit). Charter prime directive honoured: **G4 (guard
> removal) was LAST and GATED on G3 green; N=1 byte-identity re-proven after every sub-seam.** EC2 NOT
> provisioned. PR opened on `main`; **STOPPED at the merge gate.**

## 1. What Seam G wired + verified (this session)

| Part | What it does | Four-way | N=1 | Evidence |
|---|---|---|---|---|
| **G1 — N-way fan-out merge** | One `FanOutBuffer` + `Compactor` per shard, each fed by its group's commit listener on its OWN owner thread (single-writer-per-buffer, no lock). Per-shard sequences + cursor vector; NO fabricated cross-shard global order (ADR-D-A/D-C). Watch primary-only; edge fail-closed at N>1. | diff APPROVE-WITH-NITS + red-team SHIP (0 must-fix) | byte-identical | `ShardedFanOutTest` 6/0 |
| **G2 — live shared-node isolation** | On the REAL driver + owner pool (4 groups, 2 owners): a STUCK apply starves a co-owned sibling (coupling-leak RED via a per-group liveness witness — the SF1 mandate) while the other owner stays live; per-shard safety preserved; recovery on release. | red-team SOUND-WITH-NITS | test-only | `SharedNodeFaultIsolationLiveTest` 2/0 (stable) |
| **G3 — integrated N>1 sweep** | The REAL bring-up (`buildRaftGroup`) COMPOSED with the sharded fan-out at N>1 on shared owners: per-shard isolation in BOTH store and fan-out. The `wiring-g` gate runs the whole sweep (G1+G2+G3 + thread-safety net + coalesced-HB). **GATES G4.** | the sweep IS the gate | — | `MultiShardIntegratedSweepTest` 1/0; gate `wiring-g` GREEN |
| **Thread-safety audit** | Every node-level dep shared across owner threads at N>1 audited SAFE (ConfigSigner per-call Signature; InvariantCheckers/Metrics LongAdder+CHM; IntegrityEnvelope per-call Mac; Compactor CSLM; …). **Verdict: SAFE TO LIFT.** | fresh-context audit | — | DL-W-G3-01 |
| **G4 — the switch-flip** | The `N>1` boot refusal in `resolveShardCount` is REMOVED (gated on G3 green). N>1 boots; fixed-at-deploy now applies to N>1. Partial-bring-up cleanup + P-vs-N warning + edge fail-closed landed with it. | diff APPROVE-WITH-NITS + red-team SHIP-WITH-FIXES (0 must-fix; MEDIUM edge → fixed fail-closed) | byte-identical | `NGreaterThanOneBootSmokeTest` 2/0; `ShardCountConfigTest` 9/0; full server 346/0 |

- **`gate-phase1` GREEN end-to-end** — new `wiring-g` (the integrated sweep that gates G4) + `wiring-g4`
  (the switch-flip smoke + the flipped config test + the "boot-refusal message is gone" non-vacuity grep),
  ordered so the gate STRUCTURE encodes "guard removed only with the sweep green".
- **Decision log:** `server-wiring-decision-log.md` DL-W-G1-01..04, DL-W-G2-01, DL-W-G3-01 (+ audit),
  DL-W-G4-01..04. **Designs:** `seam-g1-fanout-merge.md`, `seam-g2-live-isolation.md`.

## 2. Safety state at this stop
- **N=1 (every default deployment) is byte-identical** — re-proven after every sub-seam (full
  `configd-server` 346/0 incl. boot/restart, the real-wire `NettyConsensusLivenessTest`, the metrics
  regression). The guard removal touched ONLY the `shardCount > 1` branch.
- **N>1 now BOOTS** — every shard is a registered Raft group (Seam C), routed per shard (Seam D), with its
  own fan-out (G1), proven shared-node isolation (G2), coalesced heartbeats live at N>1 (Seam F activates
  automatically), and every shared node-level dep thread-safe (the audit). The integrated sweep (`wiring-g`)
  is the proof; G4 removed the guard only with it green.
- **§3 peer authentication** unchanged (NettyRaftTransport mTLS + `needClientAuth`).
- **Fail-closed guards**: a reshard (changed N) is rejected; `--edge-port` + N>1 without
  `-Dconfigd.edge.allowPartialShardView=true` is REFUSED (no silent partial-view edge data plane).
- Dynamic resharding NOT built; rehoming DORMANT (D-016 re-verify not triggered); no early-ack; EC2 NOT
  provisioned.

## 3. EC2 readiness — G done; the server runs N>1 live; EC2 is the NEXT session (money gate)

**G complete; the server runs N>1 live (N-way fan-out, isolation, authenticated peers, coalesced
heartbeats); ready for the operator-approved EC2 N×knee aggregate-throughput measurement** —
`configd.raft.shardCount=N`, `configd.raft.ownerPoolSize>=N` (else a startup warning fires and shards
serialize), run the Raft transport with **mTLS + client-auth** (`--tls-*`) — which also runs the deferred
**soak + DR drill**. Do NOT provision EC2 this session (money/operator gate). **Not provisioned.**

**NOTE before EC2 (the operator's "in order before burning money" directive):** settle the cheap cleanup
first — CI de-flake (gate-phase0 `RehomingInjectedSweepTest` is a known 2-vCPU timing flake → re-run
green), ADR-0030/0032 ratification (Proposed→Accepted), doc reconciliation — and the **watches /
encryption-at-rest v1/v2 decisions** (RR-098). These should be settled before the EC2 spend.

## 4. v2-deferred list (carried forward)
- **The sharded edge client** (cursor-vector multiplexing the N per-shard fan-out sources) — the server
  exposes per-shard sources; the polished client is v2. N>1 + `--edge-port` is fail-closed until then.
- **Cross-shard watch aggregation** (per-shard WatchService + cursor vector, ticked per-owner) — watch is
  bound to the primary group only (dormant infra; no production register() path).
- Dynamic resharding / running-cluster N change (still rejected; not built).
- Cross-region / multi-DC placement policy.
- The epoch field's ACTIVATION (DL-P1-04) — the wire bytes are reserved; activating needs only the
  `Frame.epoch()` accessor + an `encode` overload (no further wire bump).

## 5. Tracked follow-ups (from the G four-ways — not blockers)
- **`Storage` not closed on `shutdown()`** (pre-existing; ×N at N>1; process-exit-reclaimed) — make
  `Storage` `AutoCloseable` + close per-shard storage in `shutdown()` and the bring-up catch.
- **Per-shard `fanout.buffer.dropped.<gid>`** (Seam-E style) — the aggregate counter conflates shards once
  N>1 boots; ship per-shard drop counters with the EC2 work.
- **A 2-node N>1 boot smoke** exercising the real-wire cross-shard demux + coalesced-HB EMIT (the current
  smoke is single-node; the wire paths are covered by `MultiShardIntegratedSweepTest`/`RaftInboundDemuxTest`/
  the Seam-F battery).
- Global `raft_pending_apply_entries` is owner-0/group-0 only at N>1 (per-shard `raft.shard.apply_lag.<gid>`
  cover each shard).

## 6. Did NOT (this session)
Provision EC2; build the sharded edge client / cross-shard watch (v2); activate the epoch field; activate
rehoming; change the N=1 runtime behaviour; merge to main (PR opened, STOPPED at the merge gate).

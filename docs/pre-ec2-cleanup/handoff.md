# Pre-EC2 Cleanup — Handoff (2026-06-27)

## Status: the system is "in order" for the paid measurement

This session settled everything that is free/local so the **money-gated EC2 measurement runs once, on a
clean, finished, internally-consistent system** — not re-measured after changes. See
[`decision-log.md`](decision-log.md) for every decision.

| Pre-EC2 precondition | State after this session |
|---|---|
| CI de-flaked | `RehomingInjectedSweepTest` made **deterministic in verdict** (wall-clock-free; real threads kept) — red/green + **10/10 repeated-run stable under throttle**. gate-4 boot: class hang ceiling `@Timeout(10)→60` (boot measured robust at 16-burner throttle, 3/3 PASS; headroom for credit-exhaustion). gate-phase0 GREEN incl. the de-flaked test. Other candidates assessed. |
| Contract-critical ADRs ratified | **ADR-0030 + ADR-0032 → Accepted** (reality-update notes). ADR-0031 confirmed Accepted. The 4 multi-Raft ADRs **deferred-with-reason** to the multi-Raft go/no-go. |
| Docs reconciled to reality | `consistency-contract.md §2/§7` (commit-ts/frontier staleness), `known-limitations.md` (reconciled to the audited register), `Integration-Guide.md`, `README.md`. |
| v1/v2 product decisions applied | **Watches → v2** (documented). **Encryption-at-rest → v2 (RR-098)** + the **`secure/` trap fixed by honesty** (freshness-not-confidentiality, "don't store secrets") across docs + code Javadoc + register. |
| Guardrails | No consensus/sharding logic changed; no features built; no money spent; not merged to main. |

**The only remaining pre-go/no-go work is the EC2 measurement** (below) — plus merging this cleanup PR and
PR #13 (the N>1 boot switch-flip), which the N×knee measurement depends on.

---

## The EC2 session — exactly what to run, config, and cost

### Prerequisite
- Merge **this cleanup PR** and **PR #13** (Seam G — N>1 now boots). On `main` today N>1 is boot-refused;
  the N×knee measurement needs N>1 to boot, which PR #13 provides. (Soak + DR can run single-group too, but
  run them on the same release commit.)

### What runs (one provisioned box, measured once, verified-teardown)
1. **N×knee aggregate-throughput measurement** — the multi-Raft thesis. Boot N shards
   (`configd.raft.shardCount=N`, `ownerPoolSize>=N`) for a ladder of N (e.g. N = 1, 2, 4, 8, …), and measure
   **aggregate committed write throughput** vs the MODELED `~800/s × N × 0.75` (register §9.2, target ≈ 10k/s
   near N≈17). Confirms whether the aggregate scales ~linearly past the single-group ~800/s knee (register
   §9.1). This is what validates `adr-throughput-target` (currently Proposed/deferred).
2. **Sustained-load soak** — the deferred empirical-validation item (register §9.7; the prior 24h soak
   OOM-killed at ~3.45h on a too-small box, leak-clean until then). Run a full window on a box with adequate
   headroom; confirm FD/threads/heap flat + no safety violations end-to-end.
3. **DR drills** — never executed (register §7.5; `ops/dr-drills/` is README-only). Run at least
   **restore-from-snapshot** and **leader-loss** on the release commit, capture results to
   `ops/dr-drills/results/`.

### Config
- Instance: **m6id.4xlarge** (the box used for the single-group knee + Netty Phase-V, capacity-proven) — or
  larger for soak heap headroom (the OOM was box-capacity, RR-112).
- `configd.raft.shardCount=N`, `ownerPoolSize>=N`; **mTLS + client-auth** (`--tls-*`); durability Level 0/1
  fsync-before-ack (unchanged, no early-ack); **ZGC** (ADR-0041); **Epoll** transport (ADR-0043 default).
- N-way fan-out / per-shard isolation / authenticated peers / coalesced heartbeats activate at N>1 (Seam G).

### Cost (on-demand, verified-teardown; prior short runs were ~$0.47–0.59)
- **Lean (6–8h soak + N×knee ladder + DR): ≈ $10–15.**
- **Full (24h soak + N×knee ladder + DR): ≈ $30–35.**

### Done = go/no-go inputs
- N×knee curve vs the model (does sharding deliver the aggregate?); a completed soak (leak-clean over the
  full window); executed DR drills (RTO/RPO observed). With those measured, the readiness review has its last
  empirical inputs and the 4 multi-Raft ADRs + `adr-throughput-target` can be ratified or revised.

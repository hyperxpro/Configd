# Configd

A region-local, strongly-consistent configuration control plane: a single Raft "root" for
linearizable writes, with asynchronous, bounded-staleness fan-out to in-process edge readers
(a Quicksilver-shaped topology — see [ADR-0030](docs/decisions/adr-0030-quicksilver-shaped-topology.md)).

## Status: v1 (pre-GA)

v1 is code-level complete with a verified correctness core (consensus, durability, the edge
read/consistency plane, and a Porcupine-backed linearizability harness). **Before relying on it, read
[docs/known-limitations.md](docs/known-limitations.md).** The most important v1 limitations:

- **No encryption at rest.** Values — **including `secure/` keys** — are stored **plaintext**
  (integrity-checked only via HMAC; not encrypted). `secure/` is a read-**freshness** class
  (always-linearizable, fail-closed for security-critical keys), **not** confidentiality. **Do not store
  secrets** (passwords, tokens, keys) in Configd — use a dedicated secret manager. At-rest encryption is a
  **v2** item ([RR-098](docs/readiness/production-readiness-register.md)).
- **Client "watch" / change-subscription:** the RFC §2 watch protocol is **implemented server-side**
  (N=1) on the edge endpoint (wire `0x02`, multiplex/filter veneer, whole-target authz gate, bounded
  revocation). A **conforming client driver is the next deliverable**, and N>1 multi-shard watch is **v3**;
  until a driver ships, use polling / delta-apply. See [known-limitations §2](docs/known-limitations.md)
  for the guarantees + the deployment security model.
- **N=1 by default (sharding is built, server-wired, and metal-proven).** v1 defaults to a single Raft
  group (byte-identical to a non-sharded build). Multi-shard scaling is now wired into the server
  (`StaticShardMap` + `shardFor` routing) and **measured near-linear on real hardware — ~2.45× on 3
  machines** (656→1075→1607 committed writes/s;
  [horizontal run](docs/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md)). Measured single-group
  write knee is **~800 writes/s** (churn-bound). N>1 + the edge endpoint fail-closes unless explicitly opted
  in, and **sustained horizontal scale is operator-managed** — leadership is not yet auto-balanced (one
  leader per box; a `transferLeadership` balancer is a v2 follow-up).
- **Empirically validated on metal, with bounded residuals.** DR drills are **executed** (**372 ms**
  failover, single bounded election, **0/1000** committed-write loss across three fault modes; RTO
  4.2 s / 5.9 s — [DR drills](docs/measurement/ec2-2026-06-30/02-dr-drills.md)), and a **6 h** soak passed
  leak/OOM-clean ([soak](docs/measurement/ec2-2026-06-30/04-soak.md)). Honest residuals: **no full 24 h/72 h
  soak** (proven to 6 h, not 24 h), no literal sustained 10 k/s, and no cross-region/WAN measurement
  (single-region by design).

## Documentation

- [Integration Guide](docs/wiki/Integration-Guide.md) · [Getting Started](docs/wiki/Getting-Started.md)
- [Consistency Contract](docs/consistency-contract.md) · [Known Limitations](docs/known-limitations.md)
- [Production-Readiness Register](docs/readiness/production-readiness-register.md) · [ADRs](docs/decisions/)
- [v1 Go/No-Go Review](docs/readiness/v1-go-no-go-2026-07-01.md) · [Operator Runsheet](docs/operator-runsheet.md) · [Deployer MUST-KNOW](docs/deployer-must-know.md) · [Burn-in Contract](docs/burn-in-contract.md)

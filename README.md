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
- **Single Raft group (N=1) by design.** Multi-shard scaling is built and sim-verified but its aggregate
  throughput is **unmeasured** (v2 / EC2). The measured single-group write knee is ~800 writes/s.
- **Empirical validation deferred** — no completed 24h soak, DR drills not yet executed.

## Documentation

- [Integration Guide](docs/wiki/Integration-Guide.md) · [Getting Started](docs/wiki/Getting-Started.md)
- [Consistency Contract](docs/consistency-contract.md) · [Known Limitations](docs/known-limitations.md)
- [Production-Readiness Register](docs/readiness/production-readiness-register.md) · [ADRs](docs/decisions/)

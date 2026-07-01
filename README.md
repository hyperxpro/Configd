# Configd

A strongly-consistent, sharded, mTLS-securable configuration store. Writes go through sharded Raft for durability and horizontal scale; committed changes fan out to a region-local fleet of edge readers that serve reads from an in-process, lock-free cache in microseconds.

The split is the point: consensus gives you linearizable, durable writes, and the edge gives you fast local reads without paying a consensus round trip on the read path.

## What v1 is

- **Durable, linearizable writes** through Raft, with strong reads available via ReadIndex and bounded-staleness reads at the edge by default.
- **Sharded for horizontal scale.** A single region-local group is the default (N=1); sharding is wired and proven, and scale is near-linear across machines.
- **mTLS on the edge and replication surfaces**, with a per-key authorization model (roles, policies, deny-precedence) and a keyed-HMAC audit log. The admin API is HTTPS with a bearer token rather than client certificates, and edge reads are plaintext by design.
- **At-rest integrity**, meaning tamper detection, not encryption. Values are stored in plaintext and integrity-checked; do not put secrets in Configd (see the limitations below).
- **Secure by configuration, not by default.** TLS and mTLS, authentication, the audit log, and replay protection are off until you turn them on (the server warns loudly while they are off). See the [operator runsheet](docs/operations/operator-runsheet.md).

## What v1 proved on real hardware

- **Durability under fault.** Disaster-recovery drills failed over in about 372 ms with zero committed-write loss across three fault modes (recovery time 4.2 to 5.9 s), and a 6-hour soak ran leak- and OOM-clean.
- **Horizontal scale.** Near-linear at about 2.45x across three machines (656, then 1075, then 1607 committed writes per second). A single group's write knee is about 800 writes/s, and a single box plateaus near 1100.
- **Honest residuals.** Soak is proven to 6 hours, not 24. Measurement is single-region by design. Sustained multi-machine scale is operator-managed, because leadership is not auto-balanced yet.

The full evidence lives in [`docs/archive/`](docs/archive/): the [go/no-go review](docs/archive/readiness/v1-go-no-go-2026-07-01.md), the audited [readiness register](docs/archive/readiness/production-readiness-register.md), and the two paid [EC2 measurement runs](docs/archive/measurement/).

## Where to go

| You want to | Start here |
|---|---|
| Understand the whole system | [`docs/architecture/`](docs/architecture/) |
| Write a client driver | [`docs/rfc/driver-protocol/`](docs/rfc/driver-protocol/) - a self-contained, implementable protocol spec |
| Deploy or operate a cluster | [`docs/operations/`](docs/operations/) - runsheet, deployer must-knows, runbooks |
| Know the honest edges | [`docs/operations/known-limitations.md`](docs/operations/known-limitations.md) |
| Understand a design decision | [`docs/adr/`](docs/adr/) |
| See what is coming in v2 | [`docs/v2-backlog.md`](docs/v2-backlog.md) |

A fuller map is in [`docs/README.md`](docs/README.md).

## Honest scope: v1 vs v2

v1 is deliberately scoped. These are known and understood, not surprises:

- **No encryption at rest.** At-rest protection is integrity only; values, including `secure/` keys, are plaintext (the `secure/` prefix is a read-freshness class, not confidentiality). Keep secrets in a dedicated secret manager. Encryption at rest is a v2 item.
- **Watches are server-side.** The watch protocol is implemented for a single group; a conforming client driver is buildable from the RFC but not yet shipped, and cross-shard watches are v3. Until a driver ships, poll and delta-apply.
- **Horizontal scale is operator-managed.** Multi-machine scale needs one leader per box, placed and maintained by the operator; an automatic leadership balancer is a v2 item.

See [`docs/v2-backlog.md`](docs/v2-backlog.md) for the rest.

## Build

Requires JDK 25. Build and test with `mvn clean install`.

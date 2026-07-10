# Configd

A strongly-consistent, sharded, mTLS-securable configuration store. Writes go through sharded Raft for durability and horizontal scale; committed changes fan out to a region-local fleet of edge readers that serve reads from an in-process, lock-free cache in microseconds.

The split is the point: consensus gives you linearizable, durable writes, and the edge gives you fast local reads without paying a consensus round trip on the read path.

## What v1 is

- **Durable, linearizable writes** through Raft, with strong reads available via ReadIndex and bounded-staleness reads at the edge by default. Snapshots stream to lagging followers in chunks, so total state is not capped by the wire frame size.
- **Tamper- and rollback-evident on disk.** Every persistent format carries a version marker and, in an authenticated posture, an integrity envelope; the WAL binds a per-record hash chain, and a dual-slot, monotonic durability anchor (folding in the Raft vote state) makes truncation or rollback of committed data fail closed at recovery rather than silently accepted. In a real multi-node cluster a peer-quorum witness closes the within-term vote-rollback (split-brain) case. The threat model is a filesystem-write adversary without the key; the honest residuals (freshness/anchor-rollback within a term, and the N>=5 fast-vote window) are documented in [the frozen-format design](docs/design/frozen-format-v1-2026-07-03.md).
- **Sharded for horizontal scale.** A single region-local group is the default (N=1); sharding is wired and proven, and scale is near-linear across machines. Per-group leadership can be moved with an ADMIN-gated transfer route.
- **mTLS on the edge and replication surfaces**, with a per-key authorization model (roles, policies, deny-precedence) and a keyed-HMAC audit log. The admin API is HTTPS with a bearer token rather than client certificates, and edge reads are plaintext by design.
- **At-rest integrity by default, encryption available.** By default, values are stored in plaintext and integrity-checked (tamper detection); node-local AES-256-GCM encryption at rest can be enabled with a flag, backed by a pluggable KMS-provider SPI. At-rest integrity keys are term-versioned in both postures and rooted in a persisted, dual-slot keyring, so key rotation is non-destructive by construction (old-term data still reads); the keyless posture is byte-for-byte identical to pre-freeze. Enabling encryption is a one-way door — read [the deployer must-knows](docs/operations/deployer-must-know.md) first. With encryption off, do not put secrets in Configd (see the limitations below).
- **Secure by configuration, not by default.** TLS and mTLS, authentication, the audit log, encryption at rest, and replay protection are off until you turn them on (the server warns loudly while they are off). See the [operator runsheet](docs/operations/operator-runsheet.md).

## What v1 proved on real hardware

- **Durability under fault.** Disaster-recovery drills failed over in about 372 ms with zero committed-write loss across three fault modes (recovery time 4.2 to 5.9 s), and a 6-hour soak ran leak- and OOM-clean.
- **Linearizability under fault.** The faulted linearizability suite is green on the release commit: 8 of 8 histories linearizable with fault injection active, and edge staleness bounds held with wide margin (p99 of 13 to 24 ms against a 500 ms bound).
- **Horizontal scale.** Near-linear at about 2.45x across three machines (656, then 1075, then 1607 committed writes per second). A single group's write knee is about 800 writes/s, and a single box plateaus near 1100.
- **Encryption cost.** Enabling encryption at rest cost about 2.5% of the write knee and 1.5% at p50; the overhead is tail-weighted (p99 roughly doubles).
- **Honest residuals.** Soak is proven to 6 hours, not 24. Measurement is single-region by design. Leadership auto-balance is built and E2E-tested but the 2.45x number predates it (measured under manual one-leader-per-box placement), so sustained multi-machine scale under the balancer is not yet number-captured.

The full evidence lives in [`docs/archive/`](docs/archive/): the [go/no-go review](docs/archive/readiness/v1-go-no-go-2026-07-01.md), the audited [readiness register](docs/archive/readiness/production-readiness-register.md), and the two paid [EC2 measurement runs](docs/archive/measurement/). The release-commit measurements (faulted linearizability, staleness bounds, encryption overhead) are in [`docs/measurement/`](docs/measurement/).

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

- **Encryption at rest is off by default and a one-way door.** With it off, values — including `secure/` keys — are plaintext (the `secure/` prefix is a read-freshness class, not confidentiality); keep secrets in a dedicated secret manager. Once enabled, it cannot be disabled and the binary cannot roll back to a pre-encryption version. The default `local` key provider derives its root from the cluster signing key (confidentiality fate-shares with it); an external **Vault Transit** KMS provider also ships for off-host custody, and other cloud KMS/HSM backends can be added behind the same SPI.
- **Key rotation is non-destructive but offline in v1.** The term-rotation and signing-key-rotation mechanisms are built, crash-atomic, and tested, but no online/admin trigger ships yet — a rotation is an out-of-band maintenance action on a stopped node. The security-audit HMAC chain is the one at-rest key not term-versioned (it derives from the signing key), so a *signing-key* rotation leaves pre-rotation audit records readable but no longer tamper-verifiable across the boundary; term rotation is unaffected. Both are documented residuals, not data-loss defects.
- **Watches ship server-side plus a Java reference client.** The watch protocol is implemented for N≥1 (multi-shard watches are served by a server-side aggregating endpoint); a conforming Java reference client and a conformance suite ship, and drivers in other languages are buildable from the RFC. Ordering is per-shard, never global — a globally-ordered cross-shard watch is out of scope by design.
- **Horizontal scale is leadership auto-balanced.** Multi-machine scale needs one leader per box; a built-in decentralized balancer (on by default) maintains that placement, plus an ADMIN transfer route for manual moves. The measured 2.45x was captured under manual placement, so the balancer is built and E2E-tested but not yet load-measured at scale; transfer-on-graceful-shutdown remains a v2 item.

See [`docs/v2-backlog.md`](docs/v2-backlog.md) for the rest.

## Build

Requires JDK 25. Build and test with `mvn clean install`.

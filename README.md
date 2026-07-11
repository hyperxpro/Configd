# Configd

A strongly-consistent, sharded configuration store. Writes go through Raft, so they are durable and
linearizable. Committed changes fan out to a region-local fleet of edge readers that serve reads from
an in-process, lock-free cache in microseconds.

The split is the whole idea: consensus gives you durable, linearizable writes, and the edge gives you
fast local reads without paying a consensus round trip on every read.

## What it does

- **Durable, linearizable writes** through Raft. Strong reads go through ReadIndex; edge reads are
  bounded-staleness by default. Snapshots stream to lagging followers in chunks, so total state is not
  capped by the wire frame size.
- **Sharded for horizontal scale.** The default is a single region-local Raft group; sharding is wired
  and scales near-linearly across machines. A decentralized balancer keeps one leader per box, and an
  ADMIN-gated route moves a group's leadership by hand.
- **Tamper- and rollback-evident on disk.** Every persistent format carries a version marker; in an
  authenticated posture it also carries an integrity envelope, the WAL binds a per-record hash chain,
  and a dual-slot monotonic durability anchor makes truncation or rollback of committed data fail
  closed at recovery rather than being silently accepted. In a real multi-node cluster a peer-quorum
  witness closes the within-term vote-rollback (split-brain) case. The threat model is a
  filesystem-write adversary who does not hold the key.
- **A pluggable authentication system** — No-Auth, HTTP Basic, Bearer, mTLS, and OIDC/JWT — shared by
  the admin API and the edge plane through one authenticator chain. Authorization is per-key (roles,
  policies, deny-precedence), the edge honors credential expiry and revocation, and the audit log is a
  keyed-HMAC chain. Raft node membership is mTLS-only against a per-node allow-list.
- **At-rest integrity by default; encryption optional.** Values are stored in plaintext and
  integrity-checked (tamper detection) unless you turn on node-local AES-256-GCM encryption, which is
  backed by a pluggable KMS provider (a local key derived from the signing key, or external Vault
  Transit). At-rest keys are versioned per Raft term and rooted in a persisted dual-slot keyring, so
  rotation is non-destructive: old-term data still reads. With encryption off, the keyless posture is
  byte-for-byte identical to a build that never had the feature.
- **Secure by configuration, not by default.** TLS and mTLS, authentication, the audit log, encryption
  at rest, and replay protection are all off until you turn them on, and the server warns loudly while
  they are off. One control is unconditional: the default bind is loopback, and binding a non-loopback
  interface with auth off is refused unless you explicitly override it.
- **Fails closed under load and faults.** Write admission (429 + `Retry-After`) is on by default under
  a conservative in-flight-proposal cap; readiness is shard-aware (a node that has lost quorum on any
  shard it hosts reports not-ready), and a node drains before it shuts down on `SIGTERM`.

## Limitations worth knowing up front

These are deliberate, not surprises. The full list is in
[`docs/operations/known-limitations.md`](docs/operations/known-limitations.md).

- **Encryption at rest is off by default and a one-way door.** With it off, every value is plaintext,
  including `secure/` keys — the `secure/` prefix buys read *freshness* (always linearizable, never
  served stale), not confidentiality. Keep real secrets in a dedicated secret manager. Once you write
  the first encrypted record, encryption cannot be turned back off and the binary cannot roll back to a
  version that predates it.
- **Key rotation is non-destructive but offline.** Term and signing-key rotation are crash-atomic and
  keep old data readable, but there is no online admin trigger yet — rotation is a maintenance action on
  a stopped node. The audit-log HMAC chain is the one at-rest key that is not term-versioned, so its
  metadata is integrity-protected but not encrypted.
- **Watches order per shard, never globally.** The watch protocol is served server-side and consumed by
  the bundled Java reference client (with a conformance suite); drivers in other languages build from
  the protocol spec. Events are ordered within a key and within a shard, but there is no cross-shard
  global order — that is out of scope by design, because there is no global clock to take a cut against.
- **Measurement is single-region.** Reads are region-local; the system is not designed for cross-region
  or WAN operation. Endurance is verified to hours, not weeks. Measured numbers and their exact
  conditions live in [`docs/measurement/`](docs/measurement/) and [`docs/archive/`](docs/archive/).

## Build and run

Configd targets **Java 25** and builds with the bundled Maven wrapper:

```bash
./mvnw clean install      # compile every module and run the test suite
```

It ships a control-plane server (`io.configd.server.ConfigdServer`) and a standalone edge reader
(`io.configd.edge.node.EdgeNodeMain`). The quickest way to bring up a small mTLS cluster is the Compose
topology under [`deploy/compose/`](deploy/compose/). To launch a single node directly:

```bash
java --enable-preview -XX:+UseZGC -XX:MaxRAMPercentage=50.0 \
     -cp "configd-server/target/*:configd-server/target/libs/*" \
     io.configd.server.ConfigdServer \
     --node-id 1 --data-dir /var/lib/configd \
     --bind-port 9090 --api-port 8080 --edge-port 7070
```

The config API is under `/v1/config`, liveness and readiness are at `/health/live` and `/health/ready`,
and Prometheus metrics are at `/metrics`. Turn on security before production — see the
[operator runsheet](docs/operations/operator-runsheet.md) and
[deployer must-knows](docs/operations/deployer-must-know.md). Full instructions are in the
[Getting Started guide](docs/wiki/Getting-Started.md).

## Documentation

| You want to | Start here |
|---|---|
| Get a feel for the system | [Architecture overview](docs/wiki/Architecture-Overview.md) |
| Understand it in depth | [`docs/architecture/`](docs/architecture/) |
| Build and run it | [Getting Started](docs/wiki/Getting-Started.md) |
| Write a client driver | [`docs/rfc/driver-protocol/`](docs/rfc/driver-protocol/) — a self-contained, implementable spec |
| Deploy or operate a cluster | [`docs/operations/`](docs/operations/) and the incident runbooks in [`ops/runbooks/`](ops/runbooks/) |
| Know the honest edges | [Known limitations](docs/operations/known-limitations.md) |
| Understand a design decision | [`docs/adr/`](docs/adr/) |
| See the measurement evidence | [`docs/measurement/`](docs/measurement/) and [`docs/archive/`](docs/archive/) |

A fuller map is in [`docs/README.md`](docs/README.md).

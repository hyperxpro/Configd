# Configd

Configd is a strongly-consistent, sharded configuration store. Writes go through Raft, so they're
durable and linearizable. Committed changes fan out to region-local edge nodes, which serve reads from
an in-process cache.

## Build and run

Needs Java 25. Build with the bundled Maven wrapper:

```bash
./mvnw clean install
```

Start a single node:

```bash
java --enable-preview -XX:+UseZGC -XX:MaxRAMPercentage=50.0 \
     -cp "configd-server/target/*:configd-server/target/libs/*" \
     io.configd.server.ConfigdServer \
     --node-id 1 --data-dir /var/lib/configd \
     --bind-port 9090 --api-port 8080 --edge-port 7070
```

The config API is under `/v1/config`; `/health/live` and `/health/ready` are the health checks, and
`/metrics` serves Prometheus. For a small mTLS cluster, use the Compose topology in
[`deploy/compose/`](deploy/compose/). Turn on security before production, starting with the
[operator runsheet](docs/operations/operator-runsheet.md).

## Before you rely on it

- Encryption at rest is off by default: values are stored in plaintext, `secure/` keys included, so
  keep real secrets in a dedicated secret manager.
- Once a node writes its first encrypted record, encryption can't be turned back off, and the binary
  can't roll back to a version that predates it.
- TLS, authentication, the audit log, and replay protection are also off until you enable them; the
  server warns while they are, and won't bind a non-loopback interface with auth off unless you
  override it.
- Watches order events within a key and within a shard, never across shards.
- Single region only. Reads are region-local, WAN operation isn't a goal, and endurance is verified in
  hours, not weeks.

The rest are in [known limitations](docs/operations/known-limitations.md).

## Documentation

| You want to | Start here |
|---|---|
| Get a feel for the system | [Architecture overview](docs/wiki/Architecture-Overview.md) |
| Understand it in depth | [`docs/architecture/`](docs/architecture/) |
| Build and run it | [Getting Started](docs/wiki/Getting-Started.md) |
| Write a client driver | [`docs/rfc/driver-protocol/`](docs/rfc/driver-protocol/) |
| Deploy or operate a cluster | [`docs/operations/`](docs/operations/) and the runbooks in [`ops/runbooks/`](ops/runbooks/) |
| Know the honest edges | [Known limitations](docs/operations/known-limitations.md) |
| Understand a design decision | [`docs/adr/`](docs/adr/) |
| See the measurement evidence | [`docs/measurement/`](docs/measurement/) and [`docs/archive/`](docs/archive/) |

A fuller map is in [`docs/README.md`](docs/README.md).

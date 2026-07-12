# Configd

[![CI](https://github.com/hyperxpro/Configd/actions/workflows/ci.yml/badge.svg)](https://github.com/hyperxpro/Configd/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)

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
[`deploy/compose/`](deploy/compose/) (see [Docker](docs/wiki/Docker.md)). Turn on security before
production, starting with the [operator runsheet](docs/operations/operator-runsheet.md).

## Try it

Write and read a config value against that node (the key is a path, the value is the raw request body):

```bash
# write
curl -X PUT --data-binary 'jdbc:postgresql://db:5432/orders' \
     http://localhost:8080/v1/config/orders/db/url
# 200  Committed: seq=1

# read it back — the value comes back as the response body
curl -i http://localhost:8080/v1/config/orders/db/url
# 200  jdbc:postgresql://db:5432/orders
#      X-Config-Version: 1   X-Consistency: stale

# force a linearizable (read-your-writes) read, or DELETE the key
curl 'http://localhost:8080/v1/config/orders/db/url?consistency=linearizable'
curl -X DELETE http://localhost:8080/v1/config/orders/db/url
```

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
| Call the API | [HTTP API reference](docs/wiki/HTTP-API.md) |
| Look up a knob | [Configuration reference](docs/wiki/Configuration.md) |
| Compare with etcd, Consul, ZooKeeper | [Comparison](docs/wiki/Comparison.md) |
| Get a quick answer | [FAQ](docs/wiki/FAQ.md) |
| Write a client driver | [`docs/rfc/driver-protocol/`](docs/rfc/driver-protocol/) |
| Deploy or operate a cluster | [`docs/operations/`](docs/operations/) and the runbooks in [`ops/runbooks/`](ops/runbooks/) |
| Know the honest edges | [Known limitations](docs/operations/known-limitations.md) |
| Understand a design decision | [`docs/adr/`](docs/adr/) |
| See the measurement evidence | [`docs/measurement/`](docs/measurement/) and [`docs/archive/`](docs/archive/) |

A fuller map is in [`docs/README.md`](docs/README.md).

## Contributing

Contributions are welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) first. The bar for correctness and
operator-honesty is high, and the build runs the full reactor on every change.

## Security

To report a vulnerability, use GitHub's private vulnerability reporting rather than a public issue; see
[SECURITY.md](SECURITY.md).

## License

Configd is licensed under the [Apache License 2.0](LICENSE).

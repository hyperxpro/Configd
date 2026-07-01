# Getting Started

## Prerequisites

- **Java 25** (Amazon Corretto or Eclipse Temurin recommended). Configd targets Java 25 and builds
  with `--enable-preview` (ADR-0022); a later LTS migration is planned.
- **Maven** -- the wrapper (`./mvnw`, Apache Maven 3.9.9) is included, so no global install is needed.

## Build from source

```bash
git clone <repo-url> && cd configd
./mvnw clean verify
```

This compiles all modules and runs the full test suite. The `--enable-preview` flag is configured in
the build (compiler and test JVM), so you do not pass it by hand. Artifacts land in each module's
`target/` directory.

To build without tests:

```bash
./mvnw clean install -DskipTests
```

To build a single module and everything it depends on:

```bash
./mvnw -pl configd-consensus-core -am install
```

## Run the tests

```bash
# Everything
./mvnw test

# One module
./mvnw -pl configd-consensus-core test

# One test class
./mvnw -pl configd-consensus-core test -Dtest=RaftNodeTest
```

See [Testing](Testing.md) for the deterministic simulation, linearizability, and jcstress harnesses.

## Project structure

```
configd/
  configd-common/               Shared types: NodeId, Clock, HybridClock, ConfigScope, Storage
  configd-transport/            Transport abstraction, framing, TLS, JDK TCP baseline
  configd-netty/                Netty 4.2 transport implementation (all four surfaces)
  configd-consensus-core/       Raft: RaftNode, RaftLog, elections, replication
  configd-config-store/         MVCC control-plane store, HAMT, state machine, signing
  configd-edge-cache/           Lock-free edge read store, staleness, cursors
  configd-replication-engine/   Multi-Raft driver, ShardMap, owner-executor pool, flow control
  configd-distribution-service/ Fan-out (Plumtree/HyParView), edge frames, watches
  configd-control-plane-api/    ACL, auth, audit, replay guard, rate limiter, write/read services
  configd-observability/        Metrics, Prometheus exporter, SLO tracking, invariant monitor
  configd-server/               ConfigdServer -- the control-plane node (has a main)
  configd-edge-node/            EdgeNodeMain -- the standalone edge reader (has a main)
  configd-testkit/              Deterministic simulation + JMH benchmarks (test only)
  configd-linz/                 Porcupine linearizability harness (test only)
  configd-jcstress/             Java Memory Model concurrency tests (test only)
  docker/                       Build and runtime container images
  deploy/                       Compose and Kubernetes deployment scaffolding
  docs/                         Architecture, ADRs, operations, the RFC, and this wiki
  pom.xml                       Root Maven reactor
```

## Run a server

Configd ships a real control-plane server (`io.configd.server.ConfigdServer`) and a standalone edge
reader (`io.configd.edge.node.EdgeNodeMain`) -- it is no longer library-only.

The easiest way to bring up a small cluster is the Compose topology under `deploy/compose/` (three
control-plane nodes plus edge readers, wired with mTLS); see [Docker](Docker.md).

To launch a node directly, put the module jars on the classpath and run the server main:

```bash
java --enable-preview \
     -XX:+UseZGC \
     -XX:MaxRAMPercentage=50.0 \
     -XX:+ExitOnOutOfMemoryError \
     -cp "configd-server/target/*:configd-server/target/libs/*" \
     io.configd.server.ConfigdServer \
     --node-id 1 --data-dir /var/lib/configd \
     --bind-port 9090 --api-port 8080 --edge-port 7070 \
     --peer-addresses 1=cp1:9090,2=cp2:9090,3=cp3:9090
```

Ports: `--bind-port` (9090) is the inter-node Raft wire, `--api-port` (8080) is the control-plane /
admin API, and `--edge-port` (7070) is the edge fan-out. Liveness and readiness are at
`/health/live` and `/health/ready`; Prometheus metrics at `/metrics`; the config API under
`/v1/config`.

Enable security before production. v1 is secure-by-config, not secure-by-default: set `--auth-token`,
configure TLS (`--tls-cert`/`--tls-key`/`--tls-trust-store`), and enable the audit log and replay
protection. Each control is off by default and logs a loud warning when off. See
[`../operations/operator-runsheet.md`](../operations/operator-runsheet.md) and
[`../operations/deployer-must-know.md`](../operations/deployer-must-know.md).

## Recommended JVM flags

Configd is designed for Java 25 with the generational ZGC low-latency collector (ADR-0041):

```bash
java --enable-preview \
     -XX:+UseZGC \
     -XX:MaxRAMPercentage=50.0 \
     -XX:+ExitOnOutOfMemoryError \
     -Djava.security.egd=file:/dev/urandom \
     -cp "libs/*" io.configd.server.ConfigdServer ...
```

Do not pass `-XX:+ZGenerational` -- it was removed in JDK 24, generational is the only ZGC on Java 25,
and passing it only produces an "ignoring option" warning. The edge read path is allocation-free by
design, so it does not feed the collector; ZGC keeps the write-commit and read tails free of
multi-millisecond GC pauses.

## Using Configd as a library

You can also embed the Configd libraries directly (for example, the lock-free edge read store in an
application that receives deltas from elsewhere). Modules publish under the `io.configd` group at the
project version in the root `pom.xml` (currently `0.1.0-SNAPSHOT`):

```xml
<dependency>
  <groupId>io.configd</groupId>
  <artifactId>configd-edge-cache</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

See the [Integration Guide](Integration-Guide.md) for edge-only and full-consensus embedding.

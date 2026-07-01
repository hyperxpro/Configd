# Docker

Configd ships two Dockerfiles under `docker/`, plus a ready-to-run Compose topology under
`deploy/compose/`.

| File | Purpose | Base image |
|---|---|---|
| `docker/Dockerfile.build` | CI/CD -- build and run the full test suite in a hermetic container | `eclipse-temurin:25-jdk-noble` |
| `docker/Dockerfile.runtime` | Minimal runtime image that runs the control-plane server | builds on `eclipse-temurin:25-jdk-noble`, runs on `eclipse-temurin:25-jre-noble` |

Both build with Maven and Java 25. Configd is no longer library-only: the runtime image runs a real
control-plane server (`io.configd.server.ConfigdServer`), and the edge reader
(`io.configd.edge.node.EdgeNodeMain`) is packaged alongside it.

## Build image

Compiles all modules and runs `mvn clean verify` (the default command).

```bash
# Build the image
docker build -f docker/Dockerfile.build -t configd-build .

# Build + run the full test suite
docker run --rm configd-build

# Build without tests
docker run --rm configd-build mvn clean install -DskipTests -B

# Run one module's tests
docker run --rm configd-build mvn -pl configd-consensus-core test -B
```

Artifacts are written to `/workspace/*/target/` inside the container.

## Runtime image

A multi-stage build: it compiles with the JDK 25 image (`mvn clean package -DskipTests`), collects
every runtime jar into `/app/libs`, and packages them onto a minimal Temurin JRE 25 image. The image
runs as a non-root `configd` user, exposes the API and Raft ports, and starts the control-plane
server under generational ZGC.

```bash
docker build -f docker/Dockerfile.runtime -t configd-runtime .

docker run --rm \
  -p 8080:8080 -p 9090:9090 \
  -v configd-data:/data \
  configd-runtime \
  --node-id 1 --data-dir /data \
  --bind-port 9090 --api-port 8080 --edge-port 7070 \
  --peer-addresses 1=host1:9090,2=host2:9090,3=host3:9090
```

Details of the image:

- **Entrypoint** runs `io.configd.server.ConfigdServer` with
  `--enable-preview -XX:+UseZGC -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError`. Heap is a
  percentage of the container memory, so size it with `docker run -m`; override JVM options via
  `JAVA_OPTS`. Note `-XX:+ZGenerational` is deliberately not passed (removed in JDK 24; generational
  is the only ZGC on Java 25).
- **Ports:** `EXPOSE 8080 9090` -- 8080 is the control-plane/admin API, 9090 is the inter-node Raft
  wire. Add `-p 7070:7070` if you serve edge fan-out from this node (`--edge-port`).
- **Healthcheck:** the runtime image probes `http://localhost:8080/health/live`. When TLS is enabled
  (as in the Compose topology below), the probe is the HTTPS variant.
- **Edge node:** its shaded jar is in `/app/libs` too. To run the edge reader instead of the server,
  override the entrypoint to launch `io.configd.edge.node.EdgeNodeMain`. The Compose topology uses
  purpose-built slim images for that.

## Compose cluster (deploy/compose)

`deploy/compose/` brings up a three-node control-plane cluster (`cp1`, `cp2`, `cp3`) plus edge readers,
wired end to end with mTLS. It uses its own slim Dockerfiles (`Dockerfile.server`, `Dockerfile.edge`,
both on `eclipse-temurin:25-jre-noble`) and a secrets bundle.

```bash
cd deploy/compose

# Generate the mTLS keystores/truststores and the signing key (one-time)
./setup-secrets.sh

# Bring up the cluster
docker compose -f compose.yaml up --build
```

Each control-plane node is launched with
`--bind-address 0.0.0.0 --bind-port 9090 --api-port 8080 --edge-port 7070 --peer-addresses 1=cp1:9090,2=cp2:9090,3=cp3:9090`
and the TLS/signing flags from the secrets bundle; each edge node connects over mTLS with a
certificate DN identity (for example `--edge-id CN=edge-1`). The API ports are published on the loopback
interface (for example `127.0.0.1:18081` for `cp1`). The signing key is mounted read-only from a trust
boundary separate from the data volume, satisfying the fail-closed co-location rule (ADR-0044).

Kubernetes bootstrap scaffolding lives in `deploy/kubernetes/`.

## Layer caching

Both Dockerfiles copy the POM files first and run `mvn dependency:resolve` before copying the source,
so dependency downloads are cached across builds as long as the POMs do not change:

1. POM files -- change sometimes; a change re-resolves dependencies.
2. Source -- changes often; only recompiles.

# Production Deployment Guide

## Prerequisites

- Java 25 (Amazon Corretto 25 recommended)
- 3+ nodes for control plane (quorum requires majority)
- mTLS certificates (PKCS12 keystores)
- Persistent storage (SSD recommended for WAL fsync performance)

## Quick Start

```bash
# Build
./mvnw clean package -DskipTests

# Start node 0
java --enable-preview \
  -XX:+UseZGC -XX:+ZGenerational \
  -Xms512m -Xmx2g \
  -XX:+ExitOnOutOfMemoryError \
  -jar configd-server/target/configd-server-0.1.0-SNAPSHOT.jar \
  --node-id node-0 \
  --data-dir /var/lib/configd/data \
  --peers node-0,node-1,node-2 \
  --bind-address 0.0.0.0 \
  --bind-port 9090 \
  --api-port 8080 \
  --tls-cert /etc/configd/tls/server.p12 \
  --tls-key /etc/configd/tls/server.p12 \
  --tls-trust-store /etc/configd/tls/truststore.p12 \
  --auth-token <secret-bearer-token>
```

## Configuration Reference

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--node-id` | Yes | — | Unique node identifier |
| `--data-dir` | Yes | — | Path for WAL, snapshots, and state |
| `--peers` | Yes | — | Comma-separated list of all node IDs |
| `--bind-address` | No | `0.0.0.0` | Raft transport bind address |
| `--bind-port` | No | `9090` | Raft transport port |
| `--api-port` | No | `8080` | HTTP API / health check port |
| `--tls-cert` | No | — | Path to PKCS12 keystore |
| `--tls-key` | No | — | Path to PKCS12 key file |
| `--tls-trust-store` | No | — | Path to PKCS12 trust store |
| `--auth-token` | No | — | Bearer token for write/admin API |

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health/live` | No | Liveness probe (always 200 if process is running) |
| `GET` | `/health/ready` | No | Readiness probe (200 if Raft has elected a leader) |
| `GET` | `/metrics` | No | Prometheus metrics in text exposition format |
| `PUT` | `/v1/config/{key}` | Yes | Write config value (body = raw bytes) |
| `GET` | `/v1/config/{key}` | No | Read config value |
| `DELETE` | `/v1/config/{key}` | Yes | Delete config key |

## JVM Tuning

### Garbage Collection

Use ZGC Generational for sub-millisecond GC pauses on the edge read path:

```
-XX:+UseZGC -XX:+ZGenerational
```

### Heap Sizing

| Workload | Keys | Recommended Heap |
|----------|------|-----------------|
| Small | < 100K | 512m - 1g |
| Medium | 100K - 1M | 1g - 4g |
| Large | 1M - 10M | 4g - 16g |

The HAMT uses ~80 bytes per key-value entry (node overhead + references). Budget 100 bytes/key for sizing.

### OOM Handling

Always use `-XX:+ExitOnOutOfMemoryError` in production. Do NOT use `-XX:+HeapDumpOnOutOfMemoryError` — heap dumps expose config values in plaintext (see `docs/security-heap-dump-policy.md`).

## TLS Certificate Setup

### Generate Self-Signed Certificates (Development)

```bash
# Generate CA
keytool -genkeypair -alias ca -keyalg EC -groupname secp256r1 \
  -keystore ca.p12 -storetype PKCS12 -storepass changeit \
  -dname "CN=Configd CA" -ext bc:c

# Generate server cert
keytool -genkeypair -alias server -keyalg EC -groupname secp256r1 \
  -keystore server.p12 -storetype PKCS12 -storepass changeit \
  -dname "CN=configd-server"

# Sign with CA
keytool -certreq -alias server -keystore server.p12 -storetype PKCS12 \
  -storepass changeit | \
keytool -gencert -alias ca -keystore ca.p12 -storetype PKCS12 \
  -storepass changeit -ext san=dns:configd-0,dns:configd-1,dns:configd-2 | \
keytool -importcert -alias server -keystore server.p12 -storetype PKCS12 \
  -storepass changeit

# Create trust store
keytool -exportcert -alias ca -keystore ca.p12 -storetype PKCS12 \
  -storepass changeit | \
keytool -importcert -alias ca -keystore truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt
```

### Certificate Rotation

1. Generate new certificates signed by the same CA (or a new CA added to the trust store)
2. Replace certificate files on disk
3. The `TlsManager` supports hot-reload — call the admin endpoint or send SIGHUP
4. Connections using the old certificate will drain naturally
5. See `docs/runbooks/cert-rotation.md` for the full procedure

## Kubernetes Deployment

Apply the StatefulSet and PodDisruptionBudget:

```bash
kubectl apply -f deploy/kubernetes/configd-statefulset.yaml
```

Key features of the k8s deployment:
- **StatefulSet** with 3 replicas for quorum
- **Pod anti-affinity** to spread across nodes
- **PersistentVolumeClaim** for WAL durability across pod restarts
- **PodDisruptionBudget** allows max 1 unavailable during rolling updates
- **Readiness/liveness probes** via HTTP health endpoints

## Monitoring

### SLO Definitions

| SLO | Target | Window |
|-----|--------|--------|
| Write commit latency p99 | < 150ms | 5m |
| Edge read latency p99 | < 1ms | 5m |
| Edge read latency p999 | < 5ms | 5m |
| Propagation delay p99 | < 500ms | 5m |
| Control plane availability | 99.999% | 30d |
| Edge read availability | 99.9999% | 30d |
| Write throughput baseline | > 10,000/s | 5m |

### Burn-Rate Alerts

The `BurnRateAlertEvaluator` computes multi-window burn rates:
- **Critical** (burn rate >= 14.4x): Pages on-call immediately
- **Warning** (burn rate >= 1.0x): Ticket for investigation

### Key Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `configd_raft_commit_total` | Counter | Total committed entries |
| `configd_raft_term` | Gauge | Current Raft term |
| `configd_edge_read_latency_ns` | Histogram | Edge read latency |
| `configd_propagation_lag` | Gauge | Leader commit → edge applied gap |
| `configd_propagation_violations_total` | Counter | LIVE-1 violations |

## Troubleshooting

See the operational runbooks in `docs/runbooks/`:
- `region-loss.md` — Region failure and recovery
- `leader-stuck.md` — Stuck Raft leader
- `reconfiguration-rollback.md` — Failed cluster membership change
- `edge-catchup-storm.md` — Edge fleet reconnection storm
- `poison-config.md` — Rollback a bad config push
- `cert-rotation.md` — TLS certificate rotation
- `write-freeze.md` — Emergency write freeze
- `version-gap.md` — Edge version divergence

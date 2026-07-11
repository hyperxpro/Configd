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
  -XX:+UseZGC \
  -Xms512m -Xmx2g \
  -XX:+ExitOnOutOfMemoryError \
  -jar configd-server/target/configd-server-0.1.0-SNAPSHOT.jar \
  --node-id 0 \
  --data-dir /var/lib/configd/data \
  --peer-addresses 0=configd-0:9090,1=configd-1:9090,2=configd-2:9090 \
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
| `--node-id` | Yes | - | Unique integer node ID for this node |
| `--data-dir` | Yes | - | Path for WAL, snapshots, and state |
| `--peer-addresses` | Yes | - | Map of node ID to host:port for all cluster members, e.g. `0=host0:9090,1=host1:9090,2=host2:9090` |
| `--bind-address` | No | `0.0.0.0` | Raft transport bind address |
| `--bind-port` | No | `9090` | Raft transport (inter-node) port |
| `--api-port` | No | `8080` | Control-plane / admin API and health port |
| `--edge-port` | No | - | Edge fan-out port; enables the edge plane when set |
| `--tls-cert` | No | - | Path to the PKCS12 keystore |
| `--tls-key` | No | - | Path to the PKCS12 key file |
| `--tls-trust-store` | No | - | Path to the PKCS12 trust store |
| `--auth-token` | No | - | Bearer token required for write and admin API calls |
| `--signing-key-file` | No | `<data-dir>/signing-key.bin` | Cluster signing key; must live outside `--data-dir` or the server fails closed (see [adr-0044](../adr/adr-0044-signing-key-management.md)) |
| `--strong-read-prefixes` | No | `secure/` | Key prefixes served as fail-closed linearizable reads |
| `--bind-address` | No | **`127.0.0.1`** | Default is **loopback**; a non-loopback bind while auth is OFF is refused (see `--allow-insecure-public-bind`) |
| `--allow-insecure-public-bind` | No | (unset) | Explicit acknowledgement to bind a non-loopback interface with auth OFF (loudly warned); footgun-fix, not "auth required" |

**Key system properties (`-D...`)** - not CLI flags:

| Property | Default | Description |
|----------|---------|-------------|
| `configd.raft.shardCount` | `1` | Number of Raft shard groups (N); N=1 is byte-identical to non-sharded |
| `configd.raft.autobalance.enabled` | `true` | Decentralized leadership auto-balance loop (N>1); 30s cadence with 25% jitter and a 60s cooldown by default, also tunable under `configd.raft.autobalance.*` (`dryRun`, `intervalMs`, `jitterPct`, `cooldownMs`) |
| `configd.write.maxInflightProposals` | ON (conservative) | Write-admission bound (429 + Retry-After when exceeded); `0` disables |
| `configd.replay.enabled` | `false` | Opt-in replay guard (`X-Configd-Timestamp` + `X-Configd-Nonce`) |
| `configd.raft.encryption.enabled` | `false` | Opt-in at-rest AES-256-GCM (`algId=2`); one-way door |
| `configd.raft.encryption.kms.provider` | `local` | KMS provider (`local` HKDF-from-signing-key, or `vault-transit`) |
| `configd.raft.encryption.requireEncrypted` | `false` | Refuse legacy `algId=0/1` records once the plaintext prefix is compacted away |
| `configd.raft.maxReassembledSnapshotBytes` | `512 MiB` | Fail-closed cap on chunked-snapshot reassembly (heap-bound) |

Authentication modes (No-Auth / HTTP Basic / OIDC-Bearer / mTLS) and node-join identity policy
(`configd.raft.peerIdentity.*`) are documented in the [operator runsheet](operator-runsheet.md).

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/health/live` | No | Liveness probe (always 200 if process is running) |
| `GET` | `/health/ready` | No | Readiness probe (200 if Raft has elected a leader) |
| `GET` | `/metrics` | No | Prometheus metrics in text exposition format |
| `PUT` | `/v1/config/{key}` | Yes | Write config value (body = raw bytes) |
| `GET` | `/v1/config/{key}` | No | Read config value |
| `DELETE` | `/v1/config/{key}` | Yes | Delete config key |
| `POST` | `/v1/admin/groups/{groupId}/transfer-leadership?target=<nodeId>` | ADMIN | Initiate an async leadership transfer for a shard group (refused when auth is OFF) |

## JVM Tuning

### Garbage Collection

Use ZGC for sub-millisecond GC pauses on the edge read path. On Java 25, ZGC is generational by default, so pass only:

```
-XX:+UseZGC
```

Do not pass `-XX:+ZGenerational` - that flag was removed in JDK 24.

### Heap Sizing

| Workload | Keys | Recommended Heap |
|----------|------|-----------------|
| Small | < 100K | 512m - 1g |
| Medium | 100K - 1M | 1g - 4g |
| Large | 1M - 10M | 4g - 16g |

The HAMT uses ~80 bytes per key-value entry (node overhead + references). Budget 100 bytes/key for sizing.

### OOM Handling

Always use `-XX:+ExitOnOutOfMemoryError` in production. Do NOT use `-XX:+HeapDumpOnOutOfMemoryError` - heap dumps expose config values in plaintext (see `security-heap-dump-policy.md`).

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
3. The server re-reads the certificate files from disk automatically, about every 60 seconds - no restart, signal, or endpoint is needed
4. Connections using the old certificate will drain naturally

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

Throughput is a capacity figure, not an SLO. Measured: a single Raft group commits about 800 writes/s, a single box plateaus near 1100 writes/s, and a 3-machine cluster reached about 1600 writes/s (near-linear 2.45x); no literal sustained 10,000/s has been run - the 10,000/s figure is a sharded, multi-machine aggregate target, not a single-cluster baseline (see the [measurement archive](../archive/measurement/)).

### Burn-Rate Alerts

The `BurnRateAlertEvaluator` computes multi-window burn rates:
- **Critical** (burn rate >= 14.4x): Pages on-call immediately
- **Warning** (burn rate >= 1.0x): Ticket for investigation

### Key Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `configd_write_commit_total` | Counter | Total committed writes |
| `raft_shard_current_term_<gid>` | Gauge | Current Raft term, per shard |
| `configd_edge_read_seconds` | Histogram | Edge read latency (seconds) |
| `configd_propagation_delay_seconds` | Histogram | Leader-commit -> edge-applied delay (seconds) |
| `configd_edge_staleness_violation_total` | Counter | Edge staleness-bound (INV-S1) violations |

## Troubleshooting

See the incident runbooks in [`ops/runbooks/`](../../ops/runbooks/):
- `raft-saturation.md` - stuck or wedged Raft leader, election livelock, apply backlog
- `control-plane-down.md` - control-plane API unavailable
- `disaster-recovery.md` - quorum loss and other cluster-wide recovery
- `restore-from-snapshot.md` - rebuilding a node from a snapshot
- `disk-full-fsync.md` - disk-full or fsync-stall conditions
- `edge-catchup-storm.md` - edge fleet reconnection storm
- `acl-policy-load.md` - a rejected or frozen `_acl/` policy load
- `overload-shedding.md` - sustained write-admission shedding
- `release.md` - rollback procedure

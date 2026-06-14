# Session 6 — Deployment, Bootstrap, Upgrade/Rollback, Wire-Compat (Workstream C)

## 1. The bootstrap problem — how Configd configures itself before Configd exists

Like etcd / Consul, Configd uses a **static seed**: the initial cluster membership is supplied as
configuration, not discovered. There is deliberately **no "join" vs "first-formation" mode** — every
node receives the same seed and begins consensus immediately.

- Seed config (verified, `ServerConfig.java`): `--node-id`, `--peers <csv>` (the other members,
  excluding self; **empty = single-node**), `--peer-addresses id=host:port,…`, `--bind-address`,
  `--bind-port`, `--api-port`. No dynamic discovery (no Consul/etcd lookup); peers are static CLI /
  k8s-ConfigMap values. Growing the cluster today = restart the members with an updated `--peers`
  (joint-consensus `ClusterConfig` exists in consensus-core but is not yet exposed as an add/remove
  CLI — tracked forward).
- **Self-hosting cutover:** Configd's *own* bootstrap config (cluster membership) is the static seed;
  once the cluster forms, *application* config is served by Configd itself. The membership is not yet
  self-hosted (a deliberate, common bootstrap-ordering choice — the seed cannot live in the store it
  bootstraps).
- **Cold-start proof (gate-6 (e)):** `BootstrapColdStartTest` drives a TRUE zero-state cold start
  (empty data dir, empty peer set) and proves the cluster forms and self-elects a leader from the
  live tick loop, with the live `/metrics` already serving the SLO histogram buckets. The 3-node
  cold start is `gates/smoke-multinode.sh` (static seed → quorum → serving).

## 2. Wire protocol & versioning (the interop invariant)

| Wire | Constant | Layout | Decoder |
|---|---|---|---|
| Raft peer-to-peer | `FrameCodec.WIRE_VERSION = 0x01` | `[len][ver@4][type][group][term][payload][CRC32C]`, len-prefixed, ≤16 MiB | strict tripwire — any other version → `UnsupportedWireVersionException` |
| Edge streaming | `EdgeFrameCodec.EDGE_WIRE_VERSION = 0x01` | `[len][ver@4][type][payload][CRC32C]`, len-prefixed, ≤2 MiB, **CRC checked before ver/type** | strict tripwire → `BAD_WIRE_VERSION` (counted: `edge_fanout_sessions_closed_bad_wire_version_total`) |

On-disk: the Raft WAL (`raft-log`) and snapshots (`raft-log.snapshot{,-meta}`) are **not yet
format-versioned** (a known gap; the rollback story below depends on it).

## 3. Rolling upgrade & rollback — what is PROVEN vs deferred

The dangerous state is a mixed-version cluster. The safety argument has two parts:

**(a) Wire interop within a wire version — PROVEN (gate-6 (d)).** `WireCompatGoldenBytesTest`
(16 cases) and `EdgeFrameCodecGoldenFixtureTest` (25 cases) pin every message type's serialized
bytes to a golden fixture coupled to the wire-version constant. Therefore **any two builds at wire
version `0x01` produce and consume byte-identical frames** — so "old-leader / new-follower" AND
"new-leader / old-follower" interop within `0x01` holds *by construction*, for both the Raft and the
edge wire. A wire change cannot land silently: it breaks the golden test, which forces a
`WIRE_VERSION` bump, which in turn requires the deferred peer Hello handshake (ADR-0030+) and the
two-release deprecation cycle (R-005) — at which point the currently-`@Disabled`
`WalWireCompatStubTest` / `SnapshotWireCompatStubTest` and a migration script
(`ops/scripts/migrate-<from>-to-<to>.sh`) MUST be enabled. This discipline is gate-enforced.

**(b) No write loss / no availability gap during a rolling restart — PROVEN at the node level.** A
3-node cluster tolerates one member down (quorum = 2), so restarting members one at a time keeps the
control plane available; and `ConfigdServerTest.serverSurvivesRestart` proves a node recovers its own
committed state from the durable WAL on restart (no write loss). Within an unchanged on-disk format,
**rollback N+1→N is the same operation in reverse** — restart the prior binary on the same data dir;
the durable WAL is format-stable, so the old binary reads it. (If a future release changes the WAL/
snapshot format, rollback safety is gated by the format-version bump + the now-enabled stub tests +
a downgrade-tested migration — not assumed.)

**Deferred to S7.5 (honest gap).** A literal *cross-binary* rolling-upgrade matrix (run version N and
version N+1 in one live cluster, measure zero write-loss + zero availability-gap end to end) requires
an actual N+1 release artifact — the repo is at a single `0.1.0-SNAPSHOT`, so there is no second
version to mix yet. gate-6 proves the **interop invariant** (byte-stability) + cold-start + durable
restart; the live N↔N+1 fleet measurement is the first concrete item when a v0.2 tag exists (S7.5 /
release decision). It is **not** claimed as load-validated here.

## 4. Deployment artifacts (committed)

| Artifact | Path | Provisions |
|---|---|---|
| Runtime image | `docker/Dockerfile.runtime` | multi-stage Temurin-25 JRE; ports 8080 (API) / 9090 (Raft); `/health/live` healthcheck |
| Build image | `docker/Dockerfile.build` | hermetic `mvn clean verify` |
| Compose topology | `deploy/compose/compose.yaml` | 3 CP + 3 edge + bootstrap edge, static IPs, mTLS, secrets |
| Kubernetes | `deploy/kubernetes/configd-statefulset.yaml`, `configd-bootstrap.yaml` | CP StatefulSet (PVC, headless DNS) + bootstrap ConfigMap |
| Release CI | `.github/workflows/release.yml` | on `vX.Y.Z` tag: build → GHCR image → cosign keyless sign → CycloneDX SBOM → SLSA L3 provenance |

**Not present (documented follow-ups, not blockers):** Helm chart and systemd units — the prod target
is the committed k8s StatefulSet / Compose; Helm packaging is a release-engineering follow-up.

## 5. Reproduce

```
./mvnw -o -pl configd-server          test -Dtest=BootstrapColdStartTest          # cold start
./mvnw -o -pl configd-transport       test -Dtest=WireCompatGoldenBytesTest       # raft wire-compat
./mvnw -o -pl configd-distribution-service test -Dtest=EdgeFrameCodecGoldenFixtureTest  # edge wire-compat
./mvnw -o -pl configd-config-store    test -Dtest=BackupRestoreRoundTripTest       # backup/restore equality
bash gates/smoke-multinode.sh                                                      # 3-node static-seed cold start
```

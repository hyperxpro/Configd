# Session 6 → Session 7 Handoff: the operability surface security must review

> Session 6 made Configd **operable**: dashboards and alerts wired to series that are proven emitted
> and recorded, runbooks validated by execution against injected faults, bootstrap/upgrade/rollback
> with wire-compat, and `gate-6` green. Session 7 (security) inherits a much larger *attack surface*
> than before — every new admin/observability/deployment seam is a thing to authenticate, authorize,
> rate-limit, and harden. This handoff enumerates that surface, the PROPOSED thresholds awaiting
> S7.5 load confirmation, and the deployment artifacts to harden. Read with `decision-log.md`,
> `observability.md`, and `deployment.md`.

## 1. The operability surface security must review

### 1.1 The `/metrics` endpoint (NEW exposure)
- Both processes serve Prometheus exposition at `/metrics` (`HttpApiServer`, `EdgeHttpServer`). S6
  added the JVM/process gauges (`jvm_*`, `process_open_fds`) and the SLO histograms. **Question for
  S7:** is `/metrics` authenticated / network-segmented? Today it shares the API port. Metrics leak
  operational detail (subscribed-prefix counts, term churn, FD/thread counts, write rates) — an
  unauthenticated scrape is reconnaissance. Recommend: scrape-only auth or a separate bound interface
  / NetworkPolicy. **No secrets are emitted** (values are counts/latencies), but cardinality and
  rates are sensitive.
- The `cluster`/`instance` labels are Prometheus-added (external) — confirm the scrape config does
  not inject attacker-controlled labels.

### 1.2 The write/admin API and overload surface (RR-110)
- The bounded-proposal-queue 429 + `Retry-After: 1` (D-1) is the only write backpressure. An
  unauthenticated or under-rate-limited writer can drive the leader to the 1024 bound and shed
  legitimate writes (a DoS lever). S7 should confirm write-path authn/authz (`AclService`,
  `authInterceptor`) gate the proposer BEFORE the queue, and that the `RateLimiter` (10k/s default)
  is per-principal, not global-only.
- The `configd_write_rejected_overloaded_total` series makes shedding observable — good for
  detecting an overload attack, but the alert threshold is PROPOSED (see §2).

### 1.3 Dashboards & alerts as code
- `ops/dashboards/*.json` + `ops/alerts/configd-slo-alerts.yaml` are committed. Runbook `runbook_url`
  annotations are *paths* consumed by the operator's paging integration — S7 should confirm the
  paging pipeline cannot be tricked into rendering a malicious URL, and that alert annotations
  (operator-facing) are not attacker-influenced (they are static strings today).

### 1.4 Runbook commands that touch secrets / mutate state
- `ops/runbooks/*` resolution steps include operator commands. S7 must audit any that handle secrets
  or do destructive actions:
  - `restore-from-snapshot.md` / `ops/scripts/restore-snapshot.sh` — gated behind `--dry-run=false`
    AND `--i-have-a-backup`; mutates the data dir. Confirm the snapshot file has integrity protection
    (it currently has **no magic header / no signature** — PA-2021; an attacker-supplied snapshot
    could be restored). **This is a real hardening item.**
  - `cert-rotation.md` — handles TLS/mTLS keys (`/secrets`).
  - The Ed25519 signing key (`SigningKeyStore.loadOrCreate`) — confirm key-at-rest protection.
- The edge `strong-read` fail-closed keys (`secure/` prefix) are served only via the CP linearizable
  path — confirm that path's authz.

### 1.5 New test/observability seams
- `ConfigdServer.scrapeMetrics()` is package-private (test support) — not exposed externally.
- `JvmMetrics.openFds` casts to `com.sun.management.UnixOperatingSystemMXBean` — no security impact.

## 2. PROPOSED alert thresholds awaiting S7.5 confirmation

Every alert threshold in `ops/alerts/configd-slo-alerts.yaml` is **PROPOSED**, derived from S5
reference-hardware baselines + a stated margin, NOT load-validated. S7.5 (the M-1…M-10 infrastructure
campaign on real hardware) must confirm/tighten them under production-representative load:

| Alert | PROPOSED threshold | Derivation | Confirm under |
|---|---|---|---|
| WriteCommitFastBurn/SlowBurn | 99% < 150 ms burn-rate | S5 local p99 16 ms | M-1 (X-region commit) |
| EdgeReadFastBurn / P999 | 99% < 1 ms / p999 > 5 ms | S5 p99 1.6 µs, p999 32 µs | M-4/M-5 (NUMA, 10⁹ keys) |
| EdgeStalenessWarn/Degraded | 500 ms / 2 s | S5 local 255 ms; contract boundaries | M-2 (global propagation) |
| WriteOverloadShedding | rate > 1/s for 5m | sheds at queue 1024 | M-9/M-10 (sustained/burst, ladder) |
| RaftApplyBacklog | > 5000 for 5m | heuristic (≈0 steady) | M-9 |
| FD/Thread/Heap leak | 500 / 400 / 90% | S5 soak flats 69 / 93 / ~260 MB | M-4 representative soak |

## 3. Deployment artifacts security must harden

- **Container images** (`docker/Dockerfile.runtime`): confirm non-root user, read-only rootfs,
  minimal base, pinned digests; the release pipeline already does cosign keyless signing + SBOM +
  SLSA L3 — S7 verifies the verification side (admission policy requiring the attestation).
- **k8s** (`deploy/kubernetes/`): add NetworkPolicy isolating Raft (9090) and `/metrics` from the
  public API; confirm secret mounts are RO and not in env; PodSecurity restricted.
- **Compose** (`deploy/compose/compose.yaml`): mTLS is wired; confirm the secrets are not baked into
  images and the static IPs are not a trust boundary.
- **On-disk format** (`raft-log`, snapshots): **unversioned and unsigned** — a tampered WAL/snapshot
  on disk is a code-execution-adjacent risk on restore. The snapshot-header + integrity item (PA-2021
  / the disabled `*WireCompatStubTest`) should be prioritized; it is also the rollback-safety gate
  (deployment.md §3).

## 4. State for S7

- gate-6 is CI-wired (`needs: gate-5`), cumulative; green locally (run captured in
  `docs/session-6/captures/`). It locks the operability bar so S7+ cannot silently erode it.
- Open carry-forwards: RR-108 (consistency-contract anchor refresh), RR-105 (stale-owner re-triage),
  RR-112 (full box-local 24 h soak — re-run with smaller heaps or M-4), the M-1…M-10 campaign.
- The full multi-node game-day drill (`gates/game-day-drill.sh`) is the ops/nightly lane; the CI
  subset (`GameDayDrillTest`) gates the alert→runbook→recovery loop for one scenario.

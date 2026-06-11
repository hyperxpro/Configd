# C2 As-Built Design Note — Edge Node Process

> **Status: AS-BUILT** (commits `7034f67` part a, `37cf3c6` part b) — for dual sign-off
> (review-architect + contract-qa, charter §2). Describes what IS, with test names as
> citations (charter §1 rule 3). The pre-implementation draft is
> `c2-edge-node-design-draft.md`; the screen conditions are
> `../reviews/c2-c5-design-screen.md` §C2. Deviations from the draft are §7; known gaps
> are §8 — nothing is silently under-delivered.

## 1. What exists at runtime now

`configd-edge-node` is a separately runnable process (shaded jar, `Main-Class:
io.configd.edge.node.EdgeNodeMain`) that connects to a `FanOutServer` edge endpoint over
mTLS, SUBSCRIBEs with a resume cursor, verifies and applies the signed delta chain,
serves cursor/staleness-governed reads over HTTP, and exports the edge metric series.
Together with C1's server half, the data plane RR-001 indicts now exists end-to-end at
runtime: `EdgeNodeIntegrationTest` runs a real `ConfigdServer` + a real edge process on
loopback and proves write → commit-notification boundary → fan-out wire → verified apply
→ cursor-bound HTTP read (`CT-35` path), signed with a real Ed25519 key exported by the
real `VerifyKeyExporter` path (CT-23).

## 2. Layering (draft §2, built as drawn)

```
configd-edge-node:
  EdgeNodeMain      — lifecycle orchestrator + main(); injectable-TlsManager seam
                      start(cfg, tls) (the FanOutServer test-seam precedent); names the
                      DISCONNECTED re-bootstrap stub seam rebootstrapHook() (C3 plugs in;
                      edge_rebootstrap_triggered_total counts firings)
  EdgeNodeConfig    — CLI per draft §4 verbatim; partial TLS triple rejected fail-closed
                      (EdgeNodeConfigTest, 8 tests)
  EdgeStreamClient  — socket shell (§3 below)
  EdgeHttpServer    — read surface (§4 below)
  EdgeNodeMetrics   — every §4 series eagerly registered (RR-013 discipline;
                      EdgeNodeMetricsTest, 10 tests)
configd-edge-cache (extended in part a):
  EdgeClientCore    — transport-agnostic client session engine; ALL protocol policy
                      (EdgeClientCoreTest, 37 w/ nested classes incl. verifier wiring)
  StalenessTracker  — REWORKED per ADR-0039 (frontier; idle proxy DELETED)
  PrefixStorageFilter / StrongReadKeyClass — ADR-0038 storage filter + shared strong-read
                      predicate (EdgePrefixStorageFilterTest, StrongReadKeyClassTest)
```

The shell owns sockets; the core owns policy. The simulator drives the SAME
`EdgeClientCore` the process runs (`EdgeActor` refactor, part a), so the 507-seed gate
exercises production C2 logic (`EdgeSeedCompatTest` proves the digest stayed
byte-identical — screen condition C2-1 met; `AdversarialGateSeedSweepTest` green).

## 3. Transport (ADR-0037; RR-002/F-0051 discipline)

JDK sockets via the SAME `TlsManager` classes as the control plane: bounded connect
(1s) + bounded handshake (2s) + HTTPS endpoint identification. Per-connection
session/reader/writer virtual threads; bounded queues (inbound 256 — full blocks the
reader, i.e. TCP backpressure toward the server's demotion machinery; outbound 64 —
CURSOR_ACK-only, refused offers retried by the core's idempotent next-tick ack).
SUBSCRIBE is written synchronously before the reader/writer exist, so wire order is
deterministic.

Failover (CT-11/CT-12): on ANY connection end — EOF, IO error, decode corruption, the
core's heartbeat-silence directive, or the shell's transport-silence guard (covers the
pre-first-heartbeat window the core cannot) — round-robin to the NEXT endpoint, carrying
`resumeCursor = core.cursor()` and `failoverResumeCursor` once a previous endpoint had
been reached (the §3 reserved-field contract). Backoff: base doubling to 10s cap, ±50%
jitter, slept in ≤1s slices **with a staleness pump per slice** — DISCONNECTED detection
happens precisely while disconnected; a long backoff must not delay it (also pumped
between connection cycles in the session loop). Cited: `EdgeFailoverTest`
(kill-mid-stream, deterministic refusal window, cursor-monotonic resume, reconnect
metric moves), `EdgeTransportMtlsTest` (trusted accepted; rogue CLIENT cert never
subscribes; rogue SERVER cert rejected by the client trust path — coverage beyond the C1
server-half test).

## 4. Read surface (contract §2/§3 as amended; ADR-0035 + ADR-0039)

`GET /v1/config/{key}` (`EdgeHttpServerTest`, 11 tests; process-level in
`EdgeNodeIntegrationTest`):
- every response carries `X-Configd-Cursor` (the version to carry forward); hits carry
  `X-Configd-Version`;
- **cursor-behind → 404 + `X-Configd-Refused: cursor-behind`** — never blocks, never
  serves stale, uniform across steady state/catch-up/failover (the §3 contract pass per
  the C2-4 ruling; `EdgeFailoverTest` pins it across a real failover). The
  miss-vs-refusal classification snapshots the local version BEFORE the read (store
  version is monotonic ⇒ a miss with `localVersion ≥ cursor` is a true not-found;
  refusal is the safe side of the race). Refused reads still route the monitor-wired
  store so `invariant.violation.monotonic_read` fires (INV-M1 stays live — C2-1);
- `X-Configd-Stale: true` on ALL reads while STALE+ (CT-03), with
  `configd_edge_staleness_violation_total` counting STALE+ entries (CT-04);
- **strong-read keys → 503 + `X-Fail-Closed: strong-read` BEFORE the store is consulted**
  (CT-37 store-and-never-serve; `EdgeStrongReadFailClosedTest`: the `secure/` value IS
  stored — byte-checked in the core — and never leaves the process; RR-098 disk sweep
  asserts no value bytes under `--data-dir`);
- `/health/live` always 200; `/health/ready` 503 at DEGRADED+ and at boot (a never-synced
  edge is not ready — CT-05); `/metrics` via the existing `PrometheusExporter`.

## 5. Staleness (ADR-0039, screen condition C2-3 — verified landed)

`staleness = wall_now − frontier`, `frontier = max(commitTs(last applied),
serverNow(cursor-matched heartbeat))`. The idle-time proxy is **deleted** — no residual
code path; `StalenessTrackerTest` (true-stall transitions),
`ConsistencyPropertyTests$StalenessUpperBoundTest#idleButHeartbeatingEdgeStaysCurrentAndBehindHeartbeatDoesNot`
(the ADR-0039 regression test), `EdgeStalenessFrontierSimTest` (sim level: idle ≥35s
pinned CURRENT; partitioned edge walks the full ladder). CT-08 implausibility tripwire:
future-frontier beyond the 50ms skew allowance / frontier regression → counted
(`edge_staleness_implausible_total`) + clamped, never trusted
(`StalenessSkewTripwireTest`, 7 tests).

**Part-(a) defect found and fixed during part (b)** (the C2 build's own catch): ADR-0028
snapshot bodies carry no commit timestamp (`EdgeSnapshotCodec.deserialize` stamps 0), so
every legitimate snapshot cutover advanced the frontier to 0 and fired the CT-08
tripwire — false positives that would have masked real skew.
`EdgeConfigClient.loadSnapshot` now records the version WITHOUT a frontier advance when
`timestamp ≤ 0` (`StalenessTracker.recordVersion`); the frontier heals from the first
post-snapshot NOTIFY commitTs or cursor-matched heartbeat. Sim digests unaffected
(`EdgeSeedCompatTest` green). Cited: `EdgeClientCoreTest` snapshot-cutover-no-tripwire
cases.

## 6. Hot-path law (CT-34) and its honest boundary

The §3 law binds the in-process read path: `LocalConfigStore.get` /
`EdgeClientCore.get` — lock-free volatile snapshot pointer, JMH gc-profiled
(`LocalConfigStoreReadBenchmark`, run on this box, size=10k): `getMiss` 6 ns / **0
B/op**, `getInto` 117 ns / **0 B/op**, `get` hit 89 ns / 32 B = exactly the one
documented `ReadResult` (identical to `VersionedConfigStore`'s established figure);
cursor-gated reads identical. The HTTP shell above it allocates per request and is
honestly NOT the law's scope (benchmark javadoc states this); its request path logs
nothing per-request (counters only).

## 7. Deviations from the draft (each justified)

1. `edge_read_refusals_total{reason}` → per-reason series
   (`edge_read_refusals_cursor_behind_total`, `edge_read_refusals_strong_read_total`):
   `MetricsRegistry` is label-less; the `RegistryFanOutSessionMetrics` precedent. The
   contract text names the real series.
2. Two series beyond draft §4: `edge_verify_rejections_total` (a rejection must be
   visible, and is NOT a gap — `EdgeClientCore.verifyRejections()` split from
   `gapsDetected`), `edge_rebootstrap_triggered_total` (the named CT-06 trigger).
3. `VerifyKeyExporter` lives in configd-config-store (needs package-private
   `SigningKeyStore.load` to refuse-on-missing rather than silently minting a fresh
   pair); ships inside the server's shaded jar regardless.
4. Backoff cap is a documented constant (`MAX_BACKOFF_MS = 10s`); the named config
   (`edge.reconnect.backoffMs`) is the base — "bounded, jittered" per draft §4.
5. mTLS tests inject a `TlsManager` via `EdgeNodeMain.start(cfg, tls)` because
   `TlsConfig.mtls` hard-codes an empty store password keytool cannot produce — the
   exact precedent `ConfigdServerTest`/`FanOutServerMtlsTest` document.

## 8. Known residuals and gaps (named; screen condition C2-2 discharge)

- **`secure/`-at-edges exfiltration residual (C2-2, REQUIRED statement):** ADR-0038's
  store-everything topology means every edge holds `secure/` values in memory. This is
  the deliberate trade ratified by ADR-0038 (suppression detectability + keeping the V1
  snapshot–delta equivalence invariant a plain full-store byte-compare) — but it
  enlarges the exfiltration surface to every edge host. Mitigations inherited today:
  values are in-memory only (`EdgeStrongReadFailClosedTest`'s RR-098 disk sweep proves
  no value bytes land under `--data-dir`; the epoch sidecar holds metadata only), the
  serving path fail-closes (503, never served from edge state), and the mTLS-pinned
  cert-DN identity bounds who may subscribe. Registered as **RR-098** (P2, owner S5):
  `secure/` at-rest/in-memory protection if edge hosts are lower-trust than
  control-plane hosts (e.g. at-rest encryption, key-class-scoped memory hygiene). This
  residual must also appear in the handoff to Session 4 (chaos surface) and Session 5
  (RR-098 owner).
- **CT-40 edge-side absent-client-cert case**: not exercised edge-side (the edge always
  loads a keystore); covered server-side by `FanOutServerMtlsTest`. The edge CLI TLS
  path (`TlsConfig.mtls`) is not exercised with a real handshake — identical status to
  ConfigdServer's own CLI TLS path (pre-existing, not new debt).
- **Apply-throwing delta loops reconnect** (visible: `edge_reconnects_total` climbing,
  `edge_cursor_lag` growing): the bounded-retry → forced-snapshot → terminal fail-loud
  poison-pill policy is C3's (ADR-0040), wired at the commented catch site in
  `EdgeStreamClient.sessionLoop`. DISCONNECTED re-bootstrap is likewise the named C3
  stub seam.
- **INV-M1 SEVERE log noise** (observation for a register row): every cursor-behind
  refusal emits a SEVERE `InvariantMonitor` log via the contract-mandated INV-M1
  routing; routine post-failover catch-up therefore produces SEVERE spam. Pre-existing
  monitor behavior, not changed by C2; flagged for register + Session 6 alert design.

## 9. Contract rows this component claims (for the contract-qa audit)

CT-01, CT-02 (mechanism), CT-03, CT-04, CT-05, CT-07, CT-08, CT-11, CT-12, CT-13 (C2
half), CT-16 (level per map), CT-23 (wire-level F-0052), CT-25 (C2 storage-filter half),
CT-34, CT-35, CT-37, CT-40 (client half) — each with the named tests above. CT-06 is the
named stub seam + metric only (C3 orchestrates; the row stays with C3). The
contract-qa audit, not this note, flips the map.

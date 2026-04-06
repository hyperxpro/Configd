# Production Audit — GA Hardening Pass

**Phase:** 1 of 12
**Date:** 2026-04-17
**Branch:** `main`, HEAD `22d2bf3 Save`
**Authorization:** autonomous; calendar-bounded gates remain yellow without fabrication.
**Scope:** all 11 maven modules + spec/ + ops/ + docs/ + .github/ + Dockerfiles + K8s manifests.

This file is the consolidated index of the GA-readiness audit. Each cluster file
listed below is the canonical detail for its scope; this file reproduces the
**S0** findings inline and lists every **S1** finding by ID + one-line summary
so a downstream gap-closure / scheduling pass can be produced from a single
source.

---

## 1. Coverage

| Cluster | Scope                                                        | LoC read       | Files | Findings | Cluster file |
|---------|--------------------------------------------------------------|----------------|-------|----------|--------------|
| A       | hot-path: edge-cache + config-store + common                 | 5,014 main     | 34    | 35       | [`prod-audit-cluster-A.md`](prod-audit-cluster-A.md) |
| B       | consensus: consensus-core + replication-engine               | 4,387 main + 6,002 test = 9,917 | 26 main + 13 test | 40       | [`prod-audit-cluster-B.md`](prod-audit-cluster-B.md) |
| C       | distribution: distribution-service                           | 2,067 main     | 10    | 40       | [`prod-audit-cluster-C.md`](prod-audit-cluster-C.md) |
| D       | server / control-plane-api / transport                       | 3,782 main     | 21    | 24       | [`prod-audit-cluster-D.md`](prod-audit-cluster-D.md) |
| E       | observability + TLA+ spec + runbooks + reference docs        | 3,096          | n/a   | 30       | [`prod-audit-cluster-E.md`](prod-audit-cluster-E.md) |
| F       | build / CI / docs / ops / supply-chain / ADRs                | 27 docs + ADRs | n/a   | 45 (+5 R-carry) | [`prod-audit-cluster-F.md`](prod-audit-cluster-F.md) |
| **Σ**   |                                                              | ~24 KLoC code  | 91+ files | **214**  |              |

Cluster A had to be re-launched once after the first attempt stalled before
writing its report (output watchdog; subsequent run uses incremental writes).
All clusters reached completion. Coverage tally per cluster's own LoC read /
total verification at the head of each cluster file.

## 2. Severity rollup

| Severity | A | B | C | D | E | F | **Total** |
|----------|---|---|---|---|---|---|-----------|
| S0       | 4 | 0 | 6 | 1 | 4 | 0 | **15** |
| S1       | 9 | 10| 20| 13| 4 | 8 | **64** |
| S2       | 11| 15| 8 | 8 | 10| 19| **71** |
| S3       | 11| 15| 6 | 2 | 12| 13| **59** |
| Total    | 35| 40| 40| 24| 30| 45| **209** PA-ids + 5 R-carry-forwards |

Definitions:
- **S0** — data loss, ordering violation, security bypass, or undetected SLO
  breach. GA-blocking; must be code-fixed before any further phase.
- **S1** — GA-blocking: known-failure-mode without observability, perf SLO
  miss, or correctness regression that requires a non-trivial fix. Must be
  fixed or have an explicit ADR-recorded acceptance with mitigation.
- **S2** — hardening / UX / perf-tuning. Should be fixed before GA but tractable
  during ramp.
- **S3** — hygiene. Tracked but not GA-blocking.

## 3. S0 findings — full evidence inline

The 15 S0 findings below are the GA-blocking correctness / security gaps. Each
one has a code citation; each one prevents a green box on its corresponding GA
gate (G1 correctness, G2 durability, G6 security, or G14 SLO compliance).

### PA-1001 — VersionedConfigStore stamps values with raw wall-clock, not HLC
- **Cluster:** A (hot-path, config-store)
- **Owner:** config-store
- **Location:** `configd-config-store/.../VersionedConfigStore.java` (write path)
- **Evidence:** `put/delete/applyBatch` use `clock.currentTimeMillis()` directly
  for the per-value timestamp instead of consulting the HLC. Causal ordering
  across writers breaks on wall-clock skew between leader instances; this is
  the failure mode the F-0013 fix was meant to close on the read path, here
  on the write path.
- **Impact:** Direct violation of INV-V1 (per-key version monotonicity) under
  any leader change with skew. Two writers separated by less than the skew
  window can produce equal timestamps with non-equal orderings.
- **Fix:** Route every write-path stamp through the per-node HLC. Add a
  property test that asserts strict monotonicity across simulated skewed
  clocks.

### PA-1002 — ConfigStateMachine.restoreSnapshot stamps every restored entry with `now()`
- **Cluster:** A
- **Owner:** config-store
- **Location:** `configd-config-store/.../ConfigStateMachine.java` (restoreSnapshot)
- **Evidence:** Restore loop discards the original HLC timestamps embedded in
  the snapshot and stamps every restored key with the restore-time wall clock.
- **Impact:** After any Raft restore (cold-start, snapshot install, follower
  rebuild) the per-key version order is collapsed to a single tick — a
  subsequent reader cannot distinguish "value written 3 days ago" from "value
  written 30 ms ago" via the timestamp; INV-V2 (causal monotonicity) breaks.
  Compounds PA-1001 because both reads and replays go through the corrupted
  timestamps.
- **Fix:** Encode the per-entry HLC into the snapshot format and restore it
  verbatim. Add a determinism test: snapshot → restore → snapshot must produce
  byte-identical bytes.

### PA-1003 — CommandCodec write path silently truncates keys > 65535 bytes
- **Cluster:** A
- **Owner:** config-store
- **Location:** `configd-config-store/.../CommandCodec.java` (encodePut, encodeDelete)
- **Evidence:** Length fields cast as `(short)keyBytes.length` / `(short)valueBytes.length`
  with no bounds check. Same shape as F-0013 (closed for the read path) but
  on the write path. A 70 KiB key encodes a length of 70000 mod 65536 = 4464,
  so the WAL accepts a corrupted entry and the apply path either reads
  garbage or, worse, silently truncates the key on disk.
- **Impact:** Data loss on large keys. Catastrophic for tenants that use long
  composite keys. No exception, no metric.
- **Fix:** Use varint or `int` length with explicit reject above the
  configured `--max-key-bytes`. Add fuzz test on key/value sizes that
  straddle 32 KiB / 64 KiB / 1 MiB.

### PA-1004 — ConfigStateMachine.signCommand silently downgrades to unsigned on signer failure
- **Cluster:** A
- **Owner:** security / config-store
- **Location:** `configd-config-store/.../ConfigStateMachine.java` (signCommand)
- **Evidence:** Signer-failure branch catches the exception and returns the
  unsigned command. The downstream delta is then sent to edges with no
  signature; edges with a verifier reject it (gap-recovery loop) and edges
  without a verifier (the F-0052 insecure default) accept it.
- **Impact:** Punches a hole through the F-0052 signed-chain remediation:
  on signer failure, the leader silently emits unauthenticated mutations.
  Nothing escalates the fault. Also amplifies PA-3001 (no per-hop verify):
  the unsigned command propagates over the same overlay.
- **Fix:** Fail-close: refuse to apply the command, return an error to the
  client, bump `signer_failure_total{reason}`, fire an alert. Never emit an
  unsigned delta.

### PA-3001 — No per-hop signature verification in any distribution-layer forwarder (R-01 / F-0052 unmitigated)
- **Cluster:** C (distribution)
- **Owner:** security / distribution
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/`
  — `PlumtreeNode.java:117-168`, `HyParViewOverlay.java`, `FanOutBuffer.java:33-41`,
  `SubscriptionManager.java`, `WatchService.java:191-193, 262-289`,
  `CatchUpService.java:80-129`
- **Evidence:** `grep -R "Signer\|Signature\|verify\|Verifier"` inside
  `configd-distribution-service/` returns zero matches. Every forwarding path
  accepts opaque payloads.
- **Impact:** F-0052 residual R-01 confirmed unmitigated. An attacker who
  can inject an eager push or replay a captured delta propagates
  unauthenticated payloads to every downstream edge. Direct contradiction of
  the F-0052 acceptance criterion.
- **Fix:** Plumb a `DeltaVerifier` (signature + monotonic epoch + nonce per
  F-0052 §Suggested Fixes) into PlumtreeNode.receiveEagerPush,
  CatchUpService.recordDelta, and WatchService.onConfigChange. Reject with
  `fanout_verify_reject_total` counter and PRUNE the sender on verify
  failure. Make `FanOutBuffer.append` only accept a `VerifiedDelta` wrapper.

### PA-3002 — WatchService.dispatchEvent has no try/catch around listener — one throwing listener silently drops events for every watcher after it
- **Cluster:** C
- **Owner:** distribution
- **Location:** `configd-distribution-service/.../WatchService.java:262-289`
- **Evidence:** Dispatch loop calls `watch.advanceCursor(event.version())`
  on line 272 **before** invoking `watch.listener().onEvent(filtered)` on
  line 285, with no try/catch wrapping. One throwing listener aborts the
  loop; every subsequent watcher's cursor was already advanced.
- **Impact:** INV-W1 (ordering) and INV-W2 (no-drop) violation. Silent data
  loss for an unbounded number of watchers per offending event. No
  exception escapes, no metric fires.
- **Fix:** Wrap `listener.onEvent` in try/catch, advance cursor only on
  success, emit `watch_dispatch_failures_total{watch_id}`. Quarantine a
  listener after N consecutive failures. Test: register 3 watches with the
  middle one throwing; assert the third still receives the event.

### PA-3003 — WatchService advances cursor before delivery — crash mid-dispatch = silent drop
- **Cluster:** C
- **Owner:** distribution
- **Location:** `WatchService.java:271-285`
- **Evidence:** Cursor advance precedes listener invocation; no WAL on the
  cursor. A process crash, interrupt, or listener throw between lines 272
  and 285 leaves "delivered at version V" recorded with no actual delivery.
- **Impact:** INV-W2 violation on crash recovery. Compounds PA-3002.
- **Fix:** Invert the order — deliver first, then advance the cursor on
  successful return. Or move to a two-phase ack model where the cursor is
  only advanced on subscriber-side ack.

### PA-3004 — SlowConsumerPolicy.pendingCount never decremented per-ack — false-positive disconnects under bursty acks
- **Cluster:** C
- **Owner:** distribution
- **Location:** `SlowConsumerPolicy.java:113-132, 97-107`
- **Evidence:** `recordSend` does `tracker.pendingCount++`. `recordAck`
  resets to 0. There is no per-message decrement. Bursts that exceed
  `maxPendingEntries` between acks push state to SLOW even when the
  consumer is healthy; the same path then promotes to DISCONNECTED after
  `disconnectMs`.
- **Impact:** Healthy consumers are disconnected during bursts → reconnect
  storms → catch-up storms. Stability regression at fleet scale.
- **Fix:** Make `recordAck(node, ackedCount)` decrement by `ackedCount`
  (clamped to 0). Hysteresis: clear SLOW only when pending drops below
  `maxPendingEntries * 0.5`.

### PA-3011 — SubscriptionManager has no ACL — any node can subscribe to any prefix (incl. tenants)
- **Cluster:** C
- **Owner:** security / distribution
- **Location:** `SubscriptionManager.java:43-56`
- **Evidence:** `subscribe(NodeId, String prefix)` accepts unconditionally;
  no ADR-0017 namespace check, no capability gate, no audit log. A
  compromised edge subscribing to `""` receives the entire store.
- **Impact:** Confidentiality / multi-tenancy breach. Direct violation of
  ADR-0017's tenant-isolation intent.
- **Fix:** Plumb a `SubscriptionAuthorizer` (NodeId, prefix) → allow/deny.
  Re-check on dispatch so revocations take effect without re-subscription.

### PA-3021 — CatchUpService returns deltas without ACL filtering — replay leaks across tenants
- **Cluster:** C
- **Owner:** security / distribution
- **Location:** `CatchUpService.java:97-129`
- **Evidence:** `resolve(node, nodeVersion)` returns every delta in the
  history chain regardless of subscription. The reconstructing edge sees
  keys outside its subscription set.
- **Impact:** Catch-up path exposes full history of all keys; in a multi-
  tenant deployment this is a cross-tenant data leak. Larger blast radius
  than PA-3011 because it's "all retained history", not "live events".
- **Fix:** Filter each `ConfigDelta` by the requesting node's subscription
  set before returning. Pair with PA-3011.

### PA-4004 — `--auth-token` accepted without `--tls`; bearer transmitted in plaintext
- **Cluster:** D (server / API)
- **Owner:** server
- **Location:** `configd-server/.../ServerConfig.java` (parse), no cross-flag check
- **Evidence:** `ServerConfig.parse` does not require `--tls` when
  `--auth-token` is set. Bearer travels in cleartext over plain HTTP. There
  is no warning, no refusal, no probe.
- **Impact:** Operator misconfiguration → bearer token sniffable on the
  wire. Any operator following the README who omits `--tls` exposes a
  replayable admin token.
- **Fix:** Refuse to start when `--auth-token` is set without TLS (unless
  `--insecure-allow-plain-bearer` is explicitly passed for tests).

### PA-5001 — BurnRateAlertEvaluator constructed but `.evaluate()` never called in main
- **Cluster:** E (observability)
- **Owner:** observability
- **Location:** `configd-server/.../ConfigdServer.java:301` constructs the
  evaluator; only test code invokes `.evaluate()`.
- **Evidence:** Grep across `src/main` for `evaluator.evaluate(` returns
  zero hits.
- **Impact:** Every SLO breach is undetected at runtime. The "alert path"
  documented in runbooks is a no-op.
- **Fix:** Schedule the evaluator on the existing tickExecutor (1 Hz) and
  emit `slo_burn_rate{name, window}` gauges + alert events to the alert
  bus. Add an integration test that drives a 100% failure stream and
  asserts an alert fires within one window.

### PA-5002 — SloTracker.recordSuccess/Failure has zero call sites in main code
- **Cluster:** E
- **Owner:** observability
- **Location:** `configd-observability/.../SloTracker.java`; no main caller.
- **Evidence:** Grep across `src/main` for `recordSuccess(` / `recordFailure(`
  returns zero hits outside of test classes.
- **Impact:** Every compliance query returns the vacuous-truth 1.0
  regardless of actual error rate or tail. Combined with PA-5001 this
  means SLO compliance reporting is fabricated by construction.
- **Fix:** Wire success/failure recording at the write-path commit handler,
  read-path response handler, and propagation observer. Test: drive a
  10% failure stream and assert tracked compliance ≈ 0.9.

### PA-5003 — SloTracker is event-ratio, but the seven registered SLOs are latency p99/p999
- **Cluster:** E
- **Owner:** observability
- **Location:** `configd-observability/.../SloTracker.java` ratio model vs.
  registered SLO list.
- **Evidence:** `SloTracker` records (success, failure) booleans and reports
  a ratio. The seven SLOs registered in `ConfigdServer.start` are tail-
  latency targets. The bridge from `MetricsRegistry.histogram` to
  `SloTracker` does not exist.
- **Impact:** Even after PA-5001 + PA-5002 are fixed, every write counts as
  "successful" regardless of tail latency. The system cannot detect a
  latency-only SLO breach by construction. **GA gate G14 cannot be made
  green without a structural change here.**
- **Fix:** Add a latency-percentile SLO type with windowed quantile
  estimation (HdrHistogram percentile snapshot) and a "tail breach" event
  feeding the burn-rate evaluator. Or replace `SloTracker` with a generic
  SLO interface and route latency SLOs through a percentile-bucket sink.

### PA-5004 — No Prometheus rules / Grafana dashboards in repo — every runbook "Alert:" is prose-only
- **Cluster:** E
- **Owner:** ops / observability
- **Location:** `ops/` does not exist; no rule files, no dashboard JSON.
- **Evidence:** `ls /home/ubuntu/Programming/Configd/ops` returned empty
  before Phase 0; the directory was created as a skeleton in this pass but
  contains no content.
- **Impact:** Every runbook references an "Alert:" header with a metric
  name, but no rule binds that metric to an alert. PagerDuty integration
  is fictional. Pair with PA-5005 (runbooks reference metrics that do not
  exist in code) — the entire alerting story is undelivered.
- **Fix:** Generate Prometheus rule files from a single SLO-burn manifest
  (one input → rules + dashboard panels + runbook annotations). Phase 8
  deliverable.

## 4. S1 findings — one-line summaries (GA-blocking)

Detail in cluster files. Listed grouped by cluster.

### Cluster A — hot-path / config-store / common (9)
- **PA-1005** (concurrency) — `BuggifyRuntime` shares a non-thread-safe
  `L64X128MixRandom` across threads; static `random` swapped without sync;
  destroys deterministic-simulation determinism.
- **PA-1006** (observability) — `DeltaApplier` rejects (unsigned, invalid
  sig, replay) are logged but have no metric (rule 10 violation).
- **PA-1007** (memory / write-path) — `ConfigStateMachine.apply` allocates
  a full `ReadResult` per PUT for invariant check, then a `Put` mutation
  whose compact constructor clones the value bytes again — extra
  allocations on the hot write path.
- **PA-1008** (durability) — `SigningKeyStore.generateAndWrite` writes the
  keypair via `Files.write` without fsync or parent-directory fsync. Crash
  loses the freshly-minted key; next boot mints a new identity (silent
  identity churn).
- **PA-1009** (durability) — `FileStorage.renameLog` performs atomic rename
  but never fsyncs the parent directory; rename not durable across power
  loss.
- **PA-1010** (memory) — `FileStorage.readLog` casts `long fileSize` to
  `int`; silently truncates WAL files > 2 GiB.
- See `prod-audit-cluster-A.md` for PA-1011 through PA-1013.

### Cluster B — consensus / snapshot / replication (10)
- **PA-2001** — Snapshot chunk has no CRC/checksum; bit-flip silently
  installs bad state on follower.
- **PA-2002** — `RaftNode.sendInstallSnapshot` always sends single-chunk
  (`offset=0, done=true`), bypassing the chunking infrastructure that is
  unit-tested but unused.
- **PA-2003** — `applyCommitted` advances `lastApplied` even when
  `stateMachine.apply` throws → poison commands hot-spin every tick;
  `System.err.println` only.
- **PA-2004** — No retry / timeout on dropped InstallSnapshot;
  `inflightCount` is permanently consumed → follower wedged.
- **PA-2005** — `heartbeatTicksElapsed >= heartbeatIntervalMs` conflates
  ticks with milliseconds.
- **PA-2006** — Same tick-vs-ms confusion in `electionTimeoutMinMs` /
  `electionTimeoutMaxMs`.
- **PA-2007** — `SnapshotTransfer.acceptChunk` has no total-size bound →
  follower OOM DoS.
- **PA-2008** — `handleInstallSnapshot` ignores `offset` / `done` fields;
  enabling chunking would be an immediate correctness bug.
- **PA-2009** — `whenReadReady` callbacks leak on partial leader isolation
  (no per-callback deadline).
- **PA-2025** — `assemble` materialises the full snapshot 2x
  (ByteArrayOutputStream + toByteArray).

### Cluster C — distribution (20)
PA-3005 through PA-3010, PA-3012, PA-3015, PA-3017, PA-3018, PA-3019, PA-3022, PA-3023, PA-3025, PA-3026, PA-3028, PA-3030, PA-3033, PA-3037, PA-3039.
Headlines: PA-3005 (no reconnect rate-limit on QUARANTINED → readmit), PA-3007 (head-of-line blocking on I/O thread), PA-3008 (WatchCoalescer pending unbounded), PA-3010 (O(P×M) prefix match), PA-3015 (PlumtreeNode outbox unbounded on partition), PA-3018 (HyParView shuffle never auto-scheduled), PA-3019 (no built-in failure detector), PA-3028 (FanOutBuffer ring is racy under wrap), PA-3033 (rollback flips state but emits no revert mutation), PA-3037 (zero metrics anywhere in distribution package — every other finding is blind in production).

### Cluster D — server / control-plane-api / transport (13)
- **PA-4001** — AuditLogger never implemented (R-13 carry-forward).
- **PA-4002** — HTTP request body `readAllBytes()` unbounded.
- **PA-4003** — JDK `HttpServer` has no socket timeouts → slow-loris.
- **PA-4005** — TLS reload does NOT rebuild the `SSLServerSocket`; inbound
  side keeps old cert until restart. Runbook is wrong.
- **PA-4006** — TLS reload failures only `System.err.println`; no metric,
  no alert.
- **PA-4007** — mTLS verifies cert but inbound trusts the peer-claimed
  4-byte `senderId` (`TcpRaftTransport.java:222`); any node with a valid
  cert can impersonate any `NodeId`.
- **PA-4011** — `AclService` longest-prefix match means root principal at
  prefix `""` cannot override per-prefix grants.
- **PA-4012** — `AdminService` defined but never wired to any HTTP
  endpoint; runbook references nonexistent `/admin/tls-reload`.
- **PA-4013** — Rate limit is per-node not cluster-wide as docs claim.
- **PA-4014** — `readDispatchExecutor` single-threaded with unbounded
  queue → no backpressure.
- **PA-4015** — `ConfigdServer.start` not transactional; partial failure
  leaks started subsystems.
- **PA-4017** — `RaftMessageCodec.decodeAppendEntries/InstallSnapshot`
  reads attacker-chosen length, allocates `new ArrayList<>(numEntries)`
  → multi-GiB pointer-array OOM.
- **PA-4019** — `PeerConnection.sendFrame` blocks the tick thread on a
  slow peer's TCP window under `sendLock`; one slow peer freezes the
  leader.
- **PA-4020** — Outbound connection starts a reader thread; combined with
  `acceptLoop`, a logical peer-pair has up to 4 reader threads with
  double-dispatch potential.

### Cluster E — observability + spec + runbooks (4)
- **PA-5006** — INV-V1, INV-V2, INV-W1, INV-W2, INV-RYW1, INV-L1 have no
  runtime assertion bridge.
- **PA-5007** — Burn-rate is single-window threshold, not Google SRE
  multi-window/multi-burn-rate.
- **PA-5015** — Histogram emitted as Prometheus `summary` (not
  aggregatable across instances; breaks global p99 SLO math).
- **PA-5023** — SPEC-GAP-4 (ReadIndex) confirmed still open in
  `ConsensusSpec.tla` (zero occurrences).

### Cluster F — build / CI / docs / supply-chain (8)
- **PA-6001** — 376 MB of TLC on-disk state (`spec/states/`) checked into
  git (69 tracked files).
- **PA-6002** — 4.2 MB `spec/tla2tools.jar` checked in without SHA pin or
  provenance.
- **PA-6004** — `.gitignore` is 15 bytes; missing `.idea/`, `*.log`,
  `spec/states/`, `.DS_Store`.
- **PA-6008** — No SBOM generation in CI; no Syft/CycloneDX in pom.
- **PA-6009** — No image signing (no Cosign / Sigstore / OIDC).
- **PA-6010** — No vulnerability scan (no Trivy/Grype/OSS-Index/Dependabot).
- **PA-6011** — Docker base images use floating tags
  (`eclipse-temurin:25-jdk-noble`), not `sha256:` digests.
- **PA-6025** — K8s manifest uses `image: configd:latest` with
  `imagePullPolicy: IfNotPresent` → non-reproducible rollout.

## 5. S2 / S3 findings

S2: 71 total. S3: 59 total. Each is documented in its cluster file with the
same finding template (location, evidence, impact, fix direction). They are
not GA-blocking individually, but several should be batched into the
hardening release alongside the S1 fixes.

Notable S2 themes:
- Hot-path allocation in coalescer / per-event WatchEvent set construction
  (PA-3009, PA-3013, PA-3024).
- Codec / encoding edge cases (PA-1015 through PA-1020).
- Deserialisation bounds checks across codecs (PA-2018, PA-4017 marker).
- mTLS credential-rotation ergonomics (PA-4022).

## 6. Cross-cutting observations

The audit clusters were independent; the same patterns showed up across
multiple clusters and warrant cross-module fixes rather than per-cluster
patches.

1. **Rule-10 violation: error paths have no metric.**
   PA-1004, PA-1006, PA-2003, PA-3002, PA-3037, PA-4006, PA-5001-5005.
   Across config-store, distribution, transport, observability — error
   branches log to `System.err.println` (or do nothing) instead of
   bumping a counter. Rolling these into a single mechanical pass
   ("every catch block bumps a counter named `${component}_err_total`
   keyed by exception class") clears the whole cohort.

2. **Unbounded / wall-clock-only timing.**
   PA-1001, PA-1002, PA-2005, PA-2006, PA-3008, PA-3015, PA-4002, PA-4014.
   "tick == ms", "queue grows forever until OOM", and "wall clock instead
   of HLC" all show up. The fix shape: any time-or-size you don't bound is
   an exploit surface.

3. **Authentication / authorization edge of the system is not a single
   surface.**
   F-0052 residual is still real (PA-3001), and even the modules that DO
   verify (DeltaApplier) have a default constructor that skips
   verification (F-0052 §note 1). Add to that PA-3011 / PA-3021 (no ACL on
   subscribe / catch-up), PA-4004 (token without TLS), PA-4007
   (impersonation by claimed senderId), PA-4011 (root cannot override),
   PA-4012 (AdminService not wired). Net: the security perimeter is not a
   ring. Phase 7 needs to draw it as one and put a verification gate on
   every crossing.

4. **Snapshot transfer subsystem is the least-hardened in consensus.**
   Six S1 findings cluster in `SnapshotTransfer` / `RaftNode.send/handleInstallSnapshot`
   (PA-2001/2002/2004/2007/2008/2025). The chunking infrastructure exists
   and is unit-tested but the leader path bypasses it. Phase 4 should
   wire the chunked path in production code, add CRCs, and run a chaos
   scenario that drops every Nth chunk.

5. **Observability is structurally absent from code, then asserted by
   docs.**
   PA-5001-5005 in cluster E plus PA-3037 in cluster C plus PA-4006 in
   cluster D demonstrate this pattern: the runbooks describe an
   alerting story (metric → rule → page → runbook) where the metric does
   not exist, the rule does not exist, and the runbook references both.
   This isn't six separate fixes — it's one missing layer (metric
   registry → SLO sink → burn-rate evaluator → alert bus → rule file
   generator) that needs to be built end-to-end in Phase 8.

6. **Build / supply-chain hardening has not begun.**
   PA-6001-6011 cluster F: no SBOM, no signing, no scan, no image
   pinning, large binaries in git. None of this blocks correctness
   today, but G6 (security) and G7 (supply chain) on the GA scorecard
   cannot be green until each line is closed.

## 7. R-residual reconciliation against Phase 0 inventory

Phase 0 enumerated 15 open residuals (R-01 through R-15) carried from prior
audit passes. This audit's status on each:

| ID    | Description                                          | This audit                                        |
|-------|------------------------------------------------------|---------------------------------------------------|
| R-01  | F-0052 — distribution-hop signature verification     | **CONFIRMED OPEN** — PA-3001 carries it forward at S0. Zero `Signer/verify` references in `configd-distribution-service`. |
| R-02  | (TLA+) liveness invariants un-checked                | Out of scope of this audit — Phase 3 deliverable. Re-affirmed: spec has no liveness. |
| R-03  | TLA+ `MonotonicCommit` commented out                 | Spec comment at `ConsensusSpec.tla:261-266` cites a TLC spurious counter-example at `MaxTerm=3`; closure path is Apalache (PA-5020/5021). |
| R-04  | F-0075 unused dependencies                           | Out of scope — Phase 7. Confirmed `agrona` and `jctools` declared but unused. |
| R-05  | Performance numbers unverified at 1k subscribers     | Out of scope — Phase 6 (perf & capacity). |
| R-06  | Multi-Raft / sharding                                | Out of scope — Phase 11 stretch. PA-5028 (S3) flags TLA+ does not model multi-Raft. |
| R-07  | Cross-DC bridge mode                                 | Out of scope — Phase 5 (chaos automation). |
| R-08  | 72-hour soak                                         | Calendar-bounded. Phase 6 deliverable: harness only. |
| R-09  | 7-day burn-in                                        | Calendar-bounded. |
| R-10  | 30-day longevity                                     | Calendar-bounded. |
| R-11  | 14-day shadow-traffic                                | Calendar-bounded. |
| R-12  | External on-call bootstrap                           | Calendar-bounded; Phase 11 stretch. |
| R-13  | AuditLogger missing                                  | **CONFIRMED OPEN** — PA-4001 (S1). Glob for `AuditLogger.java` returns zero files. |
| R-14  | SPEC-GAP-3 (VersionMonotonicity) and -4 (ReadIndex)  | -3 is **STALE** — invariant exists at `ConsensusSpec.tla:185-186` and is checked by TLC (doc drift only — PA-5022). -4 is **CONFIRMED OPEN** — PA-5023 (S1). |
| R-15  | `NoStaleOverwrite` removed from spec                 | **STALE** — already removed in TLA + .cfg with explanatory comment at `ConsensusSpec.tla:188-195`. Stale references survive in `spec/tlc-results.md:17,37` and `docs/verification/inventory.md:41,55` (PA-5024 / PA-5030). |

Net new open residuals from this audit: PA-1001..PA-1013, PA-2001..PA-2010 (top 10 of 40), PA-3001..PA-3040, PA-4001..PA-4024, PA-5001..PA-5030, PA-6001..PA-6045. Total: 209 PA-IDs filed; 5 R-series carry-forwards in cluster F.

## 8. Stale documentation (carry-forward to Phase 11 doc-drift purge)

Phase 0 flagged 6 stale docs; this audit confirmed and added:

- `docs/rewrite-plan.md` — still prescribes Gradle/Netty/gRPC/Spring Boot
  (contradicts the Maven/JDK-HttpServer reality recorded by ADR-0010 /
  0014 / 0016 with "Superseded" notes). Re-flagged PA-6014.
- `README.md` is 10 bytes total. Re-flagged PA-6005.
- ADR-0021 and ADR-0022 missing Reviewers section. PA-6017.
- 0 / 22 ADRs have a Verification field linking to a concrete test, TLA+
  module, or JMH benchmark. PA-6018.
- 11 / 22 ADRs have partially-Superseded claims (Netty/gRPC/Spring
  references) flagged in-document but not pruned. PA-6019.
- 0 / 8 runbooks conform to the required task-spec template. Template
  unification deferred to Phase 11. PA-6020.
- Stale "10K writes/s" numbers in 2 files vs F-0054 limiter wiring.
- `spec/tlc-results.md` + `docs/verification/inventory.md` reference
  the removed `NoStaleOverwrite` invariant.

Plus 17 rows in the doc-drift register at `prod-audit-cluster-F.md` §6.

## 9. Phase 2 hand-off

Phase 2 (`docs/gap-closure.md`) will partition the 209 PA-IDs (+5 R-carries)
into:

1. **Code-only — fixable in this session.** All 15 S0, plus the S1
   findings whose fix shape is local + bounded (e.g., add try/catch +
   metric, swap `LinkedList` for `ArrayDeque`, add bounds check). Estimate
   ~120-140 of the 209 fall here.

2. **Calendar-bounded — harness-only.** Phase 6's 72h soak / 7d burn /
   30d longevity / 14d shadow / external on-call bootstrap. Harness must
   exist; durations remain yellow with measured real elapsed and **must
   not be marked green**.

3. **Cross-module structural changes — fixable but multi-PR.** PA-5003
   (latency-percentile SLO type), the unified observability layer, the
   Phase 7 supply-chain pipeline, the Phase 8 alert-rule generator. These
   need to be sequenced because downstream code depends on the new types.

4. **Out-of-scope for v0.1 GA — ADR or stretch.** R-06 (multi-Raft), R-07
   (cross-DC bridge), R-12 (external on-call bootstrap). Decisions here
   should be ADR-recorded with explicit "deferred to v0.2" framing.

Dependency-ordered execution starts with: PA-3001 (depends on a
`DeltaVerifier` API in security cluster), PA-1004 + PA-1001 + PA-1002
(all touch `ConfigStateMachine`), PA-2002 + PA-2007 + PA-2008 (all touch
the snapshot install path). The full DAG is the deliverable of Phase 2.

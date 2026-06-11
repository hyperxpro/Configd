# C2 Design Draft — Edge Node Process

> **Status: DRAFT for review-architect screening before C2 implementation** (charter §1
> rule 2). C1's sign-off precedes C2's start; this draft exists so the screen can happen
> at the C1 sign-off review. Contract rows: CT-01..CT-13 (staleness + monotonic-read
> serving), CT-16 (drain-loop gap detection level), CT-23 (wire-level F-0052), CT-25 (C2
> half: prefix storage filter), CT-35 (RYW through the real path), CT-37 (strong reads),
> CT-40 (mTLS), plus CT-39 jointly with C1/C6.

## 1. What C2 is

The separately runnable process RR-001 indicts the absence of: `configd-edge-node`, a new
reactor module producing a runnable jar (shade, mirroring configd-server's), that
subscribes to a fan-out endpoint over mTLS, verifies and applies the signed chain, serves
reads over HTTP with cursor/staleness semantics, and emits the edge metric series.

## 2. Layering — mirror C1's proven shape

C1's review-confirmed pattern (transport-agnostic deterministic core + thin socket shell)
is mirrored client-side, so the simulator drives the REAL C2 logic:

```
configd-edge-node:
  EdgeNodeMain / EdgeNodeConfig     (CLI, lifecycle)
  EdgeStreamClient                  (socket shell: connect/mTLS/reconnect threads)
  EdgeHttpServer                    (read serving surface, health, metrics)
configd-edge-cache (existing, extended):
  EdgeClientCore   [NEW]            (transport-agnostic client session engine)
  DeltaApplier / LocalConfigStore / EdgeConfigClient (existing, reused)
  StalenessTracker                  (REWORKED per ADR-0039 — frontier-based)
```

`EdgeClientCore` (the C2 analogue of `FanOutSessionCore`): clock-injected, no threads, no
sockets. API: `onFrame(EdgeFrame)` (NOTIFY → verify+apply via DeltaApplier; SNAPSHOT_*
reassemble → loadSnapshot + resetGap, refusing backward snapshots [C1(a) lesson];
HEARTBEAT → frontier per ADR-0039; SUBSCRIBE_OK → mode handling), `tick(now)` (emits
CURSOR_ACK, detects silence→reconnect signal), an outbound `FrameSink`, and a
`ConnectionDirective` stream (RECONNECT_NEXT_ENDPOINT etc.) the shell obeys. The V1
`EdgeActor` is refactored to drive `EdgeClientCore` (sim parity: the gate seeds then
exercise real C2 code; the V1 invariants judge it).

## 3. Decisions (for the screen)

1. **CT-37 strong reads: store-and-never-serve.** The chain delivers `secure/` keys
   (ADR-0038, suppression detectability); the edge stores them (keeps the V1
   snapshot–delta equivalence invariant a plain full-store byte-compare) but the serving
   path fail-closes on the strong-read key class: 503 + `X-Fail-Closed: strong-read`
   (mirroring the control plane's RR-020 contract); clients go to the control plane's
   linearizable path. The key-class predicate is the same configured prefix set as the
   control plane (`secure/` default) — shared constant, not a re-implementation.
2. **Prefix subscription = storage filter in `EdgeConfigClient`** (ADR-0038): signature
   verified over the ORIGINAL delta (byte-fidelity), then non-matching mutations are
   dropped before store apply; the chain version still advances (same from/to versions,
   subset mutations). Empty subscription set = full-store. Reads outside the subscription:
   NOT_FOUND by construction (negative-cache rows stay C3).
3. **StalenessTracker rework (ADR-0039, prod-blocking condition):** `recordUpdate(version,
   commitTs)` becomes load-bearing; new `recordFrontier(serverNowMillis)` applied only
   when the heartbeat's `latestSeq == cursor`; the idle-time proxy measurement is DELETED
   (not kept alongside). Implausibility tripwire (CT-08): negative staleness beyond 50 ms
   or frontier regression → `edge_staleness_implausible_total` + clamp.
4. **Read serving surface:** JDK HttpServer (HttpApiServer pattern). `GET
   /v1/config/{key}`; request cursor via `X-Configd-Cursor`; responses carry
   `X-Configd-Version` + `X-Configd-Cursor` (every read returns its cursor — charter C2);
   cursor-behind → 404 + `X-Configd-Refused: cursor-behind` + monotonic_read metric (the
   contract §3 refusal semantics — NEVER serve-stale-on-cursor-behind); `X-Configd-Stale:
   true` on all reads in STALE+ (CT-03) + `configd.edge.staleness_violation_total`
   (CT-04); `/health/live` always 200, `/health/ready` 503 when DEGRADED+ (CT-05);
   DISCONNECTED → re-bootstrap trigger (full re-subscribe cursor=0 path — CT-06, C3
   orchestrates). `/metrics` via the existing PrometheusExporter.
5. **Failover (CT-11/CT-12):** multiple `--fanout-endpoints`; on reconnect the client
   SUBSCRIBEs to the next endpoint carrying `failoverResumeCursor = cursor`. Reads keep
   refusing cursor-behind during catch-up (consistent-refusal semantics — the CT-12
   contradiction is resolved by ADR in the contract pass: refusal, not block-and-serve-stale;
   the failover steps 3-4 text is amended).
6. **Verify key distribution:** `--verify-key <path>` (Ed25519 public key); a small
   `--export-verify-key` utility on the server side (or documented keytool/openssl step)
   produces it from `signing-key.bin`. Unsigned deltas with a verifier configured are
   rejected (DeltaApplier fail-closed, existing). The sim runs unsigned (no verifier) as
   in V1; wire-level signature tests (CT-23) run signed end-to-end in the C2 integration
   test.
7. **Monotonic reads across restart (CT-13):** crash loses the store (cache semantics);
   post-restart reads with a client cursor ahead of the rebuilt store refuse via the
   existing cursor check — pinned by an explicit restart test with a held cursor.
8. **Hot-path law scoping (CT-34):** the law binds the in-process read path
   (`LocalConfigStore.get` — lock-free, zero steady-state allocation, JMH gc-profiled in
   gate-3 via a new `LocalConfigStoreReadBenchmark`); the HTTP serving shell above it
   allocates per request (it is not the §3 library read path) — stated honestly in the
   design note and the benchmark javadoc.

## 4. Config & metrics (named, per charter §6 rule 8)

`--edge-id` (must match mTLS cert identity), `--fanout-endpoints h:p[,h:p]`, `--api-port`,
`--data-dir` (epoch.lock; SEC-017), `--verify-key`, `--subscribe-prefix` (repeatable),
TLS paths (same triple as the server), `edge.reconnect.backoffMs` (bounded, jittered),
`edge.heartbeat.silenceFactor` (reconnect after N×heartbeatMs silence).

Series: `edge_staleness_ms`, `edge_staleness_state`, `configd.edge.staleness_violation_total`,
`edge_staleness_implausible_total`, `edge_cursor_lag`, `edge_applied_total`,
`edge_gaps_total`, `edge_snapshots_applied_total`, `edge_reads_total`,
`edge_read_refusals_total{reason}`, `edge_reconnects_total`, plus the existing
`invariant.violation.monotonic_read`.

## 5. Test plan (written first, per map rows)

Unit: `EdgeClientCoreTest` (frame handling matrix, backward-snapshot refusal, frontier
math incl. latestSeq>cursor never advancing it), reworked `StalenessTrackerTest` +
`StalenessUpperBoundTest` (frontier clock; idle-but-heartbeating pinned CURRENT — the
ADR-0039 regression test), `StalenessSkewTripwireTest` (CT-08), prefix-filter tests
(CT-25 C2 half), strong-read fail-close (CT-37).
Sim: EdgeActor-over-EdgeClientCore under the 507-seed gate (V1 invariants judge);
staleness state machine driven by sim heartbeats (CT-07 at sim level); CT-13 restart test.
Process: `EdgeNodeIntegrationTest` (real server + real edge process loopback: write→
propagate→read with cursor; stale header; signed chain with a real verify key);
`EdgeFailoverTest` (two fan-out endpoints, kill one, cursor-monotonic reads across
reconnect — CT-11); mTLS reject/accept (CT-40).

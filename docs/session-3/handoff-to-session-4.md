# Session 3 → Session 4 Handoff: The Edge Data Plane's Fault Surface

> Session 3 built the edge data plane (C1 fan-out service, C2 edge node process, C3
> catch-up/replay/gap recovery, C4 slow-consumer policy, C5 bootstrap, C6 E2E) — all six
> components dual-signed (reviews under `docs/session-3/reviews/`), RR-001 RESOLVED with
> the review-architect's closure justification, contract map at 34 PASSING / 3 owned
> PARTIAL / 3 ADR / 1 N-A, gate-3 assembled and CI-wired. **This document is what
> Session 4's chaos matrix must now cover that the original charter §4 matrix didn't,
> plus the known weak points, environment notes, and the integrated-sim configs to
> reuse.** Read it with `docs/readiness-register.md` rows RR-095/098/099/103 open.

## 1. New fault surface for the chaos matrix

The original §4 matrix covered consensus faults. The data plane adds these axes — each
with the machinery that should absorb it and the test that pins the absorb path:

| Fault axis | Absorber | Pinned by (extend from here) |
|---|---|---|
| Fan-out endpoint death mid-stream | edge round-robin failover carrying resume cursor; consistent refusal during catch-up | `EdgeFailoverTest`; E2E phase 2 |
| Edge partition (drop, not RST) | ADR-0039 staleness ladder → DISCONNECTED → re-bootstrap trigger; bounded+jittered reconnect | `EdgeStalenessFrontierSimTest`, `EdgeReBootstrapOnDisconnectTest`; E2E phase 3 |
| Slow/wedged edge (reads but never acks) | ack-lag demotion → snapshot; governor ladder SLOW→CATCHUP→QUARANTINED→UNHEALTHY (per-identity) | `SlowConsumerStateMachineWalkTest`, `FanOutServerQuarantineTest` |
| Boundary ring lap during replay/decision | GAP → demote(REASON_GAP) → snapshot → contiguous resume | `ReplayHorizonBoundaryTest` (incl. the forced lapped-after-TAIL race) |
| Transport backpressure mid-snapshot-transfer | RR-102 would-block pause/resume (same envelope, never restart); cutover only after END accepted | `BootstrapSnapshotBackpressureTest`; the paced real-socket leg in `EdgeBootstrapUnderSustainedWritesProcessTest` |
| Poisoned/apply-throwing delta | ADR-0040 ladder: bounded retry → quarantine → forced snapshot → TERMINAL exit 3 (never a hot loop) | `PoisonPillRebootstrapTest` (core 18 + process 2) |
| Edge crash/restart (cache loss + epoch floor) | cursor-0 ⇒ SNAPSHOT_FIRST (RR-100 — epoch-safe snapshots); held client cursors refuse until covered | `MonotonicReadAcrossEdgeRestartTest` (sim + process) |
| Zero-state join under sustained writes | exact cutover cursor; equivalence judged vs pure-stream control | `EdgeBootstrapUnderSustainedWritesTest`, `EdgeBootstrapMidChurnTest` |
| Wire corruption / torn frames | length-prefixed bounds-before-allocation codec → unparseable ⇒ disconnect ⇒ resubscribe-at-cursor | `EdgeFrameCodec` tests + golden fixtures |
| Rogue client/server certs | mTLS both directions; cert-DN-authoritative identity | `EdgeTransportMtlsTest`, `FanOutServerMtlsTest` |

**Chaos legs S4 specifically owes (named during S3, not yet exercised):**
1. **Accept-then-black-hole fan-out endpoint** — proves `CONNECT_TIMEOUT_MS`/
   `HANDSHAKE_TIMEOUT_MS` actually bite edge-side (CT-40 gap, c2-contract-qa gap 3;
   bounds exist and mirror `TcpRaftTransport`, no test stalls a peer yet).
2. **Prod-threshold ack-lag demotion** — the E2E scenario can't reach 8192 seqs;
   chaos can, or tune `edge.fanout.ackLagDemoteSeqs` down explicitly (the e2e gap named
   in c6-e2e-design-note §7).
3. **Wedged-but-open transport during a paced transfer** — the pause is benign and
   bounded, but there is NO dedicated stalled-transfer signal yet (c5-signoff F2; S6
   observability item) — chaos should characterize how it presents.
4. **Long-running governor churn** — repeated quarantine/readmit cycles across many
   identities (the bounded identity map's eviction under adversarial identity counts —
   reviewed safe at C4, never load-tested).

## 2. RR-095 + RR-103: the consensus-liveness pairing (S4-owned)

- **RR-095** (7 stall seeds): re-run against the INTEGRATED config at C6 — **NO
  CHANGE**, all seven still stall at the CP layer, the edge plane starves SAFELY
  (capture: `docs/session-3/captures/rr-095-integrated-rerun.txt`; re-verified
  byte-identical by the C6 QA). The S3 cross-check is discharged.
- **RR-103** (NEW, P1, found by C5): `RaftNode` per-peer inflight window leaks
  permanently on dropped messages — a leader is silenced toward a peer for its whole
  term (inc on send `:1669/:1721`, dec only on response `:1280/:2137`, reset only at
  `becomeLeader:1593`). **Deterministic repro: seed 4242, 5 nodes** (isolate leader,
  heal; node stays ~47 entries behind for 10k+ quiet ticks). Likely a root-cause
  component of the RR-095 family — **evaluate both together**. Fix shape in the row
  (deadline/heartbeat decay or rejection-triggered window reset). Second-agent
  reproduction owed at pickup; the seed is deterministic.
- Integrated 10k sweep baseline (capture `edge-integrated-10k-sweep.txt`): 0 safety
  violations, 16 CP stalls, 97.1% quiet-window convergence — seed lists in the capture
  for S4's liveness triage.

## 3. Known weak points (each registered or review-noted — none hidden)

- **RR-098** (P2, S5): every edge holds `secure/` values in memory (ADR-0038
  store-everything; serving fail-closes; values never on disk — the E2E RR-098 disk
  sweep pins it). Exfiltration surface if edge hosts are lower-trust.
- **RR-099** (P3, S6): `invariant.violation.monotonic_read` at the edge serving surface
  conflates benign catch-up refusals with store regressions; SEVERE log spam during
  routine failover. Do NOT page on that series alone.
- **Teardown bye race** (c4 note §7): the quarantine `ERROR_CLOSE` can arrive torn when
  racing the writer; the refusal path carries the clean code 8. Cosmetic at the wire.
- **Deep-store redundant re-demote envelope** (c5-signoff F3): one extra paced snapshot
  per ack RTT post-cutover on big bootstraps — deliberate self-healing, S6 efficiency
  note; the C5 wide-window test tolerates 1..2 transfers for exactly this reason (the
  c9751d8 flake lesson: assertions must not over-pin signed-off behavior).
- **CM-049 legacy leg**: `edgeStalenessStaysWithinBounds…` still measures its own loop;
  disclaimed, rewrite at V2/S5. Do not cite it.
- **Plaintext-mode identity**: the governor and session identity key on the wire
  `edgeId` when TLS is off (test/single-node only); mTLS keys on the cert DN.
- **CT-02 sanctioned deferral (S5)**: the staleness-distribution NUMBERS (p99 < 500 ms
  etc.). The mechanism is delivered (probe both modes, ADR-0039 clock); S5 sets and
  verifies targets on real hardware.

## 4. Environment notes (hard-won; also in the lead's memory)

- 2-vCPU t3a.large: CPU-credit throttling is real; serialize Maven; the working
  busy-check is `pgrep -f "[a]pache-maven|[s]urefirebooter"` (`org.apache.maven`
  patterns match NOTHING on the 3.9.9 wrapper — and unbracketed patterns self-match
  your own shell).
- Compose topology: 7 JVMs fit only with the small heaps in `deploy/compose/`; never
  run Maven while the topology is up. `setup-secrets.sh` regenerates mTLS material
  (empty-password PKCS12 repack — `TlsConfig.mtls` constraint, documented in
  `SecretsTool.java`).
- **Operator facts** discovered at C6: the CP API serves HTTPS when TLS is on (pin
  `--cacert`); a cluster REQUIRES a shared `--signing-key-file` across CP nodes (each
  node signs its own fan-out stream; epoch is a deterministic counter so cross-node
  failover is epoch-safe, but the key must be cluster-shared).
- Stale-artifact/shaded-jar traps as documented in S2's handoff — now mechanized: the
  E2E scenario's phase 0 probes the shaded jars for marker classes/fields.

## 5. Integrated-sim configs to reuse (don't fork)

- **Gate set**: `EdgeAdversarialGateSeedSweepTest` (507 seeds, V1 invariants, byte-pinned
  digest via `EdgeSeedCompatTest`); stats baseline in any sign-off review since C2.
- **Integrated topology**: `EdgeFanOutSim(seed, 5, 3, 1500, edgeFaults, new
  C1StreamDriver(), intensity, EdgeInvariants.BOUND_MS)` + `enableEdgeRecovery(i)` on
  all edges — the exact config of `Rr095StallSeedsIntegratedRerunTest` (property-gated
  `-Dconfigd.rr095.rerun=true`) and `EdgeIntegratedNightlySweepTest`
  (`-Dconfigd.edge.nightly=true`, 10k ≈ 107 s).
- **Fault injection seams**: `partitionEdge/healEdge` (deterministic), `joinEdge`
  (zero-state mid-run), `setEdgeDupRateForTest` (deterministic dup),
  `EdgeClientCore.ApplyFaultInjector` (poison), `loadSnapshotForced` (regression
  injection for invariant non-vacuity). All opt-in; gate path byte-identical.
- mixSeed tag registry: tags ≥ 1_010 for new randomness streams (keep the 507-seed gate
  byte-identical — the established rule, held through C2..C6).

## 6. Metric series list (charter §7 DoD "named list in handoff")

**Edge process** (`EdgeNodeMetrics`, eager): `edge_staleness_ms`, `edge_staleness_state`,
`configd_edge_staleness_violation_total`, `edge_staleness_implausible_total`,
`edge_cursor_lag`, `edge_applied_total`, `edge_gaps_total`,
`edge_snapshots_applied_total`, `edge_reads_total`,
`edge_read_refusals_{cursor_behind,strong_read,not_subscribed}_total`,
`edge_reconnects_total`, `edge_rebootstrap_triggered_total`,
`edge_verify_rejections_total`, `edge_poison_retries_total`,
`configd_edge_poison_pill_total`, `configd_edge_poison_pill_terminal_total`.
**Fan-out/CP** (`RegistryFanOutSessionMetrics`, eager): `edge_fanout_notify_batches_total`,
`edge_fanout_notify_batch_size`, `edge_fanout_heartbeats_total`,
`edge_fanout_slow_consumer_warnings_total`, `edge_fanout_snapshot_transfers_total`,
`edge_fanout_demotions_{queue_overflow,ack_lag,gap,transport_block,other}_total`,
`edge_fanout_sessions_closed_{...,quarantined}_total`, `edge_fanout_queue_depth`,
`edge_fanout_connected_subscribers`, `edge_fanout_sessions_refused_total`,
`edge_fanout_subscribe_{tail,snapshot_first}_total`,
`edge_fanout_subscribe_horizon_distance`, `edge_fanout_slow_transitions_total`,
`edge_fanout_quarantines_total`, `edge_fanout_reconnects_refused_total`,
`edge_fanout_unhealthy_total`, `edge_fanout_readmissions_total`,
`edge_fanout_consumer_state_{healthy,slow,catchup,quarantined,unhealthy}`.
The consolidated machine check is `EdgeMetricsContractTest` (CT-38). Known signal gap:
no stalled-transfer signal (S6).

## 7. Where everything is

- Design notes (as-built, test-cited): `docs/session-3/design/c{1..6}-*-design-note.md`
- Reviews: `docs/session-3/reviews/` (sign-offs, QA audits, the C2-C5 screen)
- Captures: `docs/session-3/captures/` (E2E run, RR-095 rerun, 10k sweep, CT-34 gc
  artifact, gate-3 run)
- Gates: `gates/gate-3.sh` (+ `e2e-compose-scenario.sh`, `jmh-gc-check.sh`,
  `gate3-map-expectation.txt`); CI: the `gate-3` job in `.github/workflows/ci.yml`
- ADRs this session: ADR-0036..0040 (+ the §3/§7 doc-pass amendments recorded in
  `consistency-contract.md` and `architecture.md` with provenance notes)

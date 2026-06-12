# C6 As-Built Design Note — End-to-End Integration

> **Status: AS-BUILT** — for dual sign-off (review-architect + contract-qa, charter §2).
> Test/script names and capture paths are citations. C6 has no pre-implementation draft
> (the charter §4 C6 text is the spec); deviations §6; named gaps §7.

## 0. What exists now

The full advertised data plane runs as a containerized topology and survives the
charter's four-phase adversarial scenario: **3 control-plane nodes + 3 edge processes
(+1 bootstrap joiner) under Docker Compose, all mTLS, all via the real CLI paths** —
and the scripted scenario (`gates/e2e-compose-scenario.sh`, exit-code based,
deadline-bounded polls, zero sleeps-as-sync, cleanup trap) passes 19/19 assertions
end-to-end. One full green run is captured at
`docs/session-3/captures/e2e-compose-scenario-run.txt`. This is the runtime proof
RR-001's closure requires, paired with the contract map.

## 1. Topology (deploy/compose/)

`compose.yaml`: cp1-cp3 (`Dockerfile.server`, -Xmx192m) + edge1-edge3
(`Dockerfile.edge`, -Xmx128m) + edge4 under `profiles: [bootstrap]` — 7 JVMs sized for
the 2-vCPU/7.7GB box. Slim `eclipse-temurin:25-jre-noble` layers over **host-built**
shaded jars (deviation 1). `setup-secrets.sh` + `SecretsTool.java` generate the CA/cert
material — including **empty-password PKCS12 repack** (keytool refuses <6-char
passwords; `TlsConfig.mtls` hard-codes empty) — which closes the C2 note §8 gap: **the
CLI TLS path is exercised with a real handshake for the first time, on both server and
edge** (the prior status was injected-TlsManager only). Secrets are git-ignored and
docker-ignored. Static container IPs so partition heal (`network connect --ip`)
preserves published-port NAT.

## 2. The four-phase scenario (capture cites PASS 1-19)

- **Phase 0 (setup, PASS 1-5):** shaded-jar freshness probes (the Session-2 trap,
  mechanized — including an RR-104 `pendingDemotionNotice` class-in-jar probe), 3 CP
  ready over mTLS Raft + mTLS fan-out, 3 edges subscribed/verified/ready.
- **Phase 1 (propagation, PASS 6-7):** marker committed; every edge serves it via a
  **bounded read** (`X-Configd-Cursor` request → 404 cursor-behind until caught up,
  then 200+value). Bonus: `secure/` stored-but-never-served (503 fail-closed, CT-37)
  verified at every edge.
- **Phase 2 (kill leader, PASS 8-12):** `docker kill cp1` mid-stream; re-election;
  every edge progressed through the failover; **the per-edge monotonic watch sampled
  `X-Configd-Cursor` across the whole window — no edge ever saw a decrease**; staleness
  returned to CURRENT everywhere; the killed node restarted and rejoined.
- **Phase 3 (partition, PASS 13-16):** `docker network disconnect` on edge1; the victim
  walked STALE→DEGRADED (ready→503, CT-05)→DISCONNECTED with
  `edge_rebootstrap_triggered_total` firing; after heal it converged and returned
  CURRENT/ready.
- **Phase 4 (bootstrap, PASS 17-19):** edge4 joined mid-load from zero state —
  `edge_snapshots_applied_total=1` (the C3 cursor-0 SNAPSHOT_FIRST path over a real
  paced transfer), cut over to the live stream; after quiesce + fence write, **every
  written key byte-equal on all four edges vs a linearizable leader read**.

Script-hardening defects found during C6's own runs (fixed, commented at the sites): a
bare `wait` deadlocking on the still-running writer; a non-vacuity sample minimum that
punished a fast failover.

## 3. RR-095 re-run (charter obligation)

All seven stall seeds re-run against the integrated config
(`Rr095StallSeedsIntegratedRerunTest`, `-Dconfigd.rr095.rerun=true`; 5 CP + 3 edges +
recovery seams): **still-stalls, NO CHANGE** — exact match to the S2 characterization;
the edge plane starves **safely** (0 safety/delivery violations; `edgeConverged=true`
honestly flagged vacuous at v0). No new stall class from S3 code. Register row updated;
RR-103 named as a candidate root-cause component for S4 to evaluate with it. Capture:
`docs/session-3/captures/rr-095-integrated-rerun.txt`.

## 4. Integrated 10k sweep (charter bar: zero safety violations)

`EdgeIntegratedNightlySweepTest` (`-Dconfigd.edge.nightly=true`, exact gate topology):
`seeds=10000 wall=107.3s safetyViolations=0 cpElected=9984 cpStalls=16
quietWindowSeeds=3397 convergedGivenQuiet=3299/3397 (97.1%) rawConverged=6187/10000
(61.9%) seedsWithDelivery=9854 deliveryViolations=82`. **Zero safety violations.**
Liveness findings are seed lists in the capture
(`docs/session-3/captures/edge-integrated-10k-sweep.txt`) — the quiet-window
convergence rate (97.1%) matches the 507-gate's (96.1%).

## 5. RR-104 fixed (the C5 sign-off's REQUIRED, taken in this window)

`demote()`'s notice no longer flows through `emit()`: a refused offer parks in
`pendingDemotionNotice`, re-offered each tick ahead of the owed snapshot (would-block
doctrine verbatim; ordering preserved; at most one outstanding by construction).
Red-first `DemotionNoticeBackpressureTest` reproduced the phantom
`onSessionClosed("transport_gone")` pre-fix; gate path byte-identical post-fix
(`EdgeSeedCompatTest` + 507-seed stats unchanged). Register row RESOLVED.

## 6. Deviations (named)

1. E2E images are slim layers over host-built jars, not `docker/Dockerfile.runtime` —
   host builds are offline-capable and serialize on the 2-vCPU box; the in-container
   build was verified separately (both repo Dockerfiles now actually build — five
   Session-1-era fictions fixed: stale module COPY list, removed-in-JDK24 ZGenerational
   flag, hard-coded heap → MaxRAMPercentage, pre-shade `original-*` jars on the
   classpath, and a missing `.dockerignore` that shipped `.git`+`target/`+today's
   private keys into the build context).
2. Empty-password PKCS12 repack in `SecretsTool` (the `TlsConfig.mtls` constraint) —
   documented in-file; the upstream fix (configurable store password) is an S5/S7
   candidate, not C6's.
3. Partition-window observation via `docker exec` (network-independent), heal via
   `network connect --ip` (static IPs) — Docker NAT constraint, documented in the
   script.

## 7. Named gaps / operator notes (handoff items)

- **Shared signing key is a hard topology requirement** (one `--signing-key-file`
  mounted into all CP nodes; epoch is a deterministic counter so cross-node failover is
  epoch-safe, but the key must be cluster-shared) — operator docs + handoff.
- **CP API is HTTPS when TLS is on** (same SSLContext, server-auth only) — scenario
  curls pin `--cacert`; operator docs line.
- Server-side `edge_fanout_demotions_*` counters not asserted by the scenario (a
  prod-threshold ack-lag demotion needs 8192 seqs — unreachable at scenario write
  rates; the edge-side ladder/re-bootstrap/convergence are the demotion evidence).
  S4 chaos can force it with tuned thresholds.
- `Dockerfile.build`'s CMD (full in-container test suite) verified to build, not
  executed (duplicates the host reactor verify on this box).
- Environment find for gates/ docs: the Maven-busy check must be
  `pgrep -f "[a]pache-maven|[s]urefirebooter"` (the 3.9.9 wrapper JVM runs the
  plexus-classworlds launcher — `org.apache.maven` patterns match nothing).

## 8. Contract rows this component claims (the contract-qa audit flips)

CT-39 (the Compose-scale E2E + RR-095 re-run were its named remainder) and any
E2E/propagation rows the map scopes to C6. The audit, not this note, flips the map.

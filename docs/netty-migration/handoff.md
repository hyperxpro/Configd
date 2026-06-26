# Netty-migration — handoff

> **This doc is the living continuity record (latest session at top).** Prior evidence artifacts
> (`baseline.md`, `inventory.md`, `decision-log.md` DR-1..5, the `docs/jdk-vs-netty/` head-to-head)
> are immutable; this summarizes the current state and what remains.

---

## Session 6 — Phase V (io_uring measurement) — COMPLETE. The honest close of the migration.

**The io_uring rationale is MEASURED and NOT validated — latent at best, a ~2× throughput regression
at high fan-out.** Full verdict + tables: [phase-v-io-uring.md](phase-v-io-uring.md); raw evidence in
[phase-v-evidence/](phase-v-evidence/) (m6i strace summaries + matrix run log + tables). ADR-0043's
io_uring rationale (point 1) updated "expected/bet" → "measured: not validated".

### What was measured (apples-to-apples, only the forced tier varies; io_uring confirmed ACTIVE)
- **Free dev-box de-risk first** (kernel 7.0, io_uring active): built + proved the whole matrix, then
  **one operator-gated m6i.4xlarge** (16 vCPU, kernel 6.18 AL2023) ran the LOCKED matrix; an
  independent 2nd agent reproduced all four headlines on the box before teardown. **Cost ≈ $0.47**
  (33 min, torn down on capture).
- **Methodology finding (load-bearing):** io_uring batches **per event loop**, and Netty runs one
  loop per core. So the dev box (2 loops) showed edge-read **7.6×** fewer syscalls at conn64 while the
  m6i (16 loops) showed **1.1×** at the same point — the multi-core box is the honest number; the
  dev box *overstated* the benefit. And: a **traced** throughput comparison INVERTS (strace penalises
  epoll's higher syscall count) — only UNTRACED runs answer throughput. Both pitfalls handled.

### The measured verdict (m6i, independently reproduced)
- **Edge-read:** io_uring 1.1–1.5× fewer syscalls (worse below ~8 conns); **throughput + p99 TIED**
  io_uring-vs-epoll at every connection count (1→1024). Processing-bound, not syscall-bound.
- **Fan-out:** io_uring up to **5.3× fewer syscalls at 1024 subs** — but throughput TIED to 256 subs
  then at 1024 io_uring **REGRESSES ~2× (2.04M vs 4.02M notif/s) and tail ~8× (p99 24s vs 3s)**.
  Real deficit: identical 81.92M delivered, io_uring 41.5s vs epoll 21.7s wall, **0 demotions both**.
- **Consensus (1 conn):** io_uring **~2× WORSE** on syscalls (can't batch one in-flight frame).
- **Net:** io_uring delivers **no measured throughput/tail benefit** at Configd's workload, and a
  **throughput regression at growth scale**. Migration stands on its other two MEASURED grounds
  (edge-read allocation 8.7× + uniformity); io_uring stays auto-selected with Epoll the proven —
  and here *faster* — fallback. Scope: io_uring *as deployed* (Netty 4.2 defaults, no SQPOLL); tuning
  unexplored. Recommendation (operator): don't rely on io_uring for perf; consider preferring Epoll
  for fan-out pending investigation of the 1024-sub deficit.

### Harness (new, on branch `netty-migration-phase-v`)
- `configd-testkit` mains: `io.configd.fanout.{FanOutPushServerMain,FanOutLoadClientMain}` (fan-out
  push load, transport-isolated governor), `io.configd.consensus.ConsensusSendMain` (single-link Raft
  send via the production `NettyConsensusFrameEncoder`, synchronous connect).
- `docs/netty-migration/scripts/`: `phase-v-matrix.sh` (the LOCKED matrix, PROFILE=dev|m6i),
  `phase-v-parse.py`, `ec2-{provision,run,teardown}.sh`.

### Honest residuals / next
- **NOT merged** (human gate). Phase V is on branch `netty-migration-phase-v` (off M4); M4 itself
  (PR #4) still unmerged. The io_uring fan-out deficit is a flagged operator decision, not actioned.
- **Standing CVE scan still owed** (NVD_API_KEY + a real dependency-check over the full tree) before
  production go/no-go — Phase V closes the migration's *rationale*, not this security gate.

---

## Session 5 — M4 (inter-node consensus wire) → Netty — DONE on branch, four-way verified, NOT merged

**The most dangerous surface, migrated.** M4 moves the inter-node Raft consensus transport
(`configd-transport` `TcpRaftTransport`, JDK `SSLSocket`/`SSLServerSocket` mTLS + `FrameCodec`
framing + the M3 coalesced-heartbeat timing) to Netty 4.2 — the surface the entire Phase-0 safety arc
rides. Branch `netty-migration-m4` off `origin/main` (M1+M2+M3 merged; DR-N15). Seven slices A–G;
**CI 12/12 GREEN** (run `28196796312` on the cutover; final-HEAD re-dispatch after the four-way fixes);
four-way rigor (implementer + diff-review + independent re-run + adversarial red-team + fresh-context
Verifier).

### Done (verified end-to-end, evidence each)

| Slice | What | Evidence |
|---|---|---|
| A (`190faa2`) | Extract the Netty-free consensus-wire core: `RaftWireProtocol` (`[4B senderId][FrameCodec frame]` encode + bounds-before-alloc length predicate + RR-002/F-S7-FUZZ-1 policy), `RaftTransportEndpoint` interface, top-level `InboundMessage`; `TcpRaftTransport` → thin JDK adapter (DR-N16) | configd-transport **135/135** green — faithful extraction |
| B (`13a3e7f`) | `NettyRaftTransport` on `configd-netty`: in-pipeline `NettyConsensusFrameEncoder` (~0 B/op, DR-N17), event-loop-driven drain (writability-gated drop-on-overflow), mTLS both directions (DR-N18: server `setNeedClientAuth`+TLSv1.3, NEW client `SslHandler` with F-0051 hostname verification), admission cap + `IdleStateHandler` slowloris deadline, RR-002 non-blocking send; `NettyTransport.Selection` + client channel (DR-N19) | `AbstractRaftTransportContract` JDK / Netty(io_uring) / Netty(NIO) = **18 / 18 / 18** |
| C (`d3e3f6a`) | `RaftTransportAdapter` generalized to the `RaftTransport` interface (DR-N20) — the cutover seam | configd-server compiles; loopback adapter test green |
| D (`6a1ed55`) | `NettyConsensusLivenessTest` — the **real-wire no-spurious-election sweep** (3-node cluster, per-node owner threads, coalescing active on the real wire) + non-vacuity leg | THE PROOF + non-vacuity + fidelity, **3× non-flaky** (re-run independently) |
| E (`1777511`) | Cut `ConfigdServer` over to `NettyRaftTransport` (one construction line via `RaftTransportEndpoint`); SBOM regenerated | configd-server **334/334**; SBOM delta = **exactly** the `configd-netty→configd-transport` edge, 0 new components |
| F (`855d061`) | gc-proof: production encoder ~0 B/op (not 160) + byte-identity test | `idiomatic=12 B/op` (event loop) vs `naive=172` (plain thread); byte-identity 72 cases |
| G | Four-way fixes (red/green): the two red-team/Verifier LOW findings | handshake-timeout test green; contract 18/18/18 + sweep 3/3 + ConfigdServerTest 22/22 still green |

### The load-bearing proof (charter §2.2 / §3) — re-run on the Netty transport
`NettyConsensusLivenessTest` is the real-wire analog of the sim `CoalescedHeartbeatLivenessTest`: a
genuinely-concurrent 3-node cluster, each node on its own owner thread behind its own
`NettyRaftTransport`, all wired through the M3 coalesce→drain pipeline (coalescing **active** on the
wire). **THE PROOF:** a stable leader holds its term across a sustained-load window AND a sustained-idle
window (idle = the coalesced heartbeat is the sole liveness signal) — no spurious election (re-run
independently 3×: leader held term=1 across ~238 load + ~239 idle observations). **NON-VACUITY:**
severing the leader's coalesced heartbeats drives a real term advance (2→3 every run) — the harness
detects heartbeat starvation, so the proof is not vacuous. **FIDELITY:** 200 heartbeat-sized frames
over a real connection arrive all-in-order (strict per-peer FIFO, 0 reorders) at 13–19× margin under
the election floor — the empirical basis that the generous election budget is for 2-vCPU robustness,
not masking transport slowness.

### Full S2–S4 surface re-closed on Netty
The S2–S4 surface (linearizability/version-monotonicity/no-stale-overwrite/durable-prefix/
leader-completeness/log-matching/state-machine-safety) runs on the deterministic `SimulatedNetwork` and
is transport-agnostic; M4 touched **neither consensus-core, the sim, nor the `FrameCodec` wire
semantics**, so the surface re-closes by (a) the sim surface staying green — CI **build-and-test**
(CertificationTest, SeedSweepTest, ConsistencyPropertyTests), **gate-3** (edge adversarial seed-sweeps),
**gate-4** (PartitionMatrix / OverloadChaos / MiniJepsen) all GREEN — plus (b) the **real-wire delivery
faithfulness** proven by the contract (slice B) + the no-spurious-election sweep (slice D), plus (c) the
**wire-compat golden bytes** (slice A, unchanged). No correctness regression.

### Consensus mTLS re-proven by negative test on Netty (DR-N18)
The `AbstractRaftTransportContract` mTLS negatives run on JDK + Netty + forced-NIO and each performs the
attack + asserts ZERO inbound delivery: **plaintext** rejected, **expired** CA-signed end-entity
rejected, **untrusted-CA** rejected, **no-cert** rejected (server `setNeedClientAuth(true)`),
**TLSv1.2 downgrade** rejected (TLSv1.3-only), **wrong-SAN** rejected (client F-0051 hostname
verification). Mutual auth enforced both directions — server demands+verifies the client cert; the new
Netty client verifies the server's hostname.

### Idiomatic encode — ~0 B/op, not the 160 trap (DR-N17)
`docs/netty-migration/m4-consensus-gc-proof.md`: the production `NettyConsensusFrameEncoder` driven
in-pipeline ON the event loop allocates **~0 B/op** (12, the `getThreadAllocatedBytes` floor); the
naive manual alloc/release loop OFF the event loop allocates **172** (the head-to-head's "160"). The
2×2 pins the mechanism — the cliff is the `FastThreadLocalThread` (Netty's pooled-`ByteBuf` Recycler
only engages on an event-loop thread), and `NettyRaftTransport.drain()` always writes ON the event loop,
so production is ~0 by construction. Byte-identical to `RaftWireProtocol.encodeWire` (72-case test).
Corroborates + sharpens verdict.md §Surface 4.

### Four-way verification (charter §3, elevated from the M1–M3 Verifier pass)
1. **Implementer** — slices A–F, each behind the existing/new tests.
2. **Diff-review** — the contract was authored + line-by-line diff-reviewed against the incumbent JDK
   tests (faithful transcription, zero assertion dropped; the JDK contract subclass passing proves the
   harness encodes real JDK behaviour). The diff-review **caught a real bug**: `NettyRaftTransport.close()`
   fired `serverChannel.close()` fire-and-forget then tore down the event-loop groups → on io_uring the
   abandoned close SQE left the listen FD lingering → same-port rebind failed. Fixed **red/green** by
   awaiting the listen-socket close (restoring JDK synchronous-close semantics); the io_uring contract
   subclass went 17→18 green.
3. **Independent re-run** — the contract (54), the no-spurious-election sweep (3×), and the gc-proof were
   re-run from clean; CI 12/12 is the full clean re-run of the whole surface.
4. **Adversarial red-team — SAFE TO MERGE** (no Critical/High; consensus safety, per-peer FIFO across
   reconnect, drop-on-overflow, the lock-free drain, and mTLS all hold). Two **LOW** findings, both
   **fixed red/green** (slice G): (i) the Netty `SslHandler`s didn't apply the shared
   `HANDSHAKE_TIMEOUT_MS` (2 s) — defaulting to Netty's 10 s, contradicting DR-N16's "same numbers"
   (liveness-only; RR-002 holds since the handshake is off the tick thread) → now set on both handlers,
   pinned by `NettyRaftTransportHandshakeTimeoutTest`; (ii) `scheduleDrain`'s `eventLoop().execute(...)`
   was unguarded → a close/send race could throw `RejectedExecutionException` onto the tick thread →
   now guarded (reset the flag), mirroring `scheduleConnect`. Two INFO items are by-design/pre-existing
   (wire-`senderId` not cert-bound — documented DR-N18, identical to JDK, non-Byzantine model;
   empty-TLS-config guard — shared with JDK, fail-closed in prod).
   **Fresh-context Verifier — APPROVE, 0 must-fix.** Independently verified all 8 claim areas, re-ran the
   sweep (3/3) + gc-proof (2/2) green, tested the fast-revert live, confirmed CI 12/12. Its one
   should-fix was the SAME `scheduleDrain` REE guard (fixed); two nits accepted as documented choices
   (server always-mTLS matches the M3 fan-out DR-N13 + is the more-secure direction; outbound
   fire-and-forget close — the listen socket IS awaited, the leaked ephemeral FD is negligible).

### Performance (measured)
Allocation (the trustworthy axis): the production consensus encoder is **~0 B/op** idiomatic — it TIES
the JDK into-variant floor (verdict.md §Surface 4), neither winning nor regressing, exactly the
measured-neutral trade ADR-0043 accepted for the wire codecs. The migration's value here is
**uniformity + the io_uring substrate**, not a wire-codec speedup. io_uring deferred to Phase V.

### Honest residuals
- **CVE not yet run on this branch** (the standing env-blocked residual; charter §9). M4 adds **no new
  external dependency** — the SBOM delta is exactly one internal reactor edge (`configd-netty →
  configd-transport`, DR-N16), **0 new components**; the `io.netty` closure is unchanged since M1. The
  pre-production go/no-go still owes a real CVE scan (`NVD_API_KEY` + a network run) — noted, not ridden
  to the finish silently.
- **io_uring unmeasured** on this surface (Phase V); auto-selected here (the contract proves the
  forced-NIO fallback green too).
- **gc-proof ns/op is relative** (2-vCPU); allocation (B/op) is the trustworthy axis.
- **The no-spurious-election sweep uses a generous election budget** (1000–2000 ms, 20× the heartbeat
  tick ratio) for 2-vCPU robustness; the FIDELITY leg shows actual loopback delivery is 13–19× under
  that floor, so the budget is not masking transport slowness. Plaintext (TLS delivery is proven
  separately by the contract's mTLS legs; the timing proof isolates delivery).

### Branch-vs-`main` truth
M4 is on the **branch** `netty-migration-m4`, **NOT merged**. Production consensus is Netty on the
branch only; `main` still runs the JDK `TcpRaftTransport`. Merge is the human gate.

### Documented fast-revert
`git revert 1777511` (slice E) restores `ConfigdServer` to `new TcpRaftTransport(...)` — the field is
typed `RaftTransportEndpoint`, so the one construction line reverts cleanly; the JDK transport is
retained and fully tested by the contract's JDK subclass (Verifier tested this live). Verify:
`./mvnw -o -pl configd-server test -Dtest='ConfigdServerTest,JdkRaftTransportContractTest'`.

### ADRs / DRs
**ADR-0037 now FULLY superseded** (migration complete — all four surfaces on Netty); ADR-0043 updated
with the migration-complete status. DR-N15 (baseline), DR-N16 (extraction + module placement), DR-N17
(idiomatic ~0 encode + event-loop write), DR-N18 (mTLS both ways), DR-N19 (selector client channel),
DR-N20 (adapter generalization) — all in `decision-log.md`.

### Next stage
**Phase V** (io_uring syscall-reduction measurement, EC2-gated → spends money) is the ONLY remaining
Netty-arc item — preconditions: an io_uring-capable box, `strace`/`perf` syscall counts vs Epoll on the
high-volume surfaces (edge-read + fan-out), the honest delivered-now / latent-at-scale verdict. Do not
start it (deliberate stop). Also still owed (standing, pre-existing): configure `NVD_API_KEY` + run a
real CVE scan before the final production go/no-go.

---

## Session 4 — M3 (fan-out streaming) → Netty — DONE on branch, Verifier APPROVED, NOT merged

**CI: ALL 12 jobs GREEN — run `28135923407`** (build-and-test/JDK25, tlc, gate-1..7, gate-phase0,
gate-B, wire-compat). The first run (`28134729234`) was 11/12: **gate-7 caught a real test flake** —
the NEW `propagationDeliversVerbatimOrderedNotifiesAndRecovers` contract test used the strict
`policyConfig()` (demoteLimit=2) with `tinyQueueConfig()` (queueFrames=2), so the 300-publish no-ack
flood tripped a SECOND overflow demotion → QUARANTINED → connection close, racing the snapshot read
(a fast CI runner lost it: "stream closed while waiting for SnapshotBegin"; the slow 2-vCPU box won
it, and even build-and-test won it on the same run — a genuine race). Fixed in `06974d4` with a
permissive `propagationPolicyConfig()` so the demotion→snapshot→**resume** recovery path is
deterministic (quarantine-on-repeat keeps its own dedicated tests). The gate working as designed.

**Surface:** the edge fan-out streaming SERVER (`configd-server` `FanOutServer`, JDK
`SSLServerSocket` + 3 virtual threads/connection) → Netty 4.2. The edge CLIENT (`EdgeStreamClient`)
stays JDK (DR-N9): the measured win (the NOTIFY encode floor) + the slow-consumer policy are
server-side; mTLS "both directions" = mutual-auth enforced server-side; interop is preserved by the
shared byte-identical codec.

### Done (verified end-to-end, evidence each)

| Slice | What | Evidence |
|---|---|---|
| A (`500c313`) | Single-pass `EdgeFrameCodec.encodeInto(EdgeFrame, FrameSink)` (DR-N10) + `HeapFrameSink`; `encode()` delegates | `EdgeFrameCodecGoldenFixtureTest` green (byte-identical) |
| A | `-prof gc` floor proof | `m3-fanout-gc-proof.md`: prod `encodeInto` reused-heap **25,520.1 ≡ floor**; pooled-`ByteBuf` **25,760** (floor+240); baseline 69,492 |
| B (`5ff66fa`) | `FanOutConnectionDriver` extracted (session loop + governor feed + admission + cert-identity binding); JDK `FanOutServer` → thin adapter (DR-N11) | JDK contract 15/15 (faithful extraction) |
| C (`34284a7`) | `NettyFanOutServer` on `configd-netty`: SslHandler mTLS (DR-N13), `ByteToEdgeFrameDecoder` (peekLength), `EdgeFrameToByteEncoder` (in-pipeline pooled encode), bounded-in-flight sink (DR-N12) | compiles + contract |
| D (`9ad6a98`) | `AbstractFanOutServerContract` (15 methods) on JDK + Netty(io_uring) + Netty(forced-NIO); gate-7 repointed | **15 / 15 / 16** green |
| E (`eeb8955`) | `ConfigdServer` cut over to `NettyFanOutServer` (production fan-out = Netty); field typed `FanOutEndpoint` (DR-N14) | `FanOutServerIntegrationTest` 3/3 + `ConfigdServerTest` 22/22 on Netty |

**Re-proven IDENTICAL on the Netty pipeline, zero waivers:** mTLS negative (plaintext / no-cert /
untrusted-CA / expired-cert / TLSv1.2-downgrade all rejected; trusted accepted), the slow-consumer
state machine (demotion→catch-up→quarantine→disconnect→refuse→forced-rebootstrap; sustained-warn→
SLOW; quarantine→UNHEALTHY; per-identity isolation), admission `maxSessions` (refuse-before-handshake
+ counted), and the S2–S4 surface (verbatim ordered NOTIFY, version monotonicity / no-stale-overwrite,
demotion→chunked-snapshot→resume). Each runs on all three transports (15/subclass). The 4 incumbent
JDK fan-out test classes were folded VERBATIM into the contract. The real JDK `EdgeStreamClient`
interoperates with the Netty server (`EdgeNodeIntegrationTest` 3 + monotonicity/re-bootstrap legs).

### Second-agent (fresh-context Verifier): APPROVE — 0 must-fix, 0 should-fix, 1 nit (fixed)
Independently re-ran the contract from clean (JDK 15 / Netty-io_uring 15 / Netty-NIO 16 — confirmed
the host has io_uring=epoll=nio so the auto tier genuinely exercised **io_uring**, not a silent NIO
downgrade), re-measured the floor (prod `encodeInto` 25,520.1 ≡ floor; pooled 25,760.1; baseline
69,480), `comm -23`-diffed the 4 deleted test classes vs the contract → **fold is verbatim** (zero
assertions dropped), confirmed scope clean (zero pom.xml, no consensus/Raft/replication file touched,
`configd-distribution-service` Netty-free), and hunted for under-load divergence (backpressure
frame-count bound == JDK; mTLS identity = verified cert principal, fail-closed; peekLength bounds
before alloc; teardown idempotent + paired metrics; maxSessions before handshake) — **none found**.
The 1 nit (DR-N12 doc claimed a `WriteBufferWaterMark` the code doesn't configure) was corrected in
`06974d4` (the frame-count bound is the deliberate JDK-faithful backpressure).

### Performance (measured)
**Allocation (the load-bearing axis):** the production single-pass `encodeInto` hits the
**25,520 B/op message-building floor** byte-for-byte on a reused heap sink; idiomatic Netty (pooled
direct `ByteBuf`, in-pipeline `MessageToByteEncoder`) ties at **25,760** (floor + 240 holder
bookkeeping). The 69 KB→floor win is the codec rewrite (transport-independent); Netty removes none of
the floor and adds none of the 63% churn — exactly the head-to-head Surface-3 framing. Throughput is
a same-box wash (verdict). io_uring deferred to Phase V.

### Honest residuals
- **CVE/gitleaks not yet run on this branch** (nightly-only; the merge-gate precondition, shared with
  M1/M2). M3 adds **no new external dependency** (SBOM delta = **zero**; M3 changed **zero pom.xml**) —
  the `io.netty` modules `NettyFanOutServer` uses (handler/codec/ssl) were already resolved via
  `configd-netty` since M2. (Even cleaner than M2's `+configd-netty`.)
- **io_uring unmeasured** on this surface (Phase V); auto-selected here.
- **gc-proof ns/op is relative** (2-vCPU); the allocation floor is the trustworthy axis.
- **Edge CLIENT stays JDK** (DR-N9) — a deliberate scope choice, not a gap; the mutual-auth
  enforcement re-proven is server-side.

### Branch-vs-`main` truth
M3 is on the **branch** `netty-migration`, NOT merged. Production fan-out is Netty on the branch only;
`main` still runs the JDK fan-out server. Merge is the human gate.

### Documented fast-revert
`git revert eeb8955` (the slice-E cutover) restores `ConfigdServer` to constructing
`new FanOutServer(...)` (the JDK transport is retained, fully tested by the contract's JDK subclass,
and a drop-in `FanOutEndpoint`). Verification:
`./mvnw -o -pl configd-server test -Dtest='JdkFanOutServerContractTest,FanOutServerIntegrationTest'`.

### ADRs / DRs
ADR-0043 ratified further (fan-out migrated). DR-N9 (scope), DR-N10 (FrameSink single-pass), DR-N11
(FanOutConnectionDriver extraction), DR-N12 (Netty backpressure), DR-N13 (Netty mTLS), DR-N14
(FanOutEndpoint) — all in `decision-log.md`.

### Next stage
**M4 (consensus wire)** — the most dangerous: M3-coalesced-heartbeat timing, full S2–S4 +
no-spurious-election sweep on Netty, four-way rigor, ~0 B/op idiomatic encode. Keep `configd-netty`
out of consensus until M4. **Phase V** (io_uring measurement, EC2-gated) follows. Run a nightly
CVE/gitleaks on `netty-migration` before the merge gate (shared M1/M2/M3 precondition).

---

## Session 3 — M2 (admin / control-plane API) → Netty — DONE on branch, second-agent APPROVED, NOT merged

**Surface:** the admin / control-plane HTTP API (`configd-server` `HttpApiServer`, JDK
`com.sun.net.httpserver`, the write path) → Netty 4.2. Eight commits `d1b7032..e938b45` on
`netty-migration` (+ this gate report). Built behind the same DR-N2 pattern M1 proved: one
transport-agnostic decision core, thin adapters, equivalence re-proven by the identical contract per
transport.

### Done (verified end-to-end, evidence each)

| Slice | What | Evidence |
|---|---|---|
| A | New `configd-netty` platform module; `NettyTransport` selector promoted out of edge-node (DR-N6) | `d1b7032`; edge-node 97 + configd-netty 8 = **105** = M1 pre-move (faithful relocation) |
| B | `AdminApiHandler` extracted (transport-agnostic core); JDK `HttpApiServer` → thin delegating shell | `238ed57`; configd-server **165** green (faithful extraction) |
| C | `NettyHttpApiServer`: virtual-thread dispatch (handler blocks on Raft), strict per-connection serial ordering, `HttpObjectAggregator` (413), server-TLS `SslHandler` (C11), DoS hardening | `454bf21` |
| D | `AbstractAdminApiServerContract` — full S7 set on JDK + Netty(auto io_uring) + Netty(forced-NIO); + C7/C9/C10/C11 | `454bf21`+`85c977a`; **37 × 3** subclasses |
| E | `ConfigdServer` cut over to `NettyHttpApiServer` (production admin = Netty) | `0e3762e`; configd-server **250** + edge-node **97** (boots ConfigdServer w/ Netty admin in-process) |
| F | Allocation gc-proof (JDK-vs-Netty A/B) | `e59fea2`; Netty **~12–14 KB/req lower** server-side; gate met |
| G | SBOM regenerated; DR-N7 (routing) + DR-N8 (SBOM) | `f647b6a`; delta = **exactly +configd-netty**, 0 new external |

**S7 contract re-proven IDENTICAL on the new transport, zero waivers** — C1 authn (401+`WWW-Authenticate`),
C2 authz (403), C3 input-validation (400), C4 audit (completeness + no-token-leak + chain), C5 replay
(401/409), **C6 strong-read fail-closed incl. the 5 path-normalization evasion vectors**, C7 `/metrics`
Bearer gate, C8 write-result mapping, C9 429+`Retry-After`, C10 method 405, C11 server-side TLS. Each
runs on JDK + Netty + forced-NIO (37/subclass). The 4 incumbent JDK S7 classes were consolidated
**verbatim** (every assertion preserved; Verifier diff-confirmed); pure `StrongReadPolicy` units split to
`StrongReadPolicyTest`. **Equivalence by construction (C6):** the strong-read key is `URI.getPath()`
(percent-decoded, un-normalized) and both adapters build that `java.net.URI` identically
(`new URI(request.uri())` on Netty), so classification is byte-identical — proven, not hoped.
Hardening (`NettyHttpApiServerHardeningTest` 6/6): oversize header→400, body→413, slowloris incl.
dribble→closed, leak-free@PARANOID, keep-alive served.

**Second-agent (fresh-context Verifier): APPROVE — 0 must-fix, 0 should-fix, 3 nits.** Independently
re-derived the decision logic, re-ran all three suites from clean (8 / 250 / 97), diffed the deleted
incumbent tests against the contract (verbatim, zero dropped), confirmed C6/C7/C11 pass on Netty AND
forced-NIO from surefire XML, scope clean (no fan-out/consensus/replication touched), no under-load
divergence (ordering / buffers / error propagation), SBOM delta = +configd-netty only.

**CI: ALL 12 jobs GREEN — run `28120706077`** (build-and-test/JDK25, tlc-model-check, gate-1..7,
gate-phase0, gate-B, wire-compat). The first run (`28118506739`) was 11/12: gate-7's api **non-vacuity
guard correctly caught** that the S7 tests had been renamed (`ConfigHandlerAuthTest` folded into the
contract) — the gate working as designed. Fixed in `e938b45` by pointing the guard at the three
contract subclasses, which **strengthened** gate-7 (the S7 API controls — 401/403, replay 409, audit,
strong-read fail-closed — are now gated on the Netty + forced-NIO transports, not just the JDK
incumbent) and added `configd-netty` to the gate-7 MODULES set. gate-7's SBOM step regenerated +
normalized-diffed clean (no drift). `main` moves only at the human merge gate (a separate step — not
done).

### Performance (measured)
**Allocation:** Netty admin allocates **~12–14 KB/req less** than the JDK `com.sun.net.httpserver`
incumbent (server-side delta on an identical-client `-prof gc` A/B, auth off + on) — same root cause as
M1's edge-read 8.7× (JDK `HttpExchange` churn vs pooled buffers). The no-regression gate is met with
margin; admin joins edge-read as a *measured* allocation win. Honest caveats (`m2-admin-gc-proof.md`):
JVM-wide absolute (client-dominated), wide 2-vCPU bars (direction robust, magnitude a band),
**allocation only** (no throughput/latency claim), **io_uring deferred to Phase V**.

### Honest residuals
- **CVE/gitleaks not yet run on this branch** (nightly-only; didn't run on the dispatch). M2 adds **no
  new external dep** beyond M1's netty (SBOM delta = 0 new components), but a nightly run on
  `netty-migration` is the supply-chain **merge-gate precondition**.
- **io_uring unmeasured on this surface** (Phase V); auto-selected here but its syscall benefit is
  largely irrelevant to the low-QPS control plane — the value is uniformity (ADR-0043).
- **gc-proof bars wide** (2-vCPU loopback contention + warmup drift): the ~12–14 KB reduction is a
  band, not a point; direction consistent across all 4 legs.
- **Admin TLS is server-side only** (Bearer is the client identity), matching the incumbent JDK
  `HttpsServer`; mTLS stays a fan-out/consensus property (M3/M4, DR-N1). C11 proves the HTTPS
  round-trip on all three tiers.

### Deliberately not done (Prime Directive — one surface per stage)
M3 fan-out (Netty + single-pass `EdgeFrameCodec` rewrite + the DR-N1 "edge mTLS" + slow-consumer
re-proof); M4 consensus wire (most dangerous — coalesced-heartbeat timing, full S2–S4 + no-spurious-
election sweep); Phase V io_uring validation (EC2-gated; pass criteria still undefined); adding mTLS to
the admin API (a security/behaviour change, not a transport migration — separate operator decision).

### Branch-vs-`main` truth
The M2 code is on the **branch** `netty-migration` (`d1b7032..e938b45` + this gate report), pushed to
origin. It is **NOT merged to `main`** and is **NOT a cutover of `main`** — `main` still runs the JDK
admin server. Merge to `main` is the human gate: CI is now 12/12 green; the remaining precondition is a
**nightly CVE/gitleaks run on the branch** (the supply-chain checks are nightly-only and did not run on
the dispatch).

### Documented fast-revert (merge-gate requirement)
**Procedure:** `git revert 0e3762e` (the Slice-E cutover commit). This atomically restores
`ConfigdServer` to constructing the JDK `HttpApiServer` (field type + 13-arg construction + `stop(2)`
revert together — a partial construction-site edit will NOT compile, since the field is typed
`NettyHttpApiServer`; the commit-revert is the clean rollback). A feature flag was rejected because it
fights transport uniformity. **Expected post-revert state:** production admin API back on the JDK
`com.sun.net.httpserver` server; `configd-netty` / `NettyHttpApiServer` / the contract remain (unused
by production, harmless). **Verification:** `./mvnw -o -pl configd-server test` → 250 green (the JDK
contract subclass + `ConfigdServerTest` are a known-CI-green target — `HttpApiServer` is retained and
fully tested).

### ADRs / DRs touched
ADR-0043 ratified further (admin surface migrated). DR-N6 (configd-netty module), DR-N7 (admin
exact-match routing — the DR-N4 decision applied to admin), DR-N8 (SBOM delta) — all in
`decision-log.md`.

### Next stage
**M3 (fan-out)** — define its scope allowlist + S7 fan-out controls (slow-consumer policy + mTLS, the
DR-N1 "edge mTLS"); the single-pass `EdgeFrameCodec` rewrite is codec-internal (Netty-independent).
Higher-risk than M2 (inter-service streaming). Run a nightly CVE/gitleaks on `netty-migration` before
the M2 merge gate.

---

## Session 2 — the platform decision + M1 (edge-read) migration

**Steer change (DR-M0):** the prior session's measure-first recommendation was *surgical* (Netty for
edge-read only). The operator decided to **standardize ALL transport on Netty** —
[ADR-0043](../decisions/adr-0043-netty-transport-platform.md) supersedes ADR-0037 **wholesale**, on
the honest rationale **io_uring + platform uniformity + the measured edge-read 8.7× win**, explicitly
**accepting measured-neutral performance on the consensus/fan-out wire codecs** (the head-to-head
proved Netty ties JDK at ~0 there). NOT a "Netty is faster everywhere" claim.

### Done this session (foundation + M1 edge-read), all GREEN

| Item | What | Evidence |
|---|---|---|
| Phase R | `netty42-api.md`: 4.2 API + the **3-tier io_uring→Epoll→NIO runtime-detected selector** (the gap the head-to-head's 2-tier left) | [netty42-api.md](netty42-api.md) |
| ADR | ADR-0043 supersedes ADR-0037 wholesale, honest rationale; ADR-0037 marked Superseded | [adr-0043](../decisions/adr-0043-netty-transport-platform.md) |
| Decisions | DR-M0 (steer), DR-N1 (edge is **plaintext** — "edge mTLS" is the fan-out surface/M3, not M1), DR-N2 (extract transport-agnostic logic; equivalence by construction), DR-N3 (session scope) | [decision-log.md](decision-log.md) |
| M1.1 | Netty production deps in `configd-edge-node` (+ io_uring artifacts in root dependencyManagement) | poms |
| M1.2 | `NettyTransport` 3-tier selector (coherent factory+channel triple; fail-loud on forced-unavailable) | `NettyTransport.java`, `NettyTransportTest` (8 tests) |
| M1.3 | `EdgeReadHandler` (shared decision logic) + JDK `EdgeHttpServer` refactored to delegate + `NettyEdgeHttpServer` (Netty adapter, hardening) | the 3 classes |
| M1.4 | **ALL edge controls re-proven on Netty** by the identical contract matrix: `NettyEdgeHttpServerTest` **15/15** (= JDK `EdgeHttpServerTest` 15/15); hardening **5/5** (oversize header/body, slowloris incl. dribble, leak-free, legit keep-alive served) | `AbstractEdgeReadServerContract` + subclasses; full edge-node suite **87 tests, 0F/0E, 1 skipped** |
| M1.5 | **gc-proof: 8.7× HOLDS on production — 14,999 → 1,703.8 B/req = 8.80×** (tier io_uring). Red→green: naive deadline-rearm+sink regressed to 1,820 (8.24×); allocation-free redesign (handler-IS-sink + timestamp-watcher deadline) restored 1,704 | [m1-edge-read-gc-proof.md](m1-edge-read-gc-proof.md) |
| M1.6 | CI-fallback proof: `NettyEdgeHttpServerNioFallbackTest` runs the **full contract on forced NIO (15/15)**; all Netty tests are CI-gated via ci.yml's `mvn clean install`; CI runners (no io_uring) exercise the fallback + the fail-loud path naturally | the test + ci.yml |

### Key design facts (carry forward)
- **The edge-read surface is PLAINTEXT** (client-facing read API). `EdgeNodeMain` gives `TlsManager`
  to the fan-out `EdgeStreamClient`, not to the HTTP server. So M1 preserved/re-proved the controls
  it actually has (strong-read fail-close, not-subscribed/cursor-behind refusal, staleness-on-all-
  reads, `/metrics` Bearer gate, method 405, the INV-M1 seam) + added DoS hardening. **mTLS belongs
  to M3 (fan-out).** (DR-N1.)
- **Equivalence by construction:** both transports delegate to `EdgeReadHandler`; the JDK adapter
  staying green on the *unchanged* `EdgeHttpServerTest` proves the extraction is faithful, then the
  Netty adapter runs the identical matrix. (DR-N2.)
- **io_uring was selected on this box** (kernel 7.0) yet allocation is unchanged — io_uring's win is
  **syscalls, not bytes** (Phase V's axis, EC2-gated, not yet run).
- **Allocation discipline is load-bearing:** naive Netty hardening regressed the floor; idiomatic
  (no per-request `schedule()`/sink object) restored it. Same lesson as the head-to-head.

### M1 closeout (this session) — DONE
- **Second-agent replay: APPROVE, 0 must-fix** (java-distinguished-engineer; independently re-ran all
  tests, traced equivalence, live-probed hardening on the io_uring server, dropped-control hunt empty).
  One finding F1 (routing exact-match tightening) reconciled → DR-N4 + a pinning test.
- **M1.7 swap DONE:** `EdgeNodeMain` now wires `NettyEdgeHttpServer`. Full edge-node suite **105 tests,
  0F/0E, 1 skipped** — incl. the integration/process tests (EdgeNodeIntegrationTest, EdgeFailoverTest,
  EdgeBootstrapUnderSustainedWrites, GameDayDrill, ReBootstrap, PoisonPill) now booting Netty in-process.
- **Committed `b6aadf7` (M1) + `bbf3d2f` (SBOM), pushed. CI GREEN: run 28053243253 — ALL 12 jobs
  success** (build-and-test/JDK25, gate-1..7, tlc, wire-compat, gate-phase0, gate-B). The first run
  (28051558448) was 9/10 green, failing only gate-7's SBOM normalized-diff — adding Netty as a
  *production* dep drifted the committed CycloneDX SBOM (`docs/session-7/sbom/bom.json`); regenerated
  it (delta = exactly +16 io.netty 4.2.15 components, 0 removed; DR-N5). `main` moves only at the
  CI-green merge gate (a separate explicit step — not done this session).
- **Footprint note (follow-up):** netty-handler pulled `netty-codec-marshalling`/`-protobuf`/
  `-compression` transitively (unused by the HTTP read path) — candidates for `<exclusions>` in a
  later supply-chain hardening pass if the CVE surface warrants; the SBOM records them faithfully.

### Performance (measured this session)
- **Allocation (trustworthy, CPU-independent — the load-bearing axis):** JDK 14,999.2 → Netty
  **1,703.8 B/req server-side = 8.80×** (~13.3 KB/req less). io_uring tier selected; same shell cost
  (io_uring's win is syscalls, Phase V). Idle floor 56 B/s (noise).
- **Throughput / tail latency:** RELATIVE-ONLY on this contended 2-vCPU box (client+server share it) —
  a **wash** (JDK ~3,130 req/s p99 7.6 ms; Netty ~3,140 req/s p99 7.9 ms). NOT production numbers; the
  verdict rests on allocation, exactly as the head-to-head found.

### Remaining for the migration (later sessions)
- **M2 admin API** (re-prove ALL of S7: authn/authz/audit/replay/429/strong-read on Netty).
- **M3 fan-out** (Netty transport + the single-pass `EdgeFrameCodec` rewrite + mTLS + slow-consumer
  policy re-proven; the "edge mTLS" of DR-N1 lives here).
- **M4 consensus wire** (most dangerous — M3 coalesced-heartbeat timing; full S2–S4 + no-spurious-
  election sweep on Netty; four-way rigor; ~0 B/op idiomatic encode).
- **Phase V — io_uring validation** (EC2-gated; MEASURE the syscall-reduction benefit; honest
  delivered-now / latent-at-scale verdict).

## How to reproduce
- Edge-read functional + hardening + selector + fallback:
  `./mvnw -o -pl configd-edge-node test -Dtest='EdgeHttpServerTest,NettyEdgeHttpServerTest,NettyEdgeHttpServerNioFallbackTest,NettyEdgeHttpServerHardeningTest,NettyTransportTest' -Dsurefire.failIfNoSpecifiedTests=false`
- gc-proof: build `benchmarks.jar` (`./mvnw -pl configd-testkit -am package -DskipTests`), then run
  `EdgeReadAllocServerMain {jdk|netty-prod} ...` + `EdgeReadLoadClientMain ...` (commands in
  [m1-edge-read-gc-proof.md](m1-edge-read-gc-proof.md)).

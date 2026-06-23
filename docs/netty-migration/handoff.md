# Netty-migration — handoff

> **This doc is the living continuity record (latest session at top).** Prior evidence artifacts
> (`baseline.md`, `inventory.md`, `decision-log.md` DR-1..5, the `docs/jdk-vs-netty/` head-to-head)
> are immutable; this summarizes the current state and what remains.

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

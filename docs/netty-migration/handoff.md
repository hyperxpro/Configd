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

### Remaining for M1 (immediate next steps)
- **Second-agent replay** (charter gate) — IN PROGRESS at handoff; the swap is gated on its APPROVE.
- **M1.7 swap:** point `EdgeNodeMain` at `NettyEdgeHttpServer` (field type + construction +
  `start()` throws InterruptedException + `stop()` no-arg). Then run the full edge-node + integration
  suite (EdgeNodeIntegrationTest, EdgeFailoverTest, EdgeBootstrapUnderSustainedWritesProcessTest,
  GameDayDrillTest, …) which then exercises the Netty server in-process. **Until that flip, production
  is on the JDK server (a clean, not-half-migrated seam).**
- **Push the branch → CI green** (RR-097 rule: no close with unpushed branches / unverified CI
  claims). `main` moves only at the CI-green merge gate (a separate explicit step; not this session).

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

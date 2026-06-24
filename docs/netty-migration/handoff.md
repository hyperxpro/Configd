# Netty-migration — handoff

> **This doc is the living continuity record (latest session at top).** Prior evidence artifacts
> (`baseline.md`, `inventory.md`, `decision-log.md` DR-1..5, the `docs/jdk-vs-netty/` head-to-head)
> are immutable; this summarizes the current state and what remains.

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

# C6 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C6 end-to-end-integration component sign-off against charter §1
> rule 2's DONE-definition and the charter §4 C6 text (which is C6's spec — no
> pre-implementation draft exists, per the note's own header). As-built note:
> `docs/session-3/design/c6-e2e-design-note.md` (commit `1c39615`, HEAD at review time,
> clean tree at review start). **Reviewer:** review-architect. **Date:** 2026-06-12.
> **Branch:** session-3-data-plane. Files written: this one + the RR-001 register row
> (the charter §6 rule 6 closure, JOB B — written personally, as assigned by name).
> Runs performed by this reviewer, serialized per the 2-vCPU rules (busy-check
> `pgrep -f "[a]pache-maven|[s]urefirebooter"` clear before the build; Compose run
> strictly AFTER the build completed, never concurrent): one shaded-jar build
> (`./mvnw -o -pl configd-server,configd-edge-node -am clean package -DskipTests` —
> the targets were bare from a prior clean, so the committed capture's jars did not
> exist on disk; my run rebuilt them from current HEAD), then **an independent
> end-to-end run of `gates/e2e-compose-scenario.sh`: 19/19 PASS, `SCENARIO_EXIT=0`,
> 06:54:12→06:55:56 (~104 s wall), full teardown verified (`docker ps -a` empty of
> `configd-e2e-*` afterwards)**. The 10k sweep and the RR-095 re-run were NOT re-run
> (capture + property-gated committed tests accepted — no inconsistency found that
> would demand it; the C3/C4/C5 precedent for owner-recorded heavy runs).

Severities: **BLOCKING** (gates the sign-off), **REQUIRED** (must land, tracked, does
not gate), **NOTE** (advisory). Every finding carries a prod-blocking / non-blocking flag.

## Verdict: **SIGNED-OFF**

C6 is the component the whole session existed to make possible, and it is real. The
scenario script is the most honest E2E harness I have reviewed in this pipeline: every
one of the 19 assertions is a deadline-bounded poll or an explicit exit-code check that
routes through `fail` (exit 1); I hunted specifically for the three classic E2E lies
(assertions that can't fail, greps that match on absence, deadline polls that swallow
the assertion on timeout) and found none. The monotonic watch in phase 2 genuinely
samples across the failover window. The phase-4 sweep genuinely byte-compares all four
edges against a ReadIndex-confirmed linearizable leader read. The mTLS is the real CLI
path, not a test seam. And the strongest evidence is my own: **I re-ran the full
scenario independently from freshly built jars and it passed 19/19, exit 0.** One
REQUIRED doc-accuracy finding (the note overclaims one assertion's scope), two NOTEs.
RR-001 closure written (JOB B — see the register row and §JOB B below).

### Conditions of this sign-off

| # | Condition | Severity | Gate | Status |
|---|---|---|---|---|
| **C6-A** | Note §2 phase-1 accuracy: the `secure/` 503 fail-closed check runs at **edge1 only** (`e2e-compose-scenario.sh:293-296`, `EDGE_PORTS[0]`); the note says "verified at every edge". Either extend the script's check to loop all edges (3 cheap curls) or correct the note's sentence at next touch. The mechanism is identical code on identical images at every edge, and CT-37's PASSING status rests on the C2 process tests, not this bonus check — so this is doc honesty, not a coverage hole. | **REQUIRED** (non-blocking) | Next script/note touch | OPEN |

No BLOCKING conditions. CT-39's map flip is the contract-qa side of the dual sign-off
(the audit, not this review or the note, flips the map — note §8 says so itself,
correctly).

---

## Check 1 — Independent E2E re-run: **DONE — 19/19 GREEN, exit 0**

Sequence: busy-check clear → shaded jars rebuilt from bare targets (so my run proves
**current HEAD** builds and passes, not a stale artifact) → `bash
gates/e2e-compose-scenario.sh` → 19/19, `SCENARIO_EXIT=0`, ~104 s. My run's dynamic
values differ from the committed capture exactly as they should (leader cp1 killed →
cp2 elected; marker seqs 13/199/264 vs the capture's 7/275/349; `snapshots_applied=1`
identical) — evidence the assertions bind to live state, not to canned values. Cleanup
trap left zero containers. The capture
(`docs/session-3/captures/e2e-compose-scenario-run.txt`) is consistent with the script
at HEAD: PASS-line texts match the `pass` call sites one-for-one, 19 is the exact
count of `pass` invocations in the script (counted, not trusted).

## Check 2 — Scenario script honesty (read whole, hunted for the classic lies): **PASS**

- **No assertion can vacuously pass.** Every `poll_until` returns 1 on timeout and every
  call site chains `|| fail`; `fail` exits 1 (no swallowed timeouts). Metric scrapes
  (`edge_metric`) exit non-zero when the series is ABSENT (`else exit 1` in the awk),
  so greps cannot match on absence. `docker kill`/`network disconnect`/`network
  connect`/`compose up` all `|| fail`.
- **Phase 2's monotonic watch is genuine.** Watchers start BEFORE the kill and the
  script requires evidence the watch is live (≥5 samples polled, deadline-bounded)
  before `docker kill` fires; they are stopped only AFTER writes resume through a new
  leader, every edge serves `marker-p2` at ≥ its committed seq, and staleness has
  returned to CURRENT — i.e. the sample log spans kill → re-election → convergence.
  `assert_monotonic` then replays the whole log with a min-samples **non-vacuity guard**
  (≥10 per edge; the in-script comment honestly documents why 10 — a fast failover is a
  healthy outcome, not a reason to fail the watch) and fails on ANY decrease. The
  sampled value is the `X-Configd-Cursor` response header, which `EdgeHttpServer` stamps
  on every response including misses/refusals (verified in code, `EdgeHttpServer.java:36-37,70`)
  — so sampling continues through the degraded window rather than pausing during it.
- **Phase 4's sweep is vs a genuinely linearizable read.** `?consistency=linearizable`
  routes to `readService.linearizableRead` and returns 503 when leadership/ReadIndex
  cannot be confirmed (`HttpApiServer.java:265-299` — read, not assumed); the script
  resolves the leader by commit-confirmed probe PUT (200 = quorum commit, the
  RR-004/ADR-0033 definition) and pins `--cacert`. Edge-side reads carry
  `X-Configd-Cursor: $FENCE_SEQ` after every edge has been polled to the fence seq, so
  a behind edge cannot satisfy the comparison with stale bytes. Audit keys = all 4
  markers + fence + the full 40-key writer keyspace. See Finding F2 for the one
  hardening NOTE (skip-on-non-200 vacuity edge).
- **Phase 3 is observation-honest under partition:** `docker exec` scrapes (network-
  independent), ladder thresholds match `StalenessTracker` (DEGRADED >5 s = state 2,
  DISCONNECTED >30 s = state 3, CURRENT = 0 — encoding verified in source), the
  re-bootstrap assertion is a strict **delta** over a pre-partition baseline, ready-503
  at DEGRADED+ is asserted inside the container, and heal reuses the static IP so the
  published-port NAT path stays valid (the documented Docker constraint).
- **Phase 0 mechanizes the Session-2 shaded-jar trap:** classes-in-jar probes for
  `FanOutSessionCore`/`FanOutServer`/`EdgeClientCore` plus a `javap` field probe for the
  RR-104 `pendingDemotionNotice` — a stale jar fails in seconds, not four phases in. The
  Maven busy-check uses the corrected wrapper-aware pattern.
- Script-hardening defects the note admits (bare-`wait` deadlock; the min-sample bar) are
  commented at the exact sites claimed (`cleanup`, `assert_monotonic`).

## Check 3 — Topology and the mTLS reality (`deploy/compose/*`): **PASS**

3 CP + 3 edges + edge4 under `profiles: [bootstrap]`, heaps sized for the box, ports on
loopback, fan-out port internal. **This is the production CLI TLS path, not the injected
seam:** compose passes `--tls-cert/--tls-key/--tls-trust-store` (+ `--signing-key-file`
/ `--verify-key`), and `setup-secrets.sh`/`SecretsTool.java` exist precisely to defeat
the keytool-vs-`TlsConfig.mtls` empty-password mismatch — the repack writes empty-
password PKCS12 **and `verifyLoadsEmpty` proves each artifact loads exactly the way
`TlsManager` will load it**. The C2 note §8 gap this closes is real and verbatim
("the edge CLI TLS path (`TlsConfig.mtls`) is not exercised with a real handshake" —
c2 note :161-162); it is now exercised on both sides, in 7 JVMs, with edges presenting
CN=edge-N client certs as the C1 authoritative identity. The shared Ed25519 signing key
mounted into all three CP nodes is the correct (and correctly documented, note §7)
topology requirement — failover signature verification would break per-node. Secrets
are git-ignored (`deploy/compose/.gitignore`, verified with `git check-ignore` against
the real generated keys on disk) and docker-ignored (Check 6).

## Check 4 — Captures vs the note's claims: **PASS (cross-checked line-by-line)**

- `e2e-compose-scenario-run.txt`: 19/19 + `SCENARIO_EXIT=0`; per-phase PASS lines match
  note §2's claims exactly (kill cp1 → cp2; ladder walk; rebootstrap fired;
  `snapshots_applied_total=1`; byte-equal audit).
- `rr-095-integrated-rerun.txt`: all 7 seeds `cpLeaderElected=false cpFaults=8`,
  0 safety/delivery violations — matches note §3's "still-stalls, NO CHANGE" and the
  S2 characterization columns; the capture's honesty notes (vacuous `edgeConverged=true`
  at v0, `deliveredCount=0` making it visible; RR-103 named for S4) match the register
  row's RESOLVED-text claims verbatim. The test's hard-coded `safetyViolations=0` print
  is structurally justified: `sim.run()` throws (naming the seed) on ANY breach, so the
  line is unreachable otherwise — checked, not assumed.
- `edge-integrated-10k-sweep.txt`: summary line matches note §4 to the digit
  (`seeds=10000 wall=107.3s safetyViolations=0 … convergedGivenQuiet=3299/3397 (97.1%)`);
  98 quiet-window miss seeds = 3397−3299 (arithmetic checked); charter §7 bar (10k
  integrated, zero safety, liveness seeds listed) met as written. Not re-run (my
  judgment call 2: the capture + the committed property-gated test + the consistency
  with the 507-gate rates suffice).

## Check 5 — RR-104 fix + register row: **PASS (red/green confirmed by reading; never-refused path pinned)**

- The defect was real: pre-fix `demote()` routed the DEMOTED_TO_CATCHUP notice through
  `emit()`, whose refusal semantics for non-NOTIFY frames mark CLOSED + record
  `onSessionClosed("transport_gone")` (`emit()` read at HEAD, `FanOutSessionCore.java:528-539`
  — the close-marking branch is still there for genuinely-fatal control frames), and the
  demote tail resurrects to CATCHUP. Under TRANSPORT_BLOCK the queue is full by
  definition — refusal near-certain. Exactly the registered C5 F1.
- The fix is the RR-102 doctrine verbatim: refused offer parks in
  `pendingDemotionNotice`; `tick()` re-offers it FIRST in CATCHUP and returns if still
  refused, so the snapshot envelope cannot precede the notice — wire order preserved by
  control flow, not by luck. "At most one outstanding by construction" verified: all
  four `demote()` call sites live in `drainStreaming` (STREAMING-only), and STREAMING
  resumes only after the owed notice + transfer complete.
- The red/green claim is structurally sound: `DemotionNoticeBackpressureTest` leg 1 uses
  a capacity-1 sink, so the notice offer at demote() time is GUARANTEED refused; its
  `sessionClosedReasons == []` assertion fails against pre-fix `emit()` semantics by
  inspection of the pre-fix code. Exactly-once delivery, notice-before-BEGIN index
  ordering, resume-to-STREAMING, and cutover seq are all asserted. Leg 2 pins the
  **never-refused path unchanged** (GAP demote with an unbounded sink: notice emitted in
  the demote tick itself, before the envelope) — the byte-identity guard the register
  row claims. P3 owner-red/green suffices per register discipline; I did not re-run the
  revert cycle, and the row honestly says the C6 sign-off confirms at this grade. Row
  text is accurate in every checkable particular, including the mechanized jar probe
  claim (Check 2). Live-path freshness: my own E2E run passed the `pendingDemotionNotice`
  javap probe against the jars I built.

## Check 6 — Property gating + gate path untouched: **PASS**

`Rr095StallSeedsIntegratedRerunTest` is `@EnabledIfSystemProperty(configd.rr095.rerun)`,
`EdgeIntegratedNightlySweepTest` is `@EnabledIfSystemProperty(configd.edge.nightly)` —
both inert in normal reactor/gate runs. The nightly sweep enforces safety (throw names
the seed), records liveness (the gate-sweep discipline), and carries two non-vacuity
asserts (delivery on >half the seeds; quiet-window seeds exist). The commit's diff
touches NO gate script and NO gate test: `gates/gate-1.sh`/`gate-2.sh`/the 507-seed
manifest/`EdgeAdversarialGateSeedSweepTest`/`EdgeSeedCompatTest` are all absent from
`git show 1c39615 --stat` — the gate path is byte-identical by diff, independent of the
claim.

## Check 7 — Dockerfile de-fictioning + `.dockerignore`: **PASS (claim was real, is closed)**

The secrets-into-context claim was real: both `docker/Dockerfile.{build,runtime}` use
the repo root as context with `COPY . .`, no `.dockerignore` existed pre-C6, and
`deploy/compose/secrets/` holds genuine private keys on disk (verified) — so any
post-secrets-generation image build would have shipped `.git` + every `target/` + the
keys into the daemon's context. The new root `.dockerignore` excludes exactly those
(`.git`, `**/target`, `deploy/compose/secrets`). The other four fixes verified in the
diff: the three missing module poms added to BOTH Dockerfiles' COPY lists
(edge-node/linz/jcstress — the stale list would fail the reactor), `original-*`
pre-shade jars excluded from the flat lib dir (duplicate classes on the classpath),
`-XX:+ZGenerational` dropped (removed in JDK 24), hard-coded heap → `MaxRAMPercentage`.
"Verified to build; runtime image runnable" is the committer's recorded claim (deviation
1 honestly notes the E2E deliberately does NOT use these images; I did not rebuild them
— they are not on the E2E or gate path).

---

## Findings

| # | Finding | Severity | Prod-blocking? |
|---|---|---|---|
| F1 | Note §2 says the `secure/` 503 fail-closed bonus check is "verified at every edge"; the script checks edge1 only (`:293-296`). Condition C6-A. | **REQUIRED** (doc accuracy / 3-line script extension) | No — CT-37's standing rests on the C2 process tests; identical code+image at every edge |
| F2 | Phase-4 audit hardening: `truth` and `code` are fetched with two separate leader curls (a leader change or transient error between them skips the key silently — vacuity direction — or produces a spurious mismatch — fail-safe direction), and there is no minimum-audited-keys guard, so a pathological all-skip run would pass vacuously. In practice the fence + 4 markers are commit-confirmed and edge-polled seconds earlier, so the audit cannot realistically go vacuous; still, one combined `-w` request plus an `audited ≥ 5` assert would close the gap mechanically. | NOTE | No |
| F3 | Stale comment post-RR-104: `drainStreaming`'s `return true; // the DEMOTED_TO_CATCHUP frame was emitted` (`FanOutSessionCore.java:293`) — the frame may now be PARKED, not emitted. The `return true` semantics (suppress same-tick heartbeat) remain correct either way; comment-only. | NOTE | No |

## JOB B — the RR-001 closure (charter §6 rule 6)

Written personally into the RR-001 register row (status OPEN → RESOLVED), as assigned.
Basis: the joint proof the rule demands — the E2E scenario at runtime (independently
re-run by this reviewer, 19/19) + the contract→test map (zero UNIMPLEMENTED, every
clause PASSING/owned-PARTIAL/ADR/N-A, audited per component c1→c5 with c6 in the dual
sign-off now). See the register row for the full justification text.

— review-architect, 2026-06-12

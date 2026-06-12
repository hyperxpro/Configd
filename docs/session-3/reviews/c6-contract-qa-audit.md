# C6 Contract-QA Audit — row-by-row against the contract-test-map + the session-end whole-map sweep

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-12. **HEAD:** `1c39615`.
> **Scope:** component C6 as landed (`1c39615`: Compose E2E scenario 19/19 green captured;
> RR-095 integrated re-run; 10k integrated sweep 0 safety violations; RR-104 fixed;
> as-built note `docs/session-3/design/c6-e2e-design-note.md`, §8 row claims) audited
> against `docs/session-3/contract-test-map.md` — **plus the whole-map end-state sweep
> this audit owes the session as its LAST component audit** (charter §3 V3: "the session
> ends when every row is PASSING or ADR-renegotiated").
>
> **Method (evidence discipline):** every claim below was verified by (1) reading
> `gates/e2e-compose-scenario.sh` in full (464 lines — my lens is clause coverage and the
> assertions' probative value for the rows flipped; the deep script audit is the
> review-architect's), `deploy/compose/compose.yaml` (service command blocks — the
> production-defaults and prefix-subscription questions), and the new test bodies in full
> (`Rr095StallSeedsIntegratedRerunTest`, `EdgeIntegratedNightlySweepTest`,
> `DemotionNoticeBackpressureTest` by surefire result + register row); (2) cross-checking
> all three captures against the as-built note §2–§4 line by line (below); (3) reading the
> production sources where a claim depends on them (`ConfigdServer`'s `FanOutServer`
> construction — `FanOutConfig.defaults()` at the CLI path, no tuning flags in compose;
> `EdgeNodeConfig` for the `--subscribe-prefix` flag's existence); (4) the charter §4 C6
> (:143-149), §5, §7 DoD, the register rows RR-095/RR-103/RR-104 as committed; and (5) a
> fresh targeted surefire run (below). For the whole-map sweep: a mechanical
> existence check of every test class name cited in the map against the tree (nested
> classes resolved by hand), plus a re-read of every PARTIAL/ADR row's named remainder.
>
> **2-vCPU discipline:** busy-check `pgrep -f "[a]pache-maven|[s]urefirebooter"` (the
> CORRECTED pattern — the old bracketed `org.apache.maven` advice matches nothing on the
> 3.9.9 wrapper) verified CLEAR, and `docker ps` verified EMPTY (no review-architect
> Compose topology up), before my single module-scoped run. No full reactor, no PIT, no
> Compose run by me (the scenario is the review-architect's to re-run; my evidence is the
> capture + script + the lead's recorded full-reactor green at `1c39615`).
>
> **Parallel-work disclosure (mid-audit observation):** partway through this audit the
> working tree changed under me — an UNCOMMITTED `gates/gate-3.sh` appeared (the lead's
> assembly; its header names this audit file as the PARTIAL-rows authority and a
> contract-qa-owned `gates/gate3-map-expectation.txt`), along with uncommitted
> modifications to `LivePropagationProbeMain.java` (+199 lines — the probe EDGE mode),
> `ci.yml`, `configd-testkit/pom.xml`, and `EdgeBootstrapUnderSustainedWritesProcessTest`
> (±13 lines). **None of that is evidence here**: every verdict in this audit is against
> `1c39615` as committed. Consequences are recorded per-row (CT-34, CT-38, CT-02) and in
> the end-state sweep; one hygiene flag is raised for the gate-3 landing's auditor
> (sweep finding 4).

## Surefire evidence snapshot (all green; my run 06:57)

| Suite | Module | Tests | Failures |
|---|---|---|---|
| `DemotionNoticeBackpressureTest` (RR-104 red/green pin) | distribution-service | 2 | 0 |
| `Rr095StallSeedsIntegratedRerunTest` (`-Dconfigd.rr095.rerun=true`) | testkit | 1 (7 seeds) | 0 |
| `EdgeSeedCompatTest` (gate-path byte-identity after the RR-104 fix) | testkit | 1 | 0 |

The RR-095 re-run's seven `RR095-RERUN:` output lines are **byte-identical** to
`captures/rr-095-integrated-rerun.txt` — the capture reproduces deterministically on this
box. (The 10k sweep and the Compose scenario were NOT re-run — 107 s and ~6 min
respectively, the capture + the lead's run are the record; 2-vCPU rules.)

## Capture cross-checks (the audit's verification of the note's §2–§4 claims)

1. **`e2e-compose-scenario-run.txt` vs note §2:** the 19 PASS lines map exactly to the
   four phases as claimed — phase 0 = PASS 1–5 (jar freshness incl. the RR-104
   `pendingDemotionNotice` javap probe; images; 3 CP ready; leader resolved; 3 edges
   ready), phase 1 = PASS 6–7 (bounded-read propagation at seq=7; `secure/` 503), phase 2
   = PASS 8–12 (new leader cp2; all edges past seq 31; CURRENT everywhere; monotonic
   watch; cp1 rejoined), phase 3 = PASS 13–16 (DEGRADED; ready 503; DISCONNECTED +
   re-bootstrap counter; heal/converge at seq≥275), phase 4 = PASS 17–19 (snapshot=1;
   live-stream cutover at seq≥349; byte-equal audit). `SCENARIO_EXIT=0`. Script-side
   verification of what each PASS asserts: the bounded read is a real
   `X-Configd-Cursor`-headed GET (404 cursor-behind until caught up — the CT-12
   mechanism); the watch samples the cursor from EVERY response class with a min-samples
   non-vacuity guard and replays the log for any decrease; the ladder observations ride
   `docker exec` metric scrapes (partition-proof); the audit phase reads with
   cursor=fence and byte-compares against `?consistency=linearizable` leader reads.
2. **`rr-095-integrated-rerun.txt` vs note §3:** 7/7 seeds `cpLeaderElected=false
   cpFaults=8`, 0 safety / 0 delivery violations, `edgeConverged=true` flagged vacuous at
   v0 in the capture's own honesty notes — exactly the note's claims; register row RR-095
   updated consistently ("S3 cross-check DISCHARGED at sim level"; RR-103 named for S4).
   Re-verified by my own run (byte-identical lines).
3. **`edge-integrated-10k-sweep.txt` vs note §4:** the summary line matches verbatim
   (`seeds=10000 wall=107.3s safetyViolations=0 cpElected=9984 cpStalls=16
   quietWindowSeeds=3397 convergedGivenQuiet=3299/3397 (97.1%) rawConverged=6187/10000
   (61.9%) seedsWithDelivery=9854 deliveryViolations=82`); internal arithmetic checks out
   (10000−9984=16 stall seeds listed; 3397−3299=98 quiet-window miss seeds listed); the
   test body confirms safety is per-tick THROWING (`EdgeFanOutSim.run()` →
   `SimInvariants.SafetyViolation` naming the seed) so `safetyViolations=0` is
   structural, and liveness is recorded-not-failed (the gate-sweep discipline), with
   non-vacuity asserts (`seedsWithDelivery > count/2`, `quietWindowSeeds > 0`). Topology
   verified to be the EXACT 507-gate constructor (5 CP, 3 edges, 1200 ticks, edge faults,
   real `C1StreamDriver`, `defaultIntensity`, `EdgeInvariants.BOUND_MS`).

## Row-by-row findings

### CT-39 (the session headline) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The named remainder — recorded since the C1 audit and confirmed exact at the C5 audit —
was "the Compose-scale E2E (3 CP + ≥3 edge processes, production defaults) + the RR-095
stall-seed re-run". Both delivered, both verified:

- **Compose-scale, real processes, real CLI paths:** 3 CP + 3 edges (+ edge4 joiner), 7
  JVM containers, all mTLS including the previously-untested CLI TLS path (the
  `SecretsTool` empty-password PKCS12 repack closes the C2 note §8 gap — the C2 audit's
  recorded "pre-existing debt class" honest corner is now retired at topology level).
- **Production defaults verified at source, not assumed:** `compose.yaml`'s server
  command blocks carry NO fan-out tuning flags, and `ConfigdServer` constructs
  `FanOutServer` with `FanOutConfig.defaults()` on the CLI path — the c1-audit gap-3
  rule ("Compose runs must use production defaults, not sim-tuned thresholds") is met.
- **All four charter scenarios, each with teeth** (see capture cross-check 1): sustained
  writes (a background writer with retry-across-churn, ~10 commits/s on this box);
  leader SIGKILL mid-stream with the per-edge monotonic cursor watch spanning the whole
  window; partition → full staleness ladder → re-bootstrap trigger → heal → convergence;
  fresh-edge bootstrap mid-load through the C3 cursor-0 SNAPSHOT_FIRST path, ending in a
  byte-equal all-edges-vs-linearizable-leader audit. Zero sleeps-as-sync (every wait is
  a deadline-bounded poll; `sleep` appears only as poll interval), cleanup trap,
  shaded-jar freshness probes.
- **RR-095 re-run + 10k sweep:** charter §4-C6 and §7 obligations discharged with honest
  captures (vacuous-convergence flagged, liveness seeds listed, register edits left to
  the lead). My own re-run reproduced the RR-095 capture byte-identically.

RR-001 itself stays OPEN pending the review-architect's personally-written closure
justification (charter rule 6) — this flip supplies the map half of that joint proof and
deliberately does not pre-empt the other half.

Honest scope recorded in the row: one captured scenario run (gate-3 re-runs it);
server-side `edge_fanout_demotions_*` not asserted (unreachable at scenario write rates —
note §7 names it; S4 chaos with tuned thresholds); the once-owed test name
`EdgePropagationEndToEndTest` landed as the scenario script (the established
cite-what-exists rule); no prefix-subscribed edge in the topology.

### CT-09 (INV-M1, "any edge node") — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The row's named remainder — "the C2 process surface + multi-edge sim once the wire
exists" (assigned "C6 multi-edge" by the C5 audit) — is now closed on composed evidence:

- **PROCESS, multi-edge, same client:** the scenario is ONE client carrying one cursor
  across FOUR edge processes. Every phase's bounded read presents a committed seq at
  every edge and is satisfied only by a 200 at ≥ that seq (the only sub-cursor response
  the C2 surface can give is the CT-12-pinned 404 refusal, and `edge_serves_at`'s
  body-equality cannot be met below the cursor — the marker applied implies version ≥ its
  seq). The quiesce audit presents cursor=fence at all 4 edges over 45 candidate keys
  against linearizable leader truth. The per-edge watch samples `X-Configd-Cursor` from
  every response class across the kill window and replays for any decrease, with a
  min-samples liveness guard.
- **Multi-edge SIM:** the 10k integrated sweep runs read-side INV-M1 (the test-mode
  monitor wired into every edge actor's read store) + per-edge version monotonicity
  inside every tick, 3 edges per seed — 0 safety violations.

Honest corners recorded in the row (none load-bearing): the cursor source is the write
ack/fence, not a prior READ at another edge — equivalent under §3's mechanism, since the
cursor is the only client-carried state and CT-11/CT-12 pin the handshake/refusal
semantics; the script does not re-assert per-response refusal discipline (that is
process-pinned at CT-12/CT-35 and inherited, not re-proven).

### CT-26 (PASSING since C1) — notes-only: RR-104 closed in this row's clause space

The C5 sign-off's F1 was a defect in exactly this row's observability claim ("explicit
overflow→demotion ... never a silent drop without cursor evidence"): `demote()`'s notice
flowed through `emit()`, so a full outbound queue fired a phantom
`onSessionClosed("transport_gone")` plus a close/resurrect inconsistency. The C6 fix
follows the RR-102 would-block doctrine verbatim (`pendingDemotionNotice` parked,
re-offered ahead of the owed snapshot, ≤1 outstanding by construction, wire order
preserved); red-first `DemotionNoticeBackpressureTest` reproduced the phantom close
pre-fix and is green post-fix — **re-run green by this audit**, alongside
`EdgeSeedCompatTest` (gate-path byte-identity). Register row RR-104: P3 RESOLVED
(owner red/green suffices at P3; no second-agent reproduction owed). The E2E phase 0
javap-probes the shaded jar for the fix's field, so a stale jar cannot silently re-ship
the bug. Status unchanged; the row now cites the fix and the test.

### CT-02 (INV-S2 distribution) — PARTIAL(unit) stays ❌ REFUSED (reason named)

Read against its closing condition as instructed: the residual is "the real p99 < 500 ms
distribution over real propagation latency" — Session 5's, per charter §3 V2's own text.
Neither C6 deliverable touches it: the 10k sweep is logical-time SIM (mechanism evidence
the row already credits from C2), and the Compose E2E asserts propagation/convergence,
never a staleness distribution. Not over-flipped. One sweep find recorded on the row: the
Phase-V live-probe capture promised "Edge mode lands with C6" and it did NOT land in
`1c39615` (observed in-flight uncommitted at the gate-3 window) — that affects the S5
measurement *vehicle*, not this row's status.

### CT-34 (hot-path law gate) — PARTIAL(unit) stays; in-flight observation recorded

C6 correctly landed nothing here (G3-owned). The exact residual ("`gates/gate-3.sh` must
invoke `jmh-gc-check.sh` and cite the artifact") is visibly being assembled — an
uncommitted `gates/gate-3.sh` with exactly that step (g) appeared mid-audit — but no flip
on uncommitted work: the flip belongs to that landing's audit.

### CT-38 (metrics checklist) — PARTIAL(unit) stays; container-scale corroboration noted

The scenario observed `edge_staleness_state`, `edge_rebootstrap_triggered_total`,
`edge_snapshots_applied_total` moving at live containerized edges — corroboration, not
closure. No new series, no consolidated gate, no histograms landed at C6. Residual
unchanged and G3-owned (the in-flight gate-3 step (d) runs the probe in both modes, which
will mechanize the charter DoD's "histograms captured in both modes" — at its landing,
not now).

### CT-25 (ADR-RENEGOTIATED, closed) — notes-only: stale residual sentence corrected

A whole-map-sweep find (below, finding 2), fixed on the row: the residual sentence "no
process run with a non-empty prefix subscription yet" went stale at C3 —
`NotSubscribedReadTest` (CT-32's evidence) IS a process run on a genuinely
prefix-subscribed edge. What actually remains is only the Compose-scale recommendation
(no e2e topology edge runs `--subscribe-prefix`; the CLI flag exists, `EdgeNodeConfig:141`)
— not taken by C6, still non-load-bearing, handed to S4/gate-3 hardening.

## The whole-map end-state sweep (this audit's session-end obligation)

**Method:** every test class name cited in the map mechanically checked for existence on
the tree (70 names; nested `$`-classes resolved against their outer files); every row's
status/evidence/owner re-read against what the session actually delivered.

**Finding 1 — no PASSING row cites a test that no longer exists.** All 70 cited names
resolve. The only non-resolving names are deliberate: `EdgeMetricsContractTest` (CT-38's
OWED test, named as owed), and two historical mentions inside notes
(`StalenessTrackerCommitTimestampTest` in CT-01, `EdgePropagationEndToEndTest` formerly
in CT-39 — both recorded as "landed as a rework / as the script"; CT-39's cell now cites
what exists). The C4-era check that nothing cites the deleted `SlowConsumerPolicy` /
`CatchUpService` still holds.

**Finding 2 — one PARTIAL-remainder was silently delivered and never recorded:** CT-25's
residual sentence (see above). Corrected with a dated note. No status change (the row was
already closed by ADR-0038; the residual was explicitly non-load-bearing).

**Finding 3 — owners all match the handoff after this audit.** C1–C6 now own zero open
rows. The map's "Remaining owed work by owner" block was regenerated accordingly:
G3/lead — CT-34, CT-38; Session 5 — CT-02; S4 (non-row items) — RR-103+RR-095, the
prefix-subscribed Compose edge, forced server-side demotion at tuned thresholds.

**Finding 4 — hygiene flag for the gate-3 landing's auditor (not a C6 defect):** the
in-flight working tree modifies `EdgeBootstrapUnderSustainedWritesProcessTest` (±13
lines, uncommitted) — a test CITED by the PASSING row CT-24. Whoever audits the gate-3
commit must re-verify CT-24's citation still matches the landed body (the map's
read-the-body rule applies to modifications, not only creations). Likewise
`gates/gate3-map-expectation.txt` (contract-qa-owned per the in-flight script) does not
exist yet and must be created AT that landing carrying this audit's summary line — it was
deliberately NOT created by this audit (outside its edit mandate: map + audit file only).

**End-state verdict (charter §3 V3: "every row PASSING or ADR-renegotiated"):** after
this audit the map stands at 34 PASSING + 3 ADR-renegotiated + 1 N-A + **3 PARTIAL — the
literal charter end-state is NOT yet met, and the three violations are named with
owners:**

| Row | Named remainder | Who owes it | Charter-clean closure path |
|---|---|---|---|
| CT-02 | real p99 < 500 ms over real propagation | **Session 5** (charter §3 V2's own deferral) | the deferral is charter text but NOT an ADR — a one-line ADR or an explicitly sanctioned handoff row at session close turns this into a legitimate renegotiation instead of a bare violation; contract:214 ("owed to Session 3") should be amended in the consolidated doc pass |
| CT-34 | gate-3 assembly invoking `jmh-gc-check.sh` + artifact citation | **G3 / lead** (in-flight at audit time) | flips PASSING at the gate-3 landing's audit — expected to clear before session close |
| CT-38 | consolidated `EdgeMetricsContractTest`, staleness histogram, V2 probe histograms | **G3 / lead** | partially addressed by the in-flight gate-3 step (d) (probe both modes); the consolidated presence gate and the histogram series need either delivery or an explicit ADR/handoff renegotiation at close |

The in-flight `gates/gate-3.sh` codifies a tolerance ("PARTIAL rows are tolerated only
because each carries an explicit future-session owner, audited in this file") — that
policy is the lead's and is workable, but it is **not an ADR**; the honest record is that
charter §3 V3 as written requires PASSING-or-ADR, so the session-close commit should
either land the ADR/handoff sanction for CT-02/CT-38's residue or accept the deviation
on the record. CT-34 is expected to self-resolve at the gate-3 landing.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-39 | PARTIAL(unit) | PASSING |
| CT-09 | PARTIAL(unit) | PASSING |

Notes-only updates: CT-26 (RR-104 closed + audit re-run), CT-25 (stale residual
corrected), CT-02 (refusal recorded), CT-34/CT-38 (in-flight observations, residuals
unchanged), map header (+c6 audit reference), summary section + owed-work block + footer
regenerated.

## Rows deliberately NOT flipped (refusals, each with the named reason)

- **CT-02 stays PARTIAL(unit)** — neither the 10k sweep (logical time) nor the E2E (no
  distribution measurement) touches the real-latency p99 residual; S5-owned by the
  charter's own text. Don't over-flip.
- **CT-34 / CT-38 stay PARTIAL(unit)** — their remainders are G3-owned and were observed
  IN FLIGHT (uncommitted) during this audit; no flips on uncommitted work — they belong
  to the gate-3 landing's audit.
- **CT-25 / CT-32 / CT-17 stay ADR-RENEGOTIATED, CT-36 stays N-A** — nothing in C6
  re-opens them; CT-25's correction is a notes fix, not a status change.

## Defects found (probative-value review of the C6 evidence)

1. **`edge_serves_at` asserts body-equality only** — it never checks the response code or
   that intermediate non-matching responses were the consistent refusal. Verdict:
   acceptable for the rows flipped, because (a) a below-cursor 200 cannot produce the
   expected body for a once-written marker, (b) any stale serve in the audit phase
   byte-diffs against linearizable truth, and (c) the per-response refusal discipline is
   already process-pinned at CT-12/CT-35 — but recorded in both flip notes as an
   inherited, not re-proven, half. If gate-3 ever wants the scenario self-contained on
   this point, one `-w %{http_code}` check (404∈{response set} before first 200) would do.
2. **`Rr095StallSeedsIntegratedRerunTest` prints a literal `safetyViolations=0`** — the
   string is structural (the line is reachable only if `sim.run()` did not throw), not a
   counter read. Honest by construction, but a reader of the capture alone cannot tell;
   the test's javadoc says it, the capture's CONFIG block says it. Cosmetic; not tracked.
3. **The scenario's phase-2 staleness-recovery poll (`edge_state_is "$s" 0`) runs after
   the marker-p2 convergence poll** — by then recovery is near-certain, so PASS[10] is
   weaker evidence than its phrasing suggests (it proves recovery happened, not how
   fast). Within scope: no row cites recovery latency. Not tracked.
4. **None blocking.** The script's discipline claims verified true at source: zero
   sleeps-as-sync (every `sleep` is a poll interval or paced-writer cadence), targeted
   `wait`s only (the bare-`wait` deadlock fix is commented at both sites), kill by
   container name, cleanup trap, min-samples non-vacuity on the watch, idempotent
   `compose down` before `up`.

## New summary line (recounted: 34 + 3 + 0 + 0 + 3 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=34 partial=3 failing-captured=0 unimplemented=0 adr=3 na=1
```

## Sign-off

The contract-qa half of the C6 dual sign-off may be marked against this audit: the design
note §8's claim verified as written (CT-39 flipped by the audit, not the note; the
E2E/propagation rows scoped to C6 were exactly CT-39 + the C5-audit-assigned CT-09
multi-edge form, both flipped on read-and-run evidence), the §6 deviations are each
honestly recorded and none silently load-bearing (host-built jars — the in-container
build verified separately; PKCS12 repack documented in-file with the upstream fix named
S5/S7; docker-exec observation a documented NAT constraint), and the §7 gaps are real and
correctly non-blocking (shared signing key + HTTPS-API operator notes are handoff items;
the demotion-counter gap is priced in CT-39's honest scope). **Two carve-outs:** (1)
RR-001's register closure is the review-architect's personally-written justification
(charter rule 6) — this audit supplies the map half and must not be cited as the closure
itself; (2) the three PARTIAL rows survive to the gate-3/session-close window with named
owners — the end-state sweep table above is the authority the in-flight gate-3 points at,
and `gates/gate3-map-expectation.txt` must be created at that landing carrying this
audit's summary line.

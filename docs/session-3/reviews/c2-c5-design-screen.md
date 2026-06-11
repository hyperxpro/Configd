# C2–C5 Design-Draft Screen — review-architect

> **Scope (JOB B):** screen the four pre-implementation design drafts so their
> implementations can begin in sequence (charter §6 rule 4: performance-disqualifying
> designs; plus general soundness). This does NOT authorize parallel implementation
> (charter §6 rule 1: parallel design fine, parallel implementation forbidden) — each
> component still starts only when the prior is DONE and signed. Drafts:
> `c2-edge-node-design-draft.md`, `c3-catchup-design-draft.md`,
> `c4-slow-consumer-design-draft.md`, `c5-bootstrap-design-draft.md`.
> **Reviewer:** review-architect. **Date:** 2026-06-11. **Branch:** session-3-data-plane.
> Read-only on code; verified the load-bearing claims (EdgeActor shape, ADR-0039 deletion
> condition, RR-034/RR-088 register precedent) against the repo.

Verdicts: **CLEARED** / **CLEARED-WITH-CONDITIONS** / **BLOCKED**. Conditions are tracked
to the component's own sign-off (they do not block the *next* design from being screened,
only that component's implementation start). Severities as in the C1 reviews.

---

## C2 — Edge Node Process: **CLEARED-WITH-CONDITIONS**

The draft mirrors C1's review-confirmed anti-fiction shape (transport-agnostic
`EdgeClientCore` + thin socket/HTTP shell) and passes the performance-disqualifying screen
(no unbounded queue; snapshot-or-delta, never per-update full-snapshot; the §3 read path
stays the lock-free `LocalConfigStore.get` with the HTTP shell honestly priced as
non-hot-path in CT-34). Conditions:

**C2-1. EdgeClientCore sim-parity claim — REAL, but the EdgeActor refactor is load-bearing
and must preserve the digest. [REQUIRED — blocking for the C2 sign-off, non-blocking to start]**
I read `EdgeActor.java`. Its current shape composes the REAL production classes already:
`EdgeConfigClient` + `DeltaApplier` + monitor-wired `LocalConfigStore`, with `applyNotify`/
`applyNotifyBatch`/`applySnapshot`/`applyHeartbeat` as the apply surface and a `cursorAckSink`.
The draft's plan — refactor `EdgeActor` to *drive* `EdgeClientCore` so the 507 gate seeds
exercise real C2 code — is achievable because the apply logic the actor already contains is
exactly what `EdgeClientCore.onFrame` will own. **Condition:** the refactor MUST keep
`EdgeSeedCompatTest` byte-identical (the actor's apply path feeds no field into the CP digest,
so this is preservable — same discipline the C-1 skewed-timestamp fix used) AND must keep the
read path routing through the monitor-wired `LocalConfigStore` so INV-M1 stays live. Prove both
at the C2 machinery gate. The `applyHeartbeat` carrier (which today only stores
`lastHeartbeatServerNowMillis`/`lastHeartbeatLatestSeq`) is the seam ADR-0039's frontier measure
plugs into — the draft's §3 is consistent with the code.

**C2-2. `secure/` store-and-never-serve (CT-37) — ACCEPT store-and-fail-closed; the
exfiltration surface is a REQUIRED operator-documented condition, not a redesign. [REQUIRED —
prod-relevant]** Arbitration with the snapshot-equivalence-invariant cost in view: ADR-0038
already puts `secure/` on the chain to every edge for suppression-detectability (ratified). The
V1 snapshot–delta equivalence invariant compares full-store byte content, so an edge that
*drops* `secure/` keys would diverge from the leader and FAIL the equivalence invariant unless
the invariant is specialized per-subscription (extra machinery, more surface for a masking bug).
**Storing + fail-closed-on-serve is the correct call**: it keeps the equivalence invariant a
plain full-store compare (the strongest, least-bug-prone form) and the serving fail-close (503 +
`X-Fail-Closed: strong-read`, control-plane-RR-020-consistent) means the edge never *serves*
`secure/`. **The residual is real and must be named, not waved away:** an edge process
compromise can read `secure/` plaintext from the local store/snapshot on disk. This is an
*exfiltration surface created by the store-everything topology*. **Condition:** C2's design note
and the handoff MUST state this explicitly as a known residual with the mitigations the edge
already inherits (at-rest protection via `--data-dir` permissions/`epoch.lock` siblings; the
mTLS-pinned identity; the recommendation that `secure/` at-rest encryption is a future-session
hardening if edge hosts are lower-trust than control-plane hosts). It does NOT need its own ADR
(ADR-0038 already owns the store-everything decision); it needs the residual recorded as a
register row (P2, edge-trust) so it is not silently accepted. Arbitrated: **store + fail-closed
serve; record the exfiltration residual.**

**C2-3. StalenessTracker idle-proxy DELETION (ADR-0039 prod-blocking) — HONORED by the draft.
[NOTE — confirms a prior prod-blocking condition]** Draft §3 states `recordUpdate(version,
commitTs)` becomes load-bearing, `recordFrontier(serverNowMillis)` applies only when
`heartbeat.latestSeq == cursor`, and **"the idle-time proxy measurement is DELETED (not kept
alongside)"** — verbatim the ADR-0039 / C1-design-review B-1 prod-blocking condition. The
idle-but-heartbeating-pinned-CURRENT regression test is named (`StalenessTrackerTest` rework).
Confirmed honored; the C2 sign-off must verify the deletion landed (no residual idle-time code
path wired as the production signal) and that CT-08's implausibility tripwire exists.

**C2-4. CT-12 consistent-refusal failover resolution — ADR-0039 + the contract-pass cover it;
record the §3 amendment in the contract pass, NOT a new ADR. [REQUIRED — ruling on where it is
recorded]** Ruling: the CT-12 contradiction (the contract's failover steps 3–4 imply
block-and-serve-stale; the draft resolves to refuse-cursor-behind-during-catch-up) does NOT need
its own mini-ADR. It is a clarification of the *existing* monotonic-read/consistent-refusal
semantics that ADR-0035 (staleness/refusal) and ADR-0039 (frontier) already establish — the edge
NEVER serves stale on a cursor-behind read (it 404/refuses), and that rule is uniform across the
steady state, catch-up, AND failover. **The §3 "Edge Failover" text amendment is recorded in the
consistency-contract pass** (the same pass that already amended §2/§4/§5.3 for ADR-0035, per
RR-015), cross-referencing ADR-0039 for the frontier semantics. Condition: the contract-pass diff
must show the §3 steps 3–4 reworded to "refuse cursor-behind during catch-up" with the CT-12 row
flipping to PASSING-against-the-amended-clause (not a silent weakening — the refusal is *stronger*
than block-and-serve-stale). The contract-qa engineer owns the row; review-architect confirms the
amendment is recorded in the contract, not invented in the design note.

## C3 — Catch-up / Replay / Gap Detection: **CLEARED-WITH-CONDITIONS**

Sound and notably lean — much of C3's server half legitimately landed inside C1 (the session
core IS the replay path), so C3 is edge-side orchestration + two implement-or-descope decisions.
Passes the performance screen (resubscribe reuses the bounded subscription path; no new
unbounded structures). Conditions:

**C3-1. Resubscribe-with-cursor as the only recovery path (no new frames) — SOUND, ACCEPT.
[NOTE]** On `GAP_DETECTED` / decoder corruption / DISCONNECTED the client emits
`RECONNECT_RESUBSCRIBE(cursor)` and the server's *already-tested* TAIL/SNAPSHOT_FIRST decision
(C1's `decideMode`) resolves replay vs re-bootstrap. This is the right design: zero new wire
surface, so it inherits all of C1's codec/golden/property coverage and the sweep's invariant
coverage. The DISCONNECTED trigger correctly resubscribes at the *current* cursor (not 0) so a
nearly-caught-up edge does not force a needless full snapshot — and `cursor=0` is reserved for
the poison-pill terminal path. Consistent with C1's cursor discipline. ✔ **Condition (NOTE→
tracked):** the `ReplayHorizonBoundaryTest` matrix (cursor exactly-at / one-below / one-above the
ring horizon, with a concurrent writer driving eviction *between* the server's mode decision and
its first `readSince`) must include the *lapped-after-TAIL-decision* race the draft names — that
is the one genuinely subtle horizon case (server says TAIL, ring laps the cursor before the first
drain → GAP → self-heal). The draft already calls it out; the condition is that the test actually
forces that interleaving deterministically, not just the static ±1 positions.

**C3-2. Poison-pill narrow policy + §8 circuit-breaker/negative-cache descopes → ADR-0040:
PRE-RATIFY the descopes; the narrow poison policy needs the terminal-fail-loud test pinned.
[REQUIRED]** The reasoning is correct and I pre-ratify the two descopes for ADR-0040: (a) the §8
circuit-breaker (serve-previous-known-good on validation failure) presumes a value-validation
layer that does not exist — Configd stores opaque bytes and never deserializes values, so "fails
validation" has no meaning; descope is honest, not a dodge. (b) the §8 negative cache (Bloom +
key-index) is obviated by ADR-0038's store-the-full-subscription-slice: within the slice a HAMT
miss IS authoritative non-existence (the lock-free zero-alloc miss path already exists); outside
the slice the read is refused by policy with a distinct reason. No correctness or *measured*
performance need remains. **Conditions for ADR-0040:** (i) it must list, as required changes, the
`BloomFilter` class disposition (retained tested-but-unwired, deletion deferred to S7's orphan
sweep — name it so it is not re-counted as live), and the CT-32/CT-33 rows flipping to
ADR-RENEGOTIATED(adr-0040); (ii) the narrow poison policy (apply-throwing delta → bounded retries
→ `configd.edge.poison_pill` metric + `RECONNECT_RESUBSCRIBE(cursor=0 force-snapshot)` → if the
SNAPSHOT itself won't apply, **terminal fail-loud, process exits non-zero**) is the *one* place
C3 can produce a process exit — it MUST have a pinned test (`PoisonPillRebootstrapTest` incl. the
terminal case) so the lying-dashboard failure mode (an edge serving a state it cannot advance) is
provably converted to a loud crash. The skip-the-bad-delta path being *forbidden* (chain break =
silent divergence) is correct and aligns with ADR-0038. ADR-0040 must be written and ratified
before C3 implementation starts.

## C4 — Slow-Consumer Policy: **CLEARED-WITH-CONDITIONS**

The governance layer over C1's substrate (session states + demotion events with cursor
evidence). Passes the performance screen with one explicit check below. Conditions:

**C4-1. Governor superseding+DELETING `SlowConsumerPolicy` — REQUIRE the register row update
(RR-034-class + RR-088). [REQUIRED — blocking-for-honesty]** This is exactly the RR-034-class
implement-or-delete the register already tracks: `RR-088` names `SlowConsumerPolicy` as 25/25
NO_COVERAGE shelfware "pending the RR-001 implement-or-descope decision," and `RR-034` is the
canonical orphan-delete precedent. The draft's plan (delete the orphan, move its useful test
patterns to the governor's suite) is the correct disposition. **Condition:** before C4 lands, the
register MUST get the row update — RR-088 (or a new Session-3 row) recording that
`SlowConsumerPolicy` is DELETED (the implement-half is `SlowConsumerGovernor`) and that
`CatchUpService` (the other distribution-service orphan in RR-088) is *separately* dispositioned
(C3's replay path supersedes its role — state whether it too is deleted or retained). Do not let
the delete happen without the register reflecting it; that is the charter §6 rule-6 discipline and
the precise gap RR-034 exists to prevent.

**C4-2. Threshold defaults sanity — REASONABLE, one tie-down. [NOTE]** `demoteLimit=3` within
`demoteWindowMs=60_000`, `quarantineCooldownMs=60_000`, `unhealthyWindowMs=3_600_000` (3
quarantines/hour), `queueWarnWindowMs=10_000` (the §7 ">0 credits for >10 s" analogue) — these
are internally consistent and map to architecture §7's policy ladder (warn → quarantine → remove)
re-based on the C1 frame/byte/ack-lag signals (correct, since the §7 credit numbers are superseded
per C1 condition 4). Sanity holds. **Tie-down (NOTE):** the demotion *reason* mix matters — an
edge that demotes 3× on GAP (a network/eviction artifact, often blameless) should not walk to
QUARANTINED as readily as one demoting 3× on ack-lag/transport-backpressure (genuine slowness).
Recommend the governor weight or separately-count GAP demotions vs distress demotions so a flaky
*network* does not get a *healthy* edge quarantined. Not a disqualifier; a threshold-policy
refinement for the C4 design note.

**C4-3. Admission-control placement (SUBSCRIBE-time refusal) — DoS/lockout footgun check:
ACCEPTABLE with a mandatory anti-permanent-lockout escape. [REQUIRED — prod-relevant]** The
governor refusing QUARANTINED/UNHEALTHY identities at SUBSCRIBE (admission control) is the right
place to shed a genuinely-abusive consumer, and keying on the **mTLS cert identity (not the
connection)** is correct (a reconnect storm can't dodge it). **The footgun the screen exists to
catch:** a *flapping-but-healthy* edge (e.g. one behind a lossy WAN link that legitimately gaps
and recovers repeatedly) could accrue quarantines and get **permanently** locked out at
UNHEALTHY, which under config-plane semantics means that edge serves *stale config forever* — a
worse outcome than letting it reconnect and catch up. **Condition:** UNHEALTHY must NOT be a
terminal lockout without an automatic time-based exit — the draft has `unhealthyCooldownMs`
(3_600_000) which provides exactly this, so the design is OK *as written*, but the condition is
that the cooldown is the ONLY required path back (operator reset is an *additional*, not the
*sole*, recovery) AND that admission refusal during cooldown emits `edge_fanout_reconnects_refused_total`
+ a structured log so a permanently-flapping edge is observable, not silently dark. Also require a
test that a healthy edge which flaps purely due to *injected network loss* (not slowness) recovers
without escalating to UNHEALTHY when the loss heals (couples to C4-2's reason-weighting). With the
auto-cooldown-exit and the GAP-vs-distress distinction, the lockout footgun is closed.

## C5 — New-Edge Bootstrap: **CLEARED**

The smallest component (the mechanism exists end-to-end after C1+C2+C3; C5 is the adversarial
proof). The choose-justify-test decision is sound and the non-vacuity demands are testable.
Cleared with no conditions; two NOTEs.

**C5-1. Exact-cutover-cursor justification — SOUND. [NOTE]** The choice (exact cutover cursor as
the mechanism, idempotent-apply as defense-in-depth) is correctly justified: cutover puts the
correctness burden on ONE proven seam (ADR-0034's cursor contract — `readSince(S)` contiguity,
exactly-once-over-effect) rather than distributing it over every apply site. The "no overlap
window by construction" claim is true given C1: `performSnapshotTransfer` sets `cursor =
replay.seq() = S` and leaves `lastAckedSeq` per the C1(a) fix, and the first post-SNAPSHOT_END
NOTIFY contains only seq > S (the `FrameBatchingChainIntegrityTest` property proves exactly this
post-snapshot contiguity, which I verified in the C1 sign-off Finding 3). Writes committed during
transfer land in the ring and are delivered as the seq>S tail. The idempotent-apply backstop
(`STALE_DELTA` discard + backward-snapshot refusal) is correctly framed as defense-in-depth, not
the happy path. The judge is the V1 snapshot–delta equivalence invariant — the right judge. ✔

**C5-2. Non-vacuity demands (the straddle write MUST occur) — TESTABLE, ENFORCE the assertion.
[NOTE]** The critical case is a write committing *exactly while SNAPSHOT_END is in flight* (the
cutover straddle). The draft commits to driving with seeds that *provably hit* the straddle and
**asserting non-vacuity** (at least one write committed during transfer; report the observed dup
count for the dup-channel coverage). This is the right discipline — a bootstrap test that never
actually races a write past the cutover proves nothing, and the charter's "no test testing the
test" doctrine demands the assertion. **Recommendation (NOTE):** make the non-vacuity assertion a
hard `assertTrue(strad­dleWritesObserved > 0)` (and `dupFramesAcrossCutover > 0` for the
dup-channel test), failing the test if the seed selection ever stops hitting the straddle — so a
future sim change that accidentally serializes writes-vs-cutover surfaces as a RED, not a silent
green. With that enforced assertion the non-vacuity demand is fully testable; no condition needed,
just the explicit hard assert.

---

## SUMMARY

| Draft | Verdict | Blocking items |
|---|---|---|
| **C2** Edge node | CLEARED-WITH-CONDITIONS | none blocking *start*; C2-1 (sim-parity digest preservation) + C2-2 (`secure/` exfiltration residual recorded) + C2-4 (CT-12 §3 amendment in the contract pass, not a new ADR) gate the C2 *sign-off* |
| **C3** Catch-up/replay | CLEARED-WITH-CONDITIONS | **ADR-0040 must be written + ratified (poison-pill descopes pre-ratified here) before C3 implementation starts**; C3-2 terminal-fail-loud test required |
| **C4** Slow-consumer | CLEARED-WITH-CONDITIONS | C4-1 (register row update for the `SlowConsumerPolicy` delete — RR-034/RR-088 discipline) + C4-3 (anti-permanent-lockout cooldown + GAP-vs-distress reason weighting) gate the C4 sign-off |
| **C5** Bootstrap | CLEARED | none |

No draft is BLOCKED. The single hard sequencing gate is **C3's ADR-0040 must exist and be
ratified before C3 implementation begins** (the descopes are pre-ratified in this screen; the ADR
must be authored and the contract-map rows flipped). All other conditions gate their own
component's sign-off, not the screening or the next design.

— review-architect, 2026-06-11

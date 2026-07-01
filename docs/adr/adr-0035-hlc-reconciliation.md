# ADR-0035: Reconcile the per-entry HLC fiction — amend the contract to a measurable staleness definition (RR-015)

- **Status:** Accepted (review-architect APPROVED 2026-06-11 — sign-off recorded below; second-reviewer co-sign rides the RR-031/RR-015 consolidated contract pass that applies the §2/§4 patch plan)
- **Date:** 2026-06-10
- **Session:** 2 (Control-Plane Correctness)
- **Finding:** RR-015 (P1) — "the contract's HLC is fiction" (MX-1; CM-010/CM-037/CM-044/CM-064; CF-17)
- **Interacts with:** ADR-0004 (version semantics — amended here), ADR-0030 (single-group topology — the enabling premise), ADR-0033 (applied-mutation-sequence definition of S — the §4 seq reconciliation must agree with it)

## Context

The consistency contract promises a per-entry Hybrid Logical Clock that does not exist in the code:

- `LogEntry` is `record LogEntry(long index, long term, byte[] command)` (`LogEntry.java:13`) — **no
  timestamp/HLC field**. Every committed entry is `(index, term, command)` and nothing else.
- `HybridClock` (`configd-common`) has **zero production consumers**: the only references in `src/main`
  are its own class and `HybridClockBenchmark` (testkit). It is dead on the control plane.
- Contract §2 defines edge staleness as `current_wall_time - timestamp_of_last_applied_entry` and states
  "Each Raft log entry carries an HLC timestamp" (`consistency-contract.md:46,:48`). INV-S1
  (`:61-66`) is written off that per-entry timestamp.
- Contract §4 promises "Each entry also carries an HLC timestamp for cross-group approximate ordering"
  (`:119`) and INV-W2 (`:156`) asserts `hlc(w1) < hlc(w2)`.
- ADR-0004 ("Decision") promised "Entries also carry an HLC timestamp" and a "16-byte (8 seq + 8 HLC)"
  per-entry footprint.
- What the code actually does: `StalenessTracker.recordUpdate(version, timestamp)` accepts a `timestamp`
  argument **documented as "informational" and then ignored** — it stores `lastUpdateNanos =
  clock.nanoTime()` (`configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:98-101`) and measures staleness as local idle time
  `clock.nanoTime() - lastUpdateNanos` (`stalenessMs()`), **not** `wall_time - entry_HLC`. So the
  one place §2's bound is enforced measures a different quantity than the contract defines.

The contract therefore defines a guarantee against a field that does not exist and is enforced against a
proxy quantity. That is the FICTION classification.

### The enabling premise: ADR-0030 makes the cross-group HLC role moot

The contract uses the per-entry HLC for **two** purposes:

1. **Cross-group approximate ordering** (§4 `:119`, §5.3 `:145-148`, INV-W2 `:156`, §9
   `:235`). Applications needing cross-group causality were told to "use HLC timestamps and accept
   bounded uncertainty."
2. **Edge staleness measurement** (§2 `:46-66`).

Under ADR-0030 (single region-local Raft group; `ConfigdServer.java:82` `DEFAULT_RAFT_GROUP = 0` is the
only group; `ConfigScope` routes everything to group 0, RR-078), **there is no second group**, so
purpose (1) is unreachable: there is no cross-group order to approximate. The contract already disclaims
cross-group total order (§5.3 "NOT GUARANTEED"); the HLC was the *mechanism* for the soft fallback, and
that fallback now governs an empty set of deployments. Per-entry HLC's only live purpose is (2), the edge
staleness clock — and §2's own measurement does not actually need a *per-entry* HLC, only a single
authoritative time reference for "when was the freshest thing I have applied produced."

## Decision: AMEND (descope per-entry HLC; redefine §2 staleness as commit-notification propagation)

We **amend** the contract rather than implement per-entry HLC on the control plane. The driving reasons:

1. **No correctness hole requires HLC on the control plane.** Linearizability (INV-L1), per-key and
   intra-group total order (INV-W1/INV-W2-seq-half), version monotonicity, and read-your-writes are all
   carried by the Raft log's `(index, term)` ordering and the applied-mutation sequence S — none of them
   need a wall-clock-ish timestamp on the entry. The HLC's only job was cross-group *approximate*
   ordering, which ADR-0030 deletes, and an edge *staleness clock*, which is a propagation-latency
   measurement, not an ordering primitive. (Audited adversarially: I looked for a control-plane invariant
   that breaks without a per-entry monotone wall-ish stamp and found none. The control plane never
   compares two entries by time; it compares by `(index, term)`.)

2. **A per-entry HLC stamped on the control plane cannot measure the thing §2 cares about anyway.** §2 is
   about *edge* staleness — how far behind the edge data plane is. An HLC assigned at append time on the
   leader, carried in the entry, and compared at the edge to the edge's wall clock measures
   leader-stamp-to-edge-read skew **plus unbounded clock skew between leader and edge** (HLC's physical
   component is each node's own wall clock; the logical component does not bound real-time skew). For a
   *staleness bound in milliseconds* that is the wrong instrument: it conflates propagation latency with
   clock divergence. The honest measurement is **one clock**: stamp the commit at the leader and measure
   "now − that stamp" at the consumer, accepting the leader→edge one-way-delay as the (small, bounded)
   systematic error — which is exactly what a staleness bound should report.

3. **Cost/benefit.** Implementing per-entry HLC means: a wire-format change to `LogEntry` (touches WAL
   format → RR-064 wire-compat stubs, snapshot format ADR-0028, codec ADR-0029), a new field threaded
   through append/replicate/apply/snapshot, and clock-skew handling on every node — for a field with no
   control-plane consumer and a now-empty cross-group use case. AMEND removes a fiction; IMPLEMENT adds a
   durable-format liability to serve a deleted requirement.

### What replaces the §2 measurement

Redefine edge staleness in terms of **commit-notification timestamps assigned at the leader at commit
time** — the same apply-path notification ADR-0033 §"Consequences" and the forthcoming §4.6
commit-notification interface already expose, which Session 3's data plane consumes:

- When the leader applies a committed mutation (the apply boundary ADR-0033 already instruments — it now
  returns the applied-mutation seq S), it records a **commit timestamp** `t_commit` = the leader's wall
  clock at apply time, and emits it on the commit-notification stream alongside `(seq S, key, value)`.
  This is a *single* authoritative reference clock (the leader's), not a per-entry field carried through
  the log and not an HLC.
- The edge `StalenessTracker.recordUpdate(version, t_commit)` stores `t_commit` for the freshest applied
  notification (this is the parameter the method *already accepts and currently ignores* — the amendment
  makes it load-bearing instead of informational).
- Edge staleness = `edge_wall_now − t_commit_of_last_applied_notification`. The leader→edge one-way delay
  is the bounded systematic component; node-to-node clock skew is acknowledged as the residual error and
  bounded operationally by NTP discipline (documented assumption, not a silent one).

This is implementable today against ADR-0033's apply seam; it requires **no `LogEntry` change**, no WAL/
snapshot format change, and no new HLC machinery. It measures propagation latency against one clock.

## Exact contract §2/§4 text changes (PATCH PLAN — do NOT apply here)

These are staged for the consolidated contract pass (RR-031/RR-015 own the same §4 text; do not apply in
this session; `docs/consistency-contract.md` is read-only until that pass).

### §2 — Staleness Measurement (`consistency-contract.md:45-48`, INV-S1 `:59-66`)

- **Replace** §2 "Staleness Measurement" body:
  - REMOVE: "Staleness at an edge node = `current_wall_time - timestamp_of_last_applied_entry`" and "Each
    Raft log entry carries an HLC timestamp. The edge node's `StalenessTracker` computes the difference
    between the current wall clock and the timestamp of the most recently applied entry."
  - INSERT: "Staleness at an edge node = `edge_wall_now − commit_timestamp(last_applied_notification)`,
    where `commit_timestamp` is assigned by the **leader at commit/apply time** and delivered on the
    commit-notification stream (§4.6). The edge `StalenessTracker` stores the commit timestamp of the
    most recently applied notification and reports `now − that`. The systematic error is the bounded
    leader→edge one-way propagation delay; inter-node clock skew is bounded operationally by NTP (target
    skew ≤ 50 ms) and is the only residual error term. **No per-entry HLC is carried in the Raft log.**"
- **Replace** INV-S1 (`:61-63`):
  - FROM: `staleness(e, t) is defined as wall_time(t) - timestamp(last_applied(e, t))`
  - TO: `staleness(e, t) := wall_now(e, t) − commit_ts(last_applied_notification(e, t))`, where
    `commit_ts` is the leader-assigned commit-notification timestamp.
  - INV-S2 (`:64-66`) wording is unchanged (it is a probabilistic bound over `staleness`, which is now
    well-defined).

### §4 — Version Semantics (`consistency-contract.md:117-119`, INV table `:108-114`, §5.3/INV-W2, §9)

- **§4 "Semantics"** (`:117-119`):
  - REMOVE the bullet "Each entry also carries an HLC timestamp for cross-group approximate ordering."
  - The seq bullet (`:118`) is **reworded for ADR-0033 reconciliation — see next section**.
- **§4 comparison table** (`:108-114`): the "Cross-key ordering / Vector Clocks … Causal only" row stays;
  add a footnote that the "HLC for cross-group" column is descoped under ADR-0030 (single group → no
  cross-group order to approximate).
- **§5.3 Cross-Key Order Across Raft Groups** (`:145-148`): change "Cross-group ordering is approximate
  via HLC timestamps" → "Cross-group ordering is N/A under the single-group topology (ADR-0030); if a
  future multi-group deployment is adopted, a cross-group ordering mechanism is re-specified at that
  time." Drop the "Use HLC timestamps" client guidance bullet.
- **INV-W2** (`:155-156`): change `seq(w1) < seq(w2) AND hlc(w1) < hlc(w2)` → `seq(w1) < seq(w2)` (the
  seq half is the real, single-group guarantee; the `hlc` conjunct is removed).
- **§9 summary** (`:235`): "Cross-key order (cross group) … HLC for approximate ordering" → "N/A under
  ADR-0030 single-group topology."

## §4 seq reconciliation with ADR-0033 (PROPOSE EXACT WORDING)

Contract §4 currently says (`:118`): *"Every committed log entry receives `seq = previous_seq + 1`
(gap-free within a group)."* ADR-0033 establishes that the client-visible sequence S is the
**applied-mutation sequence** — a counter that increments **only on mutating applies** (no-op and RCFG
config entries are skipped), assigned by the state machine in `StateMachine.apply` (now returning `long`),
and surfaced as the read cursor. It is therefore *not* the log index and *not* a per-committed-entry
counter. The contract's "every committed entry gets seq=prev+1" is false for no-op/RCFG entries and must
be reworded to the mutation stream.

**Proposed replacement for §4 "Semantics" seq bullet (`:118`):**

> - Each Raft group maintains an independent, monotonically increasing 64-bit **applied-mutation sequence**
>   counter S. Every committed entry that **mutates the config state machine** (PUT/DELETE/BATCH apply)
>   receives `S = previous_S + 1`. Non-mutating committed entries (leader no-ops and configuration-change
>   RCFG entries) **do not consume a sequence number** and are skipped by the counter. S is the value
>   returned to a client on a confirmed write (ADR-0033) and is the version carried in the read cursor
>   (§3/§6). It is gap-free **over the mutation stream**, not over raw log indices.

**Proposed replacement for INV-V2 (`:131-132`):**

> INV-V2: ∀ Raft group g, ∀ consecutive **mutating** committed entries e_i, e_{i+1} in g (i.e., adjacent
> in the applied-mutation stream, skipping no-op/RCFG entries): `S(e_{i+1}) = S(e_i) + 1`.

INV-V1 (`:128-129`, "if e1 committed before e2 then seq(e1) < seq(e2)") remains true over the mutation
stream and needs only the word "mutating" added for precision; it is otherwise unchanged.

This wording is consistent with ADR-0033's "the client seq is the applied-mutation counter, not the log
index nor contract §4 seq" and with the §8 runtime-assertion reconciliation that RR-031 owns
(`assert_sequence_gap_free` becomes "gap-free over mutating applies"; note §8's `sequence_monotonic`/
`sequence_gap_free` assertions were already A2-removed in code per RR-031 — the §8 row text is RR-031's to
fix, this ADR only fixes the §4 definition they reference).

## ADR-0004 amended status

ADR-0004 is **amended, not superseded**. Its core decision (per-group monotonic sequence number; reject
per-key versions and vector clocks) stands and is correct. The amendment, to be stamped on ADR-0004:

> **Amended 2026-06-10 (ADR-0035, RR-015):** The "entries also carry an HLC timestamp" clause and the
> "16-byte (8 seq + 8 HLC)" per-entry footprint are **descoped**. Per-entry HLC was never implemented
> (`LogEntry` has no timestamp field) and its two uses are obviated: cross-group approximate ordering is
> moot under the single-group topology (ADR-0030), and edge staleness is measured via leader-assigned
> commit-notification timestamps (ADR-0035 §2 amendment), not a per-entry HLC. The sequence number is now
> the **applied-mutation sequence** (ADR-0033), gap-free over mutating applies rather than over every
> committed entry. The per-entry footprint is 0 added bytes (no HLC field).

A one-line "Amended by ADR-0035" pointer is added to ADR-0004's Status line in the same consolidated pass.

## Alternatives considered

- **IMPLEMENT per-entry HLC now.** Rejected. Adds a durable-format field (WAL/snapshot/codec change,
  reopening RR-064 wire-compat) and per-node clock-skew handling to serve (a) a cross-group use case
  ADR-0030 deleted and (b) a staleness measurement that a single leader-assigned commit timestamp serves
  more honestly. No control-plane invariant requires it. This would be implementing a fiction faithfully
  rather than removing it.
- **Keep §2's current local-idle-time proxy and just relabel the contract to match the code.** Rejected as
  insufficient: `clock.nanoTime() - lastUpdateNanos` measures "time since this edge last received *any*
  update," which is **0 ms immediately after a heartbeat even if the heartbeat carried stale data** — it
  cannot detect a slow/stuck propagation path that still ticks. The commit-notification-timestamp
  definition measures actual data age and is the minimal honest fix. (This is also why the relabel-only
  option fails the charter's "spec and code may not disagree" *and* "guarantee must be meaningful" bars.)
- **HLC only at the edge, not on log entries.** Rejected as a confused middle: the edge has nothing to
  HLC-stamp that the leader's commit timestamp does not already carry; a second clock at the edge just
  reintroduces the skew term we are trying to make explicit.

## What Session 3 must implement for staleness measurement (HANDOFF)

These go into the Session-2→3 handoff (Session 3 owns the edge data plane and durability/recovery):

1. **Leader-assigned commit timestamp on the apply path.** At the apply boundary ADR-0033 instruments,
   capture `t_commit = leader_wall_clock_at_apply` and include it on the commit-notification emitted to
   the data-plane fan-out (the §4.6 interface; bounded per ADR-0034, to be written with the §4.6 work).
   This is control-plane work but is *enabling* for the edge measurement, so it is flagged for the
   joint §4.6 effort, not buried.
2. **Edge `StalenessTracker` consumes `t_commit`.** Make the existing-but-ignored `timestamp` parameter of
   `recordUpdate(version, timestamp)` (`StalenessTracker.java:98`) load-bearing: store it and compute
   `stalenessMs() = edge_wall_now − t_commit_of_last_applied`. Remove the "informational" Javadoc. Keep
   the INV-S1 wiring through `InvariantMonitor.assertStalenessBound` (already present, F-0073).
3. **`StalenessUpperBoundTest` must assert the p99 distribution**, not threshold transitions (RR-031
   notes the current test asserts state transitions, CM-049). With a real commit-timestamp clock this
   becomes measurable: drive simulated propagation latency, collect the staleness distribution, assert
   p99 < 500 ms / p9999 < 2 s (INV-S2). This is the discriminating test for the §2 amendment.
4. **NTP-skew assumption documented + a tripwire.** Record the ≤ 50 ms inter-node skew assumption and add
   a guard that flags negative or implausibly large `staleness` (clock ran backwards / large skew) as a
   distinct metric rather than silently reporting `staleness ≈ 0` or a huge value.

(Control-plane note for Session 2's own §4.5 spec-update work: ConsensusSpec models `EdgeApply(e)` as an
instantaneous `edgeVersion[e] := commitIndex[e]` with no time component, so no TLA+ change is needed for
this amendment — the staleness *bound* is a real-time/statistical property checked by the property test,
not a TLC safety invariant. See `docs/session-2/assertion-verification.md` INV-S1 row.)

## Sign-off

- review-architect: **APPROVED 2026-06-11.** The AMEND/descope decision is sound and the cited evidence is accurate (verified against the live code and contract): `LogEntry` has no HLC field; `HybridClock` has zero `src/main` consumers; `StalenessTracker.recordUpdate(version, timestamp)` documents `timestamp` "(informational)" and stores `clock.nanoTime()` instead (`configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java:96,100,154`); the §2/§4/§5.3/§9/INV-W2 anchors match the contract verbatim; `DEFAULT_RAFT_GROUP = 0` confirms the ADR-0030 single-group premise that makes cross-group HLC ordering moot. The §4 seq reconciliation is consistent with ADR-0033 as independently verified in the RR-004 fix review — the client-visible S is the applied-mutation counter that skips no-op/RCFG entries and is surfaced by `StateMachine.apply` (now `long`); the proposed §4/INV-V1/INV-V2 rewording ("gap-free over the mutation stream") matches the implemented behavior. The §2 single-leader-clock redefinition is more honest than per-entry HLC (avoids conflating propagation latency with inter-node clock skew) and is implementable against ADR-0033's apply seam with no `LogEntry`/WAL/snapshot/codec format change (no RR-064 wire-compat reopening). Scope is correct: this ADR authorizes the DECISION only; the actual `consistency-contract.md` §2/§4 edits are explicitly deferred to the RR-031/RR-015 consolidated pass (contract read-only this session), and the staleness-measurement implementation is handed to Session 3. One non-substantive nit for the consolidated pass: the ADR cites `configd-observability` for `StalenessTracker`; it actually lives in `configd-edge-cache` (line numbers/behavior are correct). No required changes to the decision. **[RESOLVED in the RR-031/RR-015 consolidated pass: the body reference now reads `configd-edge-cache/src/main/java/io/configd/edge/StalenessTracker.java`.]**
- second reviewer (consensus-correctness-engineer): **CO-SIGNED 2026-06-11** on the RR-031/RR-015 consolidated contract pass. Applied the §2/§4 patch plan verbatim to `consistency-contract.md` (§2 staleness redefinition + INV-S1; §4 applied-mutation-sequence seq bullet + INV-V1/INV-V2 + comparison-table footnote; §5.3 cross-group N/A; INV-W2 `hlc` conjunct removed; §9 cross-group row). Independently re-verified against live code: `LogEntry` is still `(index, term, command)` (no HLC field); the applied-mutation sequence S is the counter `StateMachine.apply` returns (skipping no-op/RCFG), consistent with ADR-0033 and the RR-004 fix; `DEFAULT_RAFT_GROUP = 0` (single group). Fixed the `configd-observability`→`configd-edge-cache` nit in the ADR body. The §2 measurement implementation + the INV-S2 p99-distribution test remain Session-3-owed (handoff §"What Session 3 must implement"). No changes to the decision.

> Open question for the lead recorded in the §"What Session 3 must implement" item 1: the commit-timestamp
> emission is control-plane work that *enables* an edge guarantee. Confirm it rides the §4.6/ADR-0034
> interface effort (joint S2-control-plane / S3-data-plane) rather than being deferred wholesale to S3.

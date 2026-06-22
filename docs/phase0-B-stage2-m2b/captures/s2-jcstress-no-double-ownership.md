# M2b S2 — the jcstress no-double-ownership proof (H-4 failure mode 1, JMM-level) — NON-VACUOUS

> **Prime directive (session §1.1, §2.2, §5-S2):** H-4's first failure mode — *no double-ownership* —
> is a Java Memory Model property (concurrency ABSENCE). A macro/sim pass does NOT close it (the M2a
> red-team confirmed the unit test could not deterministically expose the race — caught only ~2/3 of
> runs). It REQUIRES a jcstress proof, proven NON-VACUOUS: a deliberately-broken handoff that DOES
> create a double-ownership window must hit FORBIDDEN, then the real handoff must never hit it.

## The claim

There is never an instant where two distinct real threads both pass `assertOwnerThread()` for the same
group and could both execute owner-thread work on it. The rehoming handoff re-binds one
`volatile Thread ownerThread`: the losing owner A detaches (`beginHandoff()` → the `HANDOFF` sentinel),
then — ORDERED AFTER by the coordinator's executor `.get()` barrier — the gaining owner B adopts
(`adoptOwnerThread()`). The owner-entry guard reads `ownerThread` ONCE and proceeds; double-ownership is
two threads both reading `ownerThread==self` and both touching the unsynchronised node.

## The model (`configd-jcstress/RehomingDoubleOwnershipTest`, 3 `@State` classes)

The `@State` classes mirror the R-01' field declarations VERBATIM (`volatile Thread ownerThread` + the
`HANDOFF` sentinel) — the property is a property of those exact declarations. Each owner's critical
section snapshots `ownerThread` ONCE (exactly as `assertOwnerThread()` does) and uses overlap-witness
flags so an OVERLAP of the two owners' critical sections (= simultaneous double-ownership) is detectable.

| `@State` | Models | Gated? | FORBIDDEN |
|---|---|---|---|
| **CleanHandoffNoDoubleOwnership** | the real handoff: B adopts ONLY after a volatile-acquire of the HANDOFF detach (the happens-before the `.get()` barrier provides) | YES (curated gate) | overlap of the two critical sections (`1,1`) — must be UNREACHABLE |
| **BrokenHandoffDoubleOwnership** | a BROKEN handoff: B adopts WITHOUT the barrier (the design §6 "un-ordered re-bind") | NO (excluded, like `KnownRacyCounter`) | `1,1` — must be REACHABLE (the non-vacuity proof) |
| **PostAdoptGuardNoFalseNegative** | a foreign off-owner caller after the HANDOFF→B re-bind | YES (curated gate) | FALSE NEGATIVE (`2`) — foreign sees null/self in service |

The barrier model is faithful: in the clean state A's critical section is program-ordered before its
detach (volatile release of HANDOFF), B observes HANDOFF (volatile acquire) before its critical section,
so A's critical section transitively happens-before B's — they cannot overlap. The control drops exactly
that edge.

## Evidence (jcstress `-m quick`, 2 CPUs, JDK 25)

**CONTROL — non-vacuity (the harness CAN see double-ownership):** `BrokenHandoffDoubleOwnership` hits
the FORBIDDEN `1,1` "DOUBLE-OWNERSHIP — both owners' critical sections overlap" on EVERY fork →
jcstress `[FAILED]` (a green run here would be a harness failure). Observed across forks at **0.01%–1.01%**
(e.g. 37,374 / 3,696,768; 2,431; 280; 358; 1,119; 3,846 …). Full log:
`captures/jcstress/control-broken-handoff-FORBIDDEN.txt`.

```
...... [FAILED] io.configd.jcstress.RehomingDoubleOwnershipTest.BrokenHandoffDoubleOwnership
    0, 0  ~96–99%   Acceptable  no overlap on this interleaving
    1, 1   0.01–1.01%  Forbidden  DOUBLE-OWNERSHIP — both owners' critical sections overlap
```

**CLEAN — no double-ownership, NON-VACUOUS:** `CleanHandoffNoDoubleOwnership` never hits `1,1` across
~100M samples/fork, AND the post-adopt path is genuinely exercised — `0,0` "clean handoff — both owner
critical sections ran, ordered" dominates at **99.85%–99.96%** (e.g. 98,088,402 / 99.85%); `0,2` "gainer
did not observe the handoff in its bounded spin" is the rare remainder (~0.1%); `1,1` = **0**.

```
.......... [OK] io.configd.jcstress.RehomingDoubleOwnershipTest.CleanHandoffNoDoubleOwnership
    0, 0  98,088,402  99.85%  Acceptable  clean handoff — both owner critical sections ran, ordered
    0, 2      ~0.1%           Acceptable  the gainer did not observe the handoff in its bounded spin
    (1, 1 FORBIDDEN: never observed)
```

**POST-ADOPT — no false negative survives the re-bind, NON-VACUOUS:** `PostAdoptGuardNoFalseNegative`
never hits the FALSE-NEGATIVE `2` (0.00%), AND "guard FIRED — foreign observed the re-bound owner B and
was intercepted" (`0`) is observed at **2.65%–3.70%** (e.g. 770,371) — the off-owner caller genuinely
saw B in service and the guard fired; the remainder is the pre-service inert window (`1`).

## Gate integration

`run-curated-subset.sh` adds `CleanHandoffNoDoubleOwnership` + `PostAdoptGuardNoFalseNegative` to the
curated list; `BrokenHandoffDoubleOwnership` stays EXCLUDED (forbidden-hitting control, committed as the
non-vacuity proof). Curated subset (sanity mode, gate config): **15 tests, 210 planned, 210 passed, 0
failed**.

## What this closes

H-4 failure mode 1 (**no double-ownership**) is now proven at the JMM level, non-vacuously. The other
two modes (no lost/misrouted message, no torn state) are covered by the S1 mechanism + the M2a
check-and-bounce + the S3 rehoming-injected sweep; H-4 → CLOSED is recorded at S4 once S3 is green.

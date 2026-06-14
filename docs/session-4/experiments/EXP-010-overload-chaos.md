# EXP-010 — Workstream D: overload under chaos

- **Workstream:** D (charter §6/§11). **Register rows:** none new.
- **Status:** GREEN — two overload scenarios; fan-out admission/queue bounds cited (already pinned).

## D-1 — Control-plane write flood (backpressure)

`OverloadChaosTest.controlPlaneWriteFlood_shedsWithOverload_boundedQueue_recovers`: elect a leader,
then flood proposals WITHOUT stepping (no delivery → no commit → uncommitted builds; no ticks → the
leader stays leader). Oracle (§11):
- **shed:** `OVERLOADED` returned once `lastIndex − commitIndex ≥ maxPendingProposals` (the
  429-equivalent) — 478 of the first 1500 shed.
- **bounded, never unbounded:** a SECOND 1500-write wave does NOT grow the queue — it plateaus at
  exactly **1024** (`maxPendingProposals`) across both waves, and all 1500 of the second wave are
  shed (no silent buffering).
- **recovery:** resume delivery (`step`) → commits drain → the queue clears → a fresh write is
  ACCEPTED again.

Measured: `accepted=1022, sheddedFirstWave=478, queuePlateau=1024`.

## D-2 — Post-partition reconnect storm (the data plane's most dangerous overload)

`OverloadChaosTest.postPartitionReconnectStorm_allEdgesRecoverToCurrent`: 5 edges warmed to CURRENT,
then the WHOLE fleet partitioned, fed writes they miss, walked to DISCONNECTED, and HEALED at the
same instant — every edge reconnects + re-bootstraps simultaneously (the catch-up thundering herd).
Oracle: all edges recover to CURRENT (none stuck stale-but-silent), all catch up to the authoritative
version, none pushed TERMINAL. Measured: **258 recovery ticks** for the whole fleet, 0 terminal.

## Cited (already pinned — not duplicated)

Fan-out admission + queue bounds under a subscriber storm / slow consumers: `FanOutServerAdmissionBoundTest`,
`DemotionNoticeBackpressureTest`, `BootstrapSnapshotBackpressureTest`, and the A3 legs (ack-lag
demotion A3-2, wedged-transport pause A3-3, governor identity-map churn A3-4 — EXP-005).

## Reproduction

```
./mvnw -o -pl configd-testkit test -Dtest='OverloadChaosTest' -Dsurefire.failIfNoSpecifiedTests=false
```

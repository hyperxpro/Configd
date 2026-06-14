# EXP-011 — Workstream E: sustained mini-Jepsen

- **Workstream:** E (charter §6 step 5 — LAST, against the fully-fixed system; nightly, not in the CI gate).
- **Status:** GREEN — sustained mixed-fault sweep, 0 safety violations; existing 10k adversarial sweeps cited (also 0 post-fix).

## E-1 — Sustained mixed-fault history

`MiniJepsenSweepTest.sustainedMixedFaultHistoryStaysSafeAndRecovers`: a long-horizon randomized
MIXED-fault run on the 5-node control plane — random `isolate`/one-way `addPartition`, 10–40% packet
loss, latency spikes, partial + full heals, with continuous writes — and the consistency-contract
SAFETY oracle asserted **every tick**: single-leader-per-term + no divergent commit (a committed
index carries the same term on all nodes) + no committed-entry loss. A final heal must converge the
whole cluster (proving the mixed-fault history left it recoverable, never wedged).

- **CI-default run** (`seeds=8, horizon=6000`): 2088 faults injected, **0 safety violations**, worst
  final-converge **106 ticks**. (~19 s.)
- **Sustained nightly run** (`seeds=16, horizon=20000`): **13,920 faults injected, 0 safety
  violations**, worst final-converge 1641 ticks (bounded, < the 3000 bound), 267 s. →
  `captures/mini-jepsen-nightly-run.txt`.

Because safety is asserted on EVERY tick, a single split-brain / divergent commit / lost entry at any
point in the mixed-fault history fails the run — the oracle is not end-state-only.

## E-2 — Adversarial sweeps re-run on the fixed system (cited)

The mini-Jepsen complements the full adversarial sweeps, all 0-safety-violation against the
post-RR-103/RR-005 system: the 10k control-plane `SeedSweepTest` (build-and-test CI job) and the 10k
integrated edge `EdgeIntegratedNightlySweepTest` + `Rr095StallSeedsIntegratedRerunTest` (gate-4
nightly — `captures/gate-4-nightly-run.txt`, 0 safety violations; the cpStalls/deliveryViolations are
the known RR-095 ACCEPTED-RISK *liveness* artifacts, not safety).

Wired into gate-4's nightly step (`step_nightly`); excluded from the CI subset (`GATE4_SKIP_NIGHTLY=1`).

## Reproduction

```
./mvnw -o -pl configd-testkit test -Dtest='MiniJepsenSweepTest' -Dsurefire.failIfNoSpecifiedTests=false
# sustained: -Dconfigd.minijepsen.seeds=16 -Dconfigd.minijepsen.horizon=20000
```

# Session 7.5 — Bring-up gate result (gates 1–7 green on the m6id.4xlarge)

> Charter §6.3 / §15.1: gates 1–7 must be green ON THIS BOX before campaign work. Done.
> Box spec + fsync baseline: `run-log.md §6.1`.

## Result — ALL GREEN (fast CI mode), 2026-06-20

| Gate | Result | Wall-clock (UTC) | Notes |
|---|---|---|---|
| gate-1 | ✅ (inside gate-2 step a) | — | build + full suite **22,150 tests, 0 fail, 14 skip**; linz Porcupine self-tests green (Go 1.26.4 + prebuilt `PORCUPINE_BIN`) |
| gate-2 | ✅ PASS | 14:24:57→14:31:14 | cumulative (runs gate-1) + jcstress + 10k-seed sweep; PIT mutation skipped (`GATE2_SKIP_MUTATION=1`, CI-nightly lane) |
| gate-3 | ✅ PASS | 14:31:14→14:34:24 | **e2e four-phase Compose scenario 19/19** (real Docker on box): propagation, leader-kill cursor-monotonicity, partition heal, mid-load snapshot-first join + byte-equal linearizable read |
| gate-4 | ✅ PASS | 14:34:24→14:36:05 | durability/chaos CI subset (`GATE4_SKIP_NIGHTLY=1`); BUILD SUCCESS |
| gate-5 | ✅ PASS | 14:36:05→14:36:57 | performance gate (JMH GC + perf assertions) |
| gate-6 | ✅ PASS | 14:36:57→14:37:44 | operability: 14 alert rules fires/quiet green; game-day CI subset |
| gate-7 | ✅ PASS | 14:37:44→14:39:05 | security: PA-2021 snapshot/WAL/raft-state **tamper+forge+downgrade refused; S4 cells still green** |

**Total gates 2–7: ~14 min** on 16 vCPU (vs the 2-vCPU audit box's hours). All cumulative.

## Decision (logged for retroactive veto — charter §3)

**Gates run in fast CI mode** — exactly the flags CI applies on every push/PR (the canonical green
bar): `GATE2_SKIP_MUTATION=1`, `GATE3_SKIP_GATE2=1 GATE3_SKIP_MUTATION=1`,
`GATE4_SKIP_GATE3=1 GATE4_SKIP_NIGHTLY=1`, `GATE5_SKIP_GATE4=1`, `GATE6_SKIP_GATE5=1`,
`GATE7_SKIP_GATE6=1`. The **full PIT mutation** (gate-2 nightly ≈ 180 min), **gate-4 heavy chaos /
mini-Jepsen sweeps**, and **gate-7 full byte-reproducibility** stay the CI **nightly** lanes — they are
not hardware-sensitive and re-running multi-hour PIT before the box-only headline would violate the
§6a value-ordering (spend irreplaceable real-hardware time on what only this box can prove). The
deterministic functional + security + durability + e2e assertions all ran green on this box.

## Toolchain installed during bring-up
JDK 25.0.3 (apt `openjdk-25-jdk-headless`), Go 1.26.4 (official tarball — porcupine `go.mod` needs
≥1.26.4; apt's 1.26.0 was too old), Docker 29.1.3 (`docker.io`), `fio` 3.41, sysstat.
`PORCUPINE_BIN` prebuilt at `configd-linz/bin/porcupine-check`.

## Invocation
`gates/gate-N.sh` with the flags above, `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64`,
`PATH` prepended with `/usr/local/go/bin`. Driver: `/tmp/run-gate-chain.sh`. Timeline:
`captures/gate-chain-status.log`. (Raw per-gate step-logs gitignored — bulky + reproducible.)

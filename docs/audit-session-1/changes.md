# Session 1 — Change Log (build-enabling & harness-enabling modifications)

> Charter rule: the audit may modify the project only to (a) make it compile from a clean
> checkout, (b) make harnesses *runnable*, (c) create the Phase E pipeline artifacts. Everything
> else is a finding. This file logs every change actually made.

## 1. Modifications to pre-existing tracked files

**None.** The clean checkout built and tested green with zero changes (`build-report.md` §1).
No source file, test, pom, spec, or pre-existing doc in this repository was modified by Session 1.

## 2. New artifacts created (Phase E charter deliverables)

| Path | What | Justification |
|---|---|---|
| `docs/audit-session-1/ground-truth.md` | Phase A checkpoint | charter §3 |
| `docs/audit-session-1/build-report.md` | clean-build + CI audit | charter §3 |
| `docs/audit-session-1/harness-runs.md` | JMH/TLC/DST/linz/jcstress execution forensics | charter §3 |
| `docs/audit-session-1/smoke-test.md` | multi-node smoke test record | charter §3.4 |
| `docs/audit-session-1/ops-reality.md` | metrics/alerts/runbook reality check | charter §2 (sre-auditor) |
| `docs/audit-session-1/code-findings.md` | Phase D archaeology | charter §6 |
| `docs/audit-session-1/claim-evidence-matrix.md` | Phase B matrix (192 claims) | charter §4 |
| `docs/audit-session-1/test-forensics.md` | Phase C mutation/coverage/vacuity | charter §5 |
| `docs/audit-session-1/triage-reproductions.md` | P0/P1 independent reproductions | charter §2 cross-review rule |
| `docs/audit-session-1/handoff-to-session-2.md` | Session 2 handoff | charter §7.4 |
| `docs/audit-session-1/changes.md` | this file | charter §1 |
| `docs/readiness-register.md` | pipeline-wide findings register | charter §7.1 |
| `gates/smoke-multinode.sh` | 3-node control-plane smoke gate (~10s, exit-nonzero on failure) | charter §7.2 |
| `gates/gate-1.sh` | cumulative Session-1 gate | charter §7.2 |
| `gates/spec-smoke/{ConsensusSpec,ReadIndexSpec,SnapshotInstallSpec}-smoke.cfg` | reduced-bound TLC configs for gate-1 step (d) | charter §7.2 |

(CI workflow extension to run gate-1 — finalized, see §4.3.)

## 3. Harness-enablement actions OUTSIDE the repository (no tracked-file impact)

| Action | Where | Why |
|---|---|---|
| Built Porcupine linearizability checker binary (Go, 2.9s) | `/home/ubuntu/audit-artifacts/` | un-skip the 6 `PORCUPINE_BIN`-gated configd-linz self-tests; required to exercise the R-04 harness. The repo expects the binary via env var; nothing tracked was changed. |
| Scratch clones for isolated runs | `/home/ubuntu/ws-clean`, `/home/ubuntu/ws-smoke`, `/tmp/pv-strip` | clean-checkout proof; smoke-cluster isolation; `--enable-preview`-vestigiality experiment (sed-stripped pom in the throwaway clone only) |
| PIT (pitest) + JaCoCo wired into **ws-clean** poms only | `/home/ubuntu/ws-clean/**/pom.xml` | Phase C mutation/coverage measurement. Exact edits + reproduction commands recorded in `test-forensics.md` §5. The audited repo's poms are untouched; wiring PIT permanently is Session 2's call. |
| TLC run from scratch copies of `spec/` | `/home/ubuntu/audit-artifacts/tlc/` | avoid dirtying the tree with generated `states/` dirs |
| iptables REJECT rules during the linz scenario | live system, transient | required by the harness's fault injection; verified restored byte-identical to baseline afterward |
| Launcher script for manual cluster work | `/home/ubuntu/ws-smoke/launch-cluster.sh` | smoke-test development scratch; the durable artifact is `gates/smoke-multinode.sh` |

## 4. Phase E pipeline artifacts (finalized)

Created by the build-integrity-engineer at Phase E close. Nothing outside this list was
modified; the only pre-existing tracked file touched is `.github/workflows/ci.yml` (additive).

### 4.1 `gates/gate-1.sh` — cumulative Session-1 gate

Self-contained bash (`set -euo pipefail`), runs from repo root, exits non-zero on any failure,
prints a PASS/FAIL summary table with per-step timings. Steps:
(a) `./mvnw -B -fae clean verify` + count tripwire (≥ 21,000 tests, 0 failures/errors);
(b) Porcupine checker built from `configd-linz/src/main/go/porcupine-check` (PATH or
`~/sdk/go` Go; `PORCUPINE_BIN` override; `GATE1_SKIP_LINZ=1` skips LOUDLY) then the 6
`CheckerSelfTest` self-tests with 0 skips asserted;
(c) JMH executability smoke — all 9 benchmark classes, `-f 1 -wi 1 -i 1 -w 1s -r 1s -foe true`,
one pinned `@Param` each (same pinning as `harness-runs.md` §1);
(d) TLC smoke — the 3 reduced-bound configs below, run from a scratch dir, each must end
"No error";
(e) `gates/smoke-multinode.sh` (3-node failover smoke).
The header documents what a green gate-1 does NOT prove (the four P0s in
`docs/readiness-register.md` "Gate-1 blockers" are invisible to it).

**Verification record (honest):** gate-1 was run end-to-end 4 times on the audit box
(t3a.large, 2 vCPU). All 4 runs **FAILED at step (a)** — every failure was one of the two
TF-9 keytool TLS tests blowing its hardcoded `@Timeout(10)`:
`ConfigdServerTest.find0050…TlsManagerGetter` (run 1, 10.65s) and
`TcpRaftTransportTest.find0051_clientHandshakeRejectsCertWithWrongHostname` (runs 2-4,
10.23-10.31s). Root cause of the timing shift vs the green ground-truth run: the burstable
instance is CPU-credit-throttled after a day of audit workloads (boot-average steal 31%;
`clean verify` 13m08s vs the 5.5m ground truth; identical TLC smoke re-run 3.3x slower).
The same tests pass green in isolation on the idle box (verified). Per the charter, the
system under test was NOT modified and **the gate is left honestly failing** — this is
TF-9 reproduced *without* the JaCoCo agent, on the plain charter command. Steps (b)-(e)
were each verified green via the gate's own `--step` dispatch (after a
`./mvnw -pl configd-server,configd-testkit -am package -DskipTests` to produce the jars
step (a) would normally leave behind — target/-only, nothing tracked touched):
linz **PASS 15s** (6/6, 0 skips) · jmh **PASS 112s** (9/9 "Run complete") ·
tlc **PASS 480s** throttled (3/3 "No error"; 145s total unthrottled) ·
multinode **PASS 9s** (8/8 sub-checks). Step (a)'s count-tripwire parser was validated
against the ground-truth log: extracts exactly 21408/0/0/8.

### 4.2 `gates/spec-smoke/` — reduced-bound TLC smoke configs

Same invariants as the live `spec/*.cfg` (none dropped); only constants reduced. Measured on
this box, `tlc2.TLC -workers 2`:

| Spec | Full bounds (wall) | Smoke bounds (wall) | Reduction |
|---|---|---|---|
| ConsensusSpec | Nodes=3, MaxTerm=3, MaxLogLen=3, Values={v1,v2} (14m00s) | MaxTerm=**2**, rest unchanged (2m04s) | one election cycle fewer; reconfig + write-distinguishing kept |
| ReadIndexSpec | Nodes=3, MaxTerm=2, MaxIndex=2 (7m47s) | MaxIndex=**1**, rest unchanged (10s) | single committed index |
| SnapshotInstallSpec | Nodes=3, MaxTerm=3, MaxIndex=4 (2m39s) | MaxIndex=**3**, rest unchanged (11s) | snapshot chains capped at depth 3 |

Each header states full vs smoke bounds, full wall time, and "smoke run ≠ full assurance —
Session 2 owns bound adequacy". ConsensusSpec-smoke is ~4s over the ~2-min budget; that is
the smallest honest reduction (cutting Values to {v1} saves only ~7s, measured, and loses
write-distinguishing power — rejected). Wall times were measured with CPU credits available;
the credit-throttled re-run of the identical state spaces took 415s/29s/36s (~3x) — noted in
each header.

### 4.3 `.github/workflows/ci.yml` — gate-1 job (additive, +48 lines)

A new `gate-1` job was inserted between `tlc-model-check` and `wire-compat`. **No existing
job was modified** (the bare-`mvn`/unpinned-CI issue in `build-and-test` is RR-071, owned by
Session 5 — deliberately left as-is). YAML validated with `python3 yaml.safe_load`. The exact
added block:

```yaml
  gate-1:
    # Session-1 cumulative gate (audit-session-1 Phase E): clean `./mvnw clean
    # verify` + count tripwire, Porcupine-gated linz self-tests (6/6, 0 skips),
    # JMH executability smoke (9 classes), TLC smoke bounds (gates/spec-smoke/),
    # 3-node failover smoke. See gates/gate-1.sh header for what a green run
    # does and does NOT prove (docs/readiness-register.md "Gate-1 blockers").
    # NOTE: runs the pinned wrapper (./mvnw); build-and-test above runs bare
    # `mvn` — that discrepancy is registered finding RR-071 (Session 5 owns it).
    # Estimated runtime on ubuntu-latest (4 vCPU): ~15-25 min, from per-step
    # measurements on the 2-vCPU audit box: build+suite 5.5-13 min (CPU-credit
    # dependent), linz 15s, JMH smoke ~2 min, TLC smoke ~2.5-8 min, multinode
    # ~10s; a cold Maven cache adds dependency-download time.
    # KNOWN FLAKE RISK (registered TF-9, Session 2 owns): the two keytool TLS
    # tests (find0050/find0051, hardcoded @Timeout(10)) run ~7-10s on slow
    # 2-vCPU hardware and can fail the build step on a throttled runner.
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'corretto'
          cache: maven

      - name: Set up Go (builds the Porcupine checker in gate-1 step b)
        uses: actions/setup-go@v5
        with:
          # gate-1 builds with GOTOOLCHAIN=local, so the installed toolchain
          # must satisfy the go.mod "go" directive — read it from the file.
          go-version-file: configd-linz/src/main/go/porcupine-check/go.mod
          cache-dependency-path: configd-linz/src/main/go/porcupine-check/go.sum

      - name: Run gate-1
        run: bash gates/gate-1.sh
        env:
          GATE1_LOG_DIR: ${{ runner.temp }}/gate1-logs
        timeout-minutes: 45

      - name: Upload gate-1 step logs
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: gate-1-logs
          path: ${{ runner.temp }}/gate1-logs
          retention-days: 7
```

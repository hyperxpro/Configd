# Automation Prerequisites — Self-Driving Harness Orchestrator

**Authored:** 2026-04-25 by autonomous-orchestrator setup, Phase 0
prerequisite check.
**Status:** HALTED. Prerequisites for self-driving harness automation
are not met. Per the orchestrator prompt §2 ("If any prerequisite is
missing, emit `docs/automation-prerequisites.md` listing exactly what
to fix, and halt. Do not proceed with partial setup.") this document
enumerates what is missing and why building the orchestrator wrapper
before these are addressed would be premature.

This is **not** a refusal. It is the orchestrator following its own
honesty invariant: "Evidence is written only after the measurement
actually completes. A gate flips yellow→green only when real harness
output with real timestamps exists." Building wrapper code that
dispatches stubs will not flip any gate, and would produce orchestrator
artifacts that look complete while the underlying harnesses still
cannot measure anything.

---

## 1. What was checked

| Prerequisite (orchestrator §2) | Required state | Observed state | Verdict |
|---|---|---|---|
| `docs/operator-runsheet.md` | Exists with executable commands for all 7 harnesses | Exists, 15 824 bytes, authored 2026-04-22 | **PASS** |
| `docs/ga-review.md` with current gate status | Exists | Exists, 13 090 bytes, last edit 2026-04-19 | **PASS** |
| Execution host with multi-week uptime | ≥ 30 days continuous for longevity (C3) | `uptime` reports 3 days; shared dev box, no SLO | **FAIL** |
| Infrastructure credentials configured | env / secret manager has cluster credentials | `env` has no AWS/GCP/KUBE/cluster vars; `kubectl` not installed; `terraform` not installed | **FAIL** |
| `perf/results/` writable and version-controlled | Writable, in git, holds real prior results | Writable, in git, holds smoke stub (`status=YELLOW (no workload wired; duration honoured)`) and one PLACEHOLDER directory | **PARTIAL** |
| Git identity attributable | Distinct automation identity (`automation@<project>`) | `Aayush Atharva <aayush@shieldblaze.com>` — personal identity | **FAIL** |
| `infra/` Terraform / IaC with configured backend (orchestrator §0 scope) | Present | `/home/ubuntu/Programming/Configd/infra/` does not exist | **FAIL** |
| Harness scripts drive real workloads against real clusters | Each `perf/*.sh` brings up workload + measures + emits numeric result | All four scripts honour duration only; explicit comment in `perf/soak-72h.sh` header; smoke `result.txt` says `status=YELLOW (no workload wired; duration honoured)` | **FAIL — root cause** |
| `deploy/kubernetes/` cluster definition | Production-shaped 5-node StatefulSet + monitoring + chaos hooks + (for shadow) traffic mirror to second cluster | Two yamls only: `configd-bootstrap.yaml`, `configd-statefulset.yaml`. No monitoring stack, no chaos injection, no mirror | **FAIL** |
| Disk capacity for long-run telemetry | Enough headroom for 30-day metric capture + raw logs | `df -h /` shows 12 G free of 29 G, single-disk root | **MARGINAL** |

Six FAILs, one PARTIAL, one MARGINAL, two PASS.

## 2. The root cause: harnesses are stubs

The orchestrator could be authored today and would be functionally
correct. But it would dispatch to harnesses that, by their own
self-description, do not measure anything:

```
$ cat perf/results/smoke/result.txt
soak-72h harness
  requested_duration_sec=60
  ...
  measured_elapsed_sec=60
  status=YELLOW (no workload wired; duration honoured)
```

The harness scripts (`perf/soak-72h.sh`, `perf/burn-7d.sh`,
`perf/shadow-14d.sh`, `perf/longevity-30d.sh`) implement the
**duration contract** — they sleep for the requested wall-clock and
emit a `measured_elapsed_sec` line — but they do not:

- bring up a 5-node cluster,
- generate the production-shaped workload that p99 metrics are read
  from,
- inject the chaos schedule that the burn harness's pass criteria
  reference (`1 leader kill / 12 h`, `1 partition-then-heal / 24 h`,
  `1 disk-fsync stall / 48 h`, `1 TLS hot-reload / 36 h`),
- mirror traffic to a control cluster (shadow),
- compare reads byte-by-byte (shadow),
- track FD growth, RSS growth, WAL segment growth, snapshot install
  latency drift (longevity).

An orchestrator that dispatches these stubs will, after 30 days of
correct operation, leave every C-row at YELLOW because the result
files will continue to honestly report `status=YELLOW (no workload
wired; duration honoured)`. **The orchestrator is not the long pole.
The harness implementations and the cluster infrastructure are.**

## 3. Why building the orchestrator anyway would be wrong

Per orchestrator §6 hard rule #1: "Evidence is never fabricated. Every
number in `docs/ga-review.md` traces to a committed log file with real
timestamps." A stub harness that emits `measured_elapsed_sec=2592000`
after 30 days of `sleep` is *real* in the timestamp sense but *fake* in
the measurement sense. Flipping C3 to GREEN on that basis would
violate the honesty invariant.

Per §6 hard rule #2: "No gate flips green without a real harness run.
Not 'the harness would probably pass' — an actual run with real
output." A stub harness's output is real bytes but not a real run of
the *system under test*; the system under test is never started.

Per §6 hard rule #4: "Regressions are not retried. A system-under-test
failure halts the orchestrator." A stub harness can never produce a
regression because it never exercises the system. The orchestrator's
regression-halt mechanism would never fire, which is correct in a
narrow sense and a complete failure of intent in the broad sense.

The orchestrator's design is sound. The materials it needs to operate
on are not yet built.

## 4. What must be in place before the orchestrator is worth writing

In rough dependency order. Each item is independently verifiable.

### 4.1 Cluster provisioning

- [ ] **`infra/` directory created** with Terraform (or Pulumi /
  CloudFormation / Crossplane equivalent) defining:
  - one 5-node prod-shaped Kubernetes cluster (the **serial-chain
    cluster** for steps 3 / 4 / 6 of the runsheet),
  - one 5-node prod-shaped Kubernetes cluster pair plus a traffic
    mirror gateway (the **shadow cluster pair** for step 5),
  - one staging cluster (for steps 1–2 drills),
  - storage for ≥ 200 GB of result telemetry per harness with off-host
    durability (S3 / GCS / equivalent).
- [ ] Backend configured (remote state with locking).
- [ ] `terraform plan` runs cleanly on a fresh checkout.
- [ ] `terraform apply` actually provisions the above. Cost
  estimate documented in `infra/COST.md`. (For reference: 11 prod-
  shaped Kubernetes nodes plus a mirror gateway, run for the
  ~45-day critical path described in `docs/operator-runsheet.md` §9,
  is non-trivial. Get an explicit budget approval before applying.)

### 4.2 Workload-driving harnesses

- [ ] `perf/soak-72h.sh` extended to actually drive write/read traffic
  against the provisioned cluster. Its header comment is the spec:
  "you must wire the write/read/RSS-sampler loops described in the
  script's header before the run."
- [ ] `perf/burn-7d.sh` extended to drive the 80 %-capacity workload
  AND the chaos schedule (4 chaos primitives, see §2 above). Each
  chaos primitive needs a real implementation — `kubectl delete pod`
  for leader kill, NetworkPolicy or `tc` for partition, `dmsetup
  delay` or equivalent for disk-fsync stall, CRD-triggered TLS
  rotation for hot-reload.
- [ ] `perf/shadow-14d.sh` extended to: (a) run two clusters,
  (b) tee mirrored production traffic to both, (c) capture every read
  response, (d) byte-diff control vs. candidate, (e) emit a
  `byte_divergence_count` line.
- [ ] `perf/longevity-30d.sh` extended to: (a) drive sustained writes
  that produce snapshot/install/truncate cycles, (b) sample FD count,
  RSS, WAL segment count, disk-watermark, snapshot install latency
  p99 every N minutes, (c) emit slope lines the orchestrator can
  parse.
- [ ] Each harness emits its pass-criteria numbers as **greppable
  key=value lines** in `result.txt` (e.g., `p99_write_commit_ms=137`,
  `rss_growth_pct=4.2`, `byte_divergence_count=0`). The runsheet's
  pass criteria are written assuming this; the orchestrator §4 table
  requires it.

### 4.3 Drill harnesses

- [ ] `ops/dr-drills/` runner script exists that follows
  `ops/runbooks/control-plane-down.md` and
  `ops/runbooks/restore-from-snapshot.md` non-interactively, captures
  every command's stdout/stderr, and emits the result template per
  `ops/runbooks/runbook-conformance-template.md`.
- [ ] Both runbooks reviewed for any step that requires human
  judgment that cannot be expressed as a script. Human-judgment steps
  are an honest stop sign for orchestration.

### 4.4 Credentials and identity

- [ ] Cluster kubeconfig(s) provisioned to a secret store (Vault /
  Secrets Manager / equivalent) and mounted to the orchestrator host
  via short-TTL credentials.
- [ ] Cloud provider credentials with **least privilege** — read /
  write to the test clusters and the result bucket, nothing else.
- [ ] Git identity for the orchestrator: e.g., `configd-automation
  <automation@<project>>`, with a dedicated SSH key / token,
  attributable in `git log --format=%ae`. Distinct from any human
  contributor's identity.

### 4.5 Execution host

- [ ] Dedicated host (CI runner, dedicated VM, or scheduled-job
  service) with:
  - uptime SLO ≥ 60 days (covers 45-day critical path + reruns),
  - automatic resumption after restart (the orchestrator's checkpoint
    file at `docs/automation-state.json` makes this safe IF the host
    actually comes back),
  - alerting if the orchestrator process dies (so a paused state
    doesn't silently sit for weeks),
  - ≥ 100 GB free disk for orchestrator-side log mirroring.
- The current development host (`uptime` = 3 days, shared, 12 G
  free) is not suitable.

### 4.6 Cost and time approval

- [ ] Explicit budget approval for the cluster footprint × 45 days.
- [ ] Explicit calendar approval — the critical path is
  ~40–45 days wall clock per `docs/operator-runsheet.md` §9. If a
  release date is sooner than that, the orchestrator cannot
  compress time.

## 5. What is correct to do today

Two paths, depending on intent:

### Path A — fix the prerequisites first

The right path if the goal is an actually-running self-driving
harness automation. Address §4.1 → §4.2 → §4.3 → §4.4 → §4.5 → §4.6
in order. Each block is independently estimable; total effort is on
the order of multiple engineer-weeks (workload generators, chaos
infrastructure, traffic mirror, infra-as-code) before any orchestrator
work begins.

When §4 is complete, **re-invoke the orchestrator prompt**. Its Phase
0 prerequisite check will pass and it can proceed to Phase 1 (write
the wrapper) and Phase 3 (smoke dry-run) on a meaningful smoke target.

### Path B — accept v0.1 with calendar-bounded gates as documented YELLOW residuals

The path the existing artifacts already point to. `docs/ga-review.md`
explicitly says: "A gate is **GA-blocking** only if it is RED. YELLOW
gates are shippable as v0.1 with documented residuals per
gap-closure §5." `docs/ga-approval.md` §6 requires the human signer
to acknowledge each YELLOW residual.

If the v0.1 release is willing to ship with the C-rows at YELLOW
(measured stub, not real run) and convert them to GREEN in v0.2 once
§4 is done, no orchestrator work is required for v0.1 GA. The signer
explicitly accepts those residuals.

This is not a worse outcome — it is the outcome the loop already
designed for, per `docs/handoff.md` §6 and the §4.7 honesty
invariant. The orchestrator does not change the calendar bounds.

## 6. What this document does not do

- It does not flip any gate. All gate states in
  `docs/ga-review.md` remain as recorded on 2026-04-19.
- It does not modify any harness, infrastructure, or runbook.
- It does not author orchestrator code that would later need to be
  thrown away when the harnesses are wired up properly (the wiring
  may change result-file formats, command-line arguments, or
  evidence paths).
- It does not commit anything beyond this document itself.

## 7. Resumption protocol

When `§4` is complete:

1. Verify the §1 table can be re-run with all rows passing.
2. Re-invoke the orchestrator prompt.
3. Phase 0 will pass; Phase 1 (write wrapper), Phase 2 (structure
   runsheet per §4 contract), and Phase 3 (smoke dry-run) will
   execute.
4. Phase 4 (real run) launches and runs autonomously per orchestrator
   §3 dispatch policy.

Until then, this document is the orchestrator's output for this
invocation: an honest halt, not a fabricated start.
